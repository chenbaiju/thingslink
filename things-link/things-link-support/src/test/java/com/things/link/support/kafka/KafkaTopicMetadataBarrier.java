package com.things.link.support.kafka;

import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.LeaderNotAvailableException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/** 创建 Topic 后的测试前置屏障，等待新 Topic 的完整 metadata，而不重试被测生产守卫。 */
final class KafkaTopicMetadataBarrier {

    /** 创建传播期短轮询，仅依据 metadata 判定就绪而不假设固定延时足够。 */
    private static final long POLL_NANOS = Duration.ofMillis(50).toNanos();

    /** 单调时钟预算，使 Broker 无法就绪时测试仍有界失败。 */
    private final long timeoutNanos;

    /**
     * @param timeout metadata 就绪期限，必须为正数
     */
    KafkaTopicMetadataBarrier(Duration timeout) {
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("Topic metadata 屏障超时必须大于零");
        }
    }

    /**
     * 等待全部新建主题具有精确分区数和可用 leader；旧的同名 Topic 不满足本轮前置条件。
     *
     * @param partitions 每个新建 Topic 的精确分区数
     * @param topicIds 创建请求返回的本轮 Topic 身份
     * @param descriptions 新 Admin 实时读取的 metadata；调用方负责单次查询超时
     */
    void awaitReady(Map<String, Integer> partitions, Map<String, Uuid> topicIds,
                    Supplier<Map<String, TopicDescription>> descriptions) {
        if (!partitions.keySet().equals(topicIds.keySet())) {
            throw new IllegalArgumentException("Topic 分区约束与创建身份目录必须一致");
        }
        long started = System.nanoTime();
        Object actual;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("等待 Topic metadata 时线程被中断");
            }
            try {
                Map<String, TopicDescription> current = descriptions.get();
                actual = current;
                boolean ready = partitions.entrySet().stream().allMatch(entry -> {
                    TopicDescription topic = current.get(entry.getKey());
                    return topic != null && topicIds.get(entry.getKey()).equals(topic.topicId())
                            && topic.partitions().size() == entry.getValue()
                            && topic.partitions().stream().allMatch(partition ->
                                    partition.leader() != null && !partition.leader().isEmpty());
                });
                if (ready) {
                    return;
                }
            } catch (UnknownTopicOrPartitionException | LeaderNotAvailableException propagation) {
                // 只容忍创建传播期这两种明确状态；授权、网络与其他查询故障不得被吞成等待。
                actual = propagation.getClass().getSimpleName() + ": " + propagation.getMessage();
            }
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new IllegalStateException("等待 Topic metadata 超时；expected=" + partitions
                        + ", expectedTopicIds=" + topicIds + ", actual=" + actual);
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(POLL_NANOS, remaining)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Topic metadata 时线程被中断", interrupted);
            }
        }
    }
}
