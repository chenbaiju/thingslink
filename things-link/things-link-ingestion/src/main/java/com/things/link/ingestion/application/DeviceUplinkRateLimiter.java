package com.things.link.ingestion.application;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.support.observability.DataPlaneMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 使用 Redis 服务端时间执行设备及租户共享上行短窗口限流。
 *
 * <p>设备桶与租户桶分别使用不同 Cluster hash slot，不能放进同一个 Lua 脚本；顺序为租户秒桶、租户
 * 分钟桶、设备桶，后续桶拒绝时已扣的前序令牌不会返还。这种保守消耗宁可少放行也不跨 slot 破坏原子性。
 * 超限消息不进入 raw Kafka，但 HTTP 回调保持成功语义，EMQX 不会重试或断开设备连接。
 */
@Component
public class DeviceUplinkRateLimiter {

    /** 兼容旧单设备测试与策略不可用时的有限基线每秒补充数。 */
    static final int REFILL_TOKENS_PER_SECOND = 10;
    /** 兼容旧单设备测试与策略不可用时的有限基线突发容量。 */
    static final int BUCKET_CAPACITY = 20;
    /** 空闲令牌桶无需永久占用 Redis；一分钟覆盖设备桶从空到满所需时间。 */
    private static final Duration DEVICE_BUCKET_TTL = Duration.ofMinutes(1);
    /** 租户固定窗口 hash 至少保留两个窗口，确保 Redis TIME 窗口切换时不会留下热键。 */
    private static final Duration TENANT_WINDOW_TTL = Duration.ofMinutes(2);
    /** 单设备丢弃明细只供受控排障读取，Prometheus 不带 deviceId 标签。 */
    private static final Duration DROPPED_COUNTER_TTL = Duration.ofDays(1);
    /** 所有上行限流 Redis 键的公共前缀。 */
    private static final String KEY_PREFIX = "ingestion:uplink:rate:";

