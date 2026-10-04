package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.DeviceClaimResult;
import com.things.link.enduser.application.DeviceClaimService;
import com.things.link.enduser.application.DeviceUnbindService;
import com.things.link.enduser.infrastructure.persistence.JdbcAppDeviceBindTokenRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserDeviceRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR0064决策4：CLAIM与自解绑在原事务取得项目许可，生命周期拒绝先于尝试计数和幂等回读。
 * CLAIM的BusinessException不回滚合同由真实服务和数据库验证；没有HTTP请求、Redis计数或模拟许可。
 */
class AppClaimUnbindProjectLifecycleTests extends AbstractIntegrationTest {

    /** 合法用户只保存编码口令，身份种子不冒充登录API验收。 */
    private static final String PASSWORD = "secret123";
    /** 真实OWNER签发及App消费，包含noRollbackFor生产事务。 */
    @Autowired private DeviceClaimService claims;
    /** 真实关闭关系及同事务审计。 */
    @Autowired private DeviceUnbindService unbind;
    /** 冻结必须经过真实OWNER业务删除。 */
    @Autowired private ProjectService projectService;
    /** 观察APP连接、真实5秒JDBC预算及事务内事实。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 仅用于控制真实删除提交时刻，不为被测App操作包外层事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 用户种子与S11使用同一口令编码器。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 许可执行前只公布真实连接PID，不替换原SQL和结果。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实consume CAS之后暂停，验证令牌/关系及项目锁持续至原事务提交。 */
    @MockitoSpyBean private JdbcAppDeviceBindTokenRepository tokens;
    /** 真实CLOSED条件更新之后暂停，不伪造关系或审计。 */
    @MockitoSpyBean private JdbcAppUserDeviceRepository bindings;
    /** 随机夹具身份只由本例创建、查询和清理。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 真实签发器仅返回一次的明文，持久层只存哈希。 */
    private String plaintext;

