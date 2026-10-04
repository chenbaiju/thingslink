package com.things.link.ingestion.infrastructure.access;

import com.things.link.ingestion.application.access.DeviceAccessHandoffUnavailableException;
import com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证新协议标准上行进入既有 normalized 主题的接管语义。 */
class KafkaDeviceAccessUplinkPublisherTests {

    /** Kafka 生产模板替身。 */
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** 被测发布器。 */
    private KafkaDeviceAccessUplinkPublisher publisher;

    /** 每个用例使用全新替身，避免交互计数串场。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new KafkaDeviceAccessUplinkPublisher(kafkaTemplate);
    }

    /** 标准上行必须复用 MQTT 的主题与 deviceId 分区键，不得为新协议另建主题。 */
    @Test
    void publishesToSharedNormalizedTopicWithDeviceKey() {
        StandardUplinkMessage message = message();
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(message);

        verify(kafkaTemplate).send(RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC,
                message.deviceId().toString(), message);
    }

    /** broker 未确认接管时必须抛交接不可用，禁止让协议层误回受理。 */
    @Test
    void surfacesBrokerFailureAsHandoffUnavailable() {
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> publisher.publish(message()))
                .isInstanceOf(DeviceAccessHandoffUnavailableException.class)
                .hasMessageContaining("总线未确认接管");
    }

    /** 等待确认期间线程被中断必须恢复中断标志，让上层停止继续接收请求。 */
    @Test
    void restoresInterruptFlagWhenWaitingIsInterrupted() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publish(message()))
                    .isInstanceOf(DeviceAccessHandoffUnavailableException.class)
                    .hasMessageContaining("被中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * 构造标准上行信封。
     *
     * @return 标准上行信封
     */
    private static StandardUplinkMessage message() {
        UUID deviceId = Uuid7.generate();
        return new StandardUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), deviceId, null,
                TransportProtocol.HTTP, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, null,
                Instant.parse("2026-09-18T08:00:00Z"), Instant.parse("2026-09-18T08:00:01Z"),
                "0123456789abcdef0123456789abcdef", 24, Map.of("temperature", 21));
    }
}
