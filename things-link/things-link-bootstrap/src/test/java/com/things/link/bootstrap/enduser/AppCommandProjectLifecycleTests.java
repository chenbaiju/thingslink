package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppDeviceAccessService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.telemetry.application.AppCommandResult;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR0064决策4：App命令在原写事务先获项目许可，直调及幂等回读也不能绕过生命周期拒绝。
 * 真实APP角色/RLS、OWNER删除、command/attempt/Outbox参与事务；spy只设置真实执行点屏障，不伪造许可。
 */
class AppCommandProjectLifecycleTests extends AbstractIntegrationTest {

    /** 合法App夹具只存真实编码的密码，本类不发HTTP请求或借用共享IP限流桶。 */
    private static final String PASSWORD = "secret123";
    /** 固定命令幂等键只在当前随机项目内使用。 */
    private static final String IDEMPOTENCY_KEY = "project-lifecycle-command";
    /** 持久命令派发事件类型，不把Outbox接纳冒称设备网络交付。 */
    private static final String DISPATCH_EVENT = "DEVICE_COMMAND_DISPATCH";
    /** 应用真实事务代理，测试不为命令提交包外层事务。 */
    @Autowired private AppDeviceAccessService access;
    /** 删除仍经过生产OWNER权限及业务事务。 */
    @Autowired private ProjectService projectService;
    /** 对输入及真实持久信封使用生产JSON规则。 */
    @Autowired private ObjectMapper mapper;
    /** 观察同一APP物理连接及真实语句预算。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 只为控制删除提交时刻提供真实外层事务，命令入口自行开事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 合法用户种子复用S11的真实密码编码。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 真实许可SQL之前公布连接PID，保留MANDATORY及原返回结果。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实append完成后暂停，证明项目许可保持至命令/attempt/Outbox共同提交。 */
    @MockitoSpyBean private JdbcTransactionalOutboxRepository outboxRepository;
    /** 每例随机项目独占命令、角色、设备和清理范围。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());

    /** 复用S11最小ONLINE DIRECT设备、restart定义及PRIMARY关系，不增加无关遥测夹具。 */
    @BeforeEach
    void prepare() throws SQLException {
        seedFixture();
    }

    /** ACTIVE真实命令和同键重放只产生一组完整事实，发起者必须是App用户而非控制台OWNER。 */
    @Test
    void activeCommandAndIdempotentReplayCommitOneCompleteDispatch() throws Exception {
        AppCommandResult first = submit(fixture.tenantId());
        assertAccepted(first);
        List<String> beforeReplay = facts();
        AppCommandResult replay = submit(fixture.tenantId());
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(facts()).isEqualTo(beforeReplay);
    }

    /** S12-0d：同项目另一已授权App用户复用key必须冲突，不能取得首个用户的命令事实。 */
    @Test
    void rejectsIdempotencyKeyReusedByAnotherAppUser() throws Exception {
        AppCommandResult accepted = submit(fixture.tenantId());
        UUID otherUser = seedAdditionalAppUser();
        List<String> beforeConflict = facts();

        Throwable failure = catchThrowable(() -> submitAsAppUser(otherUser));

        assertFailure(failure, 10009);
        assertThat(facts()).isEqualTo(beforeConflict);
        assertThat(accepted.commandId()).isNotNull();
    }

