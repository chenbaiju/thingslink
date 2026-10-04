package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.RealtimeProjectPublisher;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/** 实时 Kafka 桥接器只接受 deviceId 分区键，并把合法增量交给 Redis 端口。 */
@ExtendWith(MockitoExtension.class)
class RealtimeKafkaConsumerTests {

    /** Redis 项目频道发布端口替身。 */
    @Mock private RealtimeProjectPublisher projectPublisher;

    /** 合法的设备 key 必须原样交给项目 Redis 扇出端口。 */
    @Test
    void forwardsDeviceKeyedUpdateToProjectPublisher() {
        DeviceRealtimeUpdate update = update();
        RealtimeKafkaConsumer consumer = new RealtimeKafkaConsumer(projectPublisher);

        consumer.consume(new ConsumerRecord<>("tc.device.realtime", 0, 0, update.deviceId().toString(), update));

        verify(projectPublisher).publish(update);
    }

    /** 错误 key 会打破单设备顺序，必须由公共 Kafka 错误处理器处理而非悄悄纠正。 */
    @Test
    void rejectsRecordWithMismatchedDeviceKey() {
        RealtimeKafkaConsumer consumer = new RealtimeKafkaConsumer(projectPublisher);

        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>(
                "tc.device.realtime", 0, 0, Uuid7.generate().toString(), update())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceId");
    }

    /** 构造一个受冻结契约约束的实时增量。 */
    private static DeviceRealtimeUpdate update() {
        return new DeviceRealtimeUpdate(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), "1.0.0", Instant.parse("2026-08-09T08:00:00Z"), 1,
                "0123456789abcdef0123456789abcdef", Map.of("temperature", "26.5"),
                Map.of("temperature", "NUMBER"));
    }
}
