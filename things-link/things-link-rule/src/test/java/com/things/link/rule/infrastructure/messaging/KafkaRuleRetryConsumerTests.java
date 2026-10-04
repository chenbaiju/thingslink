package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.queue.RuleExecutionCoordinator;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.KafkaConsumerBackoffManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** retry listener 只验证到期门禁与同一协调器入口，不启动真实 broker。 */
class KafkaRuleRetryConsumerTests {

    /** 每条重试记录必须先经过分区级 dueAt 门禁，再进入可靠等待入口。 */
    @Test
    void checksDueTimestampBeforeSubmitting() {
        RuleExecutionCoordinator coordinator = mock(RuleExecutionCoordinator.class);
        KafkaConsumerBackoffManager manager = mock(KafkaConsumerBackoffManager.class);
        KafkaConsumerBackoffManager.Context context = mock(KafkaConsumerBackoffManager.Context.class);
        @SuppressWarnings("unchecked") Consumer<Object, Object> kafkaConsumer = mock(Consumer.class);
        RuleExecutionEnvelope envelope = mock(RuleExecutionEnvelope.class);
        Instant dueAt = Instant.parse("2026-08-13T06:01:00Z");
        when(envelope.enqueuedAt()).thenReturn(dueAt);
        TopicPartition partition = new TopicPartition(KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC, 2);
        when(manager.createContext(dueAt.toEpochMilli(), KafkaRuleRetryConsumer.LISTENER_ID,
                partition, kafkaConsumer)).thenReturn(context);
        KafkaRuleRetryConsumer listener = new KafkaRuleRetryConsumer(coordinator, manager,
                Clock.fixed(Instant.parse("2026-08-13T06:00:00Z"), ZoneOffset.UTC));

        listener.consume(new ConsumerRecord<>(partition.topic(), partition.partition(), 3L,
                "message", envelope), kafkaConsumer);

        verify(manager).backOffIfNecessary(context);
        verify(coordinator).submitAndAwait(envelope);
    }
}
