package com.things.link.device.infrastructure.emqx;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 连接事件指标的低基数契约测试。 */
class DeviceConnectionMetricsTests {

    /** 上下线只产生两个固定标签组合，且分别独立计数。 */
    @Test
    void shouldRecordPersistedConnectionEventsWithFixedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeviceConnectionMetrics metrics = new DeviceConnectionMetrics(registry);

        metrics.recordConnected();
        metrics.recordConnected();
        metrics.recordDisconnected();

        assertThat(registry.get(DeviceConnectionMetrics.EVENTS).tag("event", "connected").counter().count())
                .isEqualTo(2D);
        assertThat(registry.get(DeviceConnectionMetrics.EVENTS).tag("event", "disconnected").counter().count())
                .isEqualTo(1D);
        assertThat(registry.find(DeviceConnectionMetrics.EVENTS).meters()).hasSize(2);
    }
}
