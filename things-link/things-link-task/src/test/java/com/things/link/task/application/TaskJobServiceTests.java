package com.things.link.task.application;

import com.things.link.device.application.DeviceSearchService;
import com.things.link.device.application.TaskTargetDevice;
import com.things.link.device.application.TaskTargetScope;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskErrorCode;
import com.things.link.task.domain.TaskJob;
import com.things.link.task.domain.TaskJobRepository;
import com.things.link.task.domain.TaskTarget;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.TaskDeviceCommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 任务应用服务的 misfire、手工重入、分页快照与后台上下文回收测试。 */
class TaskJobServiceTests {

    /** 任务持久化端口替身。 */
    private TaskJobRepository repository;
    /** 设备目标分页端口替身。 */
    private DeviceSearchService deviceSearchService;
    /** 项目权限与时区端口替身。 */
    private ProjectService projectService;
    /** 设备命令最终状态投影替身。 */
    private DeviceCommandService commandService;
    /** UTC 日额度服务替身。 */
    private ProjectDailyQuotaDecisionService dailyQuotaDecisionService;
    /** 原事务持续写许可替身；真实行锁由Bootstrap PG验收证明。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 原派发额度路由替身。 */
    private EffectiveQuotaPolicyProvider quotaPolicyProvider;
    /** 原限速器替身，用于确认STOPPING不新增派发。 */
    private TaskDispatchRateLimiter rateLimiter;
    /** 事务局部 RLS 范围建立器替身，只断言调用顺序，不宣称存在真实连接事务。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 被测任务应用服务。 */
    private TaskJobService service;
    /** 测试租户。 */
    private UUID tenantId;
    /** 测试项目。 */
    private UUID projectId;
    /** 测试账号。 */
    private UUID accountId;

    /** 以最小替身组合创建服务；集中范围组件的连接级保证由 support 真实 PG 测试覆盖。 */
    @BeforeEach
    void setUp() {
        repository = mock(TaskJobRepository.class);
        deviceSearchService = mock(DeviceSearchService.class);
        projectService = mock(ProjectService.class);
        commandService = mock(DeviceCommandService.class);
        dailyQuotaDecisionService = mock(ProjectDailyQuotaDecisionService.class);
        when(dailyQuotaDecisionService.decideTrustedProject(any(), any(), any()))
                .thenReturn(QuotaStatus.NORMAL);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        quotaPolicyProvider = mock(EffectiveQuotaPolicyProvider.class);
        rateLimiter = mock(TaskDispatchRateLimiter.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        when(lifecycleAccessService.lockActiveForWrite(any(), any())).thenReturn(true);
        when(repository.updateExpansion(any(), any(), anyBoolean(), anyInt(), any())).thenReturn(true);
        when(repository.updateStoppingLease(any(), anyBoolean(), any())).thenReturn(true);
        service = new TaskJobService(repository, deviceSearchService, commandService, projectService,
                quotaPolicyProvider, rateLimiter, new ObjectMapper(), transactionLocalRlsScope,
                dailyQuotaDecisionService,
                lifecycleAccessService);
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    /** 下行硬限拒绝新手工批次，不改写既有任务或命令事实。 */
    @Test
    void dailyHardLimitRejectsNewManualExecution() {
        TaskJob job = cronJob(Instant.now());
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);
        when(repository.findJob(projectId, job.id())).thenReturn(Optional.of(job));
        when(dailyQuotaDecisionService.decideTrustedProject(any(), any(), any()))
                .thenReturn(QuotaStatus.HARD_LIMIT);

        assertThatThrownBy(() -> service.run(projectId, job.id()))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .createManualExecution(any());
    }

    /** 共享测试线程不得保留上一用例设置的租户范围。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    /** 五个定义写入口共用角色、持久归属、持续许可和锁后角色复核顺序。 */
    @ParameterizedTest
    @EnumSource(DefinitionMutation.class)
    void definitionWritesUseOwnerTenantAndSharedAdmissionOrder(DefinitionMutation mutation) {
        UUID ownerTenant = UUID.randomUUID();
        TaskJob existing = cronJob(Instant.now().plusSeconds(60));
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, accountId));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        when(projectService.requireSchedulingContext(projectId))
                .thenReturn(new ProjectService.ProjectSchedulingContext(ownerTenant, "Asia/Shanghai"));
        when(repository.findJob(projectId, existing.id())).thenReturn(Optional.of(existing));
        when(repository.update(any(TaskJob.class), eq(existing.version()))).thenReturn(true);
        when(repository.softDelete(projectId, existing.id(), existing.version())).thenReturn(true);

        invokeDefinitionMutation(mutation, existing);

