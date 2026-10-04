package com.things.link.rule.infrastructure;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.KafkaBackoffException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 规则 retry 错误处理器必须保留未到期记录，同时继续有界处理真正失败。 */
class RuleRetryKafkaErrorHandlerTests {

    /** 容器包装后的 backoff 异常仍必须返回未处理，禁止 RECORD ack 提交当前 offset。 */
    @Test
    void preservesRecordWhenRetryIsNotDue() {
        RuleRetryKafkaErrorHandler handler = new RuleRetryKafkaErrorHandler();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("tc.rule.retry.1m", 5, 0L,
                "message", "value");
        KafkaBackoffException backoff = new KafkaBackoffException("尚未到期",
                new TopicPartition(record.topic(), record.partition()), "things-link-rule-retry",
                System.currentTimeMillis() + 60_000L);
        ListenerExecutionFailedException wrapped = new ListenerExecutionFailedException("listener 失败", backoff);

        boolean handled = handler.handleOne(wrapped, record, mock(Consumer.class),
                mock(MessageListenerContainer.class));

        assertThat(handled).isFalse();
        assertThat(handler.seeksAfterHandling()).isFalse();
        assertThat(handler.isAckAfterHandle()).isTrue();
    }

    /** 非 backoff 异常仍由默认处理器有界恢复，不能因 offset 修复重新形成永久热循环。 */
    @Test
    void boundsOrdinaryFailuresWithDefaultRecovery() {
        RuleRetryKafkaErrorHandler handler = new RuleRetryKafkaErrorHandler();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("tc.rule.retry.1m", 5, 0L,
                "message", "malformed");
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ListenerExecutionFailedException failure = new ListenerExecutionFailedException(
                "反序列化失败", new IllegalArgumentException("malformed"));

        for (int attempt = 1; attempt < 10; attempt++) {
            assertThat(handler.handleOne(failure, record, consumer, container))
                    .as("第 %s 次失败仍应保留记录", attempt)
                    .isFalse();
        }
        assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
    }
}
