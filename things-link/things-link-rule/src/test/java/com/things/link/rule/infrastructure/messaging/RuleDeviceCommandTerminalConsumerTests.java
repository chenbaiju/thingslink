package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.outbox.RuleDeviceActionDeliveryStore;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** S9-2 设备操作终态消费契约测试。 */
class RuleDeviceCommandTerminalConsumerTests {

    /** commandId 分区键一致时才允许回写，保证同一操作的终态顺序稳定。 */
    @Test
    void completesDeliveryWhenPartitionKeyMatchesCommand() {
        RuleDeviceActionDeliveryStore store = mock(RuleDeviceActionDeliveryStore.class);
        RuleDeviceCommandTerminalConsumer consumer = new RuleDeviceCommandTerminalConsumer(store);
        DeviceCommandTerminalEvent event = event();

        consumer.consume(new ConsumerRecord<>(RuleDeviceCommandTerminalConsumer.TOPIC, 0, 0,
                event.commandId().toString(), event));

        verify(store).complete(event);
    }

    /** 不匹配的 Kafka key 可能破坏单操作顺序，必须 fail-closed 交给既有重试/DLQ。 */
    @Test
    void rejectsMismatchedPartitionKey() {
        RuleDeviceCommandTerminalConsumer consumer =
                new RuleDeviceCommandTerminalConsumer(mock(RuleDeviceActionDeliveryStore.class));

        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>(
                RuleDeviceCommandTerminalConsumer.TOPIC, 0, 0, UUID.randomUUID().toString(), event())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造低敏成功终态事实。 */
    private static DeviceCommandTerminalEvent event() {
        return new DeviceCommandTerminalEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), DeviceCommandDispatch.OperationType.PROPERTY_SET,
                DeviceCommandTerminalEvent.Status.SUCCEEDED, null, Instant.parse("2026-08-13T12:00:00Z"),
                "0123456789abcdef0123456789abcdef");
    }
}
