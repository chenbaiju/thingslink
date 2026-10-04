package com.things.link.ingestion.application.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 接入认证面预算：按来源 IP 与设备身份各设一道固定窗口，并对连续失败做指数退避。
 *
 * <p>两条窗口回答两个不同问题：来源 IP 窗口挡住同一地址的凭据喷洒；设备身份窗口挡住针对单台设备的定向
 * 爆破——只按 IP 限流时，攻击者换源即可继续猜同一台设备的密钥。退避在失败后按 1s、2s、4s… 翻倍（上限 60s）
 * 并只对该设备生效，成功一次立即清零，因此正常设备的偶发口令错误不会被长期惩罚。</p>
 *
 * <p>窗口与退避都在 Redis 服务端时间内判定，多实例共享同一状态；Redis 不可用时按既有数据面口径
 * fail-open（ADR 0024）：认证正确性仍由凭据校验保证，预算只是额外的抗爆破保护，不能因为缓存故障
 * 让全部设备无法接入。</p>
 */
@Component
public class DeviceAccessAuthBudget {

    /** 所有认证预算键的公共前缀。 */
    private static final String KEY_PREFIX = "access:auth:";

    /** 固定窗口：首次请求设过期，窗口内超出上限即拒绝。 */
    private static final DefaultRedisScript<Long> WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            local current = tonumber(redis.call('GET', KEYS[1])) or 0
            if current >= tonumber(ARGV[1]) then
                return 0
            end
            current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """, Long.class);

    /** 退避检查：返回剩余退避毫秒数，0 表示不在退避中。 */
    private static final DefaultRedisScript<Long> BACKOFF_CHECK_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            local until_ms = tonumber(redis.call('GET', KEYS[1])) or 0
            if until_ms <= now_ms then
                return 0
            end
            return until_ms - now_ms
            """, Long.class);

    /** 失败计数：按 1、2、4… 秒翻倍到上限，并写入退避截止时刻。 */
    private static final DefaultRedisScript<Long> BACKOFF_RECORD_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            local failures = redis.call('INCR', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
            local delay_ms = 1000 * math.min(tonumber(ARGV[1]), 2 ^ (failures - 1))
            redis.call('SET', KEYS[2], now_ms + delay_ms, 'PX', tonumber(ARGV[2]))
            return delay_ms
            """, Long.class);

    /** 认证预算判定结果。 */
    public enum Decision {
        /** 允许继续校验凭据。 */
        ALLOW,
        /** 本窗口预算耗尽：协议层返回 RATE_LIMITED／BUDGET_EXCEEDED。 */
        RATE_LIMITED,
        /** 该设备仍在失败退避窗口内：协议层返回 RATE_LIMITED／429 并带 Retry-After。 */
        BACKOFF
    }

    /** 来源 IP 每分钟允许的认证请求数。 */
    private final int perMinutePerIp;

    /** 单台设备每分钟允许的认证请求数。 */
    private final int perMinutePerDevice;

    /** 失败退避上限秒数。 */
    private final int backoffMaxSeconds;

    /** Redis 文本访问入口。 */
    private final StringRedisTemplate redis;

    /**
     * @param perMinutePerIp 来源 IP 每分钟认证预算（接入合同 §6 默认 30）
     * @param perMinutePerDevice 单设备每分钟认证预算（接入合同 §6 默认 5）
     * @param backoffMaxSeconds 失败退避上限秒数（接入合同 §6 默认 60）
     * @param redis Redis 文本访问入口
     */
    public DeviceAccessAuthBudget(
            @Value("${things-link.access.budget.auth-per-minute-per-ip:30}") int perMinutePerIp,
            @Value("${things-link.access.budget.auth-per-minute-per-device:5}") int perMinutePerDevice,
            @Value("${things-link.access.budget.auth-backoff-max-seconds:60}") int backoffMaxSeconds,
            StringRedisTemplate redis) {
        this.perMinutePerIp = perMinutePerIp;
        this.perMinutePerDevice = perMinutePerDevice;
        this.backoffMaxSeconds = backoffMaxSeconds;
        this.redis = redis;
    }

    /**
     * 认证前的预算与退避判定。
     *
     * @param clientIp 来源 IP；未知或为空时跳过 IP 窗口
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @return 允许继续校验，或两类拒绝之一
     */
    public Decision check(String clientIp, String projectKey, String deviceKey) {
        String identity = identity(projectKey, deviceKey);
        try {
            if (remainingBackoffMillis(identity) > 0) {
                return Decision.BACKOFF;
            }
            if (clientIp != null && !clientIp.isBlank()
                    && !withinWindow(KEY_PREFIX + "ip:" + clientIp, perMinutePerIp)) {
                return Decision.RATE_LIMITED;
            }
            if (!withinWindow(KEY_PREFIX + "device:" + identity, perMinutePerDevice)) {
                return Decision.RATE_LIMITED;
            }
            return Decision.ALLOW;
        } catch (RuntimeException exception) {
            // 预算只是抗爆破附加保护；Redis 故障不得让全部设备无法接入（ADR 0024 fail-open）。
            return Decision.ALLOW;
        }
    }

    /**
     * 记录一次凭据校验失败，产生或延长该设备的指数退避。
     *
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @return 本次退避毫秒数；Redis 不可用时为 0
     */
    public long recordFailure(String projectKey, String deviceKey) {
        String identity = identity(projectKey, deviceKey);
        try {
            Long delay = redis.execute(BACKOFF_RECORD_SCRIPT,
                    java.util.List.of(KEY_PREFIX + "backoff-count:" + identity,
                            KEY_PREFIX + "backoff-until:" + identity),
                    String.valueOf(backoffMaxSeconds), String.valueOf(backoffMaxSeconds * 2000L));
            return delay == null ? 0L : delay;
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    /**
     * 记录一次凭据校验成功，立即清除该设备的失败退避。
     *
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     */
    public void recordSuccess(String projectKey, String deviceKey) {
        String identity = identity(projectKey, deviceKey);
        try {
            redis.delete(java.util.List.of(KEY_PREFIX + "backoff-count:" + identity,
                    KEY_PREFIX + "backoff-until:" + identity));
        } catch (RuntimeException exception) {
            // 清理失败只影响一次退避的存续时间，不影响认证结果。
        }
    }

    /** 固定窗口扣减；窗口首次请求时设置过期。 */
    private boolean withinWindow(String key, int limit) {
        Long allowed = redis.execute(WINDOW_SCRIPT, java.util.List.of(key),
                String.valueOf(limit), String.valueOf(60_000L));
        return allowed != null && allowed == 1L;
    }

    /**
     * 剩余退避毫秒数；0 表示可继续校验。
     *
     * <p>公开给协议层用于生成 {@code Retry-After}：让设备知道等多久，而不是盲目重试继续加重退避。</p>
     *
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @return 剩余毫秒数；不在退避中时为 0
     */
    public long remainingBackoffMillis(String projectKey, String deviceKey) {
        return remainingBackoffMillis(identity(projectKey, deviceKey));
    }

    /** 剩余退避毫秒数；0 表示可继续校验。 */
    private long remainingBackoffMillis(String identity) {
        Long remaining = redis.execute(BACKOFF_CHECK_SCRIPT,
                java.util.List.of(KEY_PREFIX + "backoff-until:" + identity));
        return remaining == null ? 0L : remaining;
    }

    /** 设备身份键：与 MQTT 用户名同形，便于排障时对上同一条日志。 */
    private static String identity(String projectKey, String deviceKey) {
        return projectKey + '/' + deviceKey;
    }
}
