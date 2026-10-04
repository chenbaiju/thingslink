package com.things.link.project.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 配额运行时缓存、失效和限流的低基数指标门面。
 *
 * <p>租户、项目、账号和设备都是无界集合，禁止作为 Prometheus 标签；仅以本枚举冻结的结果和维度
 * 聚合，具体 ID 只能出现在受控日志里。
 */
@Component
public class QuotaRuntimeMetrics {

    /** 策略缓存读写、回源和失效发布的结果计数器名称。 */
    public static final String POLICY_CACHE = "thingslink.quota.policy.cache";
    /** 各配额维度限流决定的结果计数器名称。 */
    public static final String RATE_LIMIT = "thingslink.quota.rate_limit";

    /** 策略缓存结果计数器，键值均为有限枚举。 */
    private final Map<CacheResult, Counter> cacheResults = new EnumMap<>(CacheResult.class);
    /** 限流决定计数器，外层维度和内层结果均为有限枚举。 */
    private final Map<QuotaDimension, Map<RateLimitResult, Counter>> rateLimits =
            new EnumMap<>(QuotaDimension.class);

    /**
     * Spring 装配入口；模块隔离测试未提供指标注册表时退回进程内实现。
     *
     * @param registryProvider 可选指标注册表
     */
    @Autowired
    public QuotaRuntimeMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 显式指标注册表入口，供单元测试使用。
     *
     * @param meterRegistry 指标注册表
     */
    public QuotaRuntimeMetrics(MeterRegistry meterRegistry) {
        for (CacheResult result : CacheResult.values()) {
            cacheResults.put(result, Counter.builder(POLICY_CACHE)
                    .description("配额控制面策略缓存结果")
                    .tag("result", result.tagValue)
                    .register(meterRegistry));
        }
        for (QuotaDimension dimension : QuotaDimension.values()) {
            Map<RateLimitResult, Counter> counters = new EnumMap<>(RateLimitResult.class);
            for (RateLimitResult result : RateLimitResult.values()) {
                counters.put(result, Counter.builder(RATE_LIMIT)
                        .description("配额运行时限流决定")
                        .tags("dimension", dimension.tagValue, "result", result.tagValue)
                        .register(meterRegistry));
            }
            rateLimits.put(dimension, counters);
        }
    }

    /**
     * 记录一次策略缓存结果。
     *
     * @param result 固定缓存结果枚举
     */
    public void recordCache(CacheResult result) {
        cacheResults.get(result).increment();
    }

    /**
     * 记录一次限流决定。
     *
     * @param dimension 固定配额维度
     * @param result 固定限流结果
     */
    public void recordRateLimit(QuotaDimension dimension, RateLimitResult result) {
        rateLimits.get(dimension).get(result).increment();
    }

    /** 策略缓存结果标签。 */
    public enum CacheResult {
        /** 有效本机快照。 */
        HIT("hit"),
        /** 从 PostgreSQL 权威源读取并填充缓存。 */
        LOAD("load"),
        /** 热实例的权威源读取失败，继续使用 LKG。 */
        LAST_KNOWN_GOOD("last_known_good"),
        /** 冷实例无法读取权威源，采用有限代码默认。 */
        SAFE_DEFAULT("safe_default"),
        /** 权威源尚未追上已收到的更高版本事件，继续保留 LKG。 */
        VERSION_LAG("version_lag"),
        /** 为保持有限内存而显式淘汰最旧缓存项。 */
        EVICTED("evicted"),
        /** Redis Pub/Sub 失效消息已发布。 */
        INVALIDATION_PUBLISHED("invalidation_published"),
        /** Redis Pub/Sub 发布失败，TTL 将兜底。 */
        INVALIDATION_PUBLISH_FAILURE("invalidation_publish_failure");

        /** 供 Prometheus 使用的固定标签值。 */
        private final String tagValue;

        /**
         * @param tagValue 固定 Prometheus 标签值
         */
        CacheResult(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** 运行时限流维度标签。 */
    public enum QuotaDimension {
        /** 单设备上行桶。 */
        UPLINK_DEVICE("uplink_device"),
        /** 租户共享上行秒桶。 */
        UPLINK_TENANT_SECOND("uplink_tenant_second"),
        /** 租户共享上行分钟桶。 */
        UPLINK_TENANT_MINUTE("uplink_tenant_minute"),
        /** 单账号读 REST 秒桶。 */
        REST_ACCOUNT_READ_SECOND("rest_account_read_second"),
        /** 单账号写 REST 秒桶。 */
        REST_ACCOUNT_WRITE_SECOND("rest_account_write_second"),
        /** 项目读 REST 分钟桶。 */
        REST_PROJECT_READ_MINUTE("rest_project_read_minute"),
        /** 项目写 REST 分钟桶。 */
        REST_PROJECT_WRITE_MINUTE("rest_project_write_minute"),
        /** 租户读 REST 分钟桶。 */
        REST_TENANT_READ_MINUTE("rest_tenant_read_minute"),
        /** 租户写 REST 分钟桶。 */
        REST_TENANT_WRITE_MINUTE("rest_tenant_write_minute"),
        /** 租户共享 WebSocket 并发连接。 */
        WEBSOCKET_CONNECTION("websocket_connection");

        /** 供 Prometheus 使用的固定标签值。 */
        private final String tagValue;

        /**
         * @param tagValue 固定 Prometheus 标签值
         */
        QuotaDimension(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** 限流决定结果标签。 */
    public enum RateLimitResult {
        /** 运行时桶明确允许请求。 */
        ALLOWED("allowed"),
        /** 运行时桶明确拒绝请求。 */
        REJECTED("rejected"),
        /** 非安全业务桶的 Redis 故障降级放行。 */
        FAIL_OPEN("fail_open"),
        /** 安全边界无法判定时的拒绝。 */
        FAIL_CLOSED("fail_closed");

        /** 供 Prometheus 使用的固定标签值。 */
        private final String tagValue;

        /**
         * @param tagValue 固定 Prometheus 标签值
         */
        RateLimitResult(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
