package com.things.link.task.application;

import com.things.link.device.application.DeviceSearchService;
import com.things.link.device.application.TaskTargetDevice;
import com.things.link.device.application.TaskTargetScope;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskErrorCode;
import com.things.link.task.domain.TaskJob;
import com.things.link.task.domain.TaskJobRepository;
import com.things.link.task.domain.TaskTarget;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.TaskDeviceCommandRequest;
import com.things.link.telemetry.application.TaskDeviceCommandResult;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/** 任务配置、调度领取、目标快照和设备命令归并的应用服务。 */
@Service
public class TaskJobService {
    /** 单次设备键集展开数量，避免大项目占用长事务。 */
    private static final int EXPANSION_PAGE_SIZE = 200;
    /** 单次命令投递与状态归并的最大目标数。 */
    private static final int TARGET_BATCH_SIZE = 100;
    /** 任务持久化端口。 */ private final TaskJobRepository repository;
    /** 受信设备键集分页端口。 */ private final DeviceSearchService deviceSearchService;
    /** 命令域的受信任务投递和状态投影端口。 */ private final DeviceCommandService commandService;
    /** 项目成员、所有者租户及权威时区端口。 */ private final ProjectService projectService;
    /** 任务定义、新执行、目标展开与派发共用的原事务持续项目写许可。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;
    /** 有效配额策略读取端口。 */ private final EffectiveQuotaPolicyProvider quotaPolicyProvider;
    /** PostgreSQL 权威 UTC 日下行额度；Redis 双桶不能充当日账单。 */
    private final ProjectDailyQuotaDecisionService dailyQuotaDecisionService;
    /** Redis 双桶限流门面。 */ private final TaskDispatchRateLimiter rateLimiter;
    /** JSON 映射器，用于冻结命令输入。 */ private final ObjectMapper objectMapper;
    /** 后台线程在当前事务连接建立可信租户与项目 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /** 创建任务服务。 */
    public TaskJobService(TaskJobRepository repository, DeviceSearchService deviceSearchService,
                          DeviceCommandService commandService, ProjectService projectService,
                          EffectiveQuotaPolicyProvider quotaPolicyProvider, TaskDispatchRateLimiter rateLimiter,
                          ObjectMapper objectMapper, TransactionLocalRlsScope transactionLocalRlsScope,
                          ProjectDailyQuotaDecisionService dailyQuotaDecisionService,
                          ProjectLifecycleAccessService lifecycleAccessService) {
        this.repository = repository; this.deviceSearchService = deviceSearchService; this.commandService = commandService;
        this.projectService = projectService; this.quotaPolicyProvider = quotaPolicyProvider;
        this.rateLimiter = rateLimiter; this.objectMapper = objectMapper;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.dailyQuotaDecisionService = dailyQuotaDecisionService;
        this.lifecycleAccessService = lifecycleAccessService;
    }