        var order = inOrder(projectService, lifecycleAccessService, repository);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenant, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        ArgumentCaptor<TaskJob> written = ArgumentCaptor.forClass(TaskJob.class);
        switch (mutation) {
            case CREATE -> {
                order.verify(projectService).requireSchedulingContext(projectId);
                order.verify(repository).create(written.capture());
            }
            case UPDATE -> {
                order.verify(repository).findJob(projectId, existing.id());
                order.verify(projectService).requireSchedulingContext(projectId);
                order.verify(repository).update(written.capture(), eq(existing.version()));
            }
            case DELETE -> order.verify(repository).softDelete(projectId, existing.id(), existing.version());
            case ENABLE, DISABLE -> {
                order.verify(repository).findJob(projectId, existing.id());
                order.verify(repository).update(written.capture(), eq(existing.version()));
            }
        }
        if (mutation != DefinitionMutation.DELETE) {
            assertThat(written.getValue().tenantId()).isEqualTo(ownerTenant);
        }
    }

    /** 归档项目的五个定义写入口均在参数、资源和仓储访问前按50017拒绝。 */
    @ParameterizedTest
    @EnumSource(DefinitionMutation.class)
    void archivedProjectShortCircuitsEveryDefinitionWrite(DefinitionMutation mutation) {
        UUID ownerTenant = UUID.randomUUID();
        TaskJob existing = cronJob(Instant.now().plusSeconds(60));
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, accountId));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        BusinessException frozen = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        doThrow(frozen).when(lifecycleAccessService).requireActiveForWrite(ownerTenant, projectId);

        assertThatThrownBy(() -> invokeDefinitionMutation(mutation, existing)).isSameAs(frozen);

        verify(projectService).requireRoleInProject(projectId);
        verify(projectService).requireProjectTenant(projectId);
        verify(lifecycleAccessService).requireActiveForWrite(ownerTenant, projectId);
        verifyNoInteractions(repository);
    }

    /** 项目锁等待期间降为VIEWER时由第二次角色检查拒绝，不能触碰任务定义。 */
    @Test
    void roleDowngradeAfterAdmissionShortCircuitsDefinitionWrite() {
        UUID ownerTenant = UUID.randomUUID();
        TaskJob existing = cronJob(Instant.now().plusSeconds(60));
        when(projectService.requireRoleInProject(projectId))
                .thenReturn(ProjectRole.ADMIN, ProjectRole.VIEWER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);

        assertThatThrownBy(() -> service.disable(projectId, existing.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(TaskErrorCode.TASK_MANAGE_FORBIDDEN));

        verify(lifecycleAccessService).requireActiveForWrite(ownerTenant, projectId);
        verifyNoInteractions(repository);
    }

    /** 长时间停机只为已领取 fire-at 创建一个执行，并从当前时刻之后计算下一次 cron。 */
    @Test
    void misfireCreatesOneExecutionAndSkipsHistoricalCatchUp() {
        Instant historicalFire = Instant.now().minus(Duration.ofDays(2));
        TaskJob job = cronJob(historicalFire);
        TaskJobRepository.DueSchedule due = new TaskJobRepository.DueSchedule(
                tenantId, projectId, job.id(), historicalFire);
        when(repository.findJob(projectId, job.id())).thenReturn(Optional.of(job));

        when(repository.advanceSchedule(eq(projectId), eq(job.id()), eq(historicalFire), any())).thenReturn(true);
        Instant before = Instant.now();
        logicalTransaction(() -> service.processDueSchedule(due));

        verify(repository).createScheduledExecution(eq(due), any(Instant.class));
        ArgumentCaptor<Instant> nextRun = ArgumentCaptor.forClass(Instant.class);
        verify(repository).advanceSchedule(eq(projectId), eq(job.id()), eq(historicalFire), nextRun.capture());
        assertThat(nextRun.getValue()).isAfterOrEqualTo(before);
    }

    /** cron 按项目 IANA 时区解释；纽约春季跳时不存在的 02:30 必须跳到下一自然日。 */
    @Test
    void cronUsesProjectTimezoneAndSkipsDstGap() {
        Instant next = TaskJobService.next(TaskJob.ScheduleType.CRON, null, "0 30 2 * * *",
                ZoneId.of("America/New_York"), Instant.parse("2026-03-08T06:59:00Z"));

        assertThat(next).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
    }

    /** 手工运行不使用 scheduled_fire_at 唯一键，同一任务可以产生多个独立执行。 */
    @Test
    void repeatedManualRunsCreateDifferentExecutionFacts() {
        TaskJob job = cronJob(Instant.now());
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);
        when(repository.findJob(projectId, job.id())).thenReturn(Optional.of(job));
        when(repository.createManualExecution(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskExecution first = service.run(projectId, job.id());
        TaskExecution second = service.run(projectId, job.id());

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.scheduledFireAt()).isNull();
        assertThat(second.scheduledFireAt()).isNull();
    }

    /** 每轮只把当前设备页写入快照，并原样持久化下一页不透明游标。 */
    @Test
    void expansionPersistsOnlyOneCursorPage() {
        TaskExecution execution = expandingExecution("cursor-0");
        UUID firstDevice = UUID.randomUUID();
        UUID secondDevice = UUID.randomUUID();
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(deviceSearchService.listTaskTargets(any(), eq("cursor-0"), eq(200)))
                .thenReturn(CursorPage.of(List.of(new TaskTargetDevice(firstDevice),
                        new TaskTargetDevice(secondDevice)), "cursor-1"));
        when(repository.appendTargets(execution.id(), tenantId, projectId, List.of(firstDevice, secondDevice)))
                .thenReturn(2);

        process(execution);

        ArgumentCaptor<TaskTargetScope> scope = ArgumentCaptor.forClass(TaskTargetScope.class);
        verify(deviceSearchService).listTaskTargets(scope.capture(), eq("cursor-0"), eq(200));
        assertThat(scope.getValue().projectId()).isEqualTo(projectId);
        assertThat(scope.getValue().groupId()).isNull();
        verify(repository).updateExpansion(eq(execution.id()), eq("cursor-1"), eq(true), eq(2), any(Instant.class));
    }

    /** 设备分页异常穿出时也必须恢复原上下文，防止共享调度线程污染下一项目。 */
    @Test
    void executionFailureRestoresPreviousTenantContext() {
        TaskExecution execution = expandingExecution(null);
        TenantScope previous = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        TenantContext.set(previous);
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(deviceSearchService.listTaskTargets(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("device page unavailable"));

        assertThatThrownBy(() -> process(execution))
                .isInstanceOf(IllegalStateException.class).hasMessage("device page unavailable");

        verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        verify(deviceSearchService).listTaskTargets(any(), any(), eq(200));
        assertThat(TenantContext.current()).contains(previous);
    }

    /** 命令成功与超时都应转成 task_target 终态，再尝试结束聚合执行。 */
    @Test
    void reconciliationMapsFinalCommandStatesAndCompletesExecution() {
        Instant now = Instant.now();
        TaskExecution execution = new TaskExecution(UUID.randomUUID(), tenantId, projectId, UUID.randomUUID(),
                TaskExecution.TriggerType.MANUAL, TaskExecution.Status.RUNNING, null,
                TaskJob.TargetType.ALL_DEVICES, null, "reboot", "{}", accountId,
                2, 2, 0, 0, 0, now, null, null, null);
        UUID succeededDevice = UUID.randomUUID();
        UUID timedOutDevice = UUID.randomUUID();
        UUID succeededCommand = UUID.randomUUID();
        UUID timedOutCommand = UUID.randomUUID();
        TaskTarget succeeded = new TaskTarget(execution.id(), tenantId, projectId, succeededDevice,
                TaskTarget.Status.ACCEPTED, succeededCommand, null, now, null);
        TaskTarget timedOut = new TaskTarget(execution.id(), tenantId, projectId, timedOutDevice,
                TaskTarget.Status.ACCEPTED, timedOutCommand, null, now, null);
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(repository.findActiveTargets(execution.id(), 100)).thenReturn(List.of(succeeded, timedOut));
        when(commandService.findTaskCommand(projectId, succeededCommand)).thenReturn(Optional.of(
                new TaskDeviceCommandResult(succeededCommand, TaskDeviceCommandResult.Status.SUCCEEDED,
                        true, null)));
        when(commandService.findTaskCommand(projectId, timedOutCommand)).thenReturn(Optional.of(
                new TaskDeviceCommandResult(timedOutCommand, TaskDeviceCommandResult.Status.TIMED_OUT,
                        true, null)));

        process(execution);

        verify(repository).completeTarget(eq(execution.id()), eq(succeededDevice),
                eq(TaskTarget.Status.SUCCEEDED), eq("SUCCEEDED"), any(Instant.class));
        verify(repository).completeTarget(eq(execution.id()), eq(timedOutDevice),
                eq(TaskTarget.Status.FAILED), eq("TIMED_OUT"), any(Instant.class));
        verify(repository).completeExecutionIfReady(eq(execution.id()), any(Instant.class));
        verifyNoInteractions(lifecycleAccessService, deviceSearchService);
        verify(repository, never()).completeStoppingExecutionIfReady(any(), any());
    }

    /** 四种空身份必须在SQL及任何许可调用前拒绝，不能以线程上下文补齐。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void incompleteDueIdentityFailsBeforeSql(int missingField) {
        TaskJobRepository.DueExecution due = switch (missingField) {
            case 0 -> null;
            case 1 -> new TaskJobRepository.DueExecution(null, projectId, UUID.randomUUID());
            case 2 -> new TaskJobRepository.DueExecution(tenantId, null, UUID.randomUUID());
            default -> new TaskJobRepository.DueExecution(tenantId, projectId, null);
        };
        assertThatThrownBy(() -> logicalTransaction(() -> service.processExecution(due)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("身份");
        verifyNoInteractions(transactionLocalRlsScope, repository, lifecycleAccessService,
                deviceSearchService, commandService);
    }

    /** 仅设置线程事务标志验证入口防线；这里没有数据库事务，不冒充真实PG持锁。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrReadOnlyTransactionFailsBeforeSql(boolean readOnly) {
        TransactionSynchronizationManager.setActualTransactionActive(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        assertThatThrownBy(() -> service.processExecution(
                new TaskJobRepository.DueExecution(tenantId, projectId, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("非只读事务");
        verifyNoInteractions(transactionLocalRlsScope, repository, lifecycleAccessService,
                deviceSearchService, commandService);
    }

    /** 仓储按完整Due三元组返回空时，缺失或错配均no-op，不能污染其他项目执行。 */
    @Test
    void missingPersistentIdentityIsNoOpAfterScopedLockRead() {
        UUID executionId = UUID.randomUUID();
        logicalTransaction(() -> service.processExecution(
                new TaskJobRepository.DueExecution(tenantId, projectId, executionId)));
        var order = inOrder(transactionLocalRlsScope, repository);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(repository).lockExecution(tenantId, projectId, executionId);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(lifecycleAccessService, deviceSearchService, commandService);
    }

    /** 锁后终态重放只读取身份，不再许可、跳过或归并目标。 */
    @ParameterizedTest
    @EnumSource(value = TaskExecution.Status.class, names = {"SUCCEEDED", "PARTIAL_FAILED", "FAILED"})
    void terminalPersistentStateDoesNotResume(TaskExecution.Status status) {
        TaskExecution execution = execution(status, "last-cursor");
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        process(execution);
        verify(repository).lockExecution(tenantId, projectId, execution.id());
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
        verifyNoInteractions(lifecycleAccessService, deviceSearchService, commandService, quotaPolicyProvider, rateLimiter);
    }

    /** 拒绝后同轮有限停止，锁序是执行行→项目许可→目标；绝不读取下一设备页。 */
    @Test
    void deniedExpansionStartsStoppingAndMaintainsOneBatchInOrder() {
        TaskExecution execution = expandingExecution("last-cursor");
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        when(repository.beginStopping(eq(execution.id()), any())).thenReturn(true);
        when(repository.skipPendingForStopping(eq(execution.id()), eq(100), any())).thenReturn(100);
        process(execution);
        var order = inOrder(repository, lifecycleAccessService);
        order.verify(repository).lockExecution(tenantId, projectId, execution.id());
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(repository).beginStopping(eq(execution.id()), any());
        order.verify(repository).skipPendingForStopping(eq(execution.id()), eq(100), any());
        order.verify(repository).findActiveTargets(execution.id(), 100);
        order.verify(repository).completeStoppingExecutionIfReady(eq(execution.id()), any());
        order.verify(repository).updateStoppingLease(eq(execution.id()), eq(true), any());
        order.verifyNoMoreInteractions();
        verifyNoInteractions(deviceSearchService, commandService, quotaPolicyProvider, rateLimiter);
    }

    /** 许可SQL异常原样穿出，不被转换成确定冻结，也不触碰目标。 */
    @Test
    void admissionFailureIsNotConvertedToStopping() {
        TaskExecution execution = expandingExecution(null);
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        RuntimeException sqlFailure = new org.springframework.dao.QueryTimeoutException("57014 permit timeout");
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenThrow(sqlFailure);
        assertThatThrownBy(() -> process(execution)).isSameAs(sqlFailure);
        verify(repository, never()).beginStopping(any(), any());
        verifyNoInteractions(deviceSearchService, commandService);
    }

    /** 持久状态CAS失败必须抛出运行时异常，交由原代理事务回滚而非继续维护。 */
    @Test
    void beginStoppingCasFailureDoesNotTouchTargets() {
        TaskExecution execution = expandingExecution(null);
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        assertThatThrownBy(() -> process(execution)).isInstanceOf(IllegalStateException.class)
                .hasMessage("任务展开停止状态转换失败");
        verify(repository, never()).skipPendingForStopping(any(), anyInt(), any());
        verifyNoInteractions(deviceSearchService, commandService);
    }

    /** 新增页已写后CAS失败，不能吞异常提交不对应总数与游标的目标页。 */
    @Test
    void expansionCasFailurePropagatesAfterAppend() {
        TaskExecution execution = expandingExecution("cursor-0");
        UUID device = UUID.randomUUID();
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(deviceSearchService.listTaskTargets(any(), eq("cursor-0"), eq(200)))
                .thenReturn(CursorPage.of(List.of(new TaskTargetDevice(device)), "cursor-1"));
        when(repository.appendTargets(execution.id(), tenantId, projectId, List.of(device))).thenReturn(1);
        when(repository.updateExpansion(eq(execution.id()), eq("cursor-1"), eq(true), eq(1), any())).thenReturn(false);
        assertThatThrownBy(() -> process(execution)).isInstanceOf(IllegalStateException.class)
                .hasMessage("任务目标展开游标更新失败");
        var order = inOrder(repository, lifecycleAccessService, deviceSearchService);
        order.verify(repository).lockExecution(tenantId, projectId, execution.id());
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(deviceSearchService).listTaskTargets(any(), eq("cursor-0"), eq(200));
        order.verify(repository).appendTargets(execution.id(), tenantId, projectId, List.of(device));
        order.verify(repository).updateExpansion(eq(execution.id()), eq("cursor-1"), eq(true), eq(1), any());
    }

    /** 已提交STOPPING即使许可恢复也不再咨询它，无目标推进时使用退避分支。 */
    @Test
    void stoppingWithoutProgressBacksOffWithoutReadmission() {
        TaskExecution execution = stoppingExecution();
        process(execution);
        verify(repository).skipPendingForStopping(eq(execution.id()), eq(100), any());
        verify(repository).findActiveTargets(execution.id(), 100);
        verify(repository).updateStoppingLease(eq(execution.id()), eq(false), any());
        verify(repository, never()).completeExecutionIfReady(any(), any());
        verifyNoInteractions(lifecycleAccessService, deviceSearchService, commandService, quotaPolicyProvider, rateLimiter);
    }

    /** 仅观察终态而条件写失败不算进展；两个目标仍逐一归并，不受首项结果短路影响。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppingProgressUsesActualTargetUpdatesAndChecksEveryCommand(boolean changed) {
        TaskExecution execution = stoppingExecution();
        TaskTarget first = acceptedTarget(execution);
        TaskTarget second = acceptedTarget(execution);
        when(repository.findActiveTargets(execution.id(), 100)).thenReturn(List.of(first, second));
        when(commandService.findTaskCommand(projectId, first.commandId())).thenReturn(Optional.of(
                new TaskDeviceCommandResult(first.commandId(), TaskDeviceCommandResult.Status.SUCCEEDED, true, null)));
        when(commandService.findTaskCommand(projectId, second.commandId())).thenReturn(Optional.empty());
        when(repository.completeTarget(eq(execution.id()), eq(first.deviceId()), eq(TaskTarget.Status.SUCCEEDED),
                eq("SUCCEEDED"), any())).thenReturn(changed);
        process(execution);
        verify(repository).completeTarget(eq(execution.id()), eq(second.deviceId()), eq(TaskTarget.Status.FAILED),
                eq("命令事实不存在"), any());
        verify(repository).updateStoppingLease(eq(execution.id()), eq(changed), any());
        verifyNoInteractions(lifecycleAccessService, deviceSearchService, quotaPolicyProvider, rateLimiter);
        verify(commandService, never()).submitTask(any());
    }

    /** 未结束的既有命令投影只等待，不伪造目标完成或重新受理命令。 */
    @Test
    void stoppingLeavesNonterminalCommandUntouched() {
        TaskExecution execution = stoppingExecution();
        TaskTarget target = acceptedTarget(execution);
        when(repository.findActiveTargets(execution.id(), 100)).thenReturn(List.of(target));
        when(commandService.findTaskCommand(projectId, target.commandId())).thenReturn(Optional.of(
                new TaskDeviceCommandResult(target.commandId(), TaskDeviceCommandResult.Status.DISPATCHED, false, null)));
        process(execution);
        verify(repository, never()).completeTarget(any(), any(), any(), any(), any());
        verify(repository).updateStoppingLease(eq(execution.id()), eq(false), any());
        verify(commandService, never()).submitTask(any());
    }

    /** 专用完成成功后不再写租约，防止已结束执行被重新投到领取队列。 */
    @Test
    void stoppingCompletionDoesNotUpdateLease() {
        TaskExecution execution = stoppingExecution();
        when(repository.completeStoppingExecutionIfReady(eq(execution.id()), any())).thenReturn(true);
        process(execution);
        verify(repository, never()).updateStoppingLease(any(), anyBoolean(), any());
        verify(repository, never()).completeExecutionIfReady(any(), any());
    }

    /** 未结束时租约CAS失败不能静默保留错误维护结果，应由原事务回滚。 */
    @Test
    void stoppingLeaseCasFailurePropagates() {
        TaskExecution execution = stoppingExecution();
        when(repository.updateStoppingLease(eq(execution.id()), anyBoolean(), any())).thenReturn(false);
        assertThatThrownBy(() -> process(execution)).isInstanceOf(IllegalStateException.class)
                .hasMessage("任务停止维护租约更新失败");
    }

    /** ADR0067：派发许可成功后原命令受理、限速及RUNNING推进保持。 */
    @Test
    void dispatchRetainsExistingCommandAcceptanceFlow() {
        TaskExecution execution = execution(TaskExecution.Status.DISPATCHING, null);
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        EffectiveQuotaPolicy policy = mock(EffectiveQuotaPolicy.class);
        when(quotaPolicyProvider.resolveTrustedProject(projectId)).thenReturn(policy);
        when(policy.taskTenantDispatchPerSecond()).thenReturn(10L);
        when(policy.taskProjectDispatchPerSecond()).thenReturn(5L);
        when(rateLimiter.tryAcquire(tenantId, projectId, 10L, 5L)).thenReturn(true);
        TaskTarget target = new TaskTarget(execution.id(), tenantId, projectId, UUID.randomUUID(),
                TaskTarget.Status.PENDING, null, null, null, null);
        UUID commandId = UUID.randomUUID();
        when(repository.claimPendingTargets(execution.id(), 100)).thenReturn(List.of(target));
        when(commandService.submitTask(any())).thenReturn(new TaskDeviceCommandResult(
                commandId, TaskDeviceCommandResult.Status.ACCEPTED, false, null));
        process(execution);
        verify(repository).markTargetAccepted(eq(execution.id()), eq(target.deviceId()), eq(commandId), any());
        verify(repository).markRunningIfNoPending(eq(execution.id()), any());
        verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        verifyNoInteractions(deviceSearchService);
        verify(repository, never()).skipPendingForStopping(any(), anyInt(), any());
    }

    /** 真实所属租户来自job，不采用协作者上下文；许可早于额度及执行创建。 */
    @Test
    void manualAdmissionUsesPersistentOwnerBeforeQuotaAndCreation() {
        TaskJob job = cronJob(Instant.now());
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, accountId));
        when(repository.findJob(projectId, job.id())).thenReturn(Optional.of(job));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);
        when(repository.createManualExecution(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.run(projectId, job.id());
        var order = inOrder(projectService, repository, lifecycleAccessService, dailyQuotaDecisionService);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).findJob(projectId, job.id());
        order.verify(lifecycleAccessService).requireActiveForWrite(tenantId, projectId);
        order.verify(dailyQuotaDecisionService).decideTrustedProject(eq(tenantId), eq(projectId), any());
        order.verify(repository).createManualExecution(any());
    }

    /** 公共许可拒绝向外传播且不触碰额度或执行；拒绝码映射由project域测试独立证明。 */
    @Test
    void manualAdmissionFailurePreventsQuotaAndExecution() {
        TaskJob job = cronJob(Instant.now());
        when(repository.findJob(projectId, job.id())).thenReturn(Optional.of(job));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);
        RuntimeException failure = new IllegalStateException("project admission rejected");
        doThrow(failure).when(lifecycleAccessService).requireActiveForWrite(tenantId, projectId);
        assertThatThrownBy(() -> service.run(projectId, job.id())).isSameAs(failure);
        verifyNoInteractions(dailyQuotaDecisionService);
        verify(repository, never()).createManualExecution(any());
    }

    /** 旧Due、缺失与归属错配均在许可前no-op，不能清理他人的触发点。 */
    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "PROJECT", "FIRE", "MISSING"})
    void scheduleRejectsUnmatchedPersistentDueWithoutSideEffects(String mismatch) {
        TaskJob job = cronJob(Instant.now().minusSeconds(5));
        TaskJobRepository.DueSchedule due = new TaskJobRepository.DueSchedule(
                mismatch.equals("TENANT") ? UUID.randomUUID() : tenantId,
                mismatch.equals("PROJECT") ? UUID.randomUUID() : projectId,
                job.id(), mismatch.equals("FIRE") ? job.nextRunAt().minusSeconds(1) : job.nextRunAt());
        if (!mismatch.equals("MISSING")) {
            when(repository.findJob(due.projectId(), job.id())).thenReturn(Optional.of(job));
        }
        logicalTransaction(() -> service.processDueSchedule(due));
        verifyNoInteractions(lifecycleAccessService, dailyQuotaDecisionService);
        verify(repository, never()).advanceSchedule(any(), any(), any(), any());
        verify(repository, never()).createScheduledExecution(any(), any());
    }

    /** 调度五种空输入必须在SQL与原许可前报调用错误，不能用环境补齐。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void scheduleIncompleteIdentityFailsBeforeSql(int missing) {
        TaskJobRepository.DueSchedule due = switch (missing) {
            case 0 -> null;
            case 1 -> new TaskJobRepository.DueSchedule(null, projectId, UUID.randomUUID(), Instant.now());
            case 2 -> new TaskJobRepository.DueSchedule(tenantId, null, UUID.randomUUID(), Instant.now());
            case 3 -> new TaskJobRepository.DueSchedule(tenantId, projectId, null, Instant.now());
            default -> new TaskJobRepository.DueSchedule(tenantId, projectId, UUID.randomUUID(), null);
        };
        assertThatThrownBy(() -> logicalTransaction(() -> service.processDueSchedule(due)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("触发时间");
        verifyNoInteractions(transactionLocalRlsScope, repository, lifecycleAccessService,
                dailyQuotaDecisionService);
    }

    /** 调度直调缺少物理事务前置时失败；线程标志仅用于拒绝分支，不提供数据库保证。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scheduleRequiresWritableTransactionBeforeSql(boolean readOnly) {
        TransactionSynchronizationManager.setActualTransactionActive(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TaskJobRepository.DueSchedule due = new TaskJobRepository.DueSchedule(
                tenantId, projectId, UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> service.processDueSchedule(due)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非只读事务");
        verifyNoInteractions(transactionLocalRlsScope, repository, lifecycleAccessService,
                dailyQuotaDecisionService);
    }

    /** 确定冻结只条件停止当前触发点；CAS未匹配同样正常no-op，不读额度或创建执行。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedScheduleOnlyConditionallyClearsMatchingTrigger(boolean changed) {
        TaskJob job = cronJob(Instant.now().minusSeconds(5));
        TaskJobRepository.DueSchedule due = scheduleDue(job);
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        when(repository.advanceSchedule(projectId, job.id(), due.scheduledFireAt(), null)).thenReturn(changed);
        logicalTransaction(() -> service.processDueSchedule(due));
        verify(repository).advanceSchedule(projectId, job.id(), due.scheduledFireAt(), null);
        verifyNoInteractions(dailyQuotaDecisionService);
        verify(repository, never()).createScheduledExecution(any(), any());
        verify(repository, never()).update(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** 成功许可后仍由CAS决定本Due能否创建；计划推进顺序早于新执行且保留原misfire规则。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admittedScheduleCreatesOnlyAfterSuccessfulCas(boolean changed) {
        TaskJob job = cronJob(Instant.now().minusSeconds(5));
        TaskJobRepository.DueSchedule due = scheduleDue(job);
        when(repository.advanceSchedule(eq(projectId), eq(job.id()), eq(due.scheduledFireAt()), any())).thenReturn(changed);
        logicalTransaction(() -> service.processDueSchedule(due));
        var order = inOrder(transactionLocalRlsScope, lifecycleAccessService,
                dailyQuotaDecisionService, repository);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(repository).findJob(projectId, job.id());
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(dailyQuotaDecisionService).decideTrustedProject(eq(tenantId), eq(projectId), any());
        order.verify(repository).advanceSchedule(eq(projectId), eq(job.id()), eq(due.scheduledFireAt()), any());
        if (changed) {
            order.verify(repository).createScheduledExecution(eq(due), any());
        } else {
            verify(repository, never()).createScheduledExecution(any(), any());
        }
    }

    /** 创建失败不吞异常，原Spring事务才能将此前schedule CAS一并回滚；这里不模拟物理回滚。 */
    @Test
    void scheduledCreationFailurePropagatesAfterAdvance() {
        TaskJob job = cronJob(Instant.now().minusSeconds(5));
        TaskJobRepository.DueSchedule due = scheduleDue(job);
        when(repository.advanceSchedule(eq(projectId), eq(job.id()), eq(due.scheduledFireAt()), any())).thenReturn(true);
        RuntimeException failure = new org.springframework.dao.DataAccessResourceFailureException("execution insert failed");
        when(repository.createScheduledExecution(eq(due), any())).thenThrow(failure);
        assertThatThrownBy(() -> logicalTransaction(() -> service.processDueSchedule(due))).isSameAs(failure);
        var order = inOrder(repository);
        order.verify(repository).findJob(projectId, job.id());
        order.verify(repository).advanceSchedule(eq(projectId), eq(job.id()), eq(due.scheduledFireAt()), any());
        order.verify(repository).createScheduledExecution(eq(due), any());
    }

    /** 许可SQL异常不转换成调度停止，不能清理尚未确认失权的原触发点。 */
    @Test
    void schedulePermitFailureDoesNotClearTrigger() {
        TaskJob job = cronJob(Instant.now().minusSeconds(5));
        TaskJobRepository.DueSchedule due = scheduleDue(job);
        RuntimeException failure = new org.springframework.dao.QueryTimeoutException("schedule permit timeout");
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenThrow(failure);
        assertThatThrownBy(() -> logicalTransaction(() -> service.processDueSchedule(due))).isSameAs(failure);
        verifyNoInteractions(dailyQuotaDecisionService);
        verify(repository, never()).advanceSchedule(any(), any(), any(), any());
        verify(repository, never()).createScheduledExecution(any(), any());
    }

    /** 派发确定冻结后走专用转换及有限停止，不调用额度、Redis或命令端口。 */
    @Test
    void deniedDispatchStopsBeforeAnyDispatchSideEffect() {
        TaskExecution execution = execution(TaskExecution.Status.DISPATCHING, null);
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        when(repository.beginDispatchStopping(eq(execution.id()), any())).thenReturn(true);
        when(repository.skipPendingForStopping(eq(execution.id()), eq(100), any())).thenReturn(100);
        process(execution);
        var order = inOrder(repository, lifecycleAccessService);
        order.verify(repository).lockExecution(tenantId, projectId, execution.id());
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(repository).beginDispatchStopping(eq(execution.id()), any());
        order.verify(repository).skipPendingForStopping(eq(execution.id()), eq(100), any());
        order.verify(repository).findActiveTargets(execution.id(), 100);
        order.verify(repository).completeStoppingExecutionIfReady(eq(execution.id()), any());
        order.verify(repository).updateStoppingLease(eq(execution.id()), eq(true), any());
        verify(repository, never()).beginStopping(any(), any());
        verifyNoInteractions(quotaPolicyProvider, rateLimiter, deviceSearchService, commandService);
    }

    /** 派发停止CAS失败必须回滚原事务，不继续处理目标。 */
    @Test
    void dispatchStoppingCasFailurePreventsMaintenance() {
        TaskExecution execution = execution(TaskExecution.Status.DISPATCHING, null);
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        assertThatThrownBy(() -> process(execution)).isInstanceOf(IllegalStateException.class)
                .hasMessage("任务派发停止状态转换失败");
        verify(repository, never()).skipPendingForStopping(any(), anyInt(), any());
        verifyNoInteractions(quotaPolicyProvider, rateLimiter, deviceSearchService, commandService);
    }

    /** @return 原读取匹配的Due；仅负责调度编排夹具，不创建或声称真实租约 */
    private TaskJobRepository.DueSchedule scheduleDue(TaskJob job) {
        when(repository.findJob(projectId, job.id())).thenReturn(Optional.of(job));
        return new TaskJobRepository.DueSchedule(tenantId, projectId, job.id(), job.nextRunAt());
    }

    /** @return 已锁定的停止执行；数据库锁及状态来源由独立PG验收负责 */
    private TaskExecution stoppingExecution() {
        TaskExecution execution = execution(TaskExecution.Status.STOPPING, "last-cursor");
        when(repository.lockExecution(tenantId, projectId, execution.id())).thenReturn(Optional.of(execution));
        return execution;
    }

    /** @return 带既有命令引用的归并逻辑夹具，不声称产生了真实命令 */
    private TaskTarget acceptedTarget(TaskExecution execution) {
        return new TaskTarget(execution.id(), tenantId, projectId, UUID.randomUUID(), TaskTarget.Status.ACCEPTED,
                UUID.randomUUID(), null, Instant.now(), null);
    }

    /** 在显式逻辑事务标志下调用直接构造的服务；没有数据库连接或物理回滚能力。 */
    private void process(TaskExecution execution) {
        logicalTransaction(() -> service.processExecution(
                new TaskJobRepository.DueExecution(tenantId, projectId, execution.id())));
    }

    /**
     * 只模拟入口前置标志以测试纯编排；真实事务、行锁与回滚必须由Bootstrap PG验收证明。
     * 原线程标志无论断言或业务异常都恢复，避免测试之间泄漏状态。
     */
    private void logicalTransaction(Runnable action) {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(active);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        }
    }

    /** @return 按指定持久状态与游标返回的执行，Due只携带身份 */
    private TaskExecution execution(TaskExecution.Status status, String cursor) {
        Instant now = Instant.now();
        return new TaskExecution(UUID.randomUUID(), tenantId, projectId, UUID.randomUUID(),
                TaskExecution.TriggerType.MANUAL, status, null, TaskJob.TargetType.ALL_DEVICES,
                null, "reboot", "{}", accountId, 0, 0, 0, 0, 0, now, null, null, cursor);
    }

    /** @param nextRunAt 当前调度点 @return 启用的每分钟周期任务 */
    private TaskJob cronJob(Instant nextRunAt) {
        Instant createdAt = Instant.now().minusSeconds(60);
        return new TaskJob(UUID.randomUUID(), tenantId, projectId, "批量巡检", null, TaskJob.Status.ACTIVE,
                1L, TaskJob.ScheduleType.CRON, null, "0 * * * * *", "Asia/Shanghai", nextRunAt,
                TaskJob.TargetType.ALL_DEVICES, null, "reboot", "{}", accountId, createdAt, createdAt);
    }

    /** @param cursor 已持久化的设备键集游标 @return 待展开执行事实 */
    private TaskExecution expandingExecution(String cursor) {
        Instant now = Instant.now();
        return new TaskExecution(UUID.randomUUID(), tenantId, projectId, UUID.randomUUID(),
                TaskExecution.TriggerType.MANUAL, TaskExecution.Status.EXPANDING, null,
                TaskJob.TargetType.ALL_DEVICES, null, "reboot", "{}", accountId,
                0, 0, 0, 0, 0, now, null, null, cursor);
    }

    /**
     * 调用一个任务定义写入口；参数均合法，便于只观察共同准入边界。
     *
     * @param mutation 待调用入口
     * @param existing update/delete/enable/disable使用的既有任务
     */
    private void invokeDefinitionMutation(DefinitionMutation mutation, TaskJob existing) {
        TaskJobCommand command = new TaskJobCommand("生命周期任务", null, TaskJob.ScheduleType.CRON,
                null, "0 * * * * *", null, TaskJob.TargetType.ALL_DEVICES, null,
                "reboot", new ObjectMapper().createObjectNode(), true,
                mutation == DefinitionMutation.CREATE ? null : existing.version());
        switch (mutation) {
            case CREATE -> service.create(projectId, command);
            case UPDATE -> service.update(projectId, existing.id(), command);
            case DELETE -> service.delete(projectId, existing.id(), existing.version());
            case ENABLE -> service.enable(projectId, existing.id());
            case DISABLE -> service.disable(projectId, existing.id());
        }
    }

    /** 本片统一接入持续写许可的五个任务定义入口。 */
    private enum DefinitionMutation {
        /** 创建任务及调度定义。 */
        CREATE,
        /** 更新任务及调度定义。 */
        UPDATE,
        /** 软删除任务定义。 */
        DELETE,
        /** 启用任务调度。 */
        ENABLE,
        /** 暂停任务调度。 */
        DISABLE
    }
}
