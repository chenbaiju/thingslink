package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * 写入 {@code tc.device.uplink.raw} 的原始上行 Kafka 信封。
 *
 * <p>项目与设备 UUID 必须由接入层根据已认证的 Topic 身份派生，绝不能接受设备 payload
 * 自报。原始字节在这一层完整保留，后续解析失败仍能借助 Topic、traceId 与摘要排障。</p>
 *
 * @param tenantId 设备档案所属租户，用于计量聚合
 * @param projectId 已认证项目 ID，也是 RLS 上下文来源
 * @param deviceId 已认证设备 ID，同时固定作为 Kafka 记录键
 * @param topic 原始 MQTT Topic
 * @param payload 原始 MQTT payload 字节
 * @param qos MQTT 服务质量等级，v1 上行契约固定为 1
 * @param retained MQTT retained 标志，v1 上行契约固定为 false
 * @param clientId Broker 观察到的客户端标识
 * @param receivedAt 平台收到消息的 UTC 时刻
 * @param traceId 接入层生成或恢复的链路追踪标识
 * @param authenticatedIdentity Broker认证时身份；旧信封为空，不能据此获得OTA报告资格
 */
public record RawUplinkMessage(UUID tenantId, UUID projectId, UUID deviceId, String topic, byte[] payload,
                               int qos, boolean retained, String clientId, Instant receivedAt, String traceId,
                               AuthenticatedDeviceIdentity authenticatedIdentity) {

    /** 保留旧信封构造入口，其缺失身份不能自动提升为当前凭据事实。 */
    public RawUplinkMessage(UUID tenantId, UUID projectId, UUID deviceId, String topic, byte[] payload,
                            int qos, boolean retained, String clientId, Instant receivedAt, String traceId) {
        this(tenantId, projectId, deviceId, topic, payload, qos, retained, clientId, receivedAt, traceId, null);
    }

    /**
     * 冻结原始上行信封的基础传输约束。
     */
    public RawUplinkMessage {
        if (tenantId == null || projectId == null || deviceId == null) {
            throw new IllegalArgumentException("设备归属不能为空");
        }
        if (authenticatedIdentity != null && (!tenantId.equals(authenticatedIdentity.tenantId())
                || !projectId.equals(authenticatedIdentity.projectId())
                || !deviceId.equals(authenticatedIdentity.deviceId()))) {
            throw new IllegalArgumentException("认证连接身份必须与原始消息权威范围一致");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic 不能为空");
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        if (qos != 1) {
            throw new IllegalArgumentException("上行消息 QoS 必须为 1");
        }
        if (retained) {
            throw new IllegalArgumentException("上行消息禁止 retained");
        }
        if (clientId == null || clientId.isBlank() || receivedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("接入元数据不完整");
        }
        // byte[] 可变；构造和读取时都复制，避免 Kafka 发送前后内容被外部修改。
        payload = payload.clone();
    }

    /**
     * 返回原始载荷的防御性副本。
     *
     * @return payload 副本
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * 返回 Kafka 记录键；S3 的分区有序性依赖该值永远等于 deviceId。
     *
     * @return 设备 ID
     */
    public UUID partitionKey() {
        return deviceId;
    }
}
