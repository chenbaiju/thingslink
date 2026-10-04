package com.things.link.rule.application.engine;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 规则内核使用的不可变消息快照；身份字段对齐 ADR 0017，节点只能派生 payload 或 metadata。
 *
 * @param messageId 上行消息的稳定幂等标识
 * @param tenantId 项目 owner tenant，用于后续配额归属
 * @param projectId 数据隔离项目
 * @param deviceId 已确权的目标设备
 * @param traceId 全链路追踪标识
 * @param occurredAt 设备事件时间
 * @param type 冻结的消息类型
 * @param payload 节点可读取或派生的 JSON 载荷
 * @param metadata 节点可读取或派生的字符串元数据
 */
public record RuleMessage(
        UUID messageId,
        UUID tenantId,
        UUID projectId,
        UUID deviceId,
        String traceId,
        Instant occurredAt,
        String type,
        JsonNode payload,
        Map<String, String> metadata) {

    /** 对可变 JSON 树和 Map 做防御性复制，保证调用者不能从记录外修改消息。 */
    public RuleMessage {
        Objects.requireNonNull(messageId, "messageId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(projectId, "projectId 不能为空");
        Objects.requireNonNull(deviceId, "deviceId 不能为空");
        Objects.requireNonNull(traceId, "traceId 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(payload, "payload 不能为空");
        payload = payload.deepCopy();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 每次读取都返回副本，防止 Jackson 节点的可变 API 穿透记录边界。 */
    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    /** 派生新载荷时保留 ADR 0017 禁止修改的全部可信身份字段。 */
    public RuleMessage withPayload(JsonNode newPayload) {
        return new RuleMessage(messageId, tenantId, projectId, deviceId, traceId, occurredAt, type,
                newPayload, metadata);
    }

    /** 派生新元数据时保留消息身份和当前载荷。 */
    public RuleMessage withMetadata(Map<String, String> newMetadata) {
        return new RuleMessage(messageId, tenantId, projectId, deviceId, traceId, occurredAt, type,
                payload, newMetadata);
    }
}
