package com.things.link.bootstrap.telemetry.command;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0070命令交付持续许可与真实领取验收：使用专库、真实Console服务/路由/claim和状态机。
 * Console仅提供真实账号的TenantContext，后台processDue无外层事务或scope，避免掩盖生产入口职责。
 */
@Import(CommandProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"COMMAND_POSTGRES"})
class CommandProjectLifecycleTests extends AbstractIntegrationTest {
    /** 隔离跨项目全局claim，不以随机ID代替数据库隔离。 */
    private static final String DATABASE_NAME = "command_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 使用既有镜像/真实Flyway；专用容器交由本类OwnedTestContainers回收。 */
    private static final PostgreSQLContainer<?> COMMAND_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** owner、Flyway及应用池的唯一实际地址。 */
    private static final String DATABASE_URL = startDatabase();
    /** 父runner固定直连共享库，不允许本例越库调整配额。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuota;
    /** 由本例显式运行claim/process，禁止自动scanner抢走观察对象。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedScanner;
    /** 无关通知不参与本例调度。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotifications;
    /** 真正受理与状态迁移服务，事务由原Spring代理自行开启。 */
    @Autowired private DeviceCommandService commands;
    /** 原全局claim与持久CAS，禁止mock成功。 */
    @Autowired private DeviceCommandRepository repository;
    /** spy仅在真实路由返回后观察或提交竞争者，不替代路由内容。 */
    @MockitoSpyBean private DeviceIngestionService routes;
    /** ADR0196续重冻结原路由，观察实际接收关系许可而非重新解析命令定义。 */
    @MockitoSpyBean private com.things.link.device.application.DeviceCommandReceiverPort receivers;
    /** 观察真实许可前PID，锁行为由原SQL完成。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实写入后故障用于证明命令状态、领取及Outbox同事务回滚。 */
    @MockitoSpyBean private TransactionalOutboxRepository outbox;
    /** 仅隔离级验收使用显式调用方事务，其余入口均自行开事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 原APP池用于实际数据库/角色与事务PID核验。 */
    @Autowired private JdbcTemplate jdbc;
    /** 原持久Outbox信封解析器。 */
    @Autowired private ObjectMapper mapper;
    /** 每例唯一真实祖先，满足角色、设备类型及命令定义前置。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate());
    /** 真实返回路由的次数可区分失败在入口前置还是出现了竞争穿透。 */
    private final AtomicInteger routeReturns = new AtomicInteger();
    /** 默认无竞争；反例只在原方法真实成功返回后插入冻结提交。 */
    private Runnable afterRoute = () -> { };

