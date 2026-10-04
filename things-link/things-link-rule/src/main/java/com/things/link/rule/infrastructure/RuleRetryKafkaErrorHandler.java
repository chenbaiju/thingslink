package com.things.link.rule.infrastructure;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.SeekUtils;

/**
 * 规则 retry Topic 的错误处理边界，区分“尚未到期”和“处理失败”。
 *
 * <p>分区退避管理器会先暂停未到期分区，再抛出 Kafka backoff 异常。Spring Kafka 默认错误处理器把该异常固定为
 * 零重试恢复；若继续返回已处理，RECORD ack 会提交当前 offset，进程重启后将永久跳过该重试信封。此处理器只让该类
 * 异常返回未处理，由容器保留当前记录直到分区恢复；其他异常仍使用默认的有界重试与恢复，避免畸形记录热循环。</p>
 */
public final class RuleRetryKafkaErrorHandler extends DefaultErrorHandler {

    /**
     * 使用容器内保留记录而非立即 seek；暂停分区恢复后，容器会重新提交同一记录给 listener。
     */
    public RuleRetryKafkaErrorHandler() {
        setSeekAfterError(false);
    }

    /**
     * 未到期异常必须报告为未处理，禁止容器提交当前 offset；其他异常保持默认有界恢复语义。
     *
     * @param thrownException listener 抛出的异常，可能由容器异常包装
     * @param record 当前消费记录
     * @param consumer 当前 Kafka consumer
     * @param container 当前 listener 容器
     * @return backoff 异常固定为 false，其他异常返回默认处理结果
     */
    @Override
    public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record,
                             Consumer<?, ?> consumer, MessageListenerContainer container) {
        if (SeekUtils.isBackoffException(thrownException)) {
            return false;
        }
        return super.handleOne(thrownException, record, consumer, container);
    }
}
