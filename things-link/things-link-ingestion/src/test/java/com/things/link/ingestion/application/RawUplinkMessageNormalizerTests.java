package com.things.link.ingestion.application;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.StandardUplinkMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证设备属性报文解析与可信原始身份到标准信封的映射。 */
class RawUplinkMessageNormalizerTests {

    /** 标准化器使用与 Boot 相同的 Jackson 3 默认时间模块发现机制。 */
    private final RawUplinkMessageNormalizer normalizer =
            new RawUplinkMessageNormalizer(new DevicePropertyReportReader(new ObjectMapper()));

    /** 合法属性报文应保留设备消息标识与采集时间，并只采用接入信封中的归属。 */
    @Test
    void normalizesPropertyReportWithTrustedIdentity() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        Instant occurredAt = Instant.parse("2026-08-05T08:00:00Z");
        Instant receivedAt = Instant.parse("2026-08-05T08:00:01Z");
        RawUplinkMessage raw = rawMessage(tenantId, projectId, deviceId,
                ("{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + occurredAt
                        + "\",\"payload\":{\"temperature\":23.5,\"online\":true}}")
                        .getBytes(StandardCharsets.UTF_8), receivedAt);

        StandardUplinkMessage result = normalizer.normalize(raw);

        assertThat(result.messageId()).isEqualTo(messageId);
        assertThat(result.tenantId()).isEqualTo(tenantId);
        assertThat(result.projectId()).isEqualTo(projectId);
        assertThat(result.deviceId()).isEqualTo(deviceId);
        assertThat(result.protocol()).isEqualTo(TransportProtocol.MQTT);
        assertThat(result.type()).isEqualTo(StandardUplinkMessage.Type.PROPERTY_REPORT);
        assertThat(result.occurredAt()).isEqualTo(occurredAt);
        assertThat(result.receivedAt()).isEqualTo(receivedAt);
        assertThat(result.rawBytes()).isEqualTo(raw.payload().length);
        assertThat(result.payload()).containsEntry("online", true).containsKey("temperature");
    }

    /** 非 UUIDv7 消息标识属于冻结协议错误，应转成错误处理器可识别的不可重试异常。 */
    @Test
    void rejectsNonUuidV7MessageIdAsNonRetryable() {
        RawUplinkMessage raw = rawMessage(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                ("{\"messageId\":\"" + UUID.randomUUID()
                        + "\",\"occurredAt\":\"2026-08-05T08:00:00Z\",\"payload\":{\"temperature\":23}}")
                        .getBytes(StandardCharsets.UTF_8),
                Instant.now());

        assertThatThrownBy(() -> normalizer.normalize(raw))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");
    }

    /** 合法但未实现的消息类型必须携带类型标注进入 DLQ，不能被误当成属性报文或静默丢弃。 */
    @Test
    void rejectsUnsupportedMessageTypeWithStableDiagnostic() {
        RawUplinkMessage raw = new RawUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "tc/v1/project/device/up/event/alarm", "{}".getBytes(StandardCharsets.UTF_8),
                1, false, "device-client", Instant.now(), "0123456789abcdef0123456789abcdef");

        assertThatThrownBy(() -> normalizer.normalize(raw))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessage("暂不支持的消息类型: event/alarm");
    }

    /**
     * 创建满足接入层传输约束的原始信封。
     *
     * @param tenantId 租户标识
     * @param projectId 项目标识
     * @param deviceId 设备标识
     * @param payload 原始设备 JSON
     * @param receivedAt 平台接收时刻
     * @return 原始上行信封
     */
    private static RawUplinkMessage rawMessage(
            UUID tenantId, UUID projectId, UUID deviceId, byte[] payload, Instant receivedAt) {
        return new RawUplinkMessage(
                tenantId, projectId, deviceId, "tc/v1/project/device/up/property/report", payload,
                1, false, "device-client", receivedAt, "0123456789abcdef0123456789abcdef");
    }
}
