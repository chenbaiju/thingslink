package com.things.link.bootstrap.fixture;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 以本次发送记录的消费组提交位点作为测试屏障，避免把上一轮业务回执误认作重放完成。
 * 此类只供测试使用；调用方负责管理查询所用的 Kafka Admin 生命周期与查询超时。
 */
public final class KafkaRecordConsumptionBarrier {

    /** 短轮询间隔只用于观察位点推进，不以固定等待时间推断业务已经处理完成。 */
    private static final long POLL_NANOS = Duration.ofMillis(10).toNanos();

    /** 单调时钟预算，防止系统时间校准影响测试截止时间。 */
    private final long timeoutNanos;

    /** 每轮都读取当前消费组位点，不缓存上一轮已经满足的业务事实。 */
    private final Supplier<Map<TopicPartition, OffsetAndMetadata>> committedOffsets;

    /**
     * 构造不持有 Kafka 连接的测试屏障。
     *
     * @param timeout 等待所有记录被提交的最长时间，必须大于零
     * @param committedOffsets 当前消费组提交位点的实时查询，异常原样向外传播
     */
    public KafkaRecordConsumptionBarrier(Duration timeout,
                                  Supplier<Map<TopicPartition, OffsetAndMetadata>> committedOffsets) {
        this.timeoutNanos = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("消费位点屏障超时必须大于零");
        }
        this.committedOffsets = Objects.requireNonNull(committedOffsets, "committedOffsets");
    }

    /**
     * 等待每个分区的提交位点越过本次最后一条记录；Kafka 提交的是下一条待消费记录的位点。
     *
     * @param records 本次实际发送成功的记录，空集合不发起任何查询
     */
    public void awaitConsumed(Collection<RecordMetadata> records) {
        Map<TopicPartition, Long> expected = new LinkedHashMap<>();
        for (RecordMetadata record : records) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            expected.merge(partition, Math.addExact(record.offset(), 1), Math::max);
        }
        if (expected.isEmpty()) {
            return;
        }

        long started = System.nanoTime();
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("等待 Kafka 消费位点时线程被中断");
            }
            Map<TopicPartition, OffsetAndMetadata> actual = committedOffsets.get();
            boolean consumed = expected.entrySet().stream().allMatch(entry -> {
                OffsetAndMetadata offset = actual.get(entry.getKey());
                return offset != null && offset.offset() >= entry.getValue();
            });
            if (consumed) {
                return;
            }

            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new IllegalStateException("等待 Kafka 消费位点超时；expected=" + expected
                        + ", actual=" + actual);
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(POLL_NANOS, remaining)));
            } catch (InterruptedException interrupted) {
                // 不能吞掉测试取消信号，否则主线程已失败时仍可能继续占用消费者和容器。
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Kafka 消费位点时线程被中断", interrupted);
            }
        }
    }
}
