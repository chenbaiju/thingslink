package com.things.link.iam.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 登录接口限流（架构文档 7.3：对登录、注册、命令、导出和开放 API 分别限流）。
 *
 * <h2>它与账号锁定是两道不同的防线</h2>
 * <ul>
 *   <li><b>账号锁定</b>盯的是「某个账号被试了多少次」，落库，权威，能真正阻止
 *       一个口令被爆破出来</li>
 *   <li><b>限流</b>盯的是「请求量」，拦的是账号锁定拦不住的那一类攻击 ——
 *       撞库：一个口令喷向大量不同账号，每个账号只试一两次，永远触发不了锁定</li>
 * </ul>
 * 只做其中一个都有明显缺口，所以两个都要。
 *
 * <h2>计数在 Redis，不在进程内存</h2>
 * 早先的实现用的是进程内 {@code ConcurrentHashMap}。它在单实例下能工作，但部署
 * 第二个实例的那一刻，实际限额就变成「配置值 × 实例数」，而且<b>不会有任何症状</b>——
 * 没有报错、没有日志，只是这道防线悄悄失效了。S4 引入邮件重发接口之后更不能再拖：
 * 那个接口每次调用都真的往外发一封信，限流失守等于发信账号被服务商封禁。
 *
 * <h2>Redis 不可用时放行，而不是拒绝</h2>
 * 这是一个明确的取舍。Redis 挂掉时：
 * <ul>
 *   <li><b>拒绝（fail-closed）</b>：登录、注册、重发全部不可用 —— 一个辅助设施的
 *       故障升级成整个认证入口的故障</li>
 *   <li><b>放行（fail-open）</b>：限流暂时失效，但账号锁定那道防线仍在库里正常工作，
 *       爆破单个账号依然拦得住</li>
 * </ul>
 * 选放行。前提是<b>放行时必须记 ERROR</b> —— 否则 Redis 长期挂着而无人知晓，
 * 就等于这道防线被永久关掉了。
 *
 * <h2>为什么不引入限流框架</h2>
 * Bucket4j / Resilience4j 都能做，但它们的价值在于复杂策略（多级令牌桶、
 * 自适应背压），而这里需要的只是「固定窗口内计数」——
 * Redis 的 {@code INCR} + {@code EXPIRE} 两条命令就够了。
 */