    /** ARCHIVED可读不能被当作命令写资格；确定拒绝为60022，不滥用刷新语义的60009。 */
    @Test
    void archivedProjectRejectsCommandWithoutSideEffects() throws Exception {
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
        }
        List<String> before = facts();
        assertFailure(catchThrowable(() -> submit(fixture.tenantId())), 60022);
        assertThat(facts()).isEqualTo(before);
        assertNoCommands();
    }

    /** 真实删除保留ACTIVE角色和PRIMARY绑定；旧授权事实不能使新命令在删除后提交。 */
    @Test
    void ownerDeletionRejectsNewCommandWhileKeepingRoleAndBindingFacts() throws Exception {
        List<String> before = facts();
        deleteAsOwner();
        assertDeletedAndBindingsPreserved();
        assertFailure(catchThrowable(() -> submit(fixture.tenantId())), 60009);
        assertThat(facts()).isEqualTo(before);
        assertNoCommands();
    }

    /** 已成功命令的幂等键也必须先经过生命周期许可，不能从遥测幂等回读提前返回成功。 */
    @Test
    void ownerDeletionRejectsReplayOfAnAlreadyAcceptedCommand() throws Exception {
        AppCommandResult accepted = submit(fixture.tenantId());
        assertAccepted(accepted);
        List<String> before = facts();
        deleteAsOwner();
        assertDeletedAndBindingsPreserved();
        assertFailure(catchThrowable(() -> submit(fixture.tenantId())), 60009);
        assertThat(facts()).isEqualTo(before);
    }

    /** 故意保留真实项目RLS范围而只传错tenant，证明显式身份二元组不能被ThreadLocal补齐。 */
    @Test
    void wrongTenantCannotBorrowProjectScopeForCommand() throws Exception {
        List<String> before = facts();
        assertFailure(catchThrowable(() -> submit(Uuid7.generate())), 60009);
        assertThat(facts()).isEqualTo(before);
        assertNoCommands();
    }

    /** append已执行但尚未提交时，删除必须被原命令事务项目SHARE许可挡住，独立连接仍看不到三组新事实。 */
    @Test
    void commandFirstHoldsProjectPermitUntilAllDispatchFactsCommit() throws Exception {
        List<String> before = facts();
        CompletableFuture<Integer> commandPid = new CompletableFuture<>();
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcTransactionalOutboxRepository target = AopTestUtils.getUltimateTargetObject(outboxRepository);
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            invocation.callRealMethod();
            if (fixture.projectId().equals(event.projectId()) && DISPATCH_EVENT.equals(event.eventType())) {
                commandPid.complete(appPid());
                for (String table : List.of("ts_device_command", "ts_device_command_attempt", "sys_outbox_event")) {
                    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?", Integer.class, fixture.projectId())).isEqualTo(1);
                }
                appended.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(target).append(any(OutboxEvent.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AppCommandResult> command = executor.submit(() -> submit(fixture.tenantId()));
            assertThat(appended.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(facts()).isEqualTo(before);
            CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
            Future<?> deletion = executor.submit(() -> deleteTransaction(deletionPid, null, null));
            assertBlockedBy(deletionPid.get(3, TimeUnit.SECONDS), commandPid.get(3, TimeUnit.SECONDS), deletion);
            assertThat(facts()).isEqualTo(before);
            release.countDown();
            assertAccepted(command.get(8, TimeUnit.SECONDS));
            deletion.get(8, TimeUnit.SECONDS);
            assertDeletedAndBindingsPreserved();
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** 删除先写但未提交，命令等待真实项目行；删除提交后许可重验失败，不能创建命令或提前回读幂等结果。 */
    @Test
    void deletionFirstMakesWaitingCommandRejectAfterCommit() throws Exception {
        List<String> before = facts();
        CompletableFuture<Integer> commandPid = capturePermitPid();
        CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> deleteTransaction(deletionPid, deleted, release));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> command = executor.submit(() -> catchThrowable(() -> submit(fixture.tenantId())));
            assertBlockedBy(commandPid.get(3, TimeUnit.SECONDS), deletionPid.get(3, TimeUnit.SECONDS), command);
            assertThat(facts()).isEqualTo(before);
            release.countDown();
            deletion.get(8, TimeUnit.SECONDS);
            assertFailure(command.get(8, TimeUnit.SECONDS), 60009);
            assertDeletedAndBindingsPreserved();
            assertThat(facts()).isEqualTo(before);
            assertNoCommands();
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** 不调测试语句预算：真实五秒JDBC锁等待取消必须传播为SQL故障，原事实不变且解锁后同键可成功。 */
    @Test
    void projectPermitSqlTimeoutRollsBackAndRecoversAfterUnlock() throws Exception {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
        List<String> before = facts();
        CompletableFuture<Integer> commandPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<Throwable> command = executor.submit(() -> catchThrowable(() -> submit(fixture.tenantId())));
                assertBlockedBy(commandPid.get(3, TimeUnit.SECONDS), holderPid, command);
                Throwable failure = command.get(10, TimeUnit.SECONDS);
                assertThat(isSqlTimeout(failure)).as("原5秒预算应取消项目锁SQL：%s", failure).isTrue();
                assertThat(failure).isNotInstanceOf(BusinessException.class);
                assertThat(facts()).isEqualTo(before);
                assertNoCommands();
                holder.rollback();
                assertAccepted(submit(fixture.tenantId()));
            } finally {
                holder.rollback();
                shutdown(executor);
            }
        }
    }

    /** 明确信任输入来自已认证App身份；只建立RLS范围，真实submit代理负责全部事务。 */
    private AppCommandResult submit(UUID tenantId) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            return access.submit(tenantId, fixture.projectId(), fixture.userId(), fixture.deviceId(),
                    IDEMPOTENCY_KEY, "restart", mapper.readTree("{\"param\":1}"));
        } finally {
            RlsScopeContext.clear();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TenantContext.current()).isEmpty();
        }
    }

    /** 以同项目另一真实ACTIVE/PRIMARY用户进入相同数据面入口。 */
    private AppCommandResult submitAsAppUser(UUID appUserId) {
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            return access.submit(fixture.tenantId(), fixture.projectId(), appUserId, fixture.deviceId(),
                    IDEMPOTENCY_KEY, "restart", mapper.readTree("{\"param\":1}"));
        } finally {
            RlsScopeContext.clear();
        }
    }

    /** 真实项目许可执行之前公布物理连接，不替换参数、结果、事务或数据库锁。 */
    private CompletableFuture<Integer> capturePermitPid() {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            pid.complete(appPid());
            return invocation.callRealMethod();
        }).when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
        return pid;
    }

    /** 真实业务事务必须经APP角色执行，PID用于锁图而非仅以Future未完成猜测阻塞。 */
    private int appPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 普通删除调用不包测试事务，生产OWNER权限和软删入口自行提交。 */
    private void deleteAsOwner() {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            projectService.delete(fixture.projectId());
        } finally {
            TenantContext.clear();
        }
    }

    /** 并发排序专用真实外层删除事务；不延伸到命令入口或外部网络。 */
    private void deleteTransaction(CompletableFuture<Integer> pid, CountDownLatch deleted, CountDownLatch release) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                pid.complete(appPid());
                projectService.delete(fixture.projectId());
                if (deleted != null) {
                    deleted.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** 只接受冻结的业务拒绝码，不把RLS/SQL错误或连接超时误当项目失效。 */
    private void assertFailure(Throwable failure, int errorCode) {
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(errorCode);
    }

    /** 三个独立PID和未授予锁共同证明等待指定持锁事务，不依赖任意固定sleep。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        assertThat(waiter).isNotEqualTo(holder);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter);
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waiter).isNotEqualTo(holder);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("业务已完成而未观察到预期项目锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定项目许可锁等待");
    }

    /** 独立连接确认真实软删提交，角色及绑定没有被删除或停用来掩盖新门禁。 */
    private void assertDeletedAndBindingsPreserved() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_role WHERE project_id = ? AND app_user_id = ? AND status = 'ACTIVE'", fixture.projectId(), fixture.userId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND relation_role = 'PRIMARY' AND status = 'ACTIVE'", fixture.projectId(), fixture.userId(), fixture.deviceId())).isEqualTo(1);
        }
    }

    /** 完整成功由command/attempt/Outbox关联及真实派发信封共同确认，不只依赖服务返回200式投影。 */
    private void assertAccepted(AppCommandResult result) throws SQLException {
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.commandKey()).isEqualTo("restart");
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("ts_device_command", "ts_device_command_attempt", "sys_outbox_event")) {
                assertThat(count(owner, "SELECT count(*) FROM " + table + " WHERE project_id = ?", fixture.projectId())).isEqualTo(1);
            }
            assertThat(count(owner, """
                    SELECT count(*) FROM ts_device_command
                     WHERE id = ? AND tenant_id = ? AND project_id = ? AND target_device_id = ?
                       AND app_user_id = ? AND requested_by IS NULL AND status = 'ACCEPTED'
                       AND idempotency_key = ? AND attempt_count = 1
                    """, result.commandId(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(), fixture.userId(), IDEMPOTENCY_KEY)).isEqualTo(1);
            try (PreparedStatement query = owner.prepareStatement("""
                    SELECT o.payload FROM sys_outbox_event o
                    JOIN ts_device_command_attempt a ON a.outbox_event_id = o.id
                     WHERE o.project_id = ? AND o.aggregate_id = ? AND o.event_type = ?
                       AND o.published_at IS NULL AND a.command_id = ? AND a.status = 'PENDING' AND a.attempt_no = 1
                    """)) {
                query.setObject(1, fixture.projectId()); query.setObject(2, result.commandId());
                query.setString(3, DISPATCH_EVENT); query.setObject(4, result.commandId());
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    DeviceCommandDispatch dispatch = mapper.readValue(rows.getString(1), DeviceCommandDispatch.class);
                    assertThat(dispatch.commandId()).isEqualTo(result.commandId());
                    assertThat(dispatch.tenantId()).isEqualTo(fixture.tenantId());
                    assertThat(dispatch.projectId()).isEqualTo(fixture.projectId());
                    assertThat(dispatch.targetDeviceId()).isEqualTo(fixture.deviceId());
                    assertThat(dispatch.connectionDeviceId()).isEqualTo(fixture.deviceId());
                    assertThat(dispatch.commandKey()).isEqualTo("restart");
                    assertThat(mapper.readTree(dispatch.inputJson())).isEqualTo(mapper.readTree("{\"param\":1}"));
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    /** 拒绝路径没有任何命令、派发尝试或发送意图。 */
    private void assertNoCommands() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("ts_device_command", "ts_device_command_attempt", "sys_outbox_event")) {
                assertThat(count(owner, "SELECT count(*) FROM " + table + " WHERE project_id = ?", fixture.projectId())).isZero();
            }
        }
    }

    /** 完整行包含attempt、租约、时间与关联事实，拒绝及回滚不能偷偷修改旧命令或授权行。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("ts_device_command", "ts_device_command_attempt", "sys_outbox_event", "app_user_role", "app_user_device")) {
                // 表名来自固定白名单，每次查询只观察本例随机项目。
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(t)::text FROM " + table + " t WHERE project_id = ? ORDER BY id")) {
                    query.setObject(1, fixture.projectId());
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(table + rows.getString(1)); }
                }
            }
        }
        return List.copyOf(result);
    }

    /** 只接受PG的query_canceled，保证反例确实消耗原SQL预算而非其他基础设施故障。 */
    private boolean isSqlTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 各测试先释放数据库锁与屏障，再收束线程，防止失败路径遗留异步写入。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 最小种子含合法OWNER、App角色/PRIMARY绑定及S11命令定义，不引入影子或属性时序点。 */
    private void seedFixture() throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '项目删除App诊断租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '项目删除owner')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) VALUES (?, ?, 'App删除项目', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, """
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, 'project_delete_app', ?, 'ACTIVE')
                    """, fixture.userId(), fixture.tenantId(), passwordEncoder.encode(PASSWORD));
            execute(owner, """
                    INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status)
                    VALUES (?, ?, ?, ?, 'APP_ADMIN', 'ACTIVE')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.userId());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status)
                    VALUES (?, ?, ?, 'app_delete_type', 'App删除设备类型', 'STANDARD', 'DIRECT', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'app_delete_device', 'App删除设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO app_user_device (id, tenant_id, project_id, app_user_id, device_id, relation_role, status)
                    VALUES (?, ?, ?, ?, ?, 'PRIMARY', 'ACTIVE')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.userId(), fixture.deviceId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, timeout_seconds)
                    VALUES (?, ?, ?, ?, 'restart', '重启', 30)
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
    }

    /** 新增同租户、同项目且以MEMBER控制同设备的第二App主体，排除授权失败对幂等候选反例的干扰。 */
    private UUID seedAdditionalAppUser() throws SQLException {
        UUID userId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, """
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, userId, fixture.tenantId(), "candidate_" + userId.toString().replace("-", ""),
                    passwordEncoder.encode(PASSWORD));
            execute(owner, """
                    INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status)
                    VALUES (?, ?, ?, ?, 'APP_ADMIN', 'ACTIVE')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), userId);
            execute(owner, """
                    INSERT INTO app_user_device (id, tenant_id, project_id, app_user_id, device_id, relation_role, status)
                    VALUES (?, ?, ?, ?, ?, 'MEMBER', 'ACTIVE')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), userId, fixture.deviceId());
            owner.commit();
        }
        return userId;
    }

    /** 按外键顺序只清理本例命令及独占授权夹具，不修改其他项目。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM ts_device_command_attempt WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM dev_command_definition WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user_device WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user_role WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user WHERE tenant_id = ?", fixture.tenantId());
            execute(owner, "DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", fixture.tenantId(), fixture.accountId());
            execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
            execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.commit();
        }
    }

    /** owner只用于种子、独立观察和清理，命令和删除始终通过真实APP角色与业务服务。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 参数化SQL固定本例身份，不拼接设备或用户输入。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 查询必须有结果，不能把数据库错误或不可见行伪装成零。 */
    private long count(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** @param tenantId 项目所属租户 @param projectId 独占项目 @param accountId 真实OWNER @param userId 真实App用户 @param typeId 类型 @param deviceId ONLINE设备 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID userId, UUID typeId, UUID deviceId) {
        /** 项目键用于真实命令冻结路由，不能跨例复用。 */
        private String projectKey() { return "app_command_" + projectId.toString().replace("-", ""); }
    }
}
