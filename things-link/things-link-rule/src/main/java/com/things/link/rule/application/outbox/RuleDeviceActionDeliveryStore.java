package com.things.link.rule.application.outbox;

import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandTerminalEvent;

import java.time.Instant;
import java.util.UUID;

/** 规则或场景设备动作投递事实端口；rule 模块拥有事实，telemetry 只通过终态事件回写。 */
public interface RuleDeviceActionDeliveryStore {
    /** 记录消息规则来源的受理或永久拒绝；actionId 重放必须幂等。 */
    void record(UUID actionId, UUID tenantId, UUID projectId, UUID ruleId, UUID ruleVersionId,
                UUID messageId, UUID deviceId, DeviceCommandDispatch.OperationType operationType,
                UUID commandId, String status, String failureCode, String traceId, Instant createdAt);
    /** 记录手动场景来源的受理或永久拒绝；来源组与规则来源互斥（见 ADR 0030）。 */
    void recordScene(UUID actionId, UUID tenantId, UUID projectId, UUID sceneId, UUID sceneVersionId,
                     UUID sceneExecutionId, UUID deviceId, DeviceCommandDispatch.OperationType operationType,
                     UUID commandId, String status, String failureCode, String traceId, Instant createdAt);
    /** 记录自动化真实执行来源，执行外键不可用时原事务失败。 */
    void recordAutomation(UUID actionId, UUID tenantId, UUID projectId, UUID automationId, UUID automationVersionId,
                     UUID automationExecutionId, UUID deviceId, DeviceCommandDispatch.OperationType operationType,
                     UUID commandId, String status, String failureCode, String traceId, Instant createdAt);
    /** 按 commandId CAS 回写设备终态。 */
    boolean complete(DeviceCommandTerminalEvent event);
}