@Component
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

    /**
     * 单个邮箱在一个窗口内允许的登录尝试次数。
     *
     * <p>比账号锁定的阈值宽松：锁定是针对「这个账号快被爆破了」的精确响应，
     * 限流是粗粒度的兜底。设得太紧会误伤正常用户 —— 忘记口令时试个五六次很常见。
     */
    private static final int MAX_ATTEMPTS_PER_EMAIL = 10;

    /**
     * 单个来源 IP 在一个窗口内允许的登录尝试次数。
     *
     * <p>比邮箱维度宽得多：办公网、校园网整栋楼共用一个出口 IP 是常态，
     * 设紧了会把整个单位一起挡在外面。它拦的是「同一台机器喷大量账号」这种模式。
     */
    private static final int MAX_ATTEMPTS_PER_IP = 60;

    /**
     * 单个来源 IP 在一个窗口内允许的注册次数。
     *
     * <p>比登录严得多：正常人不会在五分钟内注册三个以上账号，而注册是<b>写操作</b>，
     * 不限流会被刷出大量空租户（ADR 0008 的后果一节点名了这一项）。清理这些垃圾
     * 租户是纯人工成本，攻击者的成本却接近零。
     *
     * <p>注册不按邮箱维度限流：每次注册用的邮箱本来就不同，按邮箱计数拦不住任何东西。
     */
    private static final int MAX_REGISTRATIONS_PER_IP = 3;

    /**
     * 单个邮箱在一个窗口内允许的「重发验证邮件」次数。
     *
     * <p>比其他维度都严，因为这个接口<b>每次调用都花真金白银</b>：它会向外部 SMTP
     * 发一封信，而个人/企业邮箱的日发信量只有几十到几百封。被刷一轮的后果不是
     * 「服务变慢」，而是发信账号被服务商限流甚至封禁授权码 —— 那时所有人的
     * 注册验证信都发不出去，且恢复要等对方解封。
     *
     * <p>3 次对真实用户足够：没收到信的正常处置是等一会儿、翻垃圾箱，
     * 而不是五分钟内点十次。
     */
    private static final int MAX_RESENDS_PER_EMAIL = 3;

    /**
     * 单个来源 IP 在一个窗口内允许的「重发验证邮件」次数。
     *
     * <p>邮箱维度拦不住「换一个邮箱再来一次」：重发接口不要求邮箱已注册（否则
     * 它本身就成了账号枚举通道），所以攻击者可以用任意地址刷。IP 维度才是这里
     * 真正起作用的那道。
     */
    private static final int MAX_RESENDS_PER_IP = 10;

    /**
     * 单个邮箱在一个窗口内允许的「找回密码」次数。
     *
     * <p>与重发验证邮件同为 3，但这里还有一层不同的顾虑：<b>它可以被用来骚扰</b>。
     * 攻击者拿着别人的邮箱反复点「忘记密码」，受害者的收件箱就会塞满重置信，
     * 而每一封都在暗示「有人在试图进你的账号」。限住邮箱维度才拦得住这一类。
     */
    private static final int MAX_RESETS_PER_EMAIL = 3;

    /** 单个来源 IP 在一个窗口内允许的「找回密码」次数。理由同重发验证邮件的 IP 维度。 */
    private static final int MAX_RESETS_PER_IP = 10;

    /** 计数窗口。 */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /**
     * 全部计数键的公共前缀。
     *
     * <p>存在的意义是 {@link #clear()} 能一次性找到它们，以及在 {@code redis-cli}
     * 里排查时不会与其他用途的键混在一起。
     */
    private static final String KEY_PREFIX = "auth:rl:";

    private final StringRedisTemplate redis;

    public AuthRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 登录尝试是否应当被放行。
     *
     * <p><b>无论账号是否存在都要调用</b>，且计数不区分成功失败。只对失败计数的话，
     * 攻击者可以用一个已知的有效账号穿插正常登录来重置节奏；只对存在的账号计数
     * 则会让「限流是否触发」本身变成账号是否存在的信号。
     *
     * @param email    登录用的邮箱，已归一化为小写
     * @param clientIp 来源 IP，可为 {@code null}
     * @return 允许返回 true
     */
    public boolean tryAcquire(String email, String clientIp) {
        // 两个维度都要过。先判邮箱：它是精确维度，且不受反向代理影响
        boolean emailAllowed = tryAcquire("email:" + email, MAX_ATTEMPTS_PER_EMAIL);

        // IP 维度。
        //
        // G3-SEC-1在容器边界解析可信代理；此处只使用getRemoteAddr衍生值。
        // NAT出口仍共享同一IP预算，不能把IP当作用户身份。
        boolean ipAllowed = clientIp == null
                || tryAcquire("ip:" + clientIp, MAX_ATTEMPTS_PER_IP);

        if (!emailAllowed || !ipAllowed) {
            // 记 warn 而非 error：触发限流是这个机制正常工作的表现，不是故障。
            // 但**不记完整邮箱**，只记域名部分 —— 日志里堆满明文邮箱本身就是一份
            // 可被拖走的账号清单
            log.warn("登录限流触发 emailDomain={} ip={} 维度={}",
                    domainOf(email), clientIp, emailAllowed ? "ip" : "email");
            return false;
        }
        return true;
    }

    /** 邀请证明独立来源预算：每五分钟30次；缺少来源地址拒绝。 */
    public boolean tryAcquireInvitationProof(String clientIp) {
        return clientIp != null && tryAcquire("invitation:ip:" + clientIp, 30);
    }

    /**
     * 注册尝试是否应当被放行。
     *
     * <p>只按 IP 计数，不按邮箱 —— 注册用的邮箱每次都不同，按它计数拦不住任何东西。
     *
     * <p>与登录共用同一张计数表但用独立的 key 前缀：两者的额度必须互不影响，
     * 否则「登录失败几次」会连带把注册也挡住，反之亦然。
     *
     * @param clientIp 来源 IP，可为 {@code null}
     * @return 允许返回 true
     */
    public boolean tryAcquireRegistration(String clientIp) {
        if (clientIp == null) {
            // 保留既有无来源上下文降级；真实Servlet请求应始终提供容器解析后的地址。
            return true;
        }
        return tryAcquire("register:" + clientIp, MAX_REGISTRATIONS_PER_IP);
    }

    /**
     * 重发验证邮件是否应当被放行。
     *
     * <p>邮箱与 IP 两个维度都要过，理由见两个上限常量的注释：邮箱维度防「盯着一个
     * 地址狂点」，IP 维度防「换邮箱接着刷」。缺任何一个这道防线都基本无效。
     *
     * <p><b>与登录、注册用独立的 key 前缀</b>：额度必须互不影响，否则一次限流会
     * 连带把不相干的入口挡住。
     *
     * @param email    目标邮箱，已归一化为小写
     * @param clientIp 来源 IP，可为 {@code null}
     * @return 允许返回 true
     */
    public boolean tryAcquireVerificationResend(String email, String clientIp) {
        boolean emailAllowed = tryAcquire("resend:email:" + email, MAX_RESENDS_PER_EMAIL);
        boolean ipAllowed = clientIp == null
                || tryAcquire("resend:ip:" + clientIp, MAX_RESENDS_PER_IP);

        if (!emailAllowed || !ipAllowed) {
            log.warn("重发验证邮件限流触发 emailDomain={} ip={} 维度={}",
                    domainOf(email), clientIp, emailAllowed ? "ip" : "email");
            return false;
        }
        return true;
    }

    /**
     * 找回密码是否应当被放行。
     *
     * <p>与重发验证邮件同为「邮箱 + IP」双维度、独立 key 前缀。额度不与重发共用：
     * 两个都是发信接口，但一个用户可能确实先重发了验证信、又去找回密码，
     * 共用额度会让第二件事莫名被挡。
     *
     * @param email    目标邮箱，已归一化为小写
     * @param clientIp 来源 IP，可为 {@code null}
     * @return 允许返回 true
     */
    public boolean tryAcquirePasswordReset(String email, String clientIp) {
        boolean emailAllowed = tryAcquire("reset:email:" + email, MAX_RESETS_PER_EMAIL);
        boolean ipAllowed = clientIp == null
                || tryAcquire("reset:ip:" + clientIp, MAX_RESETS_PER_IP);

        if (!emailAllowed || !ipAllowed) {
            log.warn("找回密码限流触发 emailDomain={} ip={} 维度={}",
                    domainOf(email), clientIp, emailAllowed ? "ip" : "email");
            return false;
        }
        return true;
    }

    /**
     * 登录成功后放宽该邮箱的计数。
     *
     * <p>正常用户输错几次再输对是很常见的，不清掉的话他在窗口剩余时间里会莫名
     * 受限。IP 维度<b>不清</b>：共用出口 IP 的场景下，一个人登录成功不该为整栋楼
     * 的失败次数买单。
     *
     * @param email 登录成功的邮箱，已归一化为小写
     */
    public void onSuccess(String email) {
        try {
            redis.delete(KEY_PREFIX + "email:" + email);
        } catch (DataAccessException e) {
            // 清不掉只是让这个用户在窗口剩余时间里额度偏紧，不影响正确性。
            // 用 debug：Redis 真挂了的话 tryAcquire 那边已经在记 ERROR 了，
            // 这里再记一遍只会把同一个故障刷成两倍日志
            log.debug("清除登录限流计数失败 emailDomain={}", domainOf(email), e);
        }
    }

    /**
     * 清空全部计数。
     *
     * <p>存在的理由是<b>测试隔离</b>：计数现在存在 Redis 里，跨测试类、甚至跨测试
     * 运行都会累计。没有它的话，几个共用同一个测试邮箱的类合起来就会撞上限额，
     * 表现为一堆与限流毫无关系的用例莫名 429。
     *
     * <p>顺带也是一个运维出口：误配阈值把正常用户挡在外面时，重启服务已经没用了
     * （计数不在进程里），只能从这里清。
     *
     * <p>用 {@code KEYS} 而非 {@code SCAN}：它会阻塞 Redis 直到扫完整个键空间，
     * <b>因此只允许在测试与人工运维时调用，不得放进任何请求路径</b>。
     * 限流键的数量级（活跃邮箱数 + IP 数）不值得为它写一套游标遍历。
     */
    public void clear() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * 在固定窗口内计数并判断是否超限。
     *
     * <h2>为什么 INCR 之后才设 TTL</h2>
     * 只有第一次（返回值为 1）才设过期时间。每次都设的话，持续的攻击流量会不断把
     * 窗口往后推 —— 计数器永不过期，攻击者第一次触发限流之后就<b>永远</b>被挡住，
     * 而共用同一个出口 IP 的正常用户也会跟着被永久挡住。
     *
     * <h2>为什么不用 Lua 保证原子</h2>
     * INCR 与 EXPIRE 之间进程崩溃的话，会留下一个没有 TTL 的计数键，它要到
     * {@link #clear()} 才消失。这个窗口极窄，代价也只是「某个键的限流偏严」，
     * 不值得为它引入脚本与脚本缓存的一整套复杂度。
     *
     * @param dimension 计数维度（已含维度前缀，如 {@code email:x@y.com}）
     * @param limit     窗口内上限
     * @return 未超限返回 true
     */
    private boolean tryAcquire(String dimension, int limit) {
        String key = KEY_PREFIX + dimension;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                // 理论上不会发生（INCR 总有返回值）。真发生了说明连接层出了怪事，
                // 按 fail-open 处理，与 Redis 不可用同一个口径
                return true;
            }
            if (count == 1) {
                redis.expire(key, WINDOW);
            }
            return count <= limit;
        } catch (DataAccessException e) {
            // fail-open：放行。理由见类注释 —— 但必须记 ERROR，
            // 否则 Redis 长期挂着而无人知晓，就等于这道防线被永久关掉了
            log.error("限流计数不可用（Redis 故障），本次请求放行 dimension={}", dimension, e);
            return true;
        }
    }

    /**
     * 取邮箱的域名部分，用于日志。
     *
     * @param email 邮箱
     * @return {@code @} 之后的部分；没有 {@code @} 时返回固定占位符
     */
    private static String domainOf(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at) : "<invalid>";
    }

}
