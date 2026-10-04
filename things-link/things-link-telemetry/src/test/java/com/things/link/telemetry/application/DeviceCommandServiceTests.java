package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxReader;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandAttempt;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0070逻辑编排合同；事务标志仅为Console前置替身，真实锁/提交由Bootstrap PG验证。 */
class DeviceCommandServiceTests {
    /** 命令事实与CAS替身。 */
    private DeviceCommandRepository repository;
    /** 原事务Outbox写入观察。 */
    private TransactionalOutboxRepository outbox;
    /** 不可变原信封读取替身。 */
    private TransactionalOutboxReader reader;
    /** 项目真实归属与角色替身。 */
    private ProjectService projects;
    /** 显式许可替身，不依赖null绕过。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 已有路由与Schema端口。 */
    private DeviceIngestionService devices;
    /** Console连接隔离级的逻辑替身。 */
    private JdbcTemplate jdbc;
    /** 后台事务完整RLS范围的集中入口替身。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 原JSON语义映射器。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 被测完整服务。 */
    private DeviceCommandService service;
    /** 明确模拟当前原接收关系。 */
    private final com.things.link.device.application.DeviceCommandReceiverPort receivers = mock(com.things.link.device.application.DeviceCommandReceiverPort.class);
    /** 当前真实形状的命令快照。 */
    private DeviceCommand command;
    /** 当前PENDING尝试。 */
    private DeviceCommandAttempt attempt;
    /** 与原Outbox对应的冻结信封。 */
    private DeviceCommandDispatch dispatch;

    /** 所有许可、身份与当前attempt明确提供，不假装mock拥有PG锁。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        repository = mock(DeviceCommandRepository.class); outbox = mock(TransactionalOutboxRepository.class);
        reader = mock(TransactionalOutboxReader.class); projects = mock(ProjectService.class);
        lifecycle = mock(ProjectLifecycleAccessService.class); devices = mock(DeviceIngestionService.class);
        jdbc = mock(JdbcTemplate.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        service = new DeviceCommandService(repository, outbox, devices, projects, lifecycle, reader, mapper, jdbc,
                transactionLocalRlsScope, mock(DeviceCommandMetrics.class), Duration.ofSeconds(5), 100,
                mock(com.things.link.device.application.DeviceAccessSessionPort.class),
                mock(DeviceMessageDebugLogWriter.class), mock(com.things.link.device.application.DeviceMqttCapabilityPort.class), receivers, mock(CommandWebhookSource.class));
        Instant now = Instant.parse("2026-09-04T12:00:00.123456789Z");
        command = new DeviceCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), DeviceCommandDispatch.OperationType.COMMAND, "reboot", "{}", "{}",
                "{\"a\":1,\"b\":2}", null, DeviceCommand.Status.ACCEPTED, "key", UUID.randomUUID(), null,
                30, 1, 3, now.plusSeconds(30), now.plusSeconds(30), null, null, "trace", now,
                null, null, null, now);
        dispatch = new DeviceCommandDispatch(UUID.randomUUID(), command.tenantId(), command.projectId(), command.id(),
                UUID.randomUUID(), 1, command.targetDeviceId(), "target", command.connectionDeviceId(), "gateway", "project",
                "reboot", command.requestJson(), now.plusSeconds(30), "trace");
        attempt = new DeviceCommandAttempt(dispatch.attemptId(), command.tenantId(), command.projectId(), command.id(), 1,
                dispatch.eventId(), command.connectionDeviceId(), "tc/v1/project/gateway/down/command/" + command.id(),
                DeviceCommandAttempt.Status.PENDING, dispatch.deadlineAt(), now);
        when(receivers.lockCurrent(any(), any(), any(), any())).thenReturn(Optional.of(
                new com.things.link.device.application.DeviceCommandReceiverRoute(command.tenantId(),command.projectId(),
                        dispatch.projectKey(),command.targetDeviceId(),dispatch.targetDeviceKey(),command.connectionDeviceId(),dispatch.connectionDeviceKey())));
        when(repository.databaseNow()).thenReturn(now);
        installCommand();
        when(projects.requireRoleInProject(command.projectId())).thenReturn(ProjectRole.OWNER);
        when(projects.requireProjectTenant(command.projectId())).thenReturn(command.tenantId());
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(true);
        when(reader.findByIdentity(command.tenantId(), command.projectId(), dispatch.eventId())).thenReturn(Optional.of(original(mapper.writeValueAsString(dispatch))));
    }

    /** 原信封核实后才允许把当前关系拒绝写成终态。 */
    @Test void unavailableReceiverTerminatesOriginalPendingOnly() {
        when(receivers.lockCurrent(any(),any(),any(),any())).thenReturn(Optional.empty());
        when(repository.stopForUnavailableReceiver(any(),any(),any(),eq(1),any())).thenReturn(true);
        assertThat(service.admitDispatch(dispatch)).isFalse();
        var order=inOrder(receivers,repository,reader,outbox);
        order.verify(receivers).lockCurrent(command.tenantId(),command.projectId(),command.targetDeviceId(),command.connectionDeviceId());
        order.verify(repository).lockByIdentity(command.tenantId(),command.projectId(),command.id());
        order.verify(reader).findByIdentity(command.tenantId(),command.projectId(),dispatch.eventId());
        order.verify(repository).stopForUnavailableReceiver(any(),any(),any(),eq(1),any());
        order.verify(outbox).append(any());
    }

