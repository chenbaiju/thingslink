package com.things.link.support.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Outbox 指标只允许使用由部署拓扑约束的低基数标签。 */
class OutboxMetricsTests {

    /** 发布成功与登记重试必须是同一稳定指标的两个固定结果。 */
    @Test
    void recordsDeliveryOutcomesWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry);

        metrics.recordPublished("tc.device.downlink");
        metrics.recordRetry("tc.device.downlink");

        assertThat(registry.get(OutboxMetrics.DELIVERY)
                .tags("topic", "tc.device.downlink", "result", "published").counter().count()).isEqualTo(1D);
        assertThat(registry.get(OutboxMetrics.DELIVERY)
                .tags("topic", "tc.device.downlink", "result", "retry").counter().count()).isEqualTo(1D);
    }
}
