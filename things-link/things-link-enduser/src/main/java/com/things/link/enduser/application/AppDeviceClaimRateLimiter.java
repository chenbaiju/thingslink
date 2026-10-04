package com.things.link.enduser.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * App 设备绑定凭据消费入口的低基数限流器（G2-A1c/A1e/A1f）。
 *
 * <p>随机明文无法归属到某一条 {@code token_hash}，因此不能伪造增加那条令牌的
 * {@code attempt_count}。本限流器按已认证 App 用户与来源 IP 兜住随机喷射；PostgreSQL
 * 中命中哈希后的五次复核上限仍是另一道独立防线。
 *
 * <p>Redis 是辅助防滥用设施而非绑定权威源。不可用时记录 ERROR 并 fail-open，后续仍须
 * 通过高熵令牌、项目 RLS、项目角色和数据库原子仲裁，不能把 Redis 故障扩大成所有用户
 * 无法认领或接收设备主控。
 *
 * <p>类名与 Redis 前缀保留 CLAIM 是兼容既有部署键；TRANSFER 与 CLAIM 共用额度，防止攻击者
 * 在多个入口之间切换而把有效喷射预算翻倍。SHARE 复用同一预算。
 */
@Component
public class AppDeviceClaimRateLimiter {

    /** 限流故障与触发记录。 */
    private static final Logger log = LoggerFactory.getLogger(AppDeviceClaimRateLimiter.class);

    /** 单一 App 用户在五分钟内允许的消费请求数。 */
    private static final int MAX_ATTEMPTS_PER_USER = 30;

    /** 单一来源 IP 在五分钟内允许的消费请求数。 */
    private static final int MAX_ATTEMPTS_PER_IP = 60;

    /** 固定窗口；与架构 §11.4 冻结值一致。 */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** Redis 历史键前缀；CLAIM/TRANSFER/SHARE 共用，避免切换入口绕过预算。 */
    private static final String KEY_PREFIX = "app:claim:rl:";

    /** Redis 访问入口。 */
    private final StringRedisTemplate redis;

    /**
     * 创建认领限流器。
     *
     * @param redis Redis 字符串访问入口
     */
    public AppDeviceClaimRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 同时领取绑定凭据消费的用户和 IP 两个维度请求额度。
     *
     * @param appUserId 已认证终端用户
     * @param clientIp  Servlet 连接来源 IP；未知时可空
     * @return 两个维度都未超限时为 true
     */
    public boolean tryAcquire(UUID appUserId, String clientIp) {
        boolean userAllowed = increment("user:" + appUserId, MAX_ATTEMPTS_PER_USER);
        boolean ipAllowed = clientIp == null || increment("ip:" + clientIp, MAX_ATTEMPTS_PER_IP);
        if (!userAllowed || !ipAllowed) {
            log.warn("App 设备绑定凭据消费限流触发 appUserId={} ip={} 维度={}",
                    appUserId, clientIp, userAllowed ? "ip" : "user");
            return false;
        }
        return true;
    }

    /**
     * 清空本限流器计数，仅供测试隔离与人工运维。
     *
     * <p>不得放入请求路径；{@code KEYS} 会阻塞 Redis，本方法只在受控环境使用。
     */
    public void clear() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * 固定窗口递增；只在第一笔写入 TTL，避免持续请求无限延长窗口。
     *
     * @param dimension 完整维度后缀
     * @param limit     窗口上限
     * @return 未超过上限为 true
     */
    private boolean increment(String dimension, int limit) {
        try {
            String key = KEY_PREFIX + dimension;
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1) {
                redis.expire(key, WINDOW);
            }
            return count <= limit;
        } catch (DataAccessException exception) {
            log.error("App 设备绑定凭据消费限流不可用，本次 fail-open dimension={}", dimension, exception);
            return true;
        }
    }
}
