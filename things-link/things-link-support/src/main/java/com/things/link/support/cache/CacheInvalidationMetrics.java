package com.things.link.support.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 三类缓存统一的低基数访问、失效与降级指标。 */
@Component
public class CacheInvalidationMetrics {

    /** 指标标签只使用固定枚举，业务 ID 留在结构化日志。 */
    private final MeterRegistry registry;

    /** @param registry 应用统一指标注册表 */
    @Autowired
    public CacheInvalidationMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** @param registry 显式指标注册表，供隔离单测断言固定标签 */
    public CacheInvalidationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录统一失效事件结果。
     *
     * @param resource 固定缓存资源
     * @param result published/applied/ignored/failure
     */
    public void recordInvalidation(CacheResource resource, Result result) {
        Counter.builder("thingslink.cache.invalidation")
                .description("三类缓存统一失效事件结果")
                .tags("cache_class", resource.cacheClass().name().toLowerCase(),
                        "cache", resource.name().toLowerCase(), "result", result.label)
                .register(registry)
                .increment();
    }

    /**
     * 记录缓存读取结果。
     *
     * @param resource 固定缓存资源
     * @param result hit/miss/load/error
     */
    public void recordAccess(CacheResource resource, AccessResult result) {
        Counter.builder("thingslink.cache.access")
                .description("三类缓存统一读取结果")
                .tags("cache_class", resource.cacheClass().name().toLowerCase(),
                        "cache", resource.name().toLowerCase(), "result", result.label)
                .register(registry)
                .increment();
    }

    /**
     * 记录依赖故障后的固定降级结果。
     *
     * @param resource 固定缓存资源
     * @param result 回源、LKG、安全默认或拒绝
     */
    public void recordFallback(CacheResource resource, FallbackResult result) {
        Counter.builder("thingslink.cache.fallback")
                .description("三类缓存依赖故障后的固定处置")
                .tags("cache_class", resource.cacheClass().name().toLowerCase(),
                        "cache", resource.name().toLowerCase(), "result", result.label)
                .register(registry)
                .increment();
    }

    /** 缓存读取的固定结果集合。 */
    public enum AccessResult {
        /** 命中有效缓存。 */
        HIT("hit"),
        /** 未命中或被版本事件判定过期。 */
        MISS("miss"),
        /** 已从权威源加载并回填。 */
        LOAD("load"),
        /** 缓存或权威源读取异常。 */
        ERROR("error");

        /** Prometheus 固定标签值。 */
        private final String label;

        /** @param label Prometheus 固定标签值 */
        AccessResult(String label) {
            this.label = label;
        }
    }

    /** 依赖故障时的固定处置集合。 */
    public enum FallbackResult {
        /** 派生缓存回源 PostgreSQL 事实。 */
        SOURCE("source"),
        /** 控制面缓存沿用最后已知版本。 */
        LAST_KNOWN_GOOD("last_known_good"),
        /** 冷控制面缓存使用有限安全默认。 */
        SAFE_DEFAULT("safe_default"),
        /** 安全事实不可判定时明确拒绝。 */
        FAIL_CLOSED("fail_closed");

        /** Prometheus 固定标签值。 */
        private final String label;

        /** @param label Prometheus 固定标签值 */
        FallbackResult(String label) {
            this.label = label;
        }
    }

    /** 统一失效事件的固定结果集合。 */
    public enum Result {
        /** Redis 已接受广播。 */
        PUBLISHED("published"),
        /** 本机处理器已经应用。 */
        APPLIED("applied"),
        /** 重复、旧版本或非本机资源被忽略。 */
        IGNORED("ignored"),
        /** 发布、解码或处理失败。 */
        FAILURE("failure");

        /** Prometheus 固定标签值。 */
        private final String label;

        /** @param label Prometheus 固定标签值 */
        Result(String label) {
            this.label = label;
        }
    }
}
