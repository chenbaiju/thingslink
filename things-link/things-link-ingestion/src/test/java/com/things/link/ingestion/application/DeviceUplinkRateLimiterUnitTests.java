package com.things.link.ingestion.application;

import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.project.application.QuotaRuntimeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 单设备上行限流器降级策略的单元测试。 */
class DeviceUplinkRateLimiterUnitTests {

    /** Redis 故障必须降级放行，不能把辅助设施故障扩大成全平台遥测中断。 */
    @Test
    void failsOpenWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), any(), any(Object[].class))).thenThrow(new IllegalStateException("redis down"));

        DataPlaneMetrics metrics = new DataPlaneMetrics(new SimpleMeterRegistry());
        assertThat(new DeviceUplinkRateLimiter(redis, metrics).tryAcquire(UUID.randomUUID())).isTrue();
    }

    /** Redis 脚本返回 null 不是明确允许；仍放行，但必须归因到实际不可判定的租户秒桶。 */
    @Test
    void recordsTenantDimensionFailOpenWhenRedisDecisionIsNull() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeviceUplinkRateLimiter limiter = new DeviceUplinkRateLimiter(redis, new DataPlaneMetrics(registry),
                new QuotaRuntimeMetrics(registry));

        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
        assertThat(registry.get(QuotaRuntimeMetrics.RATE_LIMIT)
                .tags("dimension", "uplink_tenant_second", "result", "fail_open").counter().count()).isEqualTo(1D);
    }
}
