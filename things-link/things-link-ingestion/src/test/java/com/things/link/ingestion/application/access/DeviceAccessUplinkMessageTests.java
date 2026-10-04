package com.things.link.ingestion.application.access;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证新协议接入信封的归属、协议与载荷不可变约束。 */
class DeviceAccessUplinkMessageTests {

    /** 认证身份与消息归属必须逐字一致；任何一项被替换都意味着可以越权写入其他租户。 */
    @Test
    void rejectsIdentityMismatchAcrossTenantProjectAndDevice() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        AuthenticatedDeviceIdentity identity = new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 4);

        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                Uuid7.generate(), projectId, deviceId, TransportProtocol.HTTP, payload(), now(), TRACE, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("权威范围一致");
        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                tenantId, Uuid7.generate(), deviceId, TransportProtocol.HTTP, payload(), now(), TRACE, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("权威范围一致");
        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                tenantId, projectId, Uuid7.generate(), TransportProtocol.HTTP, payload(), now(), TRACE, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("权威范围一致");
    }

    /** 新协议接入不接受匿名信封：没有认证身份就没有可信归属。 */
    @Test
    void rejectsMissingAuthenticatedIdentity() {
        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.TCP, payload(), now(), TRACE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("认证身份");
    }

    /** MQTT 已有原始上行通道，新协议不得借道伪造 MQTT 语义。 */
    @Test
    void rejectsMqttProtocol() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();

        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.MQTT, payload(), now(), TRACE,
                new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MQTT");
    }

    /** 空载荷与缺失接入元数据属于永久错误，必须在构造点暴露而不是留到消费者。 */
    @Test
    void rejectsEmptyPayloadAndIncompleteMetadata() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        AuthenticatedDeviceIdentity identity = new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 1);

        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.COAP, new byte[0], now(), TRACE, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务载荷不能为空");
        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.COAP, null, now(), TRACE, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务载荷不能为空");
        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.COAP, payload(), null, TRACE, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("接入元数据不完整");
        assertThatThrownBy(() -> new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.COAP, payload(), now(), "  ", identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("接入元数据不完整");
    }

    /** 载荷字节在构造与读取时都必须复制，否则发送前后内容可被外部改写。 */
    @Test
    void copiesPayloadOnConstructionAndRead() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        byte[] source = payload();
        DeviceAccessUplinkMessage message = new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.HTTP, source, now(), TRACE,
                new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 1));

        source[0] = 'X';
        assertThat(message.businessPayload()).isEqualTo("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        byte[] read = message.businessPayload();
        read[0] = 'X';
        assertThat(message.businessPayload()).isEqualTo("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(message.partitionKey()).isEqualTo(deviceId.toString());
    }

    /**
     * 构造合法 JSON 载荷。
     *
     * @return 业务载荷字节
     */
    private static byte[] payload() {
        return "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造固定接收时刻。
     *
     * @return 平台接收时刻
     */
    private static Instant now() {
        return Instant.parse("2026-09-18T08:00:00Z");
    }

    /** 测试链路标识。 */
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
}
