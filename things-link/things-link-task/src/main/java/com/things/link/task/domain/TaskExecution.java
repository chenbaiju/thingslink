package com.things.link.task.domain;

import java.time.Instant;
import java.util.UUID;

/** 一次任务触发产生的不可变执行事实与汇总计数。 */
public record TaskExecution(UUID id, UUID tenantId, UUID projectId, UUID jobId, TriggerType triggerType,
                            Status status, Instant scheduledFireAt, TaskJob.TargetType targetType, UUID targetGroupId,
                            String commandKey, String inputJson, UUID requestedBy, int totalTargets, int acceptedTargets,
                            int succeededTargets, int failedTargets, int skippedTargets, Instant startedAt,
                            Instant finishedAt, String failureSummary, String expansionCursor) {

    /** 自动调度或控制台手动触发。 */
    public enum TriggerType { SCHEDULE, MANUAL }
    /** 目标展开、受理与命令终态归并的执行状态机；ADR0066 的 STOPPING 表示已确定停止展开且正在有界收束。 */
    public enum Status {
        /** 按执行快照分页展开目标，尚未进入命令受理阶段。 */
        EXPANDING,
        /** ADR0066 确定停止展开后有界跳过未受理目标并归并既有命令，不因项目恢复而继续展开。 */
        STOPPING,
        /** 目标展开已完成，正在向命令域受理待投递目标。 */
        DISPATCHING,
        /** 全部目标已离开待投递状态，等待已受理命令归并终态。 */
        RUNNING,
        /** 已展开目标全部成功，且没有失败或跳过目标。 */
        SUCCEEDED,
        /** 既有成功目标，也有失败或跳过目标的执行终态。 */
        PARTIAL_FAILED,
        /** 无已展开目标或没有成功目标的执行失败终态。 */
        FAILED
    }
}