    /**
     * 单 slot 令牌桶脚本：Redis TIME、补充、扣减、TTL 与设备丢弃计数在一次原子执行中完成。
     */
    private static final DefaultRedisScript<Long> DEVICE_ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens')) or tonumber(ARGV[1])
            local updated_at = tonumber(redis.call('HGET', KEYS[1], 'updated_at')) or now_ms
            local elapsed = math.max(0, now_ms - updated_at)
            tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]) / 1000)
            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at', now_ms)
                redis.call('PEXPIRE', KEYS[1], ARGV[3])
                return 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at', now_ms)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            redis.call('INCR', KEYS[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return 0
            """, Long.class);

    /**
     * 单 slot Redis TIME 固定窗口计数器。窗口起点来自服务端时间而不是首次请求，实例时钟漂移不会放宽套餐。
     */
    private static final DefaultRedisScript<Long> TENANT_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            local window_ms = tonumber(ARGV[1])
            local window_start = math.floor(now_ms / window_ms) * window_ms
            local stored_start = tonumber(redis.call('HGET', KEYS[1], 'window_start'))
            if stored_start == nil or stored_start ~= window_start then
                redis.call('HSET', KEYS[1], 'window_start', window_start, 'count', 0)
            end
            local count = tonumber(redis.call('HINCRBY', KEYS[1], 'count', 1))
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            if count <= tonumber(ARGV[3]) then
                return 1
            end
            return 0
            """, Long.class);

    /** Redis 不可用需要留下 ERROR 证据，但不把辅助过载保护扩大成全量遥测中断。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceUplinkRateLimiter.class);

    /** Redis 字符串与 Lua 执行入口。 */
    private final StringRedisTemplate redis;
    /** 保留 S3 指标名称的兼容数据面总计。 */
    private final DataPlaneMetrics dataPlaneMetrics;
    /** S7-3 低基数运行时维度/结果指标。 */
    private final QuotaRuntimeMetrics quotaMetrics;

    /**
     * Spring 生产构造器。
     *
     * @param redis Redis 字符串与 Lua 执行入口
     * @param dataPlaneMetrics 数据面低基数指标
     * @param quotaMetrics 配额运行时低基数指标
     */
    @Autowired
    public DeviceUplinkRateLimiter(StringRedisTemplate redis, DataPlaneMetrics dataPlaneMetrics,
                                   QuotaRuntimeMetrics quotaMetrics) {
        this.redis = redis;
        this.dataPlaneMetrics = dataPlaneMetrics;
        this.quotaMetrics = quotaMetrics;
    }

    /**
     * 兼容旧隔离测试的构造器；生产装配必须使用带 {@link QuotaRuntimeMetrics} 的构造器。
     *
     * @param redis Redis 字符串与 Lua 执行入口
     * @param dataPlaneMetrics 数据面低基数指标
     */
    public DeviceUplinkRateLimiter(StringRedisTemplate redis, DataPlaneMetrics dataPlaneMetrics) {
        this(redis, dataPlaneMetrics, new QuotaRuntimeMetrics(new SimpleMeterRegistry()));
    }

    /**
     * 按有效租户策略申请上行额度。
     *
     * @param tenantId 经项目归属解析的所有者租户 ID
     * @param deviceId 已确权设备 ID
     * @param policy 有效策略；null 字段代表不限，0 代表本维度禁止新消息
     * @return 是否允许写入 raw Kafka
     */
    public boolean tryAcquire(UUID tenantId, UUID deviceId, EffectiveQuotaPolicy policy) {
        try {
            if (!acquireTenantWindow(tenantId, policy.uplinkTenantPerSecondLimit(), 1_000L,
                    QuotaRuntimeMetrics.QuotaDimension.UPLINK_TENANT_SECOND)) {
                return reject(QuotaRuntimeMetrics.QuotaDimension.UPLINK_TENANT_SECOND);
            }
            if (!acquireTenantWindow(tenantId, policy.uplinkTenantPerMinuteLimit(), 60_000L,
                    QuotaRuntimeMetrics.QuotaDimension.UPLINK_TENANT_MINUTE)) {
                return reject(QuotaRuntimeMetrics.QuotaDimension.UPLINK_TENANT_MINUTE);
            }
            if (!acquireDeviceBucket(deviceId, policy.uplinkDeviceRefillPerSecond(),
                    policy.uplinkDeviceBurstCapacity())) {
                return reject(QuotaRuntimeMetrics.QuotaDimension.UPLINK_DEVICE);
            }
            return true;
        } catch (RedisDecisionUnavailableException exception) {
            // Redis 返回 null 代表脚本执行结果不可判定，不得伪装成明确允许；仍按 ADR 0024 放行数据面。
            quotaMetrics.recordRateLimit(exception.dimension(), QuotaRuntimeMetrics.RateLimitResult.FAIL_OPEN);
            return true;
        } catch (RuntimeException exception) {
            // Redis 只是短窗口保护；Kafka 背压、幂等和持久化仍保护事实链路，因此故障按 ADR 0024 fail-open。
            LOGGER.error("上行 Redis 限流不可用，本次消息降级放行 tenantId={} deviceId={}", tenantId, deviceId,
                    exception);
            quotaMetrics.recordRateLimit(QuotaRuntimeMetrics.QuotaDimension.UPLINK_DEVICE,
                    QuotaRuntimeMetrics.RateLimitResult.FAIL_OPEN);
            return true;
        }
    }

    /**
     * 保留原签名供 S3 回归测试；它使用有限安全默认而非把缺少策略解释成无限。
     *
     * @param deviceId 已确权设备 ID
     * @return 是否允许写入 raw Kafka
     */
    public boolean tryAcquire(UUID deviceId) {
        return tryAcquire(deviceId, deviceId, EffectiveQuotaPolicy.safeDefault(deviceId));
    }

    /** 执行租户固定窗口；null 跳过，0 在访问 Redis 前明确拒绝。 */
    private boolean acquireTenantWindow(UUID tenantId, Long limit, long windowMillis,
                                        QuotaRuntimeMetrics.QuotaDimension dimension) {
        if (limit == null) {
            quotaMetrics.recordRateLimit(dimension, QuotaRuntimeMetrics.RateLimitResult.ALLOWED);
            return true;
        }
        if (limit == 0) {
            return false;
        }
        String slot = "{" + tenantId + "}";
        Long allowed = redis.execute(TENANT_WINDOW_SCRIPT, List.of(
                        KEY_PREFIX + slot + ":tenant:" + windowMillis),
                Long.toString(windowMillis), Long.toString(TENANT_WINDOW_TTL.toMillis()), Long.toString(limit));
        if (allowed == null) {
            throw new RedisDecisionUnavailableException(dimension);
        }
        boolean accepted = allowed == 1L;
        if (accepted) {
            quotaMetrics.recordRateLimit(dimension, QuotaRuntimeMetrics.RateLimitResult.ALLOWED);
        }
        return accepted;
    }

    /** 执行单设备令牌桶；两个 null 代表不限，任一 0 代表禁用。 */
    private boolean acquireDeviceBucket(UUID deviceId, Long refill, Long capacity) {
        if (refill == null && capacity == null) {
            quotaMetrics.recordRateLimit(QuotaRuntimeMetrics.QuotaDimension.UPLINK_DEVICE,
                    QuotaRuntimeMetrics.RateLimitResult.ALLOWED);
            return true;
        }
        if (refill == null || capacity == null || refill == 0 || capacity == 0) {
            return false;
        }
        String slot = "{" + deviceId + "}";
        Long allowed = redis.execute(DEVICE_ACQUIRE_SCRIPT, List.of(
                        KEY_PREFIX + slot + ":bucket", KEY_PREFIX + slot + ":dropped"),
                Long.toString(capacity), Long.toString(refill), Long.toString(DEVICE_BUCKET_TTL.toMillis()),
                Long.toString(DROPPED_COUNTER_TTL.toMillis()));
        if (allowed == null) {
            throw new RedisDecisionUnavailableException(QuotaRuntimeMetrics.QuotaDimension.UPLINK_DEVICE);
        }
        boolean accepted = allowed == 1L;
        if (accepted) {
            quotaMetrics.recordRateLimit(QuotaRuntimeMetrics.QuotaDimension.UPLINK_DEVICE,
                    QuotaRuntimeMetrics.RateLimitResult.ALLOWED);
        }
        return accepted;
    }

    /** 记录拒绝指标且保持 EMQX 200/accepted=false 语义。 */
    private boolean reject(QuotaRuntimeMetrics.QuotaDimension dimension) {
        dataPlaneMetrics.recordRateLimited();
        quotaMetrics.recordRateLimit(dimension, QuotaRuntimeMetrics.RateLimitResult.REJECTED);
        return false;
    }

    /**
     * Redis 脚本未给出可判定结果时携带限流维度，使 fail-open 指标不会错误归因到设备桶。
     */
    private static final class RedisDecisionUnavailableException extends RuntimeException {

        /** 结果不可判定的配额维度。 */
        private final QuotaRuntimeMetrics.QuotaDimension dimension;

        /**
         * @param dimension Redis 未返回决定的配额维度
         */
        private RedisDecisionUnavailableException(QuotaRuntimeMetrics.QuotaDimension dimension) {
            this.dimension = dimension;
        }

        /** @return Redis 未返回决定的配额维度。 */
        private QuotaRuntimeMetrics.QuotaDimension dimension() {
            return dimension;
        }
    }
}
