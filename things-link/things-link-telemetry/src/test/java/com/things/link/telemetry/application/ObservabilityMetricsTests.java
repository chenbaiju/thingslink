package com.things.link.telemetry.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** S7-5 命令和时序写指标契约测试。 */
class ObservabilityMetricsTests {

    /** 命令受理计时器必须保留样本与 200ms SLO 桶；派发失败与终态（按原因拆分）独立计数。 */
    @Test
    void shouldExposeCommandAcceptanceHistogramAndTerminalCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeviceCommandMetrics metrics = new DeviceCommandMetrics(registry);

        metrics.recordAccepted(Duration.ofMillis(150));
        metrics.recordAccepted(Duration.ofMillis(250));
        metrics.recordDispatchFailure();
        metrics.recordTerminal(DeviceCommandMetrics.REASON_RESPONSE_TIMEOUT);
        metrics.recordTerminal(DeviceCommandMetrics.REASON_DISPATCH_RETRY_EXHAUSTED);

        assertThat(registry.get("thingslink.command.acceptance").timer().count()).isEqualTo(2L);
        assertThat(registry.get("thingslink.command.acceptance").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(400D);
        assertThat(registry.get("thingslink.command.dispatch_failure").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thingslink.command.terminal")
                .tag("reason", "response_timeout").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thingslink.command.terminal")
                .tag("reason", "dispatch_retry_exhausted").counter().count()).isEqualTo(1D);
    }

    /** 时序写只允许 success/failure 两个固定结果。 */
    @Test
    void shouldExposeTimeSeriesWriteResultsWithoutBusinessIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeSeriesWriteMetrics metrics = new TimeSeriesWriteMetrics(registry);

        metrics.recordSuccess();
        metrics.recordFailure();

        assertThat(registry.get(TimeSeriesWriteMetrics.WRITES).tag("result", "success").counter().count())
                .isEqualTo(1D);
        assertThat(registry.get(TimeSeriesWriteMetrics.WRITES).tag("result", "failure").counter().count())
                .isEqualTo(1D);
        assertThat(registry.find(TimeSeriesWriteMetrics.WRITES).meters()).hasSize(2);
    }
}
