package com.things.link.ingestion.infrastructure.protocol.tcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** 组合分配、撤销、重新初始化及配置边界，防止把局部或过期分配误认成全组就绪。 */
class TcpDownlinkReadinessTests {
    /** 固定下行主题。 */ private static final String TOPIC = "tc.device.downlink";
    /** 只有所有分区位置初始化才就绪；撤销任何一部分立即变为不就绪。 */
    @Test @SuppressWarnings("unchecked") void requiresAllConcurrentAssignments() {
        var gate = new TcpDownlinkReadiness(1, 2, new SimpleMeterRegistry());
        Consumer<Object,Object> a = mock(Consumer.class), b = mock(Consumer.class);
        var first = new TopicPartition(TOPIC, 0); var second = new TopicPartition(TOPIC, 1);
        var metadata = List.of(new PartitionInfo(TOPIC, 0, null, null, null), new PartitionInfo(TOPIC, 1, null, null, null));
        when(a.partitionsFor(eq(TOPIC), any(Duration.class))).thenReturn(metadata);
        when(b.partitionsFor(eq(TOPIC), any(Duration.class))).thenReturn(metadata);
        when(a.assignment()).thenReturn(Set.of(first)); when(b.assignment()).thenReturn(Set.of(second));
        gate.onPartitionsAssigned(a, Set.of(first)); assertThat(gate.ready()).isFalse();
        gate.onPartitionsAssigned(b, Set.of(second)); assertThat(gate.ready()).isFalse();
        // Describe/Position成功不证明READ授权；两个consumer都完成成功poll才可接入。
        gate.sampleLag(a); assertThat(gate.ready()).isFalse(); gate.sampleLag(b); assertThat(gate.ready()).isTrue();
        verify(a).position(eq(first), any(Duration.class)); verify(b).position(eq(second), any(Duration.class));
        gate.onPartitionsRevokedBeforeCommit(a, Set.of(first)); assertThat(gate.ready()).isFalse();
        gate.onPartitionsAssigned(a, Set.of(first)); assertThat(gate.ready()).isFalse();
        gate.sampleLag(a); assertThat(gate.ready()).isTrue();
        gate.onPartitionsLost(b, Set.of(second)); assertThat(gate.ready()).isFalse();
        // Boot会将唯一ConsumerAwareRebalanceListener Bean广播装配，必须只由专属工厂包装接口。
        assertThat(gate).isNotInstanceOf(ConsumerAwareRebalanceListener.class);
    }
    /** 无分配时必须有界拒绝，非法配置不能到运行时静默空闲。 */
    @Test void failsClosedWithoutAssignmentOrWithInvalidConfig() {
        assertThatThrownBy(() -> new TcpDownlinkReadiness(1, 1, new SimpleMeterRegistry()).awaitReady()).hasMessageContaining("未在期限内就绪");
        assertThatThrownBy(() -> new TcpDownlinkReadiness(0, 1, new SimpleMeterRegistry())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpDownlinkReadiness(301, 1, new SimpleMeterRegistry())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpDownlinkReadiness(30, 17, new SimpleMeterRegistry())).isInstanceOf(IllegalArgumentException.class);
    }
    /** Topic 少于配置并发时，即使仅有的分区已完成 poll，也不能先开放 TCP 端口。 */
    @Test @SuppressWarnings("unchecked") void rejectsTooFewPartitionsBeforeTcpBind() {
        var gate = new TcpDownlinkReadiness(1, 2, new SimpleMeterRegistry());
        Consumer<Object,Object> consumer = mock(Consumer.class);
        var partition = new TopicPartition(TOPIC, 0);
        when(consumer.partitionsFor(eq(TOPIC), any(Duration.class)))
                .thenReturn(List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        when(consumer.assignment()).thenReturn(Set.of(partition));
        gate.onPartitionsAssigned(consumer, Set.of(partition));
        gate.sampleLag(consumer);
        assertThat(gate.ready()).isFalse();
        assertThatThrownBy(gate::awaitReady).hasMessageContaining("未在期限内就绪");
    }
    /** 运行身份与消费组始终共用相同UUID，独立实例不能因标签相同而冲突。 */
    @Test void runtimeIdentityIsUniqueAndStable() {
        var a = new DeviceAccessTcpRuntime(); var b = new DeviceAccessTcpRuntime();
        assertThat(a.id()).isNotEqualTo(b.id()); assertThat(a.groupId()).endsWith(a.id());
        assertThat(java.util.UUID.fromString(a.id()).version()).isEqualTo(7);
    }
}
