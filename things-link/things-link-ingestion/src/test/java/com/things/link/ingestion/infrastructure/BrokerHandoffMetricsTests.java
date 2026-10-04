package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.HandoffDisposition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** durable handoff 指标测试，锁定正常 ACK 与暂时故障的低基数分离。 */
class BrokerHandoffMetricsTests {

    /** 构造器只能为四个可 ACK 结果注册时序，资格专用暂时故障不得污染标签集合。 */
    @Test
    void registersOnlyAckEligibleResultTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BrokerHandoffMetrics metrics = new BrokerHandoffMetrics(registry, Clock.systemUTC());

        metrics.record(HandoffDisposition.ACCEPTED);

        assertThat(registry.find(BrokerHandoffMetrics.HANDOFF_RESULTS).counters()).hasSize(4);
        assertThat(registry.get(BrokerHandoffMetrics.HANDOFF_RESULTS).tag("result", "accepted")
                .counter().count()).isEqualTo(1D);
        assertThat(registry.find(BrokerHandoffMetrics.HANDOFF_RESULTS)
                .tag("result", "transient_retry").counter()).isNull();
    }

    /** 即使调用方误传资格枚举也必须显式拒绝，不能空指针或生成错误 ACK 指标。 */
    @Test
    void rejectsTransientRetryAsAckResult() {
        BrokerHandoffMetrics metrics = new BrokerHandoffMetrics(new SimpleMeterRegistry(), Clock.systemUTC());

        assertThatThrownBy(() -> metrics.record(HandoffDisposition.TRANSIENT_RETRY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得作为 MQTT ACK");
    }
}
