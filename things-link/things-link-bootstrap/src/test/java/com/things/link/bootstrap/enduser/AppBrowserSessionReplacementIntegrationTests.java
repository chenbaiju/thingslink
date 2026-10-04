package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.enduser.application.AppAuthenticationService;
import com.things.link.enduser.application.AppIssuedSession;
import com.things.link.enduser.application.AppPasswordService;
import com.things.link.enduser.infrastructure.persistence.JdbcAppBrowserSessionReplacementRepository;
import com.things.link.shared.token.OpaqueToken;
import com.things.link.enduser.application.AppProjectWriteGuard;
import com.things.link.enduser.application.AppSessionService;
import com.things.link.enduser.domain.AppRefreshToken;
import com.things.link.enduser.infrastructure.persistence.JdbcAppRefreshTokenRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** ADR0107真实跨租户排序锁与原子替换，不以独立撤销事务或owner身份代替生产证明。 */
@Import(AppBrowserSessionReplacementIntegrationTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"SESSION_POSTGRES"})
class AppBrowserSessionReplacementIntegrationTests extends AbstractIntegrationTest {
    /** 专库专角色避免并发测试迁移及其他夹具干扰。 */
    private static final PostgreSQLContainer<?> SESSION_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("app_browser_replacement").withUsername("thingslink").withPassword("thingslink");
    /** 业务池和Flyway精确绑定同一专属PG。 */
    private static final String DATABASE_URL = startDatabase();
    /** 仅测试凭据，入库使用生产编码器。 */
    private static final String PASSWORD = "browser-replacement-password";
    /** 登录始终调用真实代理及数据库角色。 */
    @Autowired private AppAuthenticationService authentication;
    /** 真实refresh轮换互斥与撤销。 */
    @Autowired private AppSessionService sessions;
    /** 生产普通应用连接。 */
    @Autowired private JdbcTemplate jdbc;
    /** 提供精确失败回滚与在途事务屏障。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 真实密码散列与锁后验证。 */
    @Autowired private PasswordEncoder passwords;
    /** 只加调度屏障并执行真实锁SQL，不替换锁事实。 */
    @MockitoSpyBean private JdbcAppBrowserSessionReplacementRepository replacement;
    /** 共享runner不能写其他数据库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedQuotaRunner;
    /** 停用无关通知领取。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotifications;
    /** 停用无关任务扫描。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTasks;
    /** 停用无关命令超时扫描。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommands;
    /** 停用无关历史回补。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfill;
    /** 唯一造数身份仅在本类容器内存活。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 不能让owner数据源意外替代普通应用RLS身份。 */
    @BeforeEach
    void ordinaryRoleAndExactDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("app_browser_replacement");
    }

    /** 跨租户A到B替换成功仅撤旧族；错误密码与编码阶段失败均不留下半套事实。 */
    @Test
    void crossTenantReplacementAndOuterFailureAreAtomic() throws Exception {
        Fixture a = fixture(); Fixture b = fixture();
        AppIssuedSession old = login(a);
        assertBusinessCode(catchThrowable(() -> authentication.loginReplacingBrowserSession(b.projectKey(), "alice",
                "wrong", null, OpaqueToken.hash(old.refreshToken()))), 60006);
        assertThat(sessions.rotate(old.refreshToken())).isNotNull();
        AppIssuedSession second = login(a);
        long before;
        try (Connection observer = owner()) { before = number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=?", b.projectId()); }
        assertThat(catchThrowable(() -> new TransactionTemplate(transactionManager).execute(status -> {
            authentication.loginReplacingBrowserSession(b.projectKey(), "alice", PASSWORD, null, OpaqueToken.hash(second.refreshToken()));
            throw new IllegalStateException("simulate-cookie-encoding-failure");
        }))).isInstanceOf(IllegalStateException.class);
        try (Connection observer = owner()) { assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=?", b.projectId())).isEqualTo(before); }
        AppIssuedSession stillValid = sessions.rotate(second.refreshToken());
        AppIssuedSession replacementSession = authentication.loginReplacingBrowserSession(b.projectKey(), "alice", PASSWORD,
                null, OpaqueToken.hash(stillValid.refreshToken()));
        assertBusinessCode(catchThrowable(() -> sessions.rotate(stillValid.refreshToken())), 60007);
        assertThat(sessions.rotate(replacementSession.refreshToken())).isNotNull();
    }

    /** 两个实际业务事务各持目标项目许可后同时桥锁，A到B/B到A都必须成功，不以死锁回滚为调度方案。 */
    @Test
    void oppositeReplacementsUseOnePostgresUuidLockOrder() throws Exception {
        Fixture a = fixture(); Fixture b = fixture();
        AppIssuedSession oldA = login(a); AppIssuedSession oldB = login(b);
        CountDownLatch ready = new CountDownLatch(2);
        Set<Integer> pids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        doAnswer(call -> {
            pids.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            ready.countDown(); await(ready);
            return call.callRealMethod();
        }).when(replacement).lockUsers(any(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AppIssuedSession> first = executor.submit(() -> authentication.loginReplacingBrowserSession(
                    b.projectKey(), "alice", PASSWORD, null, OpaqueToken.hash(oldA.refreshToken())));
            Future<AppIssuedSession> second = executor.submit(() -> authentication.loginReplacingBrowserSession(
                    a.projectKey(), "alice", PASSWORD, null, OpaqueToken.hash(oldB.refreshToken())));
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(pids).hasSize(2);
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(12, TimeUnit.SECONDS)).isTrue(); }
    }

    /** 旧用户正在轮换时替换必须真实等待，待后继提交后整族一起撤销。 */
    @Test
    void replacementWaitsForOldRefreshAndRevokesCommittedSuccessor() throws Exception {
        Fixture a = fixture(); Fixture b = fixture();
        AppIssuedSession old = login(a);
        CountDownLatch rotated = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entering = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger(); AtomicInteger waiterPid = new AtomicInteger();
        doAnswer(call -> { waiterPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); entering.countDown(); return call.callRealMethod(); })
                .when(replacement).lockUsers(any(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AppIssuedSession> rotation = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                AppIssuedSession result = sessions.rotate(old.refreshToken());
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); rotated.countDown();
                try { await(release); } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return result;
            }));
            await(rotated);
            Future<AppIssuedSession> login = executor.submit(() -> authentication.loginReplacingBrowserSession(
                    b.projectKey(), "alice", PASSWORD, null, OpaqueToken.hash(old.refreshToken())));
            await(entering); blocked(holderPid.get(), waiterPid.get()); release.countDown();
            AppIssuedSession successor = rotation.get(10, TimeUnit.SECONDS);
            assertThat(login.get(10, TimeUnit.SECONDS)).isNotNull();
            assertBusinessCode(catchThrowable(() -> sessions.rotate(successor.refreshToken())), 60007);
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(12, TimeUnit.SECONDS)).isTrue(); }
    }

    /** 目标锁等待期间改密提交，锁后必须重新读取散列，不能使用早先定位快照的旧密码签发。 */
    @Test
    void targetPasswordChangeIsRecheckedAfterLockWait() throws Exception {
        Fixture target = fixture(); Fixture old = fixture(); AppIssuedSession previous = login(old);
        CountDownLatch entering = new CountDownLatch(1); AtomicInteger waiter = new AtomicInteger();
        doAnswer(call -> { waiter.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); entering.countDown(); return call.callRealMethod(); })
                .when(replacement).lockUsers(any(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ordinary(target.tenantId())) {
            int holderPid = (int) number(holder, "SELECT pg_backend_pid()");
            update(holder, "UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode("changed-password"), target.userId());
            Future<Throwable> attempt = executor.submit(() -> catchThrowable(() -> authentication.loginReplacingBrowserSession(
                    target.projectKey(), "alice", PASSWORD, null, OpaqueToken.hash(previous.refreshToken()))));
            await(entering); blocked(holderPid, waiter.get()); holder.commit();
            assertBusinessCode(attempt.get(10, TimeUnit.SECONDS), 60006);
            assertThat(sessions.rotate(previous.refreshToken())).isNotNull();
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(12, TimeUnit.SECONDS)).isTrue(); }
    }

    /** 改密等待的反向反例：发送时的新口令尚不可见，必须等待提交后按新事实成功认证。 */
    @Test
    void replacementAcceptsNewPasswordCommittedWhileWaiting() throws Exception {
        Fixture old = fixture(); Fixture target = fixture();
        AppIssuedSession previous = login(old);
        CountDownLatch entering = new CountDownLatch(1); AtomicInteger waiter = new AtomicInteger();
        doAnswer(call -> { waiter.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); entering.countDown(); return call.callRealMethod(); })
                .when(replacement).lockUsers(any(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ordinary(target.tenantId())) {
            int holderPid = (int) number(holder, "SELECT pg_backend_pid()");
            update(holder, "UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode("changed-password"), target.userId());
            Future<AppIssuedSession> attempt = executor.submit(() -> authentication.loginReplacingBrowserSession(
                    target.projectKey(), "alice", "changed-password", null, OpaqueToken.hash(previous.refreshToken())));
            await(entering); blocked(holderPid, waiter.get()); holder.commit();
            assertThat(attempt.get(10, TimeUnit.SECONDS)).isNotNull();
            assertBusinessCode(catchThrowable(() -> sessions.rotate(previous.refreshToken())), 60007);
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(12, TimeUnit.SECONDS)).isTrue(); }
    }

    /** 受限函数不提供PUBLIC执行、越租户目标锁或越域读取；未知hash只允许目标锁。 */
    @Test
    void bridgeAclTargetScopeAndUnknownHashStayRestricted() throws Exception {
        Fixture a = fixture(); Fixture b = fixture();
        try (Connection observer = owner()) {
            assertThat(number(observer, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='app_lock_browser_session_replacement' AND p.prosecdef AND p.prorettype='void'::regtype AND p.proconfig @> ARRAY['search_path=pg_catalog, public','lock_timeout=2s']")).isEqualTo(1);
            assertThat(number(observer, "SELECT count(*) FROM pg_proc p, LATERAL aclexplode(p.proacl) a WHERE p.proname='app_lock_browser_session_replacement' AND a.grantee=0 AND a.privilege_type='EXECUTE'")).isZero();
            assertThat(number(observer, "SELECT has_function_privilege('thingslink_app','public.app_lock_browser_session_replacement(uuid,bytea)','EXECUTE')::int")).isEqualTo(1);
        }
        try (Connection publicCaller = owner()) {
            publicCaller.setAutoCommit(false);
            update(publicCaller, "CREATE ROLE browser_bridge_public_probe NOLOGIN");
            update(publicCaller, "SET LOCAL ROLE browser_bridge_public_probe");
            assertThat(sqlState(catchThrowable(() -> executeBridge(publicCaller, a.userId(), null)))).isEqualTo("42501");
            publicCaller.rollback();
        }
        try (Connection app = ordinary(a.tenantId())) {
            assertThat(number(app, "SELECT count(*) FROM app_user WHERE id=?", b.userId())).isZero();
            assertThat(sqlState(catchThrowable(() -> number(app, "SELECT public.app_lock_browser_session_replacement(?,?) IS NULL", b.userId(), new byte[32])))).isEqualTo("42501");
        }
        try (Connection app = ordinary(a.tenantId())) {
            executeBridge(app, a.userId(), new byte[32]);
            assertThat(number(app, "SELECT count(*) FROM app_user WHERE id=?", b.userId())).isZero();
            app.commit();
        }
        AppIssuedSession old = login(a);
        assertThat(authentication.loginReplacingBrowserSession(a.projectKey(), "alice", PASSWORD, null,
                OpaqueToken.hash(old.refreshToken()))).isNotNull();
        assertBusinessCode(catchThrowable(() -> sessions.rotate(old.refreshToken())), 60007);
    }

    /** 实际普通APP连接持锁超过有界等待时得到55P03，不能无限拖住浏览器认证。 */
    @Test
    void bridgeLockWaitHasFiniteSqlDeadline() throws Exception {
        Fixture f = fixture();
        try (Connection holder = ordinary(f.tenantId()); Connection waiter = ordinary(f.tenantId())) {
            update(holder, "UPDATE app_user SET display_name='held' WHERE id=?", f.userId());
            long started = System.nanoTime();
            Throwable failure = catchThrowable(() -> executeBridge(waiter, f.userId(), null));
            assertThat(sqlState(failure)).isEqualTo("55P03");
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(6);
        }
    }

    /** 观察PG真实阻塞关系，不用固定sleep推断互斥。 */
    private void blocked(int holder, int waiter) throws Exception {
        try (Connection observer = owner()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < deadline) {
                if (number(observer, "SELECT (?=ANY(pg_blocking_pids(?)))::int", holder, waiter) == 1) return;
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到指定PG用户锁阻塞关系");
    }

    /** 普通APP连接仅设置目标租户，不使用owner执行受限桥或读取断言。 */
    private Connection ordinary(UUID tenant) throws SQLException {
        Connection connection = DriverManager.getConnection(DATABASE_URL, APP_ROLE, APP_ROLE_PASSWORD);
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
            statement.setString(1, tenant.toString()); statement.execute();
        }
        return connection;
    }

    /** void桥通过execute消费，不把返回值当可泄露身份输出。 */
    private void executeBridge(Connection connection, UUID target, byte[] hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT public.app_lock_browser_session_replacement(?,?)")) {
            statement.setObject(1, target); statement.setBytes(2, hash); statement.execute();
        }
    }

    /** 保留真实SQLException的原始SQLSTATE。 */
    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    /** 普通登录始终保留为兼容性前置。 */
    private AppIssuedSession login(Fixture f) { return authentication.login(f.projectKey(), "alice", PASSWORD, null); }
    /** 仅明确业务错误计入预期，超时或SQL异常不得假装认证拒绝。 */
    private static void assertBusinessCode(Throwable failure, int code) {
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(code);
    }
    /** 每例创建唯一租户、项目、OWNER与真实App用户，隔离登录限流和持久事实。 */
    private Fixture fixture() throws SQLException {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixtures.add(f);
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            update(c, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, ?, '会话并发owner')", f.ownerId(), f.ownerId()+"@example.com", passwords.encode(PASSWORD));
            update(c, "INSERT INTO sys_tenant(id,name) VALUES (?, '会话并发租户')", f.tenantId());
            update(c, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), f.tenantId(), f.ownerId());
            update(c, "INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?, ?, '会话并发项目', ?)", f.projectId(), f.tenantId(), f.projectKey());
            update(c, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), f.projectId(), f.ownerId());
            update(c, "INSERT INTO app_user(id,tenant_id,username,password_hash,status) VALUES (?, ?, 'alice', ?, 'ACTIVE')", f.userId(), f.tenantId(), passwords.encode(PASSWORD));
            update(c, "INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?, ?, ?, ?, 'APP_ADMIN')", Uuid7.generate(), f.tenantId(), f.projectId(), f.userId());
            c.commit();
        }
        return f;
    }

    /** 观察者只使用owner连接造数与读持久终态，不参与被测代理事务。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, SESSION_POSTGRES.getUsername(), SESSION_POSTGRES.getPassword());
    }

    /** 参数化写入仅作用于本例ID，避免跨夹具清理。 */
    private void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, values);
            statement.executeUpdate();
        }
    }

    /** 独立连接读取已提交事实，不把测试线程内的未提交可见性当结果。 */
    private long number(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, values);
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        }
    }

    /** UUID等夹具字段全部参数绑定，不拼接用户值。 */
    private void parameters(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    /** 屏障只控制调度，不把其超时当作正确的生产拒绝。 */
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("测试屏障必须按预期到达并释放").isTrue();
    }

    /** 静态实例保持至Spring上下文结束，由Testcontainers资源回收器清理，不逐例停库破坏连接池。 */
    private static String startDatabase() {
        SESSION_POSTGRES.start();
        return SESSION_POSTGRES.getJdbcUrl();
    }

    /** APP和Flyway绑定同一专库，关闭与会话仲裁无关的外发和全局扫描。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 保留APP角色与真实事务，只替换专属数据库地址。 */
        @Bean DynamicPropertyRegistrar browserReplacementDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("spring.flyway.user", SESSION_POSTGRES::getUsername);
                registry.add("spring.flyway.password", SESSION_POSTGRES::getPassword);
                // APP角色/口令及Flyway占位符沿用基类，由本实例完整迁移创建，不借用其他实例的全局角色。
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }

    /** @param tenantId 租户 @param projectId 项目 @param userId App用户 @param ownerId 控制台OWNER */
    private record Fixture(UUID tenantId, UUID projectId, UUID userId, UUID ownerId) {
        /** 唯一项目键隔离密码登录的真实限流计数。 */
        String projectKey() { return "concurrent_" + projectId.toString().replace("-", ""); }
    }
}