    /** 专库角色及空候选先于夹具写入核验，所有业务路由均保留原逻辑。 */
    @BeforeEach
    void prepare() throws Exception {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(mockingDetails(unusedRestQuota).isMock()).isTrue();
        assertThat(mockingDetails(unusedScanner).isMock()).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                Map<String, Object> identity = jdbc.queryForMap("SELECT current_database(), current_user");
                assertThat(identity.get("current_database")).isEqualTo(DATABASE_NAME);
                assertThat(identity.get("current_user")).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection owner = fixtureOwnerConnection()) {
            try (PreparedStatement query = owner.prepareStatement("SELECT current_database(), current_user");
                 ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
                assertThat(rows.getString(2)).isEqualTo(COMMAND_POSTGRES.getUsername());
            }
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command")).isZero();
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command_attempt")).isZero();
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event")).isZero();
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '命令生命周期验收租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '命令OWNER')", fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '命令生命周期项目', 'sh-1', ?)", fixture.projectId(), fixture.tenantId(), "command_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'command_type', '命令类型', 'STANDARD', 'DIRECT', 'PUBLISHED')", fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?, ?, ?, ?, 'reboot', '重启', '{}'::jsonb, '{}'::jsonb, 30)", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'command_device', '命令设备', 'ONLINE')", fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
        DeviceIngestionService target = AopTestUtils.getUltimateTargetObject(routes);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            Object result = invocation.callRealMethod();
            routeReturns.incrementAndGet(); afterRoute.run(); return result;
        }).when(target).resolveCommandRoute(any(), any(), any(), any());
        var receiverTarget = AopTestUtils.getUltimateTargetObject(receivers);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            Object result = invocation.callRealMethod();
            routeReturns.incrementAndGet(); afterRoute.run(); return result;
        }).when((com.things.link.device.application.DeviceCommandReceiverPort) receiverTarget)
                .lockCurrent(any(), any(), any(), any());
    }

    /** ACTIVE正常受理、真实失败/claim后重试均能提交，反例不能来自错误路由或作用域。 */
    @Test
    void activeConsoleAndClaimedRetryCreateMatchingAttemptsAndOutbox() throws Exception {
        DeviceCommand first = submit("normal");
        assertFacts(1, 1, 1);
        DeviceCommandRepository.DueCommand due = prepareDue(first.id(), RetryKind.DISPATCH_FAILURE);
        process(due);
        assertFacts(1, 2, 2);
        assertThat(latestDispatch(first.id()).attemptNo()).isEqualTo(2);
        assertThat(routeReturns).hasValue(2);
    }

    /** ADR0070归档新键在原路由前明确50017，不把旧50001当成错误分类的合格结果。 */
    @Test
    void archiveBeforeNewConsoleCommandRejectsReadOnlyBeforeRoute() throws Exception {
        archive();
        Throwable failure = catchThrowable(() -> submit("new-after-archive"));
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(50017);
        assertFacts(0, 0, 0);
        assertThat(routeReturns).hasValue(0);
    }

    /** ADR0064归档业务写应50017，旧实现先回读幂等而没有路由/持续许可检查。 */
    @Test
    void archivedConsoleIdempotentCommandMustRejectWithReadOnlyCode() throws Exception {
        submit("existing-key");
        archive();
        Throwable failure = catchThrowable(() -> submit("existing-key"));
        assertFacts(1, 1, 1);
        assertThat(routeReturns).hasValue(1);
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(50017);
    }

    /** 旧RED路由后归档可先提交；新合同已持SHARE，独立归档者实际等待本次命令提交。 */
    @Test
    void consolePermitKeepsArchiveBehindCommandCommit() throws Exception {
        runRouteWriterBeforeArchive(() -> submit("route-race"));
        assertArchived();
        assertThat(routeReturns).hasValue(1);
        assertFacts(1, 1, 1);
    }

    /** 两种真实到期状态都经过原claim；许可先取得则同一尝试/Outbox提交前归档必须等待。 */
    @ParameterizedTest
    @EnumSource(RetryKind.class)
    void retryPermitKeepsArchiveBehindAttemptCommit(RetryKind kind) throws Exception {
        DeviceCommand first = submit("retry-race");
        DeviceCommandRepository.DueCommand due = prepareDue(first.id(), kind);
        runRouteWriterBeforeArchive(() -> { process(due); return null; });
        assertArchived();
        assertThat(routeReturns).hasValue(2);
        assertFacts(1, 2, 2);
    }

    /** 旧Due来自attempt1；attempt2真实失败的原5秒退避未到，不能凭同三UUID继续创建attempt3。 */
    @Test
    void oldDueMustNotSkipLaterAttemptDispatchBackoff() throws Exception {
        DeviceCommand first = submit("stale-due");
        DeviceCommandRepository.DueCommand oldDue = prepareDue(first.id(), RetryKind.DISPATCH_FAILURE);
        process(oldDue);
        DeviceCommandDispatch second = latestDispatch(first.id());
        assertThat(second.attemptNo()).isEqualTo(2);
        inData(() -> { commands.recordDispatchFailure(second, DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED, Instant.now()); return null; });
        // 不回拨新退避、不伪造新claim。原服务真实写出的未到期时间在进入旧Due前和路由返回后均确认。
        assertSecondAttemptBackoffNotDue(first.id());
        afterRoute = () -> assertSecondAttemptBackoffNotDue(first.id());
        process(oldDue);
        assertFacts(1, 2, 2);
    }

    /** 冻结先提交：两种到期状态都收束当前事实，不经过路由、不创建下一attempt或派发意图。 */
    @ParameterizedTest
    @EnumSource(RetryKind.class)
    void frozenClaimedRetryStopsWithoutNewDispatch(RetryKind kind) throws Exception {
        DeviceCommand first = submit("frozen-retry");
        DeviceCommandRepository.DueCommand due = prepareDue(first.id(), kind);
        archive();
        process(due);
        assertFacts(1, 1, 1);
        assertThat(routeReturns).hasValue(1);
        assertCommandState(first.id(), "FAILED", "PROJECT_FROZEN");
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command WHERE id = ? AND retry_token IS NULL AND retry_leased_until IS NULL AND next_attempt_at IS NULL", first.id())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event WHERE aggregate_id = ? AND event_type = 'DEVICE_COMMAND_TERMINAL'", first.id())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command_attempt WHERE command_id = ? AND status = ? AND error_code = ?", first.id(),
                    kind == RetryKind.DISPATCH_FAILURE ? "FAILED" : "TIMED_OUT",
                    kind == RetryKind.DISPATCH_FAILURE ? "DISPATCH_CONNECTION_FAILED" : "RESPONSE_TIMEOUT")).isEqualTo(1);
            execute(owner, "UPDATE sys_project SET status = 'ACTIVE' WHERE id = ?", fixture.projectId());
        }
        process(due);
        assertCommandState(first.id(), "FAILED", "PROJECT_FROZEN");
        assertFacts(1, 1, 1);
    }

    /** 协作者JWT租户不同，命令归属仍来自已授权项目，不能误当tenant错配拒绝。 */
    @Test
    void crossTenantConsoleCollaboratorUsesProjectOwnerTenant() throws Exception {
        Collaborator collaborator = collaborator();
        DeviceCommand result = submitAs(collaborator.tenantId(), collaborator.accountId(), "collaborator");
        assertThat(result.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(result.requestedBy()).isEqualTo(collaborator.accountId());
        assertFacts(1, 1, 1);
    }

    /** 锁等待期间OPERATOR被降权，许可后第二次角色查询必须拒绝，不能沿锁前旧快照继续。 */
    @Test
    void consoleRechecksRoleAfterProjectLockWait() throws Exception {
        Collaborator collaborator = collaborator();
        CompletableFuture<Integer> permitPid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> { permitPid.complete(actualPid()); return invocation.callRealMethod(); })
                .when(target).requireActiveForWrite(fixture.tenantId(), fixture.projectId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            try {
                execute(owner, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                execute(owner, "UPDATE sys_project_member SET role = 'VIEWER' WHERE project_id = ? AND account_id = ?", fixture.projectId(), collaborator.accountId());
                int blocker = (int) count(owner, "SELECT pg_backend_pid()");
                Future<Throwable> submitting = executor.submit(() -> catchThrowable(() -> submitAs(collaborator.tenantId(), collaborator.accountId(), "downgraded")));
                assertBlockedBy(permitPid.get(3, TimeUnit.SECONDS), blocker, submitting);
                owner.commit();
                Throwable failure = submitting.get(5, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(
                        com.things.link.telemetry.domain.DeviceCommandErrorCode.COMMAND_CONTROL_FORBIDDEN.code());
                assertFacts(0, 0, 0);
                assertThat(routeReturns).hasValue(0);
            } finally { owner.rollback(); shutdown(executor); }
        }
    }

    /** 实际RR和SERIALIZABLE无法提供本合同的锁后成员快照，必须拒绝且不改变调用方隔离。 */
    @ParameterizedTest
    @ValueSource(ints = {Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE})
    void consoleRejectsStrongerCallerIsolation(int isolation) throws Exception {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(isolation);
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            Throwable failure = catchThrowable(() -> outer.executeWithoutResult(status -> commands.submit(
                    fixture.projectId(), fixture.deviceId(), "strong-isolation", "reboot", mapper.createObjectNode())));
            assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
        } finally { TenantContext.clear(); }
        assertFacts(0, 0, 0);
    }

    /** 续重已实际写完新attempt与Outbox后抛错，原领取及业务事实必须整体恢复，原token还能合法重试。 */
    @Test
    void failureAfterRealRetryOutboxWriteRollsBackClaimAndAttempt() throws Exception {
        DeviceCommand first = submit("rollback-retry");
        DeviceCommandRepository.DueCommand due = prepareDue(first.id(), RetryKind.DISPATCH_FAILURE);
        List<String> before = commandFacts(first.id());
        TransactionalOutboxRepository target = AopTestUtils.getUltimateTargetObject(outbox);
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            invocation.callRealMethod();
            if (event.aggregateId().equals(first.id()) && event.eventType().equals(DeviceCommandService.DISPATCH_EVENT_TYPE)) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ts_device_command_attempt WHERE command_id = ?", Integer.class, first.id())).isEqualTo(2);
                throw new IllegalStateException("after real retry Outbox write");
            }
            return null;
        }).when(target).append(any());
        assertThat(catchThrowable(() -> process(due)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessage("after real retry Outbox write")
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(commandFacts(first.id())).isEqualTo(before);
        doAnswer(invocation -> invocation.callRealMethod()).when(target).append(any());
        process(due);
        assertFacts(1, 2, 2);
    }

    /** 项目锁等待使用原5秒SQL预算，失败回滚不消费真实领取，解锁后同token可以继续。 */
    @Test
    void retryPermissionTimeoutRetainsClaimForRecovery() throws Exception {
        DeviceCommand first = submit("timeout-retry");
        DeviceCommandRepository.DueCommand due = prepareDue(first.id(), RetryKind.RESPONSE_TIMEOUT);
        List<String> before = commandFacts(first.id());
        CompletableFuture<Integer> permitPid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> { permitPid.complete(actualPid()); return invocation.callRealMethod(); })
                .when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            try {
                execute(owner, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int blocker = (int) count(owner, "SELECT pg_backend_pid()");
                Future<Throwable> processing = executor.submit(() -> catchThrowable(() -> process(due)));
                assertBlockedBy(permitPid.get(3, TimeUnit.SECONDS), blocker, processing);
                assertThat(sqlState(processing.get(10, TimeUnit.SECONDS))).isEqualTo("57014");
                assertThat(commandFacts(first.id())).isEqualTo(before);
                owner.rollback();
            } finally { owner.rollback(); shutdown(executor); }
        }
        process(due); assertFacts(1, 2, 2);
    }

    /** 以独立线程/连接证明归档被真实SHARE阻塞，不能在同线程spy同步UPDATE制造自锁。 */
    private void runRouteWriterBeforeArchive(Supplier<?> operation) throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        afterRoute = () -> { writerPid.complete(actualPid()); reached.countDown(); await(release); };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(operation::get);
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> archivePid = new CompletableFuture<>();
            Future<?> archiver = executor.submit(() -> {
                try (Connection owner = fixtureOwnerConnection()) {
                    archivePid.complete((int) count(owner, "SELECT pg_backend_pid()"));
                    execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
            });
            assertBlockedBy(archivePid.get(3, TimeUnit.SECONDS), writerPid.get(3, TimeUnit.SECONDS), archiver);
            release.countDown(); writer.get(5, TimeUnit.SECONDS); archiver.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); shutdown(executor); afterRoute = () -> { }; }
    }

    /** 屏障中断不能假成功，保留中断并使后台操作失败。 */
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    /** 实际APP事务PID证明观察的是生产连接，不是owner或无事务查询。 */
    private int actualPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 三个不同连接与未授予锁证明顺序，Future未完成本身不算数据库锁证据。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        try (Connection observer = fixtureOwnerConnection(); PreparedStatement query = observer.prepareStatement(
                "SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)), EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)")) {
            query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isNotEqualTo(holder).isNotEqualTo(waiter);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("操作完成但未观察到预期项目锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到预期项目锁等待");
    }

    /** 退出先释放屏障，再等待线程，不能留下后台写者污染后续候选。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** SQL错误只接受数据库真实状态码，不能用任意异常伪装超时。 */
    private String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    /** 通过公开服务检验跨租户授权，成员身份由真实表关系提供。 */
    private Collaborator collaborator() throws SQLException {
        Collaborator result = new Collaborator(Uuid7.generate(), Uuid7.generate());
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '外部协作租户')", result.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '外部协作者')", result.accountId(), result.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), result.tenantId(), result.accountId());
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OPERATOR')", Uuid7.generate(), fixture.projectId(), result.accountId());
        }
        return result;
    }

    /** Console真实账号范围不改变项目owner；只有本辅助模拟HTTP已经验签的调用上下文。 */
    private DeviceCommand submitAs(UUID tenantId, UUID accountId, String key) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(tenantId, fixture.projectId(), accountId));
        try { return commands.submit(fixture.projectId(), fixture.deviceId(), key, "reboot", mapper.createObjectNode()); }
        finally { TenantContext.clear(); }
    }

    /** 全行比较覆盖领取token、调度时间、尝试及原事件，数量不够证明回滚。 */
    private List<String> commandFacts(UUID commandId) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : List.of("ts_device_command", "ts_device_command_attempt", "sys_outbox_event")) {
                String identity = table.equals("ts_device_command") ? "id" : table.equals("ts_device_command_attempt") ? "command_id" : "aggregate_id";
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(r)::text FROM " + table + " r WHERE " + identity + " = ? ORDER BY id")) {
                    query.setObject(1, commandId);
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) facts.add(table + rows.getString(1)); }
                }
            }
        }
        return facts;
    }

    /** 状态与原因以独立owner读已提交事实，不能用方法返回值冒充终态。 */
    private void assertCommandState(UUID commandId, String expected, String code) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement("SELECT status, failure_code FROM ts_device_command WHERE id = ?")) {
            query.setObject(1, commandId);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(expected); assertThat(rows.getString(2)).isEqualTo(code); }
        }
    }

    /** @param tenantId 协作者自己的租户 @param accountId 已真实加入目标项目的OPERATOR */
    private record Collaborator(UUID tenantId, UUID accountId) { }

    /** 原Console调用方只提供真实成员上下文，原submit代理自己开启业务事务。 */
    private DeviceCommand submit(String key) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { return commands.submit(fixture.projectId(), fixture.deviceId(), key, "reboot", mapper.createObjectNode()); }
        finally { TenantContext.clear(); }
    }

    /** 原状态API建立失败/派发，owner只把该行调度时间调整到期；claim完全真实且单候选。 */
    private DeviceCommandRepository.DueCommand prepareDue(UUID commandId, RetryKind kind) throws Exception {
        DeviceCommandDispatch dispatch = latestDispatch(commandId);
        if (kind == RetryKind.DISPATCH_FAILURE) {
            inData(() -> { commands.recordDispatchFailure(dispatch, DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED, Instant.now()); return null; });
        } else {
            assertThat(inData(() -> commands.markDispatched(dispatch, Instant.now()))).isTrue();
        }
        try (Connection owner = fixtureOwnerConnection()) {
            if (kind == RetryKind.DISPATCH_FAILURE) {
                assertThat(count(owner, "SELECT count(*) FROM ts_device_command WHERE id = ? AND status = 'ACCEPTED' AND deadline_at IS NULL", commandId)).isEqualTo(1);
                execute(owner, "UPDATE ts_device_command SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?", commandId);
            } else {
                assertThat(count(owner, "SELECT count(*) FROM ts_device_command WHERE id = ? AND status = 'DISPATCHED'", commandId)).isEqualTo(1);
                execute(owner, "UPDATE ts_device_command SET deadline_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?", commandId);
            }
        }
        List<DeviceCommandRepository.DueCommand> due = inData(() -> repository.claimDue(1));
        assertThat(due).singleElement().satisfies(candidate -> {
            assertThat(candidate.tenantId()).isEqualTo(fixture.tenantId());
            assertThat(candidate.projectId()).isEqualTo(fixture.projectId());
            assertThat(candidate.commandId()).isEqualTo(commandId);
        });
        return due.getFirst();
    }

    /** 不提供TenantContext/RLS或外层事务；后台入口必须按真实due自行恢复事务内作用域。 */
    private void process(DeviceCommandRepository.DueCommand due) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        inData(() -> { commands.processDue(due); return null; });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    /** 只选择生产数据池，不把测试外层事务强加给原服务。 */
    private <T> T inData(Supplier<T> work) {
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) { return work.get(); }
        finally { assertThat(TenantContext.current()).isEmpty(); assertThat(RlsScopeContext.current()).isEmpty(); }
    }

    /** 读真实Outbox完整信封，不能手拼attempt/event身份掩盖旧消息与新事实差异。 */
    private DeviceCommandDispatch latestDispatch(UUID commandId) throws Exception {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement("""
                SELECT o.payload::text FROM ts_device_command_attempt a
                  JOIN sys_outbox_event o ON o.id = a.outbox_event_id
                 WHERE a.project_id = ? AND a.command_id = ? ORDER BY a.attempt_no DESC LIMIT 1
                """)) {
            query.setObject(1, fixture.projectId()); query.setObject(2, commandId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return mapper.readValue(rows.getString(1), DeviceCommandDispatch.class);
            }
        }
    }

    /** 独立提交归档且保留设备和成员，不能用删除关系制造错误拒绝。 */
    private void archive() {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
        } catch (SQLException failure) { throw new IllegalStateException("归档竞争者提交失败", failure); }
    }

    /** 独立连接观察归档已提交，区分真实竞争与只修改内存对象。 */
    private void assertArchived() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'ARCHIVED' AND deleted_at IS NULL", fixture.projectId())).isEqualTo(1);
        }
    }

    /** 两次独立观察证实原失败API的退避仍未到；不延长或修改生产退避配置。 */
    private void assertSecondAttemptBackoffNotDue(UUID commandId) {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command WHERE id = ? AND status = 'ACCEPTED' AND attempt_count = 2 AND deadline_at IS NULL AND next_attempt_at > clock_timestamp()", commandId)).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command_attempt WHERE command_id = ? AND attempt_no = 2 AND status = 'FAILED'", commandId)).isEqualTo(1);
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    /** 精确限定本例项目的命令、attempt和派发Outbox，不把其他业务事件混入数量。 */
    private void assertFacts(int expectedCommands, int expectedAttempts, int expectedDispatches) throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command WHERE project_id = ?", fixture.projectId())).as("command事实").isEqualTo(expectedCommands);
            assertThat(count(owner, "SELECT count(*) FROM ts_device_command_attempt WHERE project_id = ?", fixture.projectId())).as("attempt事实").isEqualTo(expectedAttempts);
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = 'DEVICE_COMMAND_DISPATCH'", fixture.projectId())).as("派发Outbox").isEqualTo(expectedDispatches);
        }
    }

    /** 只清理本例可变命令/Outbox，保留任何不可变审计和祖先直至专库由OwnedTestContainers在本类上下文物理关闭后回收。 */
    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear(); RlsScopeContext.clear();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ? AND aggregate_type = 'DEVICE_COMMAND'", fixture.projectId());
            execute(owner, "DELETE FROM ts_device_command_attempt WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
        }
    }

    /** owner只用于前置、独立观察和精确清理，受理/重试均走APP生产入口。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, COMMAND_POSTGRES.getUsername(), COMMAND_POSTGRES.getPassword());
    }

    /** 参数化写操作不拼接业务输入。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 失败不能默认为0，独立结果必须存在。 */
    private long count(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        }
    }

    /** 容器先于Spring初始化，实际DB销毁交给本类OwnedTestContainers。 */
    private static String startDatabase() { COMMAND_POSTGRES.start(); return COMMAND_POSTGRES.getJdbcUrl(); }

    /** 动态Registrar保证继承的共享URL不覆盖专库；只关闭本例自动传递者。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** Flyway、控制面和数据面统一专库；其他业务预算不变。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }

    /** 分别覆盖deadline为空的派发退避和已发布后的响应超时，不手写尝试状态。 */
    private enum RetryKind {
        /** 原recordDispatchFailure产生FAILED当前attempt。 */ DISPATCH_FAILURE,
        /** 原markDispatched产生PUBLISHED当前attempt。 */ RESPONSE_TIMEOUT
    }

    /** @param tenantId 真实归属 @param projectId 本例项目 @param accountId OWNER @param typeId 命令类型 @param deviceId 直连设备 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId) { }
}
