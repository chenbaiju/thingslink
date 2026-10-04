package com.things.link.device.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 一型一密公开注册入口的跨实例固定窗口限流器。 */
@Component
public class DeviceRegistrationRateLimiter {

    /** 正常产线可能并发烧录设备，额度高于控制台注册，但仍要挡住无界扫描。 */
    private static final int MAX_ATTEMPTS_PER_IP = 300;

    /** 一分钟窗口让攻击流量快速退避，同时不形成长时间误伤。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Redis 键前缀与账号限流隔离。 */
    private static final String KEY_PREFIX = "device:register:rl:";

    /**
     * 计数与首次过期时间必须原子提交，否则进程在 INCR 与 EXPIRE 之间中断会留下永久限流键。
     */
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    /** Redis 故障日志。 */
    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationRateLimiter.class);

    /** 多实例共享计数。 */
    private final StringRedisTemplate redis;

    /** @param redis Redis 字符串操作入口 */
    public DeviceRegistrationRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 申请一次注册尝试额度。
     *
     * <p>Redis 故障时放行：产品密钥仍是 256 位强认证边界；因辅助设施故障拒绝整条产线注册代价更高。</p>
     *
     * @param clientIp 直连来源 IP
     * @return 是否放行
     */
    public boolean tryAcquire(String clientIp) {
        String normalized = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        String key = KEY_PREFIX + normalized;
        try {
            Long count = redis.execute(INCREMENT_SCRIPT, List.of(key), Long.toString(WINDOW.toMillis()));
            return count == null || count <= MAX_ATTEMPTS_PER_IP;
        } catch (RuntimeException exception) {
            log.error("设备动态注册限流不可用，本次请求放行", exception);
            return true;
        }
    }
}
