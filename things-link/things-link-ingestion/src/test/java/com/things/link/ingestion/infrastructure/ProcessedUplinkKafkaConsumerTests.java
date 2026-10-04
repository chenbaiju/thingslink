package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.UplinkPreprocessingChain;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.id.Uuid7;
import com.things.link.telemetry.application.PropertyIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 验证 processed 续接点只接受可信设备键，并在规则之后调用 telemetry。 */
class ProcessedUplinkKafkaConsumerTests {

    /** 合法规则结果必须保持完整信封进入物模型事务。 */
    @Test
    void ingestsProcessedMessageAfterPreprocessing() {
        PropertyIngestionService ingestionService = mock(PropertyIngestionService.class);
        StandardUplinkMessage message = message();
        ProcessedUplinkKafkaConsumer consumer = new ProcessedUplinkKafkaConsumer(
                ingestionService, new UplinkPreprocessingChain(List.of()));

        consumer.consume(new ConsumerRecord<>(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                0, 0L, message.deviceId().toString(), message));

        verify(ingestionService).ingest(message);
    }

    /** 错误分区键会破坏设备内顺序，必须在 telemetry 调用前拒绝。 */
    @Test
    void rejectsMismatchedDeviceKey() {
        ProcessedUplinkKafkaConsumer consumer = new ProcessedUplinkKafkaConsumer(
                mock(PropertyIngestionService.class), new UplinkPreprocessingChain(List.of()));

        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>(
                ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC, 0, 0L,
                UUID.randomUUID().toString(), message())))
                .isInstanceOf(InvalidUplinkMessageException.class);
    }

    /** @return 覆盖全部 ADR 0017 可信字段的标准属性信封 */
    private static StandardUplinkMessage message() {
        return new StandardUplinkMessage(Uuid7.generate(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), TransportProtocol.MQTT,
                StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                "1.0.0", Instant.parse("2026-08-13T08:00:00Z"), Instant.parse("2026-08-13T08:00:01Z"),
                "trace-s8-2c", 18, Map.of("temperature", 26.5));
    }
}
