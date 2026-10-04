package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 心跳保活判据：周期下界、三周期窗口与边界语义。 */
class DeviceAccessTcpKeepAliveTests {

    /** 判定时刻固定，避免用真实时钟让边界断言变得不确定。 */
    private static final Instant LAST_SEEN = Instant.parse("2026-09-18T08:00:00Z");

    /** 小于下界的周期是部署错误：必须拒绝，而不是悄悄夹取成 10 秒。 */
    @Test
    void rejectsIntervalBelowFrozenMinimum() {
        assertThatThrownBy(() -> new DeviceAccessTcpKeepAlive(Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
        assertThatThrownBy(() -> new DeviceAccessTcpKeepAlive(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DeviceAccessTcpKeepAlive(Duration.ofSeconds(10)).interval())
                .as("下界本身合法").isEqualTo(Duration.ofSeconds(10));
    }

    /** 毫秒整数上界必须在启动时校验，禁止转换为负数或无限等待。 */
    @Test void rejectsSocketTimeoutOverflowBeforeDurationConversion(){
        assertThat(new DeviceAccessTcpKeepAlive(DeviceAccessTcpKeepAlive.MAX_INTERVAL).window().toMillis()).isLessThanOrEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(()->new DeviceAccessTcpKeepAlive(DeviceAccessTcpKeepAlive.MAX_INTERVAL.plusMillis(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new DeviceAccessTcpKeepAlive(Duration.ofSeconds(Long.MAX_VALUE))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 窗口是 3 个周期，边界时刻恰好算失效，早一毫秒仍有效。 */
    @Test
    void expiresExactlyAtThreeMissedCycles() {
        DeviceAccessTcpKeepAlive keepAlive = new DeviceAccessTcpKeepAlive(Duration.ofSeconds(30));

        assertThat(keepAlive.window()).isEqualTo(Duration.ofSeconds(90));
        assertThat(keepAlive.intervalMillis()).isEqualTo(30_000L);
        assertThat(keepAlive.expired(LAST_SEEN, LAST_SEEN.plusSeconds(89))).isFalse();
        assertThat(keepAlive.expired(LAST_SEEN, LAST_SEEN.plusSeconds(90))).isTrue();
        assertThat(keepAlive.expired(LAST_SEEN, LAST_SEEN.plusSeconds(200))).isTrue();
    }
}
