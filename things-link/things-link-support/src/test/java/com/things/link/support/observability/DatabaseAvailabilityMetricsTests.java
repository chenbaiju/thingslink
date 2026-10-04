package com.things.link.support.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** G1-C4b-F2 数据库可用性 Gauge 的名称、标签和状态边界测试。 */
class DatabaseAvailabilityMetricsTests {

    /** 初始未知不得冒充故障；首次失败、重复失败和恢复必须形成确定状态转换。 */
    @Test
    void recordsOnlyFrozenPoolAvailabilityStates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DatabaseAvailabilityMetrics metrics = new DatabaseAvailabilityMetrics(registry);

        assertThat(registry.get(DatabaseAvailabilityMetrics.DATABASE_AVAILABLE)
                .tag("pool", "control").gauge().value()).isEqualTo(-1D);
        assertThat(metrics.record("control", false)).isTrue();
        assertThat(metrics.record("control", false)).isFalse();
        assertThat(metrics.state("control")).isZero();
        assertThat(metrics.record("control", true)).isTrue();
        assertThat(registry.get(DatabaseAvailabilityMetrics.DATABASE_AVAILABLE)
                .tag("pool", "control").gauge().value()).isEqualTo(1D);
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.record("tenant-1", false));
    }
}
