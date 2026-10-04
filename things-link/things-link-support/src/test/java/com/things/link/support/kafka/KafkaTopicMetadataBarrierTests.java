package com.things.link.support.kafka;

import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.LeaderNotAvailableException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 无容器验证创建传播期屏障，防止基础设施未就绪污染生产并发约束的断言。 */
class KafkaTopicMetadataBarrierTests {

    /** 旧路径在创建成功后直接读 metadata 会暴露传播错误，前置屏障只等待该状态恢复。 */
    @Test
    void immediateOldPathFailsBeforeMetadataBarrierObservesReadiness() {
        Uuid id = Uuid.randomUuid();
        AtomicInteger queries = new AtomicInteger();
        Supplier<Map<String, TopicDescription>> descriptions = () -> {
            if (queries.incrementAndGet() <= 2) {
                throw new UnknownTopicOrPartitionException("创建成功但 metadata 尚未传播");
            }
            return Map.of("raw", topic("raw", id, 3, true));
        };

        assertThatThrownBy(descriptions::get).isInstanceOf(UnknownTopicOrPartitionException.class);
        new KafkaTopicMetadataBarrier(Duration.ofSeconds(1))
                .awaitReady(Map.of("raw", 3), Map.of("raw", id), descriptions);

        assertThat(queries).hasValue(3);
    }

    /** 不存在、leader 未选举、主题缺失与旧身份都不能作为新建 Topic 就绪事实。 */
    @Test
    void waitsThroughPropagationPartialMetadataAndOldIdentityUntilAllTopicsAreReady() {
        Uuid rawId = Uuid.randomUuid();
        Uuid processedId = Uuid.randomUuid();
        AtomicInteger queries = new AtomicInteger();
        var barrier = new KafkaTopicMetadataBarrier(Duration.ofSeconds(3));

        barrier.awaitReady(Map.of("raw", 3, "processed", 4),
                Map.of("raw", rawId, "processed", processedId), () -> switch (queries.incrementAndGet()) {
                    case 1 -> throw new UnknownTopicOrPartitionException("尚未传播");
                    case 2 -> throw new LeaderNotAvailableException("正在选举");
                    case 3 -> Map.of("raw", topic("raw", rawId, 3, true));
                    case 4 -> Map.of("raw", topic("raw", rawId, 3, true),
                            "processed", topic("processed", processedId, 4, false));
                    case 5 -> Map.of("raw", topic("raw", Uuid.randomUuid(), 3, true),
                            "processed", topic("processed", processedId, 4, true));
                    default -> Map.of("raw", topic("raw", rawId, 3, true),
                            "processed", topic("processed", processedId, 4, true));
                });

        assertThat(queries).hasValue(6);
    }

    /** 真实分区比预期更多同样说明测试前置失真，不能只使用大于等于判断。 */
    @Test
    void wrongPartitionCountTimesOutWithExpectedAndActualMetadata() {
        Uuid id = Uuid.randomUuid();
        var barrier = new KafkaTopicMetadataBarrier(Duration.ofMillis(20));

        assertThatThrownBy(() -> barrier.awaitReady(Map.of("raw", 3), Map.of("raw", id),
                () -> Map.of("raw", topic("raw", id, 4, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超时")
                .hasMessageContaining("expected={raw=3}")
                .hasMessageContaining("expectedTopicIds=")
                .hasMessageContaining("actual=");
    }

    /** 永久拒绝类错误应立即暴露原异常，不能被创建传播期轮询隐藏。 */
    @Test
    void authorizationFailurePropagatesWithoutRetry() {
        AtomicInteger queries = new AtomicInteger();
        TopicAuthorizationException failure = new TopicAuthorizationException("无权限");
        var barrier = new KafkaTopicMetadataBarrier(Duration.ofSeconds(1));

        assertThatThrownBy(() -> barrier.awaitReady(Map.of("raw", 3), Map.of("raw", Uuid.randomUuid()), () -> {
            queries.incrementAndGet();
            throw failure;
        })).isSameAs(failure);
        assertThat(queries).hasValue(1);
    }

    /** 短暂错误如果始终不恢复也必须有界失败，并保留最后的 Broker 状态。 */
    @Test
    void persistentUnknownTopicTimesOut() {
        var barrier = new KafkaTopicMetadataBarrier(Duration.ofMillis(20));

        assertThatThrownBy(() -> barrier.awaitReady(Map.of("raw", 3), Map.of("raw", Uuid.randomUuid()), () -> {
            throw new UnknownTopicOrPartitionException("raw 尚未传播");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超时")
                .hasMessageContaining("actual=UnknownTopicOrPartitionException");
    }

    /** 查询后收到取消信号时必须停止轮询，并保留中断标记给上层测试生命周期。 */
    @Test
    void interruptionWhileWaitingPreservesThreadFlag() {
        var barrier = new KafkaTopicMetadataBarrier(Duration.ofSeconds(1));

        try {
            assertThatThrownBy(() -> barrier.awaitReady(Map.of("raw", 3), Map.of("raw", Uuid.randomUuid()), () -> {
                Thread.currentThread().interrupt();
                return Map.of();
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 测试框架复用工作线程，不把本用例的取消标记传播给下一条测试。
            Thread.interrupted();
        }
    }

    /**
     * 构造只含 Topic 身份、分区与 leader 的确定性 metadata，不使用 Broker 或网络。
     *
     * @param name Topic 名称
     * @param id 本轮创建返回的 Topic 身份
     * @param partitions 实际可见分区数
     * @param leaderReady 是否已选出可用 leader
     * @return Kafka metadata 查询结果
     */
    private static TopicDescription topic(String name, Uuid id, int partitions, boolean leaderReady) {
        Node leader = leaderReady ? new Node(0, "localhost", 9092) : Node.noNode();
        List<TopicPartitionInfo> infos = IntStream.range(0, partitions)
                .mapToObj(index -> new TopicPartitionInfo(index, leader, List.of(), List.of()))
                .toList();
        return new TopicDescription(name, false, infos, java.util.Set.of(), id);
    }
}
