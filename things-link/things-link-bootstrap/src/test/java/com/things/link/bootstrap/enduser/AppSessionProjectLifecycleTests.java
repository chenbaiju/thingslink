package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppAuthenticationService;
import com.things.link.enduser.application.AppIssuedSession;
import com.things.link.enduser.application.AppSessionService;
import com.things.link.enduser.domain.AppRefreshToken;
import com.things.link.enduser.infrastructure.persistence.JdbcAppRefreshTokenRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR0064决策4：会话许可必须留在登录/刷新真实事务中，禁止冻结项目新增会话或轮换标记。
 * 本类不覆盖HTTP门禁；真实密码认证、持久令牌、OWNER删除、PG阻塞图与独立连接快照构成证据。
 */
class AppSessionProjectLifecycleTests extends AbstractIntegrationTest {

    /** 仅本类夹具口令，使用生产编码器散列入库。 */
    private static final String PASSWORD = "lifecycle-session-password";
    /** 登录通过真实密码与角色认证后再进入会话签发代理。 */
    @Autowired private AppAuthenticationService authentication;
    /** 刷新、撤销及已认证principal签发使用真实事务代理。 */
    @Autowired private AppSessionService sessions;
    /** 控制台OWNER删除的真实授权与软删除入口。 */
    @Autowired private ProjectService projects;
    /** 检查真实APP角色、事务PID及SQL范围。 */
    @Autowired private JdbcTemplate jdbc;
    /** 删除侧外层事务仅用于控制提交顺序，不覆盖被测会话事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 保留生产口令算法，避免无效散列导致伪反例。 */
    @Autowired private PasswordEncoder passwords;
    /** 许可执行前只公布PID，不替换真实资格或锁结果。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** save真实执行后设置屏障，验证未提交会话与项目锁具有同一生命周期。 */
    @MockitoSpyBean private JdbcAppRefreshTokenRepository refreshRepository;
    /** 只清本类创建的项目/用户，不清空共享表或其他测试Redis限流键。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 已删除项目的真实登录和刷新均拒绝，刷新原记录所有字段保持不变。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void deletedProjectCannotIssueOrRotateSession(Operation operation) throws Exception {
        Fixture fixture = fixture();
        AppIssuedSession original = login(fixture);
        List<String> before = tokenRows(fixture);
        delete(fixture);
        assertFailure(catchThrowable(() -> invoke(operation, fixture, original)), operation);
        assertThat(tokenRows(fixture)).isEqualTo(before);
    }

    /** ARCHIVED只读不能签发新会话；拒绝不能悄悄标记旧refresh已轮换。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void archivedProjectCannotIssueOrRotateSession(Operation operation) throws Exception {
        Fixture fixture = fixture();
        AppIssuedSession original = login(fixture);
        try (Connection owner = ownerConnection()) {
            update(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
        }
        List<String> before = tokenRows(fixture);
        assertFailure(catchThrowable(() -> invoke(operation, fixture, original)), operation);
        assertThat(tokenRows(fixture)).isEqualTo(before);
    }

    /** 错tenant principal在save前拒绝；不关闭refresh复合外键伪造数据库可达的错配记录。 */
    @Test
    void mismatchedAuthenticatedPrincipalCannotPersistAnySession() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        Throwable failure = catchThrowable(() -> sessions.issueForLogin(
                new AppAuthenticatedPrincipal(other.tenantId(), fixture.projectId(), fixture.userId())));
        assertFailure(failure, Operation.LOGIN);
        assertThat(tokenRows(fixture)).isEmpty();
        assertThat(tokenRows(other)).isEmpty();
    }

    /** refresh的权威二元组来自持久令牌，不能被连接借出时的另一ACTIVE项目范围替换。 */
    @Test
    void refreshChecksPersistedProjectEvenWithAnotherActiveSqlScope() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        AppIssuedSession original = login(fixture);
        delete(fixture);
        List<String> before = tokenRows(fixture);
        RlsScopeContext.set(new RlsScope(other.tenantId(), other.projectId()));
        try {
            assertFailure(catchThrowable(() -> sessions.rotate(original.refreshToken())), Operation.REFRESH);
            assertThat(RlsScopeContext.current()).contains(new RlsScope(other.tenantId(), other.projectId()));
        } finally {
            RlsScopeContext.clear();
        }
        assertThat(tokenRows(fixture)).isEqualTo(before);
        assertThat(tokenRows(other)).isEmpty();
    }

    /** 会话save后仍未提交：OWNER删除必须被其项目SHARE锁阻挡，不能先冻结再提交新会话。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void sessionFirstBlocksOwnerDeletionUntilItsCommit(Operation operation) throws Exception {
        Fixture fixture = fixture();
        AppIssuedSession original = operation == Operation.REFRESH ? login(fixture) : null;
        List<String> before = tokenRows(fixture);
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> sessionPid = new CompletableFuture<>();
        JdbcAppRefreshTokenRepository target = AopTestUtils.getUltimateTargetObject(refreshRepository);
        doAnswer(invocation -> {
            AppRefreshToken token = invocation.getArgument(0);
            invocation.callRealMethod();
            if (token.projectId().equals(fixture.projectId())) {
                sessionPid.complete(appPid());
                saved.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(target).save(any(AppRefreshToken.class), any(byte[].class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AppIssuedSession> issuing = executor.submit(() -> invoke(operation, fixture, original));
            assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tokenRows(fixture)).isEqualTo(before);
            CompletableFuture<Integer> deletingPid = new CompletableFuture<>();
            Future<?> deleting = executor.submit(() -> deleteTransaction(fixture, deletingPid, null, null));
            assertBlockedBy(deletingPid.get(3, TimeUnit.SECONDS), sessionPid.get(3, TimeUnit.SECONDS), deleting);
            assertThat(tokenRows(fixture)).isEqualTo(before);
            release.countDown();
            assertThat(issuing.get(8, TimeUnit.SECONDS).refreshToken()).isNotBlank();
            deleting.get(8, TimeUnit.SECONDS);
            assertDeleted(fixture);
            assertSuccessfulTokens(fixture, operation);
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** 删除先写但未提交：登录在验密前等待项目许可，删除提交后拒绝；刷新同样不得越过许可。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void deletionFirstMakesWaitingSessionRejectWithoutRotation(Operation operation) throws Exception {
        Fixture fixture = fixture();
        AppIssuedSession original = operation == Operation.REFRESH ? login(fixture) : null;
        List<String> before = tokenRows(fixture);
        CompletableFuture<Integer> sessionPid = capturePermitPid(fixture);
        CompletableFuture<Integer> deletingPid = new CompletableFuture<>();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deleting = executor.submit(() -> deleteTransaction(fixture, deletingPid, deleted, release));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> issuing = executor.submit(() -> catchThrowable(() -> invoke(operation, fixture, original)));
            assertBlockedBy(sessionPid.get(3, TimeUnit.SECONDS), deletingPid.get(3, TimeUnit.SECONDS), issuing);
            assertThat(tokenRows(fixture)).isEqualTo(before);
            release.countDown();
            deleting.get(8, TimeUnit.SECONDS);
            assertFailure(issuing.get(8, TimeUnit.SECONDS), operation);
            assertDeleted(fixture);
            assertThat(tokenRows(fixture)).isEqualTo(before);
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** 未设测试外层timeout：原5秒JDBC预算取消项目许可等待，原记录完整不变，解锁后同入口可以成功。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void permitTimeoutRollsBackAndRecovers(Operation operation) throws Exception {
        assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
        Fixture fixture = fixture();
        AppIssuedSession original = operation == Operation.REFRESH ? login(fixture) : null;
        List<String> before = tokenRows(fixture);
        CompletableFuture<Integer> sessionPid = capturePermitPid(fixture);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                update(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) number(holder, "SELECT pg_backend_pid()");
                Future<Throwable> issuing = executor.submit(() -> catchThrowable(() -> invoke(operation, fixture, original)));
                assertBlockedBy(sessionPid.get(3, TimeUnit.SECONDS), holderPid, issuing);
                Throwable failure = issuing.get(10, TimeUnit.SECONDS);
                assertThat(isSqlTimeout(failure)).as("真实项目锁等待必须由SQL预算取消：%s", failure).isTrue();
                assertThat(tokenRows(fixture)).isEqualTo(before);
                holder.rollback();
                assertThat(invoke(operation, fixture, original).refreshToken()).isNotBlank();
                assertSuccessfulTokens(fixture, operation);
            } finally {
                holder.rollback();
                shutdown(executor);
            }
        }
    }

    /** 模拟恢复后旧refresh在复用撤销前失效，新登录取得当前代次；logout仍可幂等收束旧会话。 */
    @Test
    void restoredProjectRejectsOldRefreshBeforeReuseEffectsAndAllowsCurrentLogin() throws Exception {
        Fixture fixture = fixture();
        AppIssuedSession first = login(fixture);
        AppIssuedSession second = sessions.rotate(first.refreshToken());
        AppIssuedSession separate = login(fixture);
        delete(fixture);
        try (Connection owner = ownerConnection()) {
            update(owner, "UPDATE sys_project SET status='ACTIVE', deleted_at=NULL WHERE id=?", fixture.projectId());
        }
        List<String> before = tokenRows(fixture);

        assertFailure(catchThrowable(() -> sessions.rotate(first.refreshToken())), Operation.REFRESH);
        assertFailure(catchThrowable(() -> sessions.rotate(second.refreshToken())), Operation.REFRESH);
        assertThat(tokenRows(fixture)).isEqualTo(before);

        AppIssuedSession current = login(fixture);
        assertThat(current.refreshToken()).isNotBlank();
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, """
                    SELECT count(*) FROM app_refresh_token
                     WHERE project_id = ? AND project_generation = 1
                    """, fixture.projectId())).isEqualTo(1);
        }

        sessions.revoke(separate.refreshToken());
        List<String> revoked = tokenRows(fixture);
        sessions.revoke(separate.refreshToken());
        assertThat(tokenRows(fixture)).isEqualTo(revoked);
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, """
                    SELECT count(*) FROM app_refresh_token
                     WHERE project_id = ? AND revoked_at IS NOT NULL
                    """, fixture.projectId())).isEqualTo(1);
        }
    }

    /** 正常公开认证入口；不附加测试事务，不以伪造principal绕过密码及角色前置。 */
    private AppIssuedSession login(Fixture fixture) {
        return withoutTransaction(() -> authentication.login(fixture.projectKey(), "alice", PASSWORD, null));
    }

    /** 两个被测入口都由生产代理开事务，测试不以自己的事务预算掩盖实现。 */
    private AppIssuedSession invoke(Operation operation, Fixture fixture, AppIssuedSession original) {
        return operation == Operation.LOGIN ? login(fixture) : withoutTransaction(() -> sessions.rotate(original.refreshToken()));
    }

    /** 禁止隐含控制台/项目上下文参与公开会话入口。 */
    private <T> T withoutTransaction(Supplier<T> action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        T result = action.get();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return result;
    }

    /** 真实许可调用前公布APP连接，未更换参数或返回值。 */
    private CompletableFuture<Integer> capturePermitPid(Fixture fixture) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            pid.complete(appPid());
            return invocation.callRealMethod();
        }).when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
        doAnswer(invocation -> {
            pid.complete(appPid());
            return invocation.callRealMethod();
        }).when(target).lockActiveForWrite(
                org.mockito.ArgumentMatchers.eq(fixture.tenantId()),
                org.mockito.ArgumentMatchers.eq(fixture.projectId()), anyLong());
        return pid;
    }

    /** OWNER身份仅用于删除；SQL连接仍是APP角色，独立owner连接只用于种子及旁观。 */
    private void delete(Fixture fixture) {
        deleteTransaction(fixture, new CompletableFuture<>(), null, null);
    }

    /** 外层事务专用于控制真实删除提交时刻，没有给登录或刷新包事务。 */
    private void deleteTransaction(Fixture fixture, CompletableFuture<Integer> pid,
                                   CountDownLatch deleted, CountDownLatch release) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.ownerId()));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                pid.complete(appPid());
                projects.delete(fixture.projectId());
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

    /** 只接受具体认证业务码，不把外键失败、连接错误等当作正确拒绝。 */
    private void assertFailure(Throwable failure, Operation operation) {
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(operation == Operation.LOGIN ? 60006 : 60007);
    }

    /** 真正成功必须有新记录；刷新还应恰好有一个旧记录被关联至后继。 */
    private void assertSuccessfulTokens(Fixture fixture, Operation operation) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM app_refresh_token WHERE project_id = ?", fixture.projectId()))
                    .isEqualTo(operation == Operation.LOGIN ? 1 : 2);
            assertThat(number(owner, "SELECT count(*) FROM app_refresh_token WHERE project_id = ? AND replaced_by IS NOT NULL", fixture.projectId()))
                    .isEqualTo(operation == Operation.LOGIN ? 0 : 1);
        }
    }

    /** 不靠Future未完成推断阻塞；观察者验证不同PID和实际未授予的锁。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        assertThat(waiter).isNotEqualTo(holder);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            parameters(query, holder, waiter, waiter);
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waiter).isNotEqualTo(holder);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("未等待指定项目锁便已完成");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定项目锁的真实等待");
    }

    /** 真实APP连接PID，不能用owner角色证明应用锁语义。 */
    private int appPid() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 仅接受数据库取消SQLSTATE；调用方Future超时不算生产预算生效。 */
    private boolean isSqlTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 合法租户/唯一OWNER/终端用户及项目角色，口令由真实编码器生成。 */
    private Fixture fixture() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            update(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, ?, '会话owner')",
                    fixture.ownerId(), fixture.ownerId() + "@example.com", passwords.encode(PASSWORD));
            update(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '会话生命周期租户')", fixture.tenantId());
            update(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.ownerId());
            update(owner, "INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?, ?, '会话项目', ?)", fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            update(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.ownerId());
            update(owner, "INSERT INTO app_user(id,tenant_id,username,password_hash,status) VALUES (?, ?, 'alice', ?, 'ACTIVE')",
                    fixture.userId(), fixture.tenantId(), passwords.encode(PASSWORD));
            update(owner, "INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?, ?, ?, ?, 'APP_ADMIN')",
                    Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.userId());
            owner.commit();
        }
        return fixture;
    }

    /** 独立连接读取完整持久令牌行，可捕捉replaced_by/撤销/时间等部分修改。 */
    private List<String> tokenRows(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT row_to_json(t)::text FROM app_refresh_token t WHERE project_id = ? ORDER BY id")) {
            parameters(query, fixture.projectId());
            List<String> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
            return List.copyOf(result);
        }
    }

    /** 独立观察项目冻结已提交。 */
    private void assertDeleted(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
        }
    }

    /** owner连接只为种子、锁和旁观，不是被测业务连接。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 参数化修改仅作用于本例事实。 */
    private void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, values);
            statement.executeUpdate();
        }
    }

    /** 独立观察数值，不依赖APP事务未提交内容。 */
    private long number(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, values);
            try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        }
    }

    /** 身份全部参数化，不拼接用户数据。 */
    private void parameters(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    /** 先释放数据库锁和屏障，再有界收束所有工作线程。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(12, TimeUnit.SECONDS)).isTrue();
    }

    /** 本类无运行时调度事实；按FK顺序仅回收自己的会话、角色、用户、项目和OWNER。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            for (Fixture fixture : fixtures) {
                update(owner, "DELETE FROM app_refresh_token WHERE project_id = ?", fixture.projectId());
                update(owner, "DELETE FROM app_user_role WHERE project_id = ?", fixture.projectId());
                update(owner, "DELETE FROM app_user WHERE id = ?", fixture.userId());
                update(owner, "DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
                update(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                update(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenantId());
                update(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
                update(owner, "DELETE FROM sys_account WHERE id = ?", fixture.ownerId());
            }
            owner.commit();
        }
    }

    /** 两入口同样持项目锁，但保留各自60006/60007错误合同。 */
    private enum Operation {
        /** 完整密码认证登录。 */ LOGIN,
        /** 使用真实已持久refresh轮换。 */ REFRESH
    }

    /** @param tenantId 归属租户 @param projectId 项目 @param userId 终端用户 @param ownerId 控制台唯一OWNER */
    private record Fixture(UUID tenantId, UUID projectId, UUID userId, UUID ownerId) {
        /** 唯一项目键同时隔离真实登录限流，不清除其他测试计数。 */
        String projectKey() { return "session_" + projectId.toString().replace("-", ""); }
    }
}
