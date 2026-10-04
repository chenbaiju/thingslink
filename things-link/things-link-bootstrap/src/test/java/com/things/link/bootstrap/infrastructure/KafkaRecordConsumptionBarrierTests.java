package com.things.link.bootstrap.infrastructure;

import com.things.link.bootstrap.fixture.KafkaRecordConsumptionBarrier;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 精确提交位点屏障的无容器回归，验证重复业务事实不能使新记录被提前认定为完成。 */
@DisplayName("Kafka 本次发送记录消费屏障")
class KafkaRecordConsumptionBarrierTests {

    /** 空批次没有待消费事实，不能因此要求 Kafka 连接可用。 */
    @Test
    void emptyRecordsDoNotQueryKafka() {
        var barrier = new KafkaRecordConsumptionBarrier(Duration.ofSeconds(1), () -> {
            throw new AssertionError("空批次不得查询 Kafka");
        });

        assertThatCode(() -> barrier.awaitConsumed(List.of())).doesNotThrowAnyException();
    }

    /** 同分区只越过较早记录不足以完成，必须越过最大 offset。 */
    @Test
    void samePartitionRequiresMaximumOffsetPlusOne() {
        TopicPartition partition = new TopicPartition("replay", 0);
        AtomicInteger queries = new AtomicInteger();
        var barrier = new KafkaRecordConsumptionBarrier(Duration.ofSeconds(1), () ->
                Map.of(partition, new OffsetAndMetadata(queries.incrementAndGet() == 1 ? 6 : 10)));

        barrier.awaitConsumed(List.of(record(partition, 9), record(partition, 5)));

        assertThat(queries).hasValue(2);
    }

    /** 一个分区完成不能掩盖另一分区缺失或仍停留在目标记录自身位点。 */
    @Test
    void everyPartitionMustPassMissingAndStaleOffsets() {
        TopicPartition first = new TopicPartition("replay", 0);
        TopicPartition second = new TopicPartition("replay", 1);
        AtomicInteger queries = new AtomicInteger();
        var barrier = new KafkaRecordConsumptionBarrier(Duration.ofSeconds(1), () ->
                switch (queries.incrementAndGet()) {
                    case 1 -> Map.of(first, new OffsetAndMetadata(5));
                    case 2 -> Map.of(first, new OffsetAndMetadata(5), second, new OffsetAndMetadata(7));
                    default -> Map.of(first, new OffsetAndMetadata(5), second, new OffsetAndMetadata(8));
                });

        barrier.awaitConsumed(List.of(record(first, 4), record(second, 7)));

        assertThat(queries).hasValue(3);
    }

    /** 显式卡住提交位点，验证屏障在真实推进之前始终阻塞，而非依赖已有回执。 */
    @Test
    void remainsBlockedUntilCurrentRecordIsCommitted() throws Exception {
        TopicPartition partition = new TopicPartition("replay", 0);
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> offsets =
                new AtomicReference<>(Map.of(partition, new OffsetAndMetadata(12)));
        CountDownLatch queried = new CountDownLatch(1);
        var barrier = new KafkaRecordConsumptionBarrier(Duration.ofSeconds(5), () -> {
            queried.countDown();
            return offsets.get();
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var completion = executor.submit(() -> barrier.awaitConsumed(List.of(record(partition, 12))));
            try {
                assertThat(queried.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> completion.get(50, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                offsets.set(Map.of(partition, new OffsetAndMetadata(13)));
                completion.get(2, TimeUnit.SECONDS);
            } finally {
                completion.cancel(true);
            }
        }
    }

    /** 超时必须保留分区期望值和最新事实，便于 CI 区分未订阅与未提交。 */
    @Test
    void timeoutContainsExpectedAndActualOffsets() {
        TopicPartition partition = new TopicPartition("replay", 2);
        var barrier = new KafkaRecordConsumptionBarrier(Duration.ofMillis(30),
                () -> Map.of(partition, new OffsetAndMetadata(12)));

        assertThatThrownBy(() -> barrier.awaitConsumed(List.of(record(partition, 12))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超时")
                .hasMessageContaining("expected={replay-2=13}")
                .hasMessageContaining("actual=")
                .hasMessageContaining("offset=12");
    }

    /** 查询失败不能降级成业务成功或泛化成超时，否则会掩盖 Kafka 组状态故障。 */
    @Test
    void queryFailurePropagatesUnchanged() {
        RuntimeException failure = new IllegalStateException("消费组查询失败");
        var barrier = new KafkaRecordConsumptionBarrier(Duration.ofSeconds(1), () -> {
            throw failure;
        });

        assertThatThrownBy(() -> barrier.awaitConsumed(List.of(record(new TopicPartition("replay", 0), 1))))
                .isSameAs(failure);
    }

    /** 取消信号必须向上传递且保留标记，避免失败测试留下继续运行的异步等待。 */
    @Test
    void interruptionPreservesThreadFlag() {
        var barrier = new KafkaRecordConsumptionBarrier(Duration.ofSeconds(1), Map::of);

        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> barrier.awaitConsumed(List.of(record(new TopicPartition("replay", 0), 1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // JUnit 复用线程，当前用例的中断标记不可污染后续测试。
            Thread.interrupted();
        }
    }

    /**
     * 构造只涉及真实分区与 offset 的发送结果，不引入 Kafka 客户端网络线程。
     *
     * @param partition 本次记录所在分区
     * @param offset Broker 分配给记录的 offset
     * @return 可供屏障消费的发送元数据
     */
    private static RecordMetadata record(TopicPartition partition, long offset) {
        return new RecordMetadata(partition, offset, 0, 0, 0, 0);
    }
}
