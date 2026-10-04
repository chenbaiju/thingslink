package com.things.link.task.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 任务定义、执行事实与目标快照的持久化端口。 */
public interface TaskJobRepository {
    /** 保存新的任务定义。 */
    void create(TaskJob job);
    /** 读取项目内未删除任务。 */
    Optional<TaskJob> findJob(UUID projectId, UUID jobId);
    /** 列出项目内未删除任务。 */
    List<TaskJob> findJobs(UUID projectId);
    /** 乐观锁更新任务定义。 */
    boolean update(TaskJob job, long expectedVersion);
    /** 软删除任务并停止后续调度。 */
    boolean softDelete(UUID projectId, UUID jobId, long expectedVersion);
    /** 以短租约领取到期调度任务。 */
    List<DueSchedule> claimDueSchedules(int limit);
    /** 以短租约领取待展开或待投递执行。 */
    List<DueExecution> claimDueExecutions(int limit);
    /** 创建执行事实；同一 schedule/job fire-at 的唯一约束吸收多实例重复触发。 */
    Optional<TaskExecution> createScheduledExecution(DueSchedule due, Instant now);
    /** 推进已领取调度的下一次 UTC 触发时刻并释放租约。 */
    boolean advanceSchedule(UUID projectId, UUID jobId, Instant expectedFireAt, Instant nextRunAt);
    /** 创建一次手动执行。 */
    TaskExecution createManualExecution(TaskExecution execution);
    /** 读取执行。 */
    Optional<TaskExecution> findExecution(UUID projectId, UUID executionId);
    /**
     * 按 ADR0066 决策1在调用方原非只读事务内锁定执行，锁后持久身份及状态才是后续推进依据。
     * 三元组不匹配返回空；身份不得为空，禁止事务外调用或另开短事务提前释放锁。
     */
    Optional<TaskExecution> lockExecution(UUID tenantId, UUID projectId, UUID executionId);
    /** 列出任务的执行日志。 */
    List<TaskExecution> findExecutions(UUID projectId, UUID jobId);
    /** 将一页目标快照插入 execution，重复设备忽略。 */
    int appendTargets(UUID executionId, UUID tenantId, UUID projectId, List<UUID> deviceIds);
    /** 更新展开游标；hasMore 为 false 时进入 DISPATCHING。 */
    boolean updateExpansion(UUID executionId, String cursor, boolean hasMore, int appended, Instant now);
    /**
     * 持原执行行锁时，将 EXPANDING 条件转换为 STOPPING 并记录 ADR0066 固定停止原因。
     * 保留已展开总数、游标及原始快照，重复或其他状态返回 false。
     */
    boolean beginStopping(UUID executionId, Instant now);
    /**
     * ADR0067决策3：持原执行行锁时，仅将DISPATCHING转换为STOPPING并记录固定派发停止原因。
     * 不能扩展原EXPANDING转换的适用状态；失败应由调用方抛错回滚本轮。
     */
    boolean beginDispatchStopping(UUID executionId, Instant now);
    /**
     * 持原执行行锁时，以单条有序 SQL 跳过 STOPPING 执行的至多 limit 个 PENDING 目标。
     * ADR0066 决策3限定 limit 为 1..100；目标租约不构成已受理证据，暂被锁住的行留待下轮。
     */
    int skipPendingForStopping(UUID executionId, int limit, Instant now);
    /**
     * 持原执行行锁时，只在 STOPPING 无 PENDING 和 ACCEPTED 后按原规则汇总终态。
     * ADR0066 要求保留总数、最后展开游标及停止原因，终态重放不重复计数。
     */
    boolean completeStoppingExecutionIfReady(UUID executionId, Instant now);
    /**
     * 持原执行行锁时维护尚未结束的 STOPPING 租约：有推进则释放，无推进按本轮 now 重新退避30秒。
     * ADR0066 决策3禁止沿用过期租约或无进展即时重领；其他状态返回 false。
     */
    boolean updateStoppingLease(UUID executionId, boolean progressed, Instant now);
    /** 领取一批 PENDING 目标，避免同一执行被多个实例重复投递。 */
    List<TaskTarget> claimPendingTargets(UUID executionId, int limit);
    /** 绑定已受理的命令事实。 */
    boolean markTargetAccepted(UUID executionId, UUID deviceId, UUID commandId, Instant now);
    /** 记录投递前校验或命令受理失败。 */
    boolean markTargetFailed(UUID executionId, UUID deviceId, String summary, Instant now);
    /** 记录目标设备不支持命令等永久业务拒绝。 */
    boolean markTargetSkipped(UUID executionId, UUID deviceId, String summary, Instant now);
    /** 所有目标都已离开 PENDING 后将执行转入 RUNNING。 */
    boolean markRunningIfNoPending(UUID executionId, Instant now);
    /** 同步命令终态到目标快照。 */
    boolean completeTarget(UUID executionId, UUID deviceId, TaskTarget.Status status, String summary, Instant now);
    /** 返回仍有命令未终态的已受理目标。 */
    List<TaskTarget> findActiveTargets(UUID executionId, int limit);
    /** 在目标全部终态后按汇总计数结束执行。 */
    boolean completeExecutionIfReady(UUID executionId, Instant now);

    /** 被调度器领取的到期任务最小投影。 */
    record DueSchedule(UUID tenantId, UUID projectId, UUID jobId, Instant scheduledFireAt) { }
    /** 被工作器领取的执行最小投影。 */
    record DueExecution(UUID tenantId, UUID projectId, UUID executionId) { }
}