    /** 无绑定设备经真实OWNER签发一枚CLAIM，令牌attempt初始为零。 */
    @BeforeEach
    void prepare() throws SQLException {
        seedFixture();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            plaintext = claims.issue(fixture.projectId(), fixture.deviceId()).token();
        } finally {
            TenantContext.clear();
        }
        assertThat(plaintext).hasSize(43);
        assertUnconsumed();
    }

    /** 正常消费产生PRIMARY与唯一消费事实；同用户重放不能再计数或改变完整事实。 */
    @Test
    void activeClaimAndIdempotentReplayPreserveOneConsumption() throws Exception {
        DeviceClaimResult first = consume(fixture.tenantId());
        assertClaimed();
        List<String> before = facts();
        DeviceClaimResult replay = consume(fixture.tenantId());
        assertThat(replay.bindingId()).isEqualTo(first.bindingId());
        assertThat(replay.deviceId()).isEqualTo(first.deviceId());
        assertThat(replay.relationRole()).isEqualTo(first.relationRole());
        assertThat(facts()).isEqualTo(before);
    }

    /** 自解绑只关闭一次并写一次App行为人审计；原PRIMARY及首次创建时间仍保留。 */
    @Test
    void activeSelfUnbindClosesRelationAndAuditsOnlyOnce() throws Exception {
        DeviceClaimResult binding = consume(fixture.tenantId());
        invoke(Operation.UNBIND, fixture.tenantId());
        assertUnbound();
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE id = ? AND created_at = ? AND relation_role = 'PRIMARY'",
                    binding.bindingId(), java.sql.Timestamp.from(binding.createdAt()))).isEqualTo(1);
        }
        List<String> before = facts();
        invoke(Operation.UNBIND, fixture.tenantId());
        assertThat(facts()).isEqualTo(before);
    }

    /** 归档/真实删除/错tenant拒绝原业务异常；CLAIM不能被额外事务拦截器变成UnexpectedRollbackException。 */
    @ParameterizedTest(name = "{0}在{1}下拒绝且原事实不变")
    @CsvSource({"CLAIM,ARCHIVED", "CLAIM,DELETED", "CLAIM,WRONG_TENANT",
            "UNBIND,ARCHIVED", "UNBIND,DELETED", "UNBIND,WRONG_TENANT"})
    void lifecycleDenialPrecedesTokenAndBindingEffects(Operation operation, Denial denial) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        applyDenial(denial);
        UUID tenantId = denial == Denial.WRONG_TENANT ? Uuid7.generate() : fixture.tenantId();
        assertFailure(catchThrowable(() -> invoke(operation, tenantId)), denial == Denial.ARCHIVED ? 60022 : 60009);
        assertThat(facts()).isEqualTo(before);
        if (operation == Operation.CLAIM) assertUnconsumed(); else assertClaimed();
    }

    /** 已消费令牌在真实删除后也不能幂等返回旧PRIMARY；角色和关系保留不等于继续有访问资格。 */
    @Test
    void deletedProjectRejectsAlreadyConsumedClaimReplay() throws Exception {
        consume(fixture.tenantId());
        List<String> before = facts();
        deleteAsOwner();
        assertDeleted();
        assertFailure(catchThrowable(() -> consume(fixture.tenantId())), 60009);
        assertThat(facts()).isEqualTo(before);
        assertClaimed();
    }

    /** 无关系时ACTIVE允许解绑no-op，但归档/删除必须先拒绝，不能借空结果跳过写许可。 */
    @ParameterizedTest
    @EnumSource(value = Denial.class, names = {"ARCHIVED", "DELETED"})
    void missingBindingDoesNotBypassLifecycleAdmission(Denial denial) throws Exception {
        invoke(Operation.UNBIND, fixture.tenantId());
        assertUnconsumed();
        List<String> before = facts();
        applyDenial(denial);
        assertFailure(catchThrowable(() -> invoke(Operation.UNBIND, fixture.tenantId())), denial == Denial.ARCHIVED ? 60022 : 60009);
        assertThat(facts()).isEqualTo(before);
    }

    /** ACTIVE过期令牌仍保留既有noRollbackFor计数合同，不能因新门禁把60012变成整事务回滚。 */
    @Test
    void expiredClaimStillCommitsAttemptWithoutConsumptionInActiveProject() throws Exception {
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    UPDATE app_device_bind_token SET created_at = clock_timestamp() - interval '20 minutes',
                           expires_at = clock_timestamp() - interval '10 minutes' WHERE project_id = ?
                    """, fixture.projectId());
        }
        assertFailure(catchThrowable(() -> consume(fixture.tenantId())), 60012);
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND attempt_count = 1 AND consumed_at IS NULL AND consumed_by_app_user_id IS NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ?", fixture.projectId())).isZero();
            assertThat(count(owner, "SELECT count(*) FROM sys_audit_log WHERE project_id = ? AND action = 'enduser.device.unbound.self'", fixture.projectId())).isZero();
        }
    }

    /** 已真实消费或关闭关系而未提交，OWNER删除被同一业务事务项目SHARE许可阻挡。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void appWriteFirstBlocksOwnerDeletionUntilBusinessCommit(Operation operation) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseAfterRealWrite(operation, writerPid, written, release);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writing = executor.submit(() -> invoke(operation, fixture.tenantId()));
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(facts()).isEqualTo(before);
            CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
            Future<?> deletion = executor.submit(() -> deleteTransaction(deletionPid, null, null));
            assertBlockedBy(deletionPid.get(3, TimeUnit.SECONDS), writerPid.get(3, TimeUnit.SECONDS), deletion);
            assertThat(facts()).isEqualTo(before);
            release.countDown();
            writing.get(8, TimeUnit.SECONDS);
            deletion.get(8, TimeUnit.SECONDS);
            assertDeleted();
            assertSuccess(operation);
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** 删除先更新未提交时App许可真实等待；提交后拒绝原业务异常，token/关系/审计不能改变。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void ownerDeletionFirstMakesWaitingOperationRejectAfterCommit(Operation operation) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = capturePermitPid();
        CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> deleteTransaction(deletionPid, deleted, release));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> writing = executor.submit(() -> catchThrowable(() -> invoke(operation, fixture.tenantId())));
            assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), deletionPid.get(3, TimeUnit.SECONDS), writing);
            release.countDown();
            deletion.get(8, TimeUnit.SECONDS);
            assertFailure(writing.get(8, TimeUnit.SECONDS), 60009);
            assertDeleted();
            assertThat(facts()).isEqualTo(before);
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** 两入口均沿原5秒JDBC预算取消真实项目锁SQL，不能伪装业务失效；解锁后原令牌/关系可恢复。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void projectPermitTimeoutLeavesFactsUntouchedAndRecovers(Operation operation) throws Exception {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
        prepareOperation(operation);
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<Throwable> writing = executor.submit(() -> catchThrowable(() -> invoke(operation, fixture.tenantId())));
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), holderPid, writing);
                Throwable failure = writing.get(10, TimeUnit.SECONDS);
                assertThat(isSqlTimeout(failure)).as("原SQL预算应取消真实项目锁：%s", failure).isTrue();
                assertThat(failure).isNotInstanceOf(BusinessException.class);
                assertThat(facts()).isEqualTo(before);
                holder.rollback();
                invoke(operation, fixture.tenantId());
                assertSuccess(operation);
            } finally {
                holder.rollback();
                shutdown(executor);
            }
        }
    }

    /** 解绑种子必须来自同一合法CLAIM消费，不手写ACTIVE关系来省略唯一主控约束。 */
    private void prepareOperation(Operation operation) {
        if (operation == Operation.UNBIND) consume(fixture.tenantId());
    }

    /** 项目拒绝的SQL状态种子只用于ARCHIVED，删除始终走真实OWNER服务。 */
    private void applyDenial(Denial denial) throws SQLException {
        if (denial == Denial.DELETED) {
            deleteAsOwner();
            assertDeleted();
        } else if (denial == Denial.ARCHIVED) {
            try (Connection owner = ownerConnection()) {
                execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
            }
        }
    }

    /** 只建立与JWT一致的可信RLS范围，实际事务由消费代理创建；错tenant测试刻意只破坏显式参数。 */
    private DeviceClaimResult consume(UUID tenantId) {
        return scoped(() -> claims.consume(tenantId, fixture.projectId(), fixture.userId(), plaintext));
    }

    /** 参数化统一入口不为业务包事务，也不让App用户进入控制台TenantContext。 */
    private void invoke(Operation operation, UUID tenantId) {
        if (operation == Operation.CLAIM) {
            consume(tenantId);
        } else {
            scoped(() -> { unbind.unbindSelf(tenantId, fixture.projectId(), fixture.userId(), fixture.deviceId()); return null; });
        }
    }

    /** 如真实App安全链一样显式设置数据范围；结束时验证生产事务已经收束，再清ThreadLocal。 */
    private <T> T scoped(Supplier<T> action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            return action.get();
        } finally {
            RlsScopeContext.clear();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TenantContext.current()).isEmpty();
        }
    }

    /** 两个spy都先执行真实CAS/关闭；屏障不代替数据库写入，解绑审计会在屏障释放后仍于原事务追加。 */
    private void pauseAfterRealWrite(Operation operation, CompletableFuture<Integer> pid,
                                     CountDownLatch written, CountDownLatch release) {
        if (operation == Operation.CLAIM) {
            JdbcAppDeviceBindTokenRepository target = AopTestUtils.getUltimateTargetObject(tokens);
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                assertThat(result).isEqualTo(1);
                awaitCommitBarrier(operation, pid, written, release);
                return result;
            }).when(target).consume(eq(fixture.projectId()), any(UUID.class), eq(fixture.userId()), any(Instant.class));
        } else {
            JdbcAppUserDeviceRepository target = AopTestUtils.getUltimateTargetObject(bindings);
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                awaitCommitBarrier(operation, pid, written, release);
                return result;
            }).when(target).closeActive(fixture.projectId(), fixture.userId(), fixture.deviceId());
        }
    }

    /** 同一APP事务能看到刚写的状态，独立owner旁观者则仍看到提交前快照。 */
    private void awaitCommitBarrier(Operation operation, CompletableFuture<Integer> pid,
                                    CountDownLatch written, CountDownLatch release) throws InterruptedException {
        pid.complete(appPid());
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user_device WHERE project_id = ? AND status = ?", Integer.class,
                fixture.projectId(), operation == Operation.CLAIM ? "ACTIVE" : "CLOSED")).isEqualTo(1);
        if (operation == Operation.CLAIM) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND consumed_at IS NOT NULL", Integer.class, fixture.projectId())).isEqualTo(1);
        }
        written.countDown();
        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** 真实许可前只公布连接，原SQL会自行加锁或在原预算取消。 */
    private CompletableFuture<Integer> capturePermitPid() {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> { pid.complete(appPid()); return invocation.callRealMethod(); })
                .when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
        return pid;
    }

    /** 观察必须处于真实非只读APP事务，不能拿owner连接冒充原调用方。 */
    private int appPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 普通OWNER删除由生产代理自行提交。 */
    private void deleteAsOwner() {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { projectService.delete(fixture.projectId()); } finally { TenantContext.clear(); }
    }

    /** 并发用例只为删除建立真实外层事务以控制提交；App操作没有测试事务包裹。 */
    private void deleteTransaction(CompletableFuture<Integer> pid, CountDownLatch deleted, CountDownLatch release) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                pid.complete(appPid());
                projectService.delete(fixture.projectId());
                if (deleted != null) {
                    deleted.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }
            });
        } finally { TenantContext.clear(); }
    }

    /** 精确原BusinessException保证noRollbackFor语义没有退化成UnexpectedRollback或其他系统错误。 */
    private void assertFailure(Throwable failure, int code) {
        assertThat(failure).isExactlyInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(code);
    }

    /** 三个独立PID、阻塞者和未授予锁证明真实排序，不只观察Future尚未完成。 */
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
                if (operation.isDone()) throw new AssertionError("操作已完成却未观察到预期项目锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定项目许可锁等待");
    }

    /** 删除只冻结项目；App角色仍ACTIVE，拒绝不能依赖停用角色来蒙混过关。 */
    private void assertDeleted() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_role WHERE project_id = ? AND status = 'ACTIVE'", fixture.projectId())).isEqualTo(1);
        }
    }

    /** 未接纳CLAIM必须保留零attempt及未消费令牌，不出现关系或解绑审计。 */
    private void assertUnconsumed() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND attempt_count = 0 AND consumed_at IS NULL AND consumed_by_app_user_id IS NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ?", fixture.projectId())).isZero();
            assertThat(count(owner, "SELECT count(*) FROM sys_audit_log WHERE project_id = ? AND action = 'enduser.device.unbound.self'", fixture.projectId())).isZero();
        }
    }

    /** 消费成功以真实一组token+PRIMARY事实确认，业务投影本身不能代替提交证据。 */
    private void assertClaimed() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND attempt_count = 1 AND consumed_at IS NOT NULL AND consumed_by_app_user_id = ?", fixture.projectId(), fixture.userId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND status = 'ACTIVE' AND relation_role = 'PRIMARY'", fixture.projectId(), fixture.userId(), fixture.deviceId())).isEqualTo(1);
        }
    }

    /** 关系CLOSED及审计同时存在，行为人保持App身份，不能冒用控制台OWNER。 */
    private void assertUnbound() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND status = 'CLOSED' AND relation_role = 'PRIMARY'", fixture.projectId(), fixture.userId(), fixture.deviceId())).isEqualTo(1);
            assertThat(count(owner, """
                    SELECT count(*) FROM sys_audit_log a JOIN app_user_device b ON b.id = a.target_id
                     WHERE a.project_id = ? AND a.target_type = 'app_user_device' AND a.action = 'enduser.device.unbound.self'
                       AND a.actor_account_id IS NULL AND a.details->>'actorType' = 'APP_USER'
                       AND a.details->>'actorId' = ? AND a.details->>'deviceId' = ?
                    """, fixture.projectId(), fixture.userId().toString(), fixture.deviceId().toString())).isEqualTo(1);
        }
    }

    /** 参数化成功必须检查各自完整业务事实。 */
    private void assertSuccess(Operation operation) throws SQLException {
        if (operation == Operation.CLAIM) assertClaimed(); else assertUnbound();
    }

    /** 完整token含attempt/consumed/时间/哈希，绑定及角色全行；比较全部关系审计，不混入项目管理事件。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("app_device_bind_token", "app_user_device", "app_user_role", "sys_audit_log")) {
                String audit = table.equals("sys_audit_log") ? " AND target_type = 'app_user_device'" : "";
                // 表名为固定白名单，只查询本例随机项目；审计不可变事实绝不修改。
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(t)::text FROM " + table + " t WHERE project_id = ?" + audit + " ORDER BY id")) {
                    query.setObject(1, fixture.projectId());
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(table + rows.getString(1)); }
                }
            }
        }
        return List.copyOf(result);
    }

    /** 只接受PG query_canceled，不把连接错误或业务拒绝当锁超时。 */
    private boolean isSqlTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 各测试先释放持锁事务/屏障，再收束线程，失败不能泄漏后台写入。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 共用生命周期门禁但各自事务异常合同不同的两个真实入口。 */
    private enum Operation {
        /** 带noRollbackFor的令牌消费。 */ CLAIM,
        /** 关闭关系并写审计。 */ UNBIND
    }

    /** 项目拒绝原因，对外只允许只读60022或身份失效60009。 */
    private enum Denial {
        /** 归档保留只读。 */ ARCHIVED,
        /** 真实OWNER已删除。 */ DELETED,
        /** 只传错tenant，仍保留原正确RLS范围。 */ WRONG_TENANT
    }

    /** 最小种子含合法OWNER、App角色及未绑定DIRECT设备，令牌和PRIMARY关系由真实服务形成。 */
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
            owner.commit();
        }
    }

    /** 按外键顺序只清理本例令牌及授权夹具；sys_audit_log不可变审计保留至测试容器销毁。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM app_device_bind_token WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user_device WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user_role WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user WHERE id = ?", fixture.userId());
            execute(owner, "DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", fixture.tenantId(), fixture.accountId());
            execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
            execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.commit();
        }
    }

    /** owner只用于种子、独立观察和清理，消费、解绑和删除始终通过真实APP角色与业务服务。 */
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

    /** @param tenantId 租户 @param projectId 项目 @param accountId 真实OWNER @param userId App用户 @param typeId 类型 @param deviceId 未绑定DIRECT设备 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID userId, UUID typeId, UUID deviceId) {
        /** 真实OWNER签发使用独占项目路由键。 */
        private String projectKey() { return "app_claim_" + projectId.toString().replace("-", ""); }
    }
}
