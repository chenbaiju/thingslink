package com.things.link.enduser.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * App 登录接口限流（架构文档 7.3 的「开放 API 分别限流」落到终端用户侧）。
 *
 * <h2>为什么不复用 iam 的 {@code AuthRateLimiter}</h2>
 * enduser 不能依赖 iam（iam 已经依赖 project，反向会成环）。且 App 登录的维度是
 * {@code projectKey:username} 而非邮箱，额度与触发语义都不同，硬套一套只会两头迁就。
 *
 * <h2>计数在 Redis，fail-open</h2>
 * 与 iam 的 {@code AuthRateLimiter} 同一套取舍：Redis 不可用时放行并记 ERROR ——
 * 一个辅助设施故障不应升级成整个 App 登录入口的故障，而放行必须留痕，否则这道防线
 * 会悄无声息地永久失效。
 */
@Component
public class AppAuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AppAuthRateLimiter.class);

    /** 单个 (projectKey, username) 在一个窗口内允许的登录尝试次数。 */
    private static final int MAX_ATTEMPTS_PER_IDENTITY = 10;

    /** 单个来源 IP 在一个窗口内允许的登录尝试次数，拦「同一台机器喷多个项目」。 */
    private static final int MAX_ATTEMPTS_PER_IP = 60;

    /** 计数窗口。 */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** 全部计数键的公共前缀，供 {@link #clear()} 与 redis-cli 排查。 */
    private static final String KEY_PREFIX = "app:rl:";

    private final StringRedisTemplate redis;

    public AppAuthRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 登录尝试是否应当被放行。
     *
     * <p><b>无论凭据对错都要调用</b>，计数不区分成功失败：只对失败计数会让攻击者用
     * 已知有效账号穿插正常登录来重置节奏，且「限流是否触发」本身会成为账号是否存在的信号。
     *
     * @param projectKey 项目标识
     * @param username   已规范化的用户名
     * @param clientIp   来源 IP，可为 {@code null}
     * @return 允许返回 true
     */
    public boolean tryAcquire(String projectKey, String username, String clientIp) {
        String identity = projectKey + ":" + username;
        boolean identityAllowed = tryAcquire("identity:" + identity, MAX_ATTEMPTS_PER_IDENTITY);
        boolean ipAllowed = clientIp == null || tryAcquire("ip:" + clientIp, MAX_ATTEMPTS_PER_IP);

        if (!identityAllowed || !ipAllowed) {
            log.warn("App 登录限流触发 identity={} ip={} 维度={}",
                    identity, clientIp, identityAllowed ? "ip" : "identity");
            return false;
        }
        return true;
    }

    /**
     * 清空全部计数。
     *
     * <p>只用于测试隔离与人工运维，不得放进任何请求路径 —— 用 {@code KEYS} 会阻塞 Redis
     * 直到扫完整个键空间。限流键数量级不值得为它写游标遍历。
     */
    public void clear() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * 固定窗口计数。
     *
     * <p>只有第一次（返回值 1）才设 TTL：每次都设会把窗口随持续流量不断后推，计数器
     * 永不过期。INCR 与 EXPIRE 之间崩溃留下的无 TTL 键是极窄窗口，代价只是偏严，不值得
     * 为它引入 Lua 脚本。
     */
    private boolean tryAcquire(String dimension, int limit) {
        String key = KEY_PREFIX + dimension;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                // 理论上 INCR 总有返回值；真为空按 fail-open 处理
                return true;
            }
            if (count == 1) {
                redis.expire(key, WINDOW);
            }
            return count <= limit;
        } catch (DataAccessException e) {
            log.error("App 限流计数不可用（Redis 故障），本次请求放行 dimension={}", dimension, e);
            return true;
        }
    }

}
