package com.things.link.ingestion.application.access;

import com.things.link.ingestion.application.DevicePropertyReportReader;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.UplinkTimestampPolicy;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证新协议接入信封到协议无关标准上行的映射与受理前时间边界。 */
class DeviceAccessUplinkNormalizerTests {

    /** 与生产一致的未来时间边界。 */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    /** 被测标准化器。 */
    private final DeviceAccessUplinkNormalizer normalizer = new DeviceAccessUplinkNormalizer(
            new DevicePropertyReportReader(new ObjectMapper()), new UplinkTimestampPolicy(MAX_FUTURE_SKEW));

    /** 三种新协议必须映射到同一标准信封，只保留协议差异供日志与调试面展示。 */
    @Test
    void mapsAllNewProtocolsToSameStandardEnvelope() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        Instant occurredAt = Instant.parse("2026-09-18T08:00:00Z");
        Instant receivedAt = Instant.parse("2026-09-18T08:00:02Z");

        for (TransportProtocol protocol : List.of(
                TransportProtocol.HTTP, TransportProtocol.TCP, TransportProtocol.COAP)) {
            StandardUplinkMessage message = normalizer.normalize(uplink(
                    tenantId, projectId, deviceId, protocol, report(messageId, occurredAt, null, "{\"t\":21}"),
                    receivedAt));

            assertThat(message.messageId()).isEqualTo(messageId);
            assertThat(message.tenantId()).isEqualTo(tenantId);
            assertThat(message.projectId()).isEqualTo(projectId);
            assertThat(message.deviceId()).isEqualTo(deviceId);
            assertThat(message.gatewayId()).isNull();
            assertThat(message.protocol()).isEqualTo(protocol);
            assertThat(message.direction()).isEqualTo(StandardUplinkMessage.Direction.UP);
            assertThat(message.type()).isEqualTo(StandardUplinkMessage.Type.PROPERTY_REPORT);
            assertThat(message.modelVersion()).isNull();
            assertThat(message.occurredAt()).isEqualTo(occurredAt);
            assertThat(message.receivedAt()).isEqualTo(receivedAt);
            assertThat(message.traceId()).isEqualTo(TRACE);
        }
    }

    /** JSON null不是有效属性报文，三种接入协议均须返回可映射业务错误。 */
    @Test
    void nullJsonIsRejectedAcrossAccessProtocols() {
        for (var protocol : List.of(TransportProtocol.HTTP, TransportProtocol.TCP, TransportProtocol.COAP)) {
            assertThatThrownBy(() -> normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(),
                    Uuid7.generate(), protocol, "null".getBytes(StandardCharsets.UTF_8), Instant.now())))
                    .isInstanceOf(InvalidUplinkMessageException.class);
        }
    }

    /** 计费口径的原始字节数必须等于接入面收到的业务载荷长度，不能用规范化 JSON 长度替代。 */
    @Test
    void rawBytesComeFromAccessPayloadLength() {
        byte[] report = report(Uuid7.generate(), Instant.parse("2026-09-18T08:00:00Z"), "1.2.3", "{\"t\":21}");

        StandardUplinkMessage message = normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), TransportProtocol.HTTP, report, Instant.parse("2026-09-18T08:00:01Z")));

        assertThat(message.rawBytes()).isEqualTo(report.length);
        assertThat(message.modelVersion()).isEqualTo("1.2.3");
    }

    /** 设备载荷自报的归属字段只能作为普通属性，不能覆盖认证身份。 */
    @Test
    void payloadCannotOverrideTrustedIdentity() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        String payload = "{\"deviceId\":\"" + Uuid7.generate() + "\",\"projectId\":\"" + Uuid7.generate() + "\"}";

        StandardUplinkMessage message = normalizer.normalize(uplink(tenantId, projectId, deviceId,
                TransportProtocol.TCP, report(Uuid7.generate(), Instant.parse("2026-09-18T08:00:00Z"), null, payload),
                Instant.parse("2026-09-18T08:00:01Z")));

        assertThat(message.tenantId()).isEqualTo(tenantId);
        assertThat(message.projectId()).isEqualTo(projectId);
        assertThat(message.deviceId()).isEqualTo(deviceId);
        assertThat(message.payload()).containsKeys("deviceId", "projectId");
    }

    /** 十进制精度必须与 MQTT 路径一致。 */
    @Test
    void preservesDecimalPrecision() {
        StandardUplinkMessage message = normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), TransportProtocol.COAP,
                report(Uuid7.generate(), Instant.parse("2026-09-18T08:00:00Z"), null,
                        "{\"energy\":9007199254740993.123456789}"),
                Instant.parse("2026-09-18T08:00:01Z")));

        assertThat(message.payload().get("energy")).isEqualTo(new BigDecimal("9007199254740993.123456789"));
    }

    /** 超过未来窗口的设备时间在受理前永久拒绝，不能先受理再进 DLQ。 */
    @Test
    void rejectsOccurredAtBeyondFutureSkewBeforeAcceptance() {
        Instant receivedAt = Instant.parse("2026-09-18T08:00:00Z");

        assertThatThrownBy(() -> normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.HTTP,
                report(Uuid7.generate(), receivedAt.plus(MAX_FUTURE_SKEW).plusSeconds(1), null, "{\"t\":21}"),
                receivedAt)))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("未来偏差");
    }

    /** 恰好等于边界的未来时间仍然受理，边界语义与 MQTT 完全一致。 */
    @Test
    void acceptsOccurredAtExactlyAtFutureSkewBoundary() {
        Instant receivedAt = Instant.parse("2026-09-18T08:00:00Z");

        StandardUplinkMessage message = normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), TransportProtocol.HTTP,
                report(Uuid7.generate(), receivedAt.plus(MAX_FUTURE_SKEW), null, "{\"t\":21}"), receivedAt));

        assertThat(message.occurredAt()).isEqualTo(receivedAt.plus(MAX_FUTURE_SKEW));
    }

    /** 非法 JSON、非 UUIDv7 消息标识与空属性对象都是不可重放恢复的协议错误。 */
    @Test
    void rejectsPermanentPayloadViolations() {
        assertThatThrownBy(() -> normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.TCP, "not-json".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-18T08:00:01Z"))))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");
        assertThatThrownBy(() -> normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.COAP,
                report(UUID.randomUUID(), Instant.parse("2026-09-18T08:00:00Z"), null, "{\"t\":21}"),
                Instant.parse("2026-09-18T08:00:01Z"))))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");
        assertThatThrownBy(() -> normalizer.normalize(uplink(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.HTTP,
                report(Uuid7.generate(), Instant.parse("2026-09-18T08:00:00Z"), null, "{}"),
                Instant.parse("2026-09-18T08:00:01Z"))))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");
    }

    /**
     * 构造与 MQTT 同源的属性上报载荷。
     *
     * @param messageId 设备消息标识
     * @param occurredAt 设备采集时刻
     * @param modelVersion 可选物模型版本
     * @param payload 属性对象 JSON
     * @return 业务载荷字节
     */
    private static byte[] report(UUID messageId, Instant occurredAt, String modelVersion, String payload) {
        String version = modelVersion == null ? "" : ",\"modelVersion\":\"" + modelVersion + "\"";
        return ("{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + occurredAt + "\"" + version
                + ",\"payload\":" + payload + "}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造接入信封。
     *
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param deviceId 权威设备
     * @param protocol 传输协议
     * @param businessPayload 业务载荷
     * @param receivedAt 平台接收时刻
     * @return 新协议接入信封
     */
    private static DeviceAccessUplinkMessage uplink(UUID tenantId, UUID projectId, UUID deviceId,
                                                    TransportProtocol protocol, byte[] businessPayload,
                                                    Instant receivedAt) {
        return new DeviceAccessUplinkMessage(tenantId, projectId, deviceId, protocol, businessPayload, receivedAt,
                TRACE, new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 7));
    }

    /** 测试链路标识。 */
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
}
