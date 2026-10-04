package com.things.link.telemetry.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** 概要缓存低基数可观测指标；禁止使用 projectId 作为标签。 */
@Component
public class OverviewCacheMetrics {
    /** 缓存请求计数。 */
    private final Map<Result, Counter> requests = new EnumMap<>(Result.class);
    /** 缓存写失败计数。 */
    private final Counter writeErrors;

    /** @param registryProvider 运行时 Micrometer 注册表；纯模块测试无注册表时使用内存实现 */
    @Autowired
    public OverviewCacheMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** @param registry Micrometer 注册表 */
    OverviewCacheMetrics(MeterRegistry registry) {
        for (Result result : Result.values()) {
            requests.put(result, Counter.builder("thingslink.overview.cache.requests")
                    .description("项目概要缓存读取结果")
                    .tag("result", result.tagValue)
                    .register(registry));
        }
        writeErrors = Counter.builder("thingslink.overview.cache.write.errors")
                .description("项目概要缓存回填失败次数")
                .register(registry);
    }

    /** @param result 缓存读取结果 */
    public void record(Result result) {
        requests.get(result).increment();
    }

    /** 记录一次不影响事实响应的缓存写失败。 */
    public void recordWriteError() {
        writeErrors.increment();
    }

    /** 缓存读取的固定低基数分类。 */
    public enum Result {
        /** 命中有效快照。 */ HIT("hit"),
        /** 键不存在。 */ MISS("miss"),
        /** 值存在但无法解码。 */ INVALID("invalid"),
        /** Redis 访问故障。 */ ERROR("error");

        /** Prometheus 标签值。 */
        private final String tagValue;

        /** @param tagValue Prometheus 标签值 */
        Result(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
