package com.things.link.support.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** S3.5-4 数据面指标名称、标签与负时间保护的单元测试。 */
class DataPlaneMetricsTests {

    /** 各记录入口必须产生可聚合的稳定 meter，未来设备时钟不得向 Timer 写入负值。 */
    @Test
    void recordsStableLowCardinalityMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DataPlaneMetrics metrics = new DataPlaneMetrics(registry);

        metrics.recordRateLimited();
        metrics.recordDeadLetter("tc.device.uplink.raw");
        metrics.recordUplinkVisible(Instant.now().plusSeconds(30));
        metrics.recordBrokerCallback("/api/v1/emqx/auth", 401, Duration.ofMillis(10));

        assertThat(registry.get(DataPlaneMetrics.RATE_LIMITED).counter().count()).isEqualTo(1D);
        assertThat(registry.get(DataPlaneMetrics.DLQ_MESSAGES)
                .tag("source_topic", "tc.device.uplink.raw").counter().count()).isEqualTo(1D);
        assertThat(registry.get(DataPlaneMetrics.UPLINK_END_TO_END).timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .isZero();
        assertThat(registry.get(DataPlaneMetrics.BROKER_CALLBACK)
                .tags("endpoint", "/api/v1/emqx/auth", "result", "failure").timer().count()).isEqualTo(1L);
    }

    /** 真实 Prometheus 导出必须把 300 秒作为最大有限桶，超界样本只能进入 +Inf。 */
    @Test
    void exportsFrozenFiveMinuteUplinkRange() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new DataPlaneMetrics(registry);

        registry.get(DataPlaneMetrics.UPLINK_END_TO_END).timer().record(Duration.ofSeconds(299));
        registry.get(DataPlaneMetrics.UPLINK_END_TO_END).timer().record(Duration.ofSeconds(301));

        String scrape = registry.scrape();
        assertThat(scrape).contains(
                "thingslink_ingestion_uplink_end_to_end_seconds_bucket{le=\"300.0\"} 1",
                "thingslink_ingestion_uplink_end_to_end_seconds_bucket{le=\"+Inf\"} 2");
    }
}
