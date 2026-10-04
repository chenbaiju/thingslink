package com.things.link.shared.message;

import java.util.Objects;
import java.util.UUID;

/**
 * ADR0109的事务内告警状态失效事件；消费方必须AFTER_COMMIT才向外发送提示。
 *
 * @param tenantId 告警事实的权威租户
 * @param projectId 告警事实所属项目
 * @param deviceId 告警发起设备，不包含值、事件ID或正文
 */
public record AlarmStateInvalidated(UUID tenantId, UUID projectId, UUID deviceId) {
    /** 拒绝不完整范围，不能用缺失字段发布跨项目提示。 */
    public AlarmStateInvalidated {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(deviceId, "deviceId");
    }
}
