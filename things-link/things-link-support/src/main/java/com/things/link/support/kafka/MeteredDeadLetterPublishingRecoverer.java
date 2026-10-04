package com.things.link.support.kafka;

import com.things.link.support.observability.DataPlaneMetrics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.util.function.BiFunction;

/**
 * 只在 Kafka broker 确认死信后增加 DLQ 指标的共享恢复器。
 *
 * <p>G1-C4b-F11 后数据链和通知入口都需要相同的“先得到 DLQ ACK、再提交源 offset”边界；
 * 保留在 ingestion 私有包会迫使 support 的 listener 工厂复制实现，进而产生两套失败语义。</p>
 */
public final class MeteredDeadLetterPublishingRecoverer extends DeadLetterPublishingRecoverer {

    /** 统一数据面指标门面。 */
    private final DataPlaneMetrics metrics;

    /**
     * 创建严格等待 broker ACK 的死信恢复器。
     *
     * @param template Kafka 发布入口
     * @param destinationResolver 死信目标解析器
     * @param metrics 统一数据面指标门面
     */
    public MeteredDeadLetterPublishingRecoverer(
            KafkaOperations<?, ?> template,
            BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver,
            DataPlaneMetrics metrics) {
        super(template, destinationResolver);
        this.metrics = metrics;
        // 没有 broker ACK 就抛回错误处理器，禁止提交原 offset，更不能增加“已入 DLQ”计数。
        setFailIfSendResultIsError(true);
    }

    /**
     * 发布死信并在 broker 确认后记录来源主题。
     *
     * @param record 原始失败记录
     * @param consumer 当前消费者
     * @param exception 最终失败原因
     */
    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        super.accept(record, consumer, exception);
        metrics.recordDeadLetter(record.topic());
    }
}
