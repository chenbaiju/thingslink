package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.queue.RuleExecutionCoordinator;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.KafkaConsumerBackoffManager;

import java.time.Clock;
import java.util.Objects;

/** 到期后把固定退避 Topic 的信封重新送入同一公平队列。 */
public final class KafkaRuleRetryConsumer {

    /** Spring listener ID 同时是分区暂停管理器定位容器的稳定键。 */
    public static final String LISTENER_ID = "things-link-rule-retry";
    /** 唯一规则恢复消费组。 */
    public static final String GROUP_ID = "things-link-rule-retry";
    /** 生产规则队列唯一入口。 */
    private final RuleExecutionCoordinator coordinator;
    /** 分区级非阻塞退避管理器，不用 sleep 占住 consumer 线程。 */
    private final KafkaConsumerBackoffManager backoffManager;
    /** 可测试 UTC 时钟。 */
    private final Clock clock;

    /** @param coordinator 规则入口 @param backoffManager 分区暂停管理器 @param clock UTC 时钟 */
    public KafkaRuleRetryConsumer(RuleExecutionCoordinator coordinator,
                                  KafkaConsumerBackoffManager backoffManager, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.backoffManager = Objects.requireNonNull(backoffManager, "backoffManager");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 未到期记录由 Spring Kafka 暂停当前 partition、保留 offset 并定时恢复；其他 partition 不受慢规则影响。
     * 到期执行必须等待 continuation/retry/DLQ 与回执形成持久终态后才允许 RECORD ack。
     */
    @KafkaListener(id = LISTENER_ID, groupId = "${things-link.kafka.group-prefix:things-link}-rule-retry",
            containerFactory = "ruleRetryKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.rule-retry:2}",
            topics = {KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC,
                    KafkaRuleRecoveryPublisher.RETRY_FIVE_MINUTES_TOPIC})
    public void consume(ConsumerRecord<String, RuleExecutionEnvelope> record,
                        Consumer<?, ?> consumer) {
        RuleExecutionEnvelope envelope = Objects.requireNonNull(record.value(), "规则重试信封不能为空");
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        backoffManager.backOffIfNecessary(backoffManager.createContext(
                envelope.enqueuedAt().toEpochMilli(), LISTENER_ID, partition, consumer));
        coordinator.submitAndAwait(envelope);
    }
}