    /** 创建任务定义并按项目权威时区计算第一处 UTC 触发点。 */
    @Transactional
    public TaskJob create(UUID projectId, TaskJobCommand command) {
        UUID ownerTenant = requireDefinitionWrite(projectId);
        if (command.expectedVersion() != null) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "创建任务不能携带版本号");
        ProjectService.ProjectSchedulingContext context = projectService.requireSchedulingContext(projectId);
        Instant now = Instant.now();
        TaskJob job = build(Uuid7.generate(), ownerTenant, projectId, command, 1L, currentAccountId(), now, now, context.timezone());
        repository.create(job);
        return job;
    }

    /** 读取任务；全体项目成员可见。 */
    @Transactional(readOnly = true)
    public TaskJob get(UUID projectId, UUID jobId) { requireRead(projectId); return findJob(projectId, jobId); }

    /** 列出任务；规模受项目配置数量约束，无需日志型 cursor。 */
    @Transactional(readOnly = true)
    public List<TaskJob> list(UUID projectId) { requireRead(projectId); return repository.findJobs(projectId); }

    /** 使用乐观锁更新任务及调度，并重新解释 cron 的下一次 UTC 触发。 */
    @Transactional
    public TaskJob update(UUID projectId, UUID jobId, TaskJobCommand command) {
        UUID ownerTenant = requireDefinitionWrite(projectId);
        if (command.expectedVersion() == null) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "更新任务必须携带版本号");
        TaskJob old = findJob(projectId, jobId);
        ProjectService.ProjectSchedulingContext context = projectService.requireSchedulingContext(projectId);
        Instant now = Instant.now();
        TaskJob replacement = build(old.id(), ownerTenant, projectId, command, old.version() + 1,
                old.createdBy(), old.createdAt(), now, context.timezone());
        if (!repository.update(replacement, command.expectedVersion())) throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "任务版本冲突，请刷新后重试");
        return replacement;
    }

    /** 软删除任务，历史执行事实保持只读可查。 */
    @Transactional
    public void delete(UUID projectId, UUID jobId, long expectedVersion) {
        requireDefinitionWrite(projectId);
        if (!repository.softDelete(projectId, jobId, expectedVersion)) throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "任务不存在或版本冲突");
    }

    /** 显式启用任务；保留原有配置并通过普通更新推进版本。 */
    @Transactional
    public TaskJob enable(UUID projectId, UUID jobId) { return switchEnabled(projectId, jobId, true); }
    /** 显式暂停任务；不会中断已经产生的执行事实。 */
    @Transactional
    public TaskJob disable(UUID projectId, UUID jobId) { return switchEnabled(projectId, jobId, false); }

    /** 手动创建一次执行；非 VIEWER 即可触发。 */
    @Transactional
    public TaskExecution run(UUID projectId, UUID jobId) {
        requireRun(projectId);
        TaskJob job = findJob(projectId, jobId);
        // ADR0067决策1：原角色校验不替代原事务许可，可信租户必须来自持久job而非协作者JWT。
        lifecycleAccessService.requireActiveForWrite(job.tenantId(), job.projectId());
        requireDailyDispatchQuota(job.tenantId(), job.projectId());
        Instant now = Instant.now();
        TaskExecution created = new TaskExecution(Uuid7.generate(), job.tenantId(), projectId, job.id(),
                TaskExecution.TriggerType.MANUAL, TaskExecution.Status.EXPANDING, null, job.targetType(),
                job.targetGroupId(), job.commandKey(), job.inputJson(), job.createdBy(), 0, 0, 0, 0, 0, now, null, null, null);
        return repository.createManualExecution(created);
    }

    /** 读取某个任务的执行日志；全体成员可见。 */
    @Transactional(readOnly = true)
    public List<TaskExecution> executions(UUID projectId, UUID jobId) { requireRead(projectId); findJob(projectId, jobId); return repository.findExecutions(projectId, jobId); }

    /** 处理一个已由数据库短租约领取的到期调度。 */
    @Transactional
    public void processDueSchedule(TaskJobRepository.DueSchedule due) {
        // ADR0067决策2：后台Due必须完整，空身份或无原事务不能查询、更不能清他人的计划。
        if (due == null || due.tenantId() == null || due.projectId() == null
                || due.jobId() == null || due.scheduledFireAt() == null) {
            throw new IllegalArgumentException("任务调度处理必须提供租户、项目、任务与触发时间");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("任务调度处理必须加入已有非只读事务");
        }
        transactionLocalRlsScope.establish(due.tenantId(), due.projectId());
        TaskJob job = repository.findJob(due.projectId(), due.jobId()).orElse(null);
        if (job == null || job.status() != TaskJob.Status.ACTIVE
                || !due.tenantId().equals(job.tenantId()) || !due.projectId().equals(job.projectId())
                || !due.scheduledFireAt().equals(job.nextRunAt())) return;
        if (!lifecycleAccessService.lockActiveForWrite(job.tenantId(), job.projectId())) {
            // 仅停止仍匹配本Due的下一触发点，不改定义、不读取额度；CAS失败代表已过期Due。
            repository.advanceSchedule(job.projectId(), job.id(), due.scheduledFireAt(), null);
            return;
        }
        requireDailyDispatchQuota(job.tenantId(), job.projectId());
        Instant now = Instant.now();
        // ADR0025原misfire规则保持：只补一次，后续cron从当前时刻而非历史fire-at推导。
        Instant nextRunAt = nextRun(job, due.scheduledFireAt().isAfter(now) ? due.scheduledFireAt() : now);
        // ADR0067：先以CAS决定本Due是否还能新建；创建异常会将计划推进同事务回滚。
        if (!repository.advanceSchedule(job.projectId(), job.id(), due.scheduledFireAt(), nextRunAt)) return;
        repository.createScheduledExecution(due, now);
    }

    /** 推进一个执行：先分页冻结目标，再限速受理，再归并命令终态。 */
    @Transactional
    public void processExecution(TaskJobRepository.DueExecution due) {
        // ADR0066决策1：空身份是调用错误，禁止用线程范围补齐，也不能在自动提交下丢失锁。
        if (due == null || due.tenantId() == null || due.projectId() == null || due.executionId() == null) {
            throw new IllegalArgumentException("任务执行处理必须提供租户、项目与执行身份");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("任务执行处理必须加入已有非只读事务");
        }
        transactionLocalRlsScope.establish(due.tenantId(), due.projectId());
        // claim已在独立短事务提交；旧Due等待后只能使用本轮锁定的持久身份、状态与游标。
        TaskExecution execution = repository.lockExecution(due.tenantId(), due.projectId(), due.executionId())
                .orElse(null);
        if (execution == null || terminal(execution.status())) return;
        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.set(new TenantScope(execution.tenantId(), execution.projectId(), execution.requestedBy()));
        try {
            if (execution.status() == TaskExecution.Status.EXPANDING) {
                // 锁序固定为execution → project SHARE → targets；SQL异常原样回滚，不当作确定冻结。
                if (lifecycleAccessService.lockActiveForWrite(execution.tenantId(), execution.projectId())) {
                    expand(execution);
                } else {
                    if (!repository.beginStopping(execution.id(), Instant.now())) {
                        throw new IllegalStateException("任务展开停止状态转换失败");
                    }
                    stop(execution);
                }
                return;
            }
            if (execution.status() == TaskExecution.Status.STOPPING) { stop(execution); return; }
            if (execution.status() == TaskExecution.Status.DISPATCHING) {
                // ADR0067决策3：许可必须早于额度、目标领取、Redis扣减及命令幂等回读。
                if (lifecycleAccessService.lockActiveForWrite(execution.tenantId(), execution.projectId())) {
                    dispatch(execution);
                } else {
                    if (!repository.beginDispatchStopping(execution.id(), Instant.now())) {
                        throw new IllegalStateException("任务派发停止状态转换失败");
                    }
                    stop(execution);
                }
                return;
            }
            reconcile(execution);
        } finally {
            // 调度线程属于共享线程池；遗漏清理会把当前项目泄漏给下一条跨租户任务。
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }

    /** 硬限/降级时不产生新的批量执行；已经受理的单设备命令仍继续归并终态。 */
    private void requireDailyDispatchQuota(UUID ownerTenantId, UUID projectId) {
        QuotaStatus status = dailyQuotaDecisionService.decideTrustedProject(
                ownerTenantId, projectId, QuotaMetric.DOWNLINK_MESSAGE);
        if (status == QuotaStatus.HARD_LIMIT || status == QuotaStatus.DEGRADED) {
            throw new BusinessException(TaskErrorCode.TASK_DAILY_QUOTA_EXCEEDED);
        }
    }

    /** 使用键集分页冻结一页设备，不随任务后续编辑漂移。 */
    private void expand(TaskExecution execution) {
        TaskTargetScope scope = new TaskTargetScope(execution.projectId(), execution.targetGroupId());
        var page = deviceSearchService.listTaskTargets(scope, execution.expansionCursor(), EXPANSION_PAGE_SIZE);
        int appended = repository.appendTargets(execution.id(), execution.tenantId(), execution.projectId(),
                page.items().stream().map(TaskTargetDevice::deviceId).toList());
        // ADR0066决策1：游标CAS失败必须回滚本轮新增目标，不能提交孤立的目标页。
        if (!repository.updateExpansion(execution.id(), page.nextCursor(), page.hasMore(), appended, Instant.now())) {
            throw new IllegalStateException("任务目标展开游标更新失败");
        }
    }

    /** 通过租户与项目双 token bucket 控制每台设备的命令受理节奏。 */
    private void dispatch(TaskExecution execution) {
        var quota = quotaPolicyProvider.resolveTrustedProject(execution.projectId());
        for (TaskTarget target : repository.claimPendingTargets(execution.id(), TARGET_BATCH_SIZE)) {
            if (!rateLimiter.tryAcquire(execution.tenantId(), execution.projectId(), quota.taskTenantDispatchPerSecond(), quota.taskProjectDispatchPerSecond())) return;
            TaskDeviceCommandResult accepted = commandService.submitTask(new TaskDeviceCommandRequest(execution.id(),
                    execution.projectId(), execution.requestedBy(), target.deviceId(), execution.commandKey(),
                    objectMapper.readTree(execution.inputJson())));
            if (accepted.rejected()) {
                repository.markTargetSkipped(execution.id(), target.deviceId(), accepted.failureSummary(), Instant.now());
            } else {
                repository.markTargetAccepted(execution.id(), target.deviceId(), accepted.commandId(), Instant.now());
            }
        }
        repository.markRunningIfNoPending(execution.id(), Instant.now());
    }

    /**
     * ADR0066决策3：停止维护不要求ACTIVE，不新增业务；每轮最多跳过100目标并检查100个既有命令。
     * 状态转换和本轮维护共用原事务，异常时连同目标事实整体回滚，已提交STOPPING永不恢复展开。
     */
    private void stop(TaskExecution execution) {
        Instant now = Instant.now();
        boolean progressed = repository.skipPendingForStopping(execution.id(), TARGET_BATCH_SIZE, now) > 0;
        // 不使用短路或，否则跳过目标后会漏掉本轮已受理命令的归并。
        progressed |= reconcileTargets(execution);
        if (!repository.completeStoppingExecutionIfReady(execution.id(), now)
                && !repository.updateStoppingLease(execution.id(), progressed, now)) {
            throw new IllegalStateException("任务停止维护租约更新失败");
        }
    }

    /** 将 telemetry 命令终态归并到 task_target，保留原RUNNING完成条件。 */
    private void reconcile(TaskExecution execution) {
        reconcileTargets(execution);
        repository.completeExecutionIfReady(execution.id(), Instant.now());
    }

    /**
     * 只读取既有命令并沿原映射归并；超时仍映射FAILED、摘要TIMED_OUT，不改变原计数口径。
     * @return 至少一个目标条件更新真正成功；查到命令终态本身不能充当持久推进证据
     */
    private boolean reconcileTargets(TaskExecution execution) {
        boolean progressed = false;
        for (TaskTarget target : repository.findActiveTargets(execution.id(), TARGET_BATCH_SIZE)) {
            TaskDeviceCommandResult command = commandService.findTaskCommand(execution.projectId(), target.commandId()).orElse(null);
            if (command == null) {
                progressed |= repository.completeTarget(execution.id(), target.deviceId(), TaskTarget.Status.FAILED,
                        "命令事实不存在", Instant.now());
            } else if (command.terminal()) {
                progressed |= repository.completeTarget(execution.id(), target.deviceId(),
                        command.status() == TaskDeviceCommandResult.Status.SUCCEEDED ? TaskTarget.Status.SUCCEEDED : TaskTarget.Status.FAILED,
                        command.status().name(), Instant.now());
            }
        }
        return progressed;
    }

    /** 创建或更新任务时统一校验、冻结 JSON，并以项目权威时区计算 next UTC。 */
    private static TaskJob build(UUID id, UUID tenantId, UUID projectId, TaskJobCommand command, long version,
                                 UUID createdBy, Instant createdAt, Instant updatedAt, String projectTimezone) {
        if (command.name() == null || command.name().isBlank() || command.commandKey() == null || command.commandKey().isBlank()
                || command.input() == null || !command.input().isObject()) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "任务参数不完整");
        if ((command.targetType() == TaskJob.TargetType.ALL_DEVICES) != (command.targetGroupId() == null))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "任务目标范围不合法");
        if (command.timezone() != null && !command.timezone().isBlank() && !projectTimezone.equals(command.timezone()))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "任务时区必须与项目时区一致");
        ZoneId zone = ZoneId.of(projectTimezone);
        Instant next = next(command.scheduleType(), command.runAt(), command.cronExpression(), zone, Instant.now());
        return new TaskJob(id, tenantId, projectId, command.name().strip(), blank(command.description()),
                command.enabled() ? TaskJob.Status.ACTIVE : TaskJob.Status.PAUSED, version, command.scheduleType(), command.runAt(),
                blank(command.cronExpression()), projectTimezone, command.enabled() ? next : null, command.targetType(),
                command.targetGroupId(), command.commandKey().strip(), command.input().toString(), createdBy, createdAt, updatedAt);
    }

    /** 计算下一次 UTC fire；cron 的本地解释永远以项目 IANA 时区完成。 */
    static Instant next(TaskJob.ScheduleType type, Instant runAt, String expression, ZoneId zone, Instant base) {
        if (type == TaskJob.ScheduleType.ONCE) { if (runAt == null) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "一次任务必须指定执行时间"); return runAt; }
        if (expression == null || expression.isBlank()) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "周期任务必须指定 cron");
        try { ZonedDateTime value = CronExpression.parse(expression).next(base.atZone(zone)); if (value == null) throw new IllegalArgumentException(); return value.toInstant(); }
        catch (IllegalArgumentException exception) { throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "cron 表达式不合法"); }
    }
    /** 领取后的下一触发点；一次任务推进为 NULL。 */
    private static Instant nextRun(TaskJob job, Instant fireAt) { return job.scheduleType() == TaskJob.ScheduleType.ONCE ? null : next(job.scheduleType(), null, job.cronExpression(), ZoneId.of(job.timezone()), fireAt); }
    /** @return 是否最终执行状态 */ private static boolean terminal(TaskExecution.Status status) { return status == TaskExecution.Status.SUCCEEDED || status == TaskExecution.Status.PARTIAL_FAILED || status == TaskExecution.Status.FAILED; }
    /** @return 空白字符串转为 null */ private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    /** @return 当前控制台账号；任务定义必须可追溯创建者 */ private static UUID currentAccountId() { return TenantContext.current().map(TenantScope::accountId).orElseThrow(() -> new IllegalStateException("缺少租户上下文")); }
    /** 全体成员可读。 */ private void requireRead(UUID projectId) { projectService.requireRoleInProject(projectId); }
    /** OWNER/ADMIN 可管理定义。 */ private void requireManage(UUID projectId) { ProjectRole role = projectService.requireRoleInProject(projectId); if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) throw new BusinessException(TaskErrorCode.TASK_MANAGE_FORBIDDEN); }
    /** 角色预检后用项目持久归属取得原事务许可，并在锁等待结束后复核管理角色。 */
    private UUID requireDefinitionWrite(UUID projectId) {
        requireManage(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycleAccessService.requireActiveForWrite(ownerTenant, projectId);
        requireManage(projectId);
        return ownerTenant;
    }
    /** 非 VIEWER 可手工运行。 */ private void requireRun(UUID projectId) { if (projectService.requireRoleInProject(projectId) == ProjectRole.VIEWER) throw new BusinessException(TaskErrorCode.TASK_RUN_FORBIDDEN); }
    /** 启停的内部实现。 */ private TaskJob switchEnabled(UUID projectId, UUID jobId, boolean enabled) { UUID ownerTenant = requireDefinitionWrite(projectId); TaskJob old = findJob(projectId, jobId); TaskJob command = new TaskJob(old.id(), ownerTenant, old.projectId(), old.name(), old.description(), enabled ? TaskJob.Status.ACTIVE : TaskJob.Status.PAUSED, old.version() + 1, old.scheduleType(), old.runAt(), old.cronExpression(), old.timezone(), enabled ? nextRun(old, Instant.now()) : null, old.targetType(), old.targetGroupId(), old.commandKey(), old.inputJson(), old.createdBy(), old.createdAt(), Instant.now()); if (!repository.update(command, old.version())) throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "任务版本冲突"); return command; }
    /** 获取任务或按项目资源不存在处理。 */ private TaskJob findJob(UUID projectId, UUID jobId) { return repository.findJob(projectId, jobId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "任务不存在")); }
}
