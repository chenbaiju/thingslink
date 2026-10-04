package com.things.link.telemetry.domain;

import com.things.link.shared.message.DeviceCommandDispatch;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备命令事实聚合。
 *
 * @param id 稳定 commandId @param tenantId 租户 @param projectId 项目
 * @param targetDeviceId 目标设备 @param connectionDeviceId 实际连接设备
 * @param commandDefinitionId 命令定义 @param commandKey 命令键
 * @param inputSchema 输入 Schema 快照 @param outputSchema 输出 Schema 快照
 * @param requestJson 请求对象 JSON @param responseJson 响应对象 JSON
 * @param status 当前状态 @param idempotencyKey 业务幂等键 @param requestedBy 发起账号
 * @param appUserId 发起终端用户（App 数据面，S11-2b）；控制台/任务/规则命令为 null
 * @param timeoutSeconds 单次响应窗口 @param attemptCount 尝试数 @param maxAttempts 最大尝试数
 * @param nextAttemptAt 下次处理时间 @param deadlineAt 当前截止时间
 * @param failureCode 失败码 @param failureMessage 失败摘要 @param traceId 链路 ID
 * @param acceptedAt 受理时间 @param dispatchedAt 派发时间 @param acknowledgedAt ACK 时间
 * @param completedAt 完成时间 @param updatedAt 更新时间
 */
public record DeviceCommand(UUID id, UUID tenantId, UUID projectId,
                            UUID targetDeviceId, UUID connectionDeviceId, UUID commandDefinitionId,
                            DeviceCommandDispatch.OperationType operationType,
                            String commandKey, String inputSchema, String outputSchema,
                            String requestJson, String responseJson, Status status,
                            String idempotencyKey, UUID requestedBy, UUID appUserId, int timeoutSeconds,
                            int attemptCount, int maxAttempts, Instant nextAttemptAt, Instant deadlineAt,
                            String failureCode, String failureMessage, String traceId,
                            Instant acceptedAt, Instant dispatchedAt, Instant acknowledgedAt,
                            Instant completedAt, Instant updatedAt) {
    /** 命令状态；Broker 接受与设备确认必须分段表达。 */
    public enum Status {
        /** API 与 Outbox 同事务受理。 */ ACCEPTED,
        /** EMQX 已接受报文。 */ DISPATCHED,
        /** 设备明确收到但尚未终态。 */ ACKNOWLEDGED,
        /** 设备执行成功。 */ SUCCEEDED,
        /** 设备执行失败、永久协议错误或平台明确拒绝（ADR0070 PROJECT_FROZEN）。 */ FAILED,
        /** 三次派发均未在响应窗口内完成。 */ TIMED_OUT
    }

    /** @return 是否已不可再迁移 */
    public boolean terminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.TIMED_OUT;
    }
}
