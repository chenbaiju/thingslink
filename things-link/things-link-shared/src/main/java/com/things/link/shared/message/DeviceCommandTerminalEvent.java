package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备命令或属性设置进入终态后，经事务 Outbox 发布的低敏回写事件。
 *
 * @param eventId Outbox 事件与消费幂等 ID
 * @param tenantId 归属租户
 * @param projectId 项目隔离轴
 * @param commandId 稳定设备操作 ID
 * @param operationType 命令或属性设置
 * @param status SUCCEEDED、FAILED 或 TIMED_OUT
 * @param failureCode 设备域稳定失败码，可空
 * @param completedAt 终态 UTC 时刻
 * @param traceId 链路 ID
 */
public record DeviceCommandTerminalEvent(
        UUID eventId,
        UUID tenantId,
        UUID projectId,
        UUID commandId,
        DeviceCommandDispatch.OperationType operationType,
        Status status,
        String failureCode,
        Instant completedAt,
        String traceId) {

    /** 终态回写不能缺少租户隔离轴、业务关联键或固定结果。 */
    public DeviceCommandTerminalEvent {
        if (eventId == null || tenantId == null || projectId == null || commandId == null
                || operationType == null || status == null || completedAt == null
                || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("设备操作终态事件不完整");
        }
    }

    /** 跨模块只暴露封闭终态，不泄露设备响应正文。 */
    public enum Status {
        /** 设备确认执行成功。 */ SUCCEEDED,
        /** 设备明确执行失败。 */ FAILED,
        /** 重试耗尽：三次派发均未形成终态回复（RESPONSE_TIMEOUT），或三次均未交给 Broker（DISPATCH_RETRY_EXHAUSTED）。 */ TIMED_OUT
    }

    /** Outbox 发布器的固定事件类型。 */
    public static final String EVENT_TYPE = "DEVICE_COMMAND_TERMINAL";
}
