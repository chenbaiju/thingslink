package com.things.link.task.infrastructure.persistence;

import com.things.link.shared.id.Uuid7;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskJob;
import com.things.link.task.domain.TaskJobRepository;
import com.things.link.task.domain.TaskTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC 实现：所有跨项目后台领取均委托受控数据库函数取得短租约。 */
@Repository
public class JdbcTaskJobRepository implements TaskJobRepository {

    /** JDBC 数据库访问门面。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc JDBC 数据库访问门面 */
    public JdbcTaskJobRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void create(TaskJob job) {
        jdbc.update("""
                INSERT INTO task_job (id, tenant_id, project_id, name, description, status, version, target_type,
                    target_group_id, command_key, input, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, job.id(), job.tenantId(), job.projectId(), job.name(), job.description(), job.status().name(),
                job.version(), job.targetType().name(), job.targetGroupId(), job.commandKey(), job.inputJson(),
                job.createdBy(), time(job.createdAt()), time(job.updatedAt()));
        jdbc.update("""
                INSERT INTO task_schedule (id, tenant_id, project_id, job_id, schedule_type, run_at, cron_expression,
                    timezone, next_run_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Uuid7.generate(), job.tenantId(), job.projectId(), job.id(), job.scheduleType().name(),
                time(job.runAt()), job.cronExpression(), job.timezone(), time(job.nextRunAt()),
                time(job.createdAt()), time(job.updatedAt()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<TaskJob> findJob(UUID projectId, UUID jobId) {
        return jdbc.query(selectJobs() + " WHERE j.project_id = ? AND j.id = ? AND j.deleted_at IS NULL",
                this::mapJob, projectId, jobId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<TaskJob> findJobs(UUID projectId) {
        return jdbc.query(selectJobs() + " WHERE j.project_id = ? AND j.deleted_at IS NULL ORDER BY j.updated_at DESC", this::mapJob, projectId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean update(TaskJob job, long expectedVersion) {
        int changed = jdbc.update("""
                UPDATE task_job SET name = ?, description = ?, status = ?, target_type = ?, target_group_id = ?,
                    command_key = ?, input = ?::jsonb, version = version + 1, updated_at = ?
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, job.name(), job.description(), job.status().name(), job.targetType().name(), job.targetGroupId(),
                job.commandKey(), job.inputJson(), time(job.updatedAt()), job.projectId(), job.id(), expectedVersion);
        if (changed != 1) return false;
        int scheduleChanged = jdbc.update("""
                UPDATE task_schedule SET schedule_type = ?, run_at = ?, cron_expression = ?, timezone = ?,
                    next_run_at = ?, lease_until = NULL, updated_at = ? WHERE project_id = ? AND job_id = ?
                """, job.scheduleType().name(), time(job.runAt()), job.cronExpression(), job.timezone(),
                time(job.nextRunAt()), time(job.updatedAt()), job.projectId(), job.id());
        // 每个有效任务必须恰有一条调度定义；静默接受零行会提交无法调度的半份任务配置。
        if (scheduleChanged != 1) throw new IllegalStateException("任务定义更新必须同步更新唯一调度定义");
        return true;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean softDelete(UUID projectId, UUID jobId, long expectedVersion) {
        return jdbc.update("""
                UPDATE task_job SET deleted_at = now(), status = 'PAUSED', version = version + 1, updated_at = now()
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, projectId, jobId, expectedVersion) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<DueSchedule> claimDueSchedules(int limit) {
        return jdbc.query("SELECT tenant_id, project_id, job_id, scheduled_fire_at FROM claim_due_task_schedules(?)",
                (rs, row) -> new DueSchedule(rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("job_id", UUID.class), instant(rs, "scheduled_fire_at")), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<DueExecution> claimDueExecutions(int limit) {
        return jdbc.query("SELECT tenant_id, project_id, execution_id FROM claim_due_task_executions(?)",
                (rs, row) -> new DueExecution(rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("execution_id", UUID.class)), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<TaskExecution> createScheduledExecution(DueSchedule due, Instant now) {
        UUID id = Uuid7.generate();
        int inserted = jdbc.update("""
                INSERT INTO task_execution (id, tenant_id, project_id, job_id, trigger_type, status, scheduled_fire_at,
                    target_type, target_group_id, command_key, input, requested_by, started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SCHEDULE', 'EXPANDING', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) ON CONFLICT (job_id, scheduled_fire_at) WHERE scheduled_fire_at IS NOT NULL DO NOTHING
                """, id, due.tenantId(), due.projectId(), due.jobId(), time(due.scheduledFireAt()),
                jobTargetType(due.projectId(), due.jobId()).name(), jobTargetGroup(due.projectId(), due.jobId()),
                jobCommandKey(due.projectId(), due.jobId()), jobInput(due.projectId(), due.jobId()), jobRequestedBy(due.projectId(), due.jobId()), time(now), time(now), time(now));
        return inserted == 1 ? findExecution(due.projectId(), id) : Optional.empty();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean advanceSchedule(UUID projectId, UUID jobId, Instant expectedFireAt, Instant nextRunAt) {
        return jdbc.update("""
                UPDATE task_schedule SET next_run_at = ?, lease_until = NULL, updated_at = now()
                 WHERE project_id = ? AND job_id = ? AND next_run_at = ?
                """, time(nextRunAt), projectId, jobId, time(expectedFireAt)) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public TaskExecution createManualExecution(TaskExecution execution) {
        jdbc.update("""
                INSERT INTO task_execution (id, tenant_id, project_id, job_id, trigger_type, status, target_type, target_group_id,
                    command_key, input, requested_by, started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MANUAL', 'EXPANDING', ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, execution.id(), execution.tenantId(), execution.projectId(), execution.jobId(),
                execution.targetType().name(), execution.targetGroupId(), execution.commandKey(), execution.inputJson(), execution.requestedBy(),
                time(execution.startedAt()), time(execution.startedAt()), time(execution.startedAt()));
        return findExecution(execution.projectId(), execution.id()).orElseThrow();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<TaskExecution> findExecution(UUID projectId, UUID executionId) {
        return jdbc.query(selectExecutions() + " WHERE project_id = ? AND id = ?", this::mapExecution,
                projectId, executionId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<TaskExecution> lockExecution(UUID tenantId, UUID projectId, UUID executionId) {
        Objects.requireNonNull(tenantId, "执行租户身份不能为空");
        Objects.requireNonNull(projectId, "执行项目身份不能为空");
        Objects.requireNonNull(executionId, "执行身份不能为空");
        // ADR0066 决策1要求锁延续至原业务提交；自动提交会在读取后立即释放，不能充当执行互斥。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("执行锁定读取必须在原非只读事务中调用");
        }
        return jdbc.query(selectExecutions() + " WHERE tenant_id = ? AND project_id = ? AND id = ? FOR UPDATE",
                this::mapExecution, tenantId, projectId, executionId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<TaskExecution> findExecutions(UUID projectId, UUID jobId) {
        return jdbc.query(selectExecutions() + " WHERE project_id = ? AND job_id = ? ORDER BY started_at DESC",
                this::mapExecution, projectId, jobId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public int appendTargets(UUID executionId, UUID tenantId, UUID projectId, List<UUID> deviceIds) {
        int appended = 0;
        for (UUID deviceId : deviceIds) appended += jdbc.update("""
                INSERT INTO task_target (execution_id, tenant_id, project_id, device_id, status)
                VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT (execution_id, device_id) DO NOTHING
                """, executionId, tenantId, projectId, deviceId);
        return appended;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean updateExpansion(UUID executionId, String cursor, boolean hasMore, int appended, Instant now) {
        return jdbc.update("""
                UPDATE task_execution SET expansion_cursor = ?, total_targets = total_targets + ?,
                    status = CASE WHEN ? THEN 'EXPANDING' ELSE 'DISPATCHING' END, lease_until = NULL, updated_at = ?
                 WHERE id = ? AND status = 'EXPANDING'
                """, cursor, appended, hasMore, time(now), executionId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean beginStopping(UUID executionId, Instant now) {
        // 停止原因只用于展示；ADR0066 决策2以持久状态控制重入，不改动已经展开的历史事实。
        return jdbc.update("""
                UPDATE task_execution SET status = 'STOPPING', failure_summary = '项目已冻结，目标展开停止', updated_at = ?
                 WHERE id = ? AND status = 'EXPANDING'
                """, time(now), executionId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean beginDispatchStopping(UUID executionId, Instant now) {
        // ADR0067决策3区分停止来源，保留原执行快照、全部计数及目标事实。
        return jdbc.update("""
                UPDATE task_execution SET status = 'STOPPING', failure_summary = '项目已冻结，任务派发停止', updated_at = ?
                 WHERE id = ? AND status = 'DISPATCHING'
                """, time(now), executionId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public int skipPendingForStopping(UUID executionId, int limit, Instant now) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("停止收束目标批量必须在1到100之间");
        }
        // 调用方先持执行锁；本语句再按稳定设备顺序锁目标，遵守 ADR0066 决策1、3的锁序与有限批量。
        // 不筛选目标 lease：PENDING 的旧租约不代表命令已受理，真正的行锁冲突由 SKIP LOCKED 留待下轮。
        return jdbc.update("""
                WITH pending AS (
                    SELECT t.execution_id, t.device_id FROM task_target t
                     WHERE t.execution_id = ? AND t.status = 'PENDING'
                       AND EXISTS (SELECT 1 FROM task_execution e WHERE e.id = t.execution_id AND e.status = 'STOPPING')
                     ORDER BY t.device_id FOR UPDATE OF t SKIP LOCKED LIMIT ?
                ) UPDATE task_target t SET status = 'SKIPPED', failure_summary = '项目已冻结，目标未受理',
                          completed_at = ?, updated_at = ?, lease_until = NULL
                   FROM pending p WHERE t.execution_id = p.execution_id AND t.device_id = p.device_id
                     AND t.status = 'PENDING'
                """, executionId, limit, time(now), time(now));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean completeStoppingExecutionIfReady(UUID executionId, Instant now) {
        // ADR0066 决策3要求同时排除未受理与已受理未终态目标，不能套用旧分支只检查 ACCEPTED 的完成条件。
        // 汇总读取本执行全部目标，但不重写 total/cursor/failure_summary；100条上限仅约束每轮目标变更。
        return jdbc.update("""
                UPDATE task_execution e SET accepted_targets = s.accepted, succeeded_targets = s.succeeded,
                    failed_targets = s.failed, skipped_targets = s.skipped,
                    status = CASE WHEN e.total_targets = 0 THEN 'FAILED'
                                  WHEN s.failed = 0 AND s.skipped = 0 THEN 'SUCCEEDED'
                                  WHEN s.succeeded = 0 THEN 'FAILED' ELSE 'PARTIAL_FAILED' END,
                    finished_at = ?, lease_until = NULL, updated_at = ?
                  FROM (SELECT count(*) FILTER (WHERE status IN ('PENDING','ACCEPTED')) unfinished,
                               count(*) FILTER (WHERE status IN ('ACCEPTED','SUCCEEDED','FAILED','TIMED_OUT')) accepted,
                               count(*) FILTER (WHERE status = 'SUCCEEDED') succeeded,
                               count(*) FILTER (WHERE status IN ('FAILED','TIMED_OUT')) failed,
                               count(*) FILTER (WHERE status = 'SKIPPED') skipped
                          FROM task_target WHERE execution_id = ?) s
                 WHERE e.id = ? AND e.status = 'STOPPING' AND s.unfinished = 0
                """, time(now), time(now), executionId, executionId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean updateStoppingLease(UUID executionId, boolean progressed, Instant now) {
        // ADR0066 决策3沿用30秒领取租约；无进展从本轮处理时间重算，避免过期租约造成忙轮询。
        return jdbc.update("""
                UPDATE task_execution SET lease_until = ?, updated_at = ? WHERE id = ? AND status = 'STOPPING'
                """, progressed ? null : time(now.plusSeconds(30)), time(now), executionId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<TaskTarget> claimPendingTargets(UUID executionId, int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT execution_id, device_id FROM task_target
                     WHERE execution_id = ? AND status = 'PENDING' AND (lease_until IS NULL OR lease_until < now())
                     ORDER BY device_id FOR UPDATE SKIP LOCKED LIMIT ?
                ) UPDATE task_target t SET lease_until = now() + interval '30 seconds'
                  FROM due WHERE t.execution_id = due.execution_id AND t.device_id = due.device_id
                RETURNING t.execution_id, t.tenant_id, t.project_id, t.device_id, t.status, t.command_id,
                          t.failure_summary, t.accepted_at, t.completed_at
                """, this::mapTarget, executionId, limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markTargetAccepted(UUID executionId, UUID deviceId, UUID commandId, Instant now) {
        return jdbc.update("""
                UPDATE task_target SET status = 'ACCEPTED', command_id = ?, accepted_at = ?, lease_until = NULL, updated_at = ?
                 WHERE execution_id = ? AND device_id = ? AND status = 'PENDING'
                """, commandId, time(now), time(now), executionId, deviceId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markTargetFailed(UUID executionId, UUID deviceId, String summary, Instant now) {
        return jdbc.update("""
                UPDATE task_target SET status = 'FAILED', failure_summary = ?, completed_at = ?, lease_until = NULL, updated_at = ?
                 WHERE execution_id = ? AND device_id = ? AND status = 'PENDING'
                """, summary, time(now), time(now), executionId, deviceId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markTargetSkipped(UUID executionId, UUID deviceId, String summary, Instant now) {
        return jdbc.update("""
                UPDATE task_target SET status = 'SKIPPED', failure_summary = ?, completed_at = ?, lease_until = NULL, updated_at = ?
                 WHERE execution_id = ? AND device_id = ? AND status = 'PENDING'
                """, summary, time(now), time(now), executionId, deviceId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markRunningIfNoPending(UUID executionId, Instant now) {
        return jdbc.update("""
                UPDATE task_execution e SET status = 'RUNNING', lease_until = NULL, updated_at = ?
                 WHERE e.id = ? AND e.status = 'DISPATCHING'
                   AND NOT EXISTS (SELECT 1 FROM task_target t WHERE t.execution_id = e.id AND t.status = 'PENDING')
                """, time(now), executionId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean completeTarget(UUID executionId, UUID deviceId, TaskTarget.Status status, String summary, Instant now) {
        return jdbc.update("""
                UPDATE task_target SET status = ?, failure_summary = ?, completed_at = ?, updated_at = ?
                 WHERE execution_id = ? AND device_id = ? AND status = 'ACCEPTED'
                """, status.name(), summary, time(now), time(now), executionId, deviceId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<TaskTarget> findActiveTargets(UUID executionId, int limit) {
        return jdbc.query("""
                SELECT execution_id, tenant_id, project_id, device_id, status, command_id, failure_summary, accepted_at, completed_at
                  FROM task_target WHERE execution_id = ? AND status = 'ACCEPTED' ORDER BY device_id LIMIT ?
                """, this::mapTarget, executionId, limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean completeExecutionIfReady(UUID executionId, Instant now) {
        return jdbc.update("""
                UPDATE task_execution e SET accepted_targets = s.accepted, succeeded_targets = s.succeeded,
                    failed_targets = s.failed, skipped_targets = s.skipped,
                    status = CASE WHEN e.total_targets = 0 THEN 'FAILED'
                                  WHEN s.failed = 0 AND s.skipped = 0 THEN 'SUCCEEDED'
                                  WHEN s.succeeded = 0 THEN 'FAILED' ELSE 'PARTIAL_FAILED' END,
                    finished_at = ?, lease_until = NULL, updated_at = ?
                  FROM (SELECT count(*) FILTER (WHERE status = 'ACCEPTED') active,
                               count(*) FILTER (WHERE status IN ('ACCEPTED','SUCCEEDED','FAILED','TIMED_OUT')) accepted,
                               count(*) FILTER (WHERE status = 'SUCCEEDED') succeeded,
                               count(*) FILTER (WHERE status IN ('FAILED','TIMED_OUT')) failed,
                               count(*) FILTER (WHERE status = 'SKIPPED') skipped
                          FROM task_target WHERE execution_id = ?) s
                 WHERE e.id = ? AND s.active = 0 AND e.status IN ('DISPATCHING','RUNNING')
                """, time(now), time(now), executionId, executionId) == 1;
    }

    /** 返回任务及调度的统一读取 SQL。 */
    private static String selectJobs() { return """
            SELECT j.id, j.tenant_id, j.project_id, j.name, j.description, j.status, j.version, j.target_type,
                   j.target_group_id, j.command_key, j.input::text input, j.created_by, j.created_at, j.updated_at,
                   s.schedule_type, s.run_at, s.cron_expression, s.timezone, s.next_run_at
              FROM task_job j JOIN task_schedule s ON s.job_id = j.id
             """; }
    /** 返回执行读取 SQL。 */
    private static String selectExecutions() { return """
            SELECT id, tenant_id, project_id, job_id, trigger_type, status, scheduled_fire_at, target_type, target_group_id,
                   command_key, input::text input, requested_by, total_targets,
                   accepted_targets, succeeded_targets, failed_targets, skipped_targets, expansion_cursor,
                   started_at, finished_at, failure_summary FROM task_execution
             """; }
    /** JDBC 任务行映射。 */
    private TaskJob mapJob(ResultSet rs, int row) throws SQLException { return new TaskJob(rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("name"),
            rs.getString("description"), TaskJob.Status.valueOf(rs.getString("status")), rs.getLong("version"),
            TaskJob.ScheduleType.valueOf(rs.getString("schedule_type")), instant(rs, "run_at"), rs.getString("cron_expression"),
            rs.getString("timezone"), instant(rs, "next_run_at"), TaskJob.TargetType.valueOf(rs.getString("target_type")),
            rs.getObject("target_group_id", UUID.class), rs.getString("command_key"), rs.getString("input"),
            rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at")); }
    /** JDBC 执行行映射。 */
    private TaskExecution mapExecution(ResultSet rs, int row) throws SQLException { return new TaskExecution(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getObject("job_id", UUID.class), TaskExecution.TriggerType.valueOf(rs.getString("trigger_type")),
            TaskExecution.Status.valueOf(rs.getString("status")), instant(rs, "scheduled_fire_at"),
            TaskJob.TargetType.valueOf(rs.getString("target_type")), rs.getObject("target_group_id", UUID.class),
            rs.getString("command_key"), rs.getString("input"), rs.getObject("requested_by", UUID.class), rs.getInt("total_targets"), rs.getInt("accepted_targets"), rs.getInt("succeeded_targets"), rs.getInt("failed_targets"), rs.getInt("skipped_targets"),
            instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("failure_summary"), rs.getString("expansion_cursor")); }
    /** JDBC 目标行映射。 */
    private TaskTarget mapTarget(ResultSet rs, int row) throws SQLException { return new TaskTarget(
            rs.getObject("execution_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getObject("device_id", UUID.class), TaskTarget.Status.valueOf(rs.getString("status")),
            rs.getObject("command_id", UUID.class), rs.getString("failure_summary"), instant(rs, "accepted_at"), instant(rs, "completed_at")); }
    /** 将可空时间点转换为 JDBC 时间戳。 */
    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    /** 将 JDBC 时间戳转换为可空时间点。 */
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    /** 从仍有效的任务读取执行创建瞬间要冻结的目标类型。 */
    private TaskJob.TargetType jobTargetType(UUID projectId, UUID jobId) { return jdbc.queryForObject("SELECT target_type FROM task_job WHERE project_id = ? AND id = ? AND deleted_at IS NULL", TaskJob.TargetType.class, projectId, jobId); }
    /** 从仍有效的任务读取执行创建瞬间要冻结的设备组。 */
    private UUID jobTargetGroup(UUID projectId, UUID jobId) { return jdbc.queryForObject("SELECT target_group_id FROM task_job WHERE project_id = ? AND id = ? AND deleted_at IS NULL", UUID.class, projectId, jobId); }
    /** 从仍有效的任务读取执行创建瞬间要冻结的命令键。 */
    private String jobCommandKey(UUID projectId, UUID jobId) { return jdbc.queryForObject("SELECT command_key FROM task_job WHERE project_id = ? AND id = ? AND deleted_at IS NULL", String.class, projectId, jobId); }
    /** 从仍有效的任务读取执行创建瞬间要冻结的命令输入。 */
    private String jobInput(UUID projectId, UUID jobId) { return jdbc.queryForObject("SELECT input::text FROM task_job WHERE project_id = ? AND id = ? AND deleted_at IS NULL", String.class, projectId, jobId); }
    /** 从任务读取执行创建瞬间要冻结的命令审计账号。 */
    private UUID jobRequestedBy(UUID projectId, UUID jobId) { return jdbc.queryForObject("SELECT created_by FROM task_job WHERE project_id = ? AND id = ? AND deleted_at IS NULL", UUID.class, projectId, jobId); }
}
