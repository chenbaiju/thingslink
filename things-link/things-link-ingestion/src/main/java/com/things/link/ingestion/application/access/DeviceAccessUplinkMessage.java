package com.things.link.ingestion.application.access;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.UUID;

/**
 * 新协议接入面（HTTP／TCP／CoAP）交给标准上行管道的协议无关信封。
 *
 * <p>该信封对应接入合同 §3.1 冻结的共同规则：接入面只负责传输、认证与帧解析，业务载荷保持与 MQTT
 * 同源的原始字节，由 {@link DeviceAccessUplinkNormalizer} 统一读取。租户、项目与设备只能来自认证
 * 时签发的 {@link AuthenticatedDeviceIdentity}，设备载荷自报的归属永远不参与确权。</p>
 *
 * <p>MQTT 不进本信封：它已有 {@code RawUplinkMessage} 与 Broker 回调确权通道，允许新协议伪造
 * MQTT 语义会让 QoS、Topic ACL 与 EMQX Hook 结论失去唯一来源。</p>
 *
 * @param tenantId 认证时权威租户
 * @param projectId 认证时权威项目
 * @param deviceId 认证时权威设备，不能用载荷中的子设备替换
 * @param protocol 本次接入使用的传输协议，只允许 HTTP／TCP／CoAP
 * @param businessPayload 与 MQTT 同源的业务载荷原始字节，也是计费口径的原始字节数来源
 * @param receivedAt 接入面收到完整报文的平台时刻
 * @param traceId 接入面生成或恢复的链路追踪标识
 * @param authenticatedIdentity 认证时签发的设备身份事实，不能为历史消息补查当前凭据代际
 */
public record DeviceAccessUplinkMessage(UUID tenantId, UUID projectId, UUID deviceId,
                                        TransportProtocol protocol, byte[] businessPayload,
                                        Instant receivedAt, String traceId,
                                        AuthenticatedDeviceIdentity authenticatedIdentity) {

    /**
     * 冻结新协议接入信封的归属、协议与载荷约束。
     */
    public DeviceAccessUplinkMessage {
        if (tenantId == null || projectId == null || deviceId == null) {
            throw new IllegalArgumentException("设备归属不能为空");
        }
        if (authenticatedIdentity == null) {
            throw new IllegalArgumentException("新协议接入必须携带认证身份");
        }
        if (!tenantId.equals(authenticatedIdentity.tenantId())
                || !projectId.equals(authenticatedIdentity.projectId())
                || !deviceId.equals(authenticatedIdentity.deviceId())) {
            throw new IllegalArgumentException("认证接入身份必须与消息权威范围一致");
        }
        if (protocol == null || protocol == TransportProtocol.MQTT) {
            throw new IllegalArgumentException("接入信封只承载 HTTP／TCP／CoAP，MQTT 走原始上行通道");
        }
        if (businessPayload == null || businessPayload.length == 0) {
            throw new IllegalArgumentException("业务载荷不能为空");
        }
        if (receivedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("接入元数据不完整");
        }
        // byte[] 可变；构造和读取时都复制，避免发送前后内容被外部修改。
        businessPayload = businessPayload.clone();
    }

    /**
     * 返回业务载荷的防御性副本。
     *
     * @return 业务载荷副本
     */
    @Override
    public byte[] businessPayload() {
        return businessPayload.clone();
    }

    /**
     * 返回 Kafka 记录键；同设备有序性依赖该值永远等于 deviceId。
     *
     * @return 设备 ID 字符串
     */
    public String partitionKey() {
        return deviceId.toString();
    }
}
