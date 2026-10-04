package com.things.link.shared.message;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 S3-11A 三层上行消息契约的不可回退约束。 */
class UplinkMessageContractTests {

    /** 设备属性报文必须使用 UUIDv7、合法属性键并在构造后保持不可变。 */
    @Test
    void devicePropertyReportFreezesIdentityAndPayload() {
        Map<String, Object> values = new HashMap<>();
        values.put("temperature", 26.5);

        DevicePropertyReport report = new DevicePropertyReport(
                Uuid7.generate(), Instant.parse("2026-08-05T10:00:00Z"), "1.0.0", values);
        values.put("temperature", 99.0);

        assertThat(report.messageId().version()).isEqualTo(7);
        assertThat(report.payload()).containsEntry("temperature", 26.5);
        assertThatThrownBy(() -> report.payload().put("switch", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new DevicePropertyReport(
                UUID.randomUUID(), report.occurredAt(), report.modelVersion(), report.payload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");
        assertThatThrownBy(() -> new DevicePropertyReport(
                Uuid7.generate(), report.occurredAt(), report.modelVersion(), Map.of("bad/key", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性标识符");
    }

    /** 原始 Kafka 信封必须坚持 QoS 1、非 retained、deviceId 分区键及字节防御性复制。 */
    @Test
    void rawUplinkFreezesTransportAndPartitionContract() {
        UUID deviceId = Uuid7.generate();
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        RawUplinkMessage message = new RawUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), deviceId,
                "tc/v1/project/device/up/property/report", bytes, 1, false,
                "client-1", Instant.parse("2026-08-05T10:00:01Z"), "trace-1");

        bytes[0] = 'X';
        byte[] returned = message.payload();
        returned[0] = 'Y';

        assertThat(message.partitionKey()).isEqualTo(deviceId);
        assertThat(new String(message.payload(), StandardCharsets.UTF_8)).isEqualTo("payload");
        assertThatThrownBy(() -> new RawUplinkMessage(
                message.tenantId(), message.projectId(), deviceId, message.topic(), message.payload(),
                0, false, message.clientId(), message.receivedAt(), message.traceId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS");
        assertThatThrownBy(() -> new RawUplinkMessage(
                message.tenantId(), message.projectId(), deviceId, message.topic(), message.payload(),
                1, true, message.clientId(), message.receivedAt(), message.traceId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retained");
    }

    /** 标准信封保留设备时钟与平台时钟，设备时钟超前时也不得静默改写事实。 */
    @Test
    void standardEnvelopePreservesUntrustedDeviceTime() {
        Instant occurredAt = Instant.parse("2026-08-05T10:00:05Z");
        Instant receivedAt = Instant.parse("2026-08-05T10:00:00Z");
        StandardUplinkMessage message = new StandardUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", occurredAt, receivedAt, "trace-2",
                Map.of("temperature", 26.5));

        assertThat(message.occurredAt()).isEqualTo(occurredAt);
        assertThat(message.receivedAt()).isEqualTo(receivedAt);
        assertThat(message.payload()).containsEntry("temperature", 26.5);
    }
    /** 历史认证事实精确保留连接范围，不能给网关载荷中的其他设备复用。 */
    @Test
    void authenticatedRawIdentityCannotChangeScopeOrGeneration() {
        var identity = new AuthenticatedDeviceIdentity(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 7L);
        var message = new RawUplinkMessage(identity.tenantId(), identity.projectId(), identity.deviceId(),
                "tc/v1/project/device/up/property/report", new byte[] {1}, 1, false,
                "client", Instant.now(), "trace", identity);
        assertThat(message.authenticatedIdentity()).isEqualTo(identity);
        assertThatThrownBy(() -> new RawUplinkMessage(identity.tenantId(), identity.projectId(), Uuid7.generate(),
                message.topic(), message.payload(), 1, false, "client", message.receivedAt(), "trace", identity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedDeviceIdentity(identity.tenantId(), identity.projectId(),
                identity.deviceId(), -1L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RawUplinkMessage(identity.tenantId(), identity.projectId(), identity.deviceId(),
                message.topic(), message.payload(), 1, false, "client", message.receivedAt(), "trace")
                .authenticatedIdentity()).isNull();
    }

}