    /** 当前关系缺失不能把篡改原信封变成合法拒绝事实。 */
    @Test void missingReceiverDoesNotAuthorizeForgedOriginalEnvelope() {
        when(receivers.lockCurrent(any(),any(),any(),any())).thenReturn(Optional.empty());
        when(reader.findByIdentity(any(),any(),any())).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.admitDispatch(dispatch)).isInstanceOf(InvalidCommandDispatchException.class);
        verify(repository,never()).stopForUnavailableReceiver(any(),any(),any(),anyInt(),any());
        verifyNoInteractions(outbox);
    }

    /** 瞬时关系读取失败不能被转成永久业务原因。 */
    @Test void receiverDatabaseFailurePropagatesWithoutTerminalWrite() {
        var failure=new org.springframework.dao.DataAccessResourceFailureException("receiver database unavailable");
        when(receivers.lockCurrent(any(),any(),any(),any())).thenThrow(failure);
        assertThatThrownBy(()->service.admitDispatch(dispatch)).isSameAs(failure);
        verify(repository,never()).stopForUnavailableReceiver(any(),any(),any(),anyInt(),any());
        verifyNoInteractions(outbox);
    }

    /** 逻辑事务标志和调用方范围不能污染下一例。 */
    @AfterEach
    void clear() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TenantContext.clear();
    }

    /** 跨租户协作者使用持久ownerTenant，并在许可后第二次核验角色才幂等回读。 */
    @Test
    void consoleLocksRealOwnerAndRechecksRoleBeforeIdempotentRead() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TenantContext.set(new TenantScope(UUID.randomUUID(), command.projectId(), command.requestedBy()));
        when(repository.findByIdempotencyKey(command.projectId(), "key")).thenReturn(Optional.of(command));
        assertThat(service.submit(command.projectId(), command.targetDeviceId(), "key", "reboot",
                mapper.readTree("{\"b\":2,\"a\":1}"))).isEqualTo(command);
        var order = inOrder(projects, lifecycle, repository);
        order.verify(projects).requireRoleInProject(command.projectId());
        order.verify(projects).requireProjectTenant(command.projectId());
        order.verify(lifecycle).requireActiveForWrite(command.tenantId(), command.projectId());
        order.verify(projects).requireRoleInProject(command.projectId());
        order.verify(repository).findByIdempotencyKey(command.projectId(), "key");
    }

    /** S12-0d：项目级唯一键命中后仍须核设备、命令、JSON语义与发起主体，冲突不得解析新路由。 */
    @Test
    void rejectsEveryChangedFieldOfExistingCommandCandidate() {
        when(repository.findByIdempotencyKey(command.projectId(), "key")).thenReturn(Optional.of(command));
        JsonNode sameInput = mapper.readTree("{\"b\":2,\"a\":1}");

        assertCandidateConflict(() -> service.submitTrusted(command.projectId(), UUID.randomUUID(), "key",
                command.commandKey(), sameInput, command.requestedBy(), null));
        assertCandidateConflict(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(), "key",
                "other", sameInput, command.requestedBy(), null));
        assertCandidateConflict(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(), "key",
                command.commandKey(), mapper.readTree("{\"a\":9,\"b\":2}"), command.requestedBy(), null));
        assertCandidateConflict(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(), "key",
                command.commandKey(), sameInput, UUID.randomUUID(), null));
        assertCandidateConflict(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(), "key",
                command.commandKey(), sameInput, null, UUID.randomUUID()));
        verify(devices, never()).resolveCommandRoute(any(), any(), any(), any());
    }

    /** HTTP来源不得占用task/rule内部命名空间，避免同账号同候选借内部键吞掉一次人工命令。 */
    @Test
    void rejectsReservedInternalIdempotencyPrefixesBeforeLookup() {
        assertThatThrownBy(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(),
                "task:execution:device", command.commandKey(), mapper.createObjectNode(), command.requestedBy(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("保留前缀");
        assertThatThrownBy(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(),
                "rule:action", command.commandKey(), mapper.createObjectNode(), command.requestedBy(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("保留前缀");
        assertThatThrownBy(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(),
                "open:client-forged", command.commandKey(), mapper.createObjectNode(), command.requestedBy(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("保留前缀");
        verify(repository, never()).findByIdempotencyKey(any(), anyString());
    }

    /** 并发插入落败后必须复用同一候选核验，不能把竞态胜出的异内容命令返回给败方。 */
    @Test
    void rejectsDifferentConcurrentWinnerBeforeCreatingDispatchFacts() {
        JsonNode differentInput = mapper.readTree("{\"a\":9,\"b\":2}");
        when(repository.findByIdempotencyKey(command.projectId(), "key"))
                .thenReturn(Optional.empty(), Optional.of(command));
        when(devices.resolveCommandRoute(command.projectId(), command.targetDeviceId(), command.commandKey(),
                differentInput)).thenReturn(new DeviceIngestionService.DeviceCommandRoute(command.tenantId(),
                "project", command.targetDeviceId(), "target", command.connectionDeviceId(), "gateway",
                command.commandDefinitionId(), command.commandKey(), command.inputSchema(), command.outputSchema(),
                command.timeoutSeconds()));
        when(repository.create(any())).thenReturn(false);

        assertCandidateConflict(() -> service.submitTrusted(command.projectId(), command.targetDeviceId(), "key",
                command.commandKey(), differentInput, command.requestedBy(), null));
        verify(repository, never()).createAttempt(any());
        verify(outbox, never()).append(any());
    }

    /** 锁等待后降为VIEWER不能沿之前OWNER快照回读或创建。 */
    @Test
    void consoleRoleRevocationAfterPermitRejectsBeforeIdempotency() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(projects.requireRoleInProject(command.projectId())).thenReturn(ProjectRole.OWNER, ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.submit(command.projectId(), command.targetDeviceId(), "key", "reboot", mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).findByIdempotencyKey(any(), anyString());
    }

    /** RR不能提供锁等待后的新成员快照，拒绝且不得改变调用方隔离级。 */
    @Test
    @SuppressWarnings("unchecked")
    void consoleRepeatableReadIsRejectedBeforeAuthorization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        assertThatThrownBy(() -> service.submit(command.projectId(), command.targetDeviceId(), "key", "reboot", mapper.createObjectNode()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
        verify(projects, never()).requireRoleInProject(any());
    }

    /** 完整原信封匹配后才通过；JSON属性顺序不影响语义，历史缺operationType仍按COMMAND解释。 */
    @Test
    void acceptsSemanticInputAndLegacyOperationTypeAfterOrderedLocks() {
        String legacy = mapper.writeValueAsString(dispatch).replace("\"operationType\":\"COMMAND\",", "");
        when(reader.findByIdentity(any(), any(), any())).thenReturn(Optional.of(original(legacy)));
        DeviceCommandDispatch reordered = new DeviceCommandDispatch(dispatch.eventId(), dispatch.tenantId(), dispatch.projectId(),
                dispatch.commandId(), dispatch.attemptId(), 1, dispatch.targetDeviceId(), dispatch.targetDeviceKey(),
                dispatch.connectionDeviceId(), dispatch.connectionDeviceKey(), dispatch.projectKey(), dispatch.commandKey(),
                "{\"b\":2,\"a\":1}", dispatch.deadlineAt(), dispatch.traceId());
        assertThat(service.admitDispatch(reordered)).isTrue();
        var order = inOrder(lifecycle, repository, reader);
        order.verify(lifecycle).lockActiveForWrite(command.tenantId(), command.projectId());
        order.verify(repository).lockByIdentity(command.tenantId(), command.projectId(), command.id());
        order.verify(repository).findAttempts(command.projectId(), command.id());
        order.verify(reader).findByIdentity(command.tenantId(), command.projectId(), dispatch.eventId());
    }

    /** 当前PENDING冻结只提交失败终态及一次终态Outbox，不写DISPATCHED或新attempt。 */
    @Test
    void frozenPendingBecomesTerminalOnlyAfterOriginalIdentityMatches() {
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(false);
        when(repository.stopForProjectFreeze(any(), any(), any(), eq(1), any())).thenReturn(true);
        assertThat(service.admitDispatch(dispatch)).isFalse();
        verify(repository).stopForProjectFreeze(any(), any(), any(), eq(1), any());
        verify(outbox).append(any());
        verify(repository, never()).createAttempt(any());
        verify(repository, never()).markDispatched(any(), any(), anyInt(), any());
    }

    /** 原Outbox中不同targetKey属于确定信封冲突，不能因此替真实命令写冻结失败。 */
    @Test
    void mismatchedOriginalRouteRejectsBeforeFrozenTerminal() {
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(false);
        when(reader.findByIdentity(any(), any(), any())).thenReturn(Optional.of(original(
                mapper.writeValueAsString(dispatch).replace("\"targetDeviceKey\":\"target\"", "\"targetDeviceKey\":\"other\""))));
        assertThatThrownBy(() -> service.admitDispatch(dispatch)).isInstanceOf(InvalidCommandDispatchException.class);
        verify(repository, never()).stopForProjectFreeze(any(), any(), any(), anyInt(), any());
        verify(outbox, never()).append(any());
    }

    /** 数据库许可故障原样传播，不能变成无效消息或PROJECT_FROZEN。 */
    @Test
    void admissionDatabaseFailureRetainsOriginalException() {
        IllegalStateException failure = new IllegalStateException("SQL failure");
        when(lifecycle.lockActiveForWrite(any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> service.admitDispatch(dispatch)).isSameAs(failure);
        verify(reader, never()).findByIdentity(any(), any(), any());
        verify(outbox, never()).append(any());
    }

    /** 旧token消费失败必须在状态推进和新路由之前退出。 */
    @Test
    void staleDueDoesNotCreateAttemptOrResolveRoute() {
        DeviceCommandRepository.DueCommand due = new DeviceCommandRepository.DueCommand(
                command.tenantId(), command.projectId(), command.id(), UUID.randomUUID(), 1, DeviceCommandRepository.ClaimKind.PUSH_PENDING_TIMEOUT);
        service.processDue(due);
        var order = inOrder(transactionLocalRlsScope, repository);
        order.verify(transactionLocalRlsScope).establish(due.tenantId(), due.projectId());
        order.verify(repository).findByCommandId(due.projectId(), due.commandId());
        order.verify(repository).consumeRetryClaim(due);
        verify(devices, never()).resolveCommandRoute(any(), any(), any(), any());
        verify(repository, never()).prepareRetry(any(), any(), anyInt(), anyInt(), any());
        verify(outbox, never()).append(any());
    }

    /** 已消费合法领取后，冻结终态CAS失败必须抛错使领取也回滚。 */
    @Test
    void consumedFrozenRetryWithFalseTerminalCasFails() {
        when(repository.consumeRetryClaim(any())).thenReturn(true);
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.processDue(new DeviceCommandRepository.DueCommand(command.tenantId(), command.projectId(), command.id(), UUID.randomUUID(), 1, DeviceCommandRepository.ClaimKind.PUSH_PENDING_TIMEOUT)))
                .isInstanceOf(IllegalStateException.class).hasMessage("命令冻结终态CAS失败");
        verify(outbox, never()).append(any());
    }

    /** 原信封合法也必须受锁后数据库截止约束，边界时刻不再准入。 */
    @Test
    void expiredOriginalDispatchIsNotAdmitted() {
        when(repository.databaseNow()).thenReturn(dispatch.deadlineAt());
        assertThat(service.admitDispatch(dispatch)).isFalse();
        verify(repository, never()).markDispatched(any(), any(), anyInt(), any());
    }

    /** ADR0143：到期但从未投出的尝试进入退避，不能误作响应超时。 */
    @Test
    void expiredPendingSchedulesDispatchBackoff() {
        when(repository.consumeRetryClaim(any())).thenReturn(true);
        when(repository.markAttemptDispatchFailed(any(), any(), eq(1), any(), any(), any())).thenReturn(true);
        when(repository.scheduleDispatchRetry(any(), any(), eq(1), any())).thenReturn(true);
        service.processDue(new DeviceCommandRepository.DueCommand(
                command.tenantId(), command.projectId(), command.id(), UUID.randomUUID(), 1, DeviceCommandRepository.ClaimKind.PUSH_PENDING_TIMEOUT));
        verify(repository).markAttemptDispatchFailed(eq(command.projectId()), eq(command.id()), eq(1),
                eq("DISPATCH_PENDING_TIMEOUT"), anyString(), any());
        verify(repository, never()).prepareRetry(any(), any(), anyInt(), anyInt(), any());
        verify(repository, never()).createAttempt(any());
    }

    /** 发布结果是纯维护，先锁当前命令和attempt，绝不反向取得project。 */
    @Test
    void markDispatchedUsesCommandLockWithoutProjectLock() {
        when(repository.markDispatched(any(), any(), eq(1), any())).thenReturn(true);
        assertThat(service.markDispatched(dispatch, Instant.now())).isTrue();
        var order = inOrder(transactionLocalRlsScope, repository);
        order.verify(transactionLocalRlsScope).establish(command.tenantId(), command.projectId());
        order.verify(repository).lockByIdentity(command.tenantId(), command.projectId(), command.id());
        order.verify(repository).findAttempts(command.projectId(), command.id());
        order.verify(repository).markDispatched(any(), any(), eq(1), any());
        verify(lifecycle, never()).lockActiveForWrite(any(), any());
    }

    /** 当前attempt已改FAILED但父命令退避CAS失败，必须回滚，不能留下部分业务成功。 */
    @Test
    void dispatchFailureRequiresParentBackoffCas() {
        when(repository.markAttemptDispatchFailed(any(), any(), eq(1), any(), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.recordDispatchFailure(dispatch, DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED, Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessage("命令派发退避CAS失败");
        verify(lifecycle, never()).lockActiveForWrite(any(), any());
    }

    /** 核验缺失领取字段，不给三UUID兼容构造留下绕过有效token的路径。 */
    @Test
    void rejectsIncompleteDueBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.processDue(new DeviceCommandRepository.DueCommand(command.tenantId(), command.projectId(), command.id(), null, 1, DeviceCommandRepository.ClaimKind.PUSH_PENDING_TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("命令重试领取身份不完整");
        verify(transactionLocalRlsScope, never()).establish(any(), any());
        verify(repository, never()).lockByIdentity(any(), any(), any());
    }

    /** 断言候选差异统一为10009，测试不读取或输出旧命令字段。 */
    private void assertCandidateConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(10009));
    }

    /** 装配同一持久事实供锁前观察和锁后重读，测试可分别覆盖覆盖变化。 */
    private void installCommand() {
        when(repository.findByCommandId(command.projectId(), command.id())).thenReturn(Optional.of(command));
        when(repository.lockByIdentity(command.tenantId(), command.projectId(), command.id())).thenReturn(Optional.of(command));
        when(repository.findAttempts(command.projectId(), command.id())).thenReturn(List.of(attempt));
    }

    /** 原事件全部元数据保持真实形状，只让测试显式改变payload。 */
    private OutboxEvent original(String payload) {
        return new OutboxEvent(dispatch.eventId(), command.tenantId(), command.projectId(), "DEVICE_COMMAND", command.id(),
                DeviceCommandService.DISPATCH_EVENT_TYPE, dispatch.connectionDeviceId().toString(), payload, "trace", command.acceptedAt());
    }
}
