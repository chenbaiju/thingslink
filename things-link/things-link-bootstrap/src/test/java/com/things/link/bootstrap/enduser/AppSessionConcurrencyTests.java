package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.enduser.application.AppAuthenticationService;
import com.things.link.enduser.application.AppIssuedSession;
import com.things.link.enduser.application.AppPasswordService;
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

/** ADR0036/S12-0a1：真实密码登录与独立APP事务验证轮换唯一后继和注销后的整族终态。 */
@Import(AppSessionConcurrencyTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"SESSION_POSTGRES"})
class AppSessionConcurrencyTests extends AbstractIntegrationTest {
    /** 角色属于PG实例；独立容器防止其他测试迁移已创建的constraint角色污染本类完整迁移。 */
    private static final PostgreSQLContainer<?> SESSION_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("app_session_concurrency")
            .withUsername("thingslink").withPassword("thingslink");
    /** Flyway与APP池在属性注册前绑定同一独占实例，保留真实角色与事务。 */
    private static final String DATABASE_URL = startDatabase();
    /** 本类临时夹具口令只通过真实编码器散列入库。 */
    private static final String PASSWORD = "session-concurrency-password";
    /** 第一改密请求的候选口令，与原值不同以排除空更新。 */
    private static final String FIRST_PASSWORD = "session-concurrency-first";
    /** 第二请求不得凭同一旧密码覆盖第一请求已提交的口令。 */
    private static final String SECOND_PASSWORD = "session-concurrency-second";
    /** 实际密码认证与项目角色检查入口，不以构造principal代替登录。 */
    @Autowired private AppAuthenticationService authentication;
    /** 被测代理自行开启事务，测试不套外层事务。 */
    @Autowired private AppSessionService sessions;
    /** 改密及全用户撤销使用真实生产代理。 */
    @Autowired private AppPasswordService passwordService;
    /** 观察被测APP连接身份和数据库事务。 */
    @Autowired private JdbcTemplate jdbc;
    /** 仅边界反例显式提供真实外层事务，正常六例继续使用业务代理自有事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 与生产相同的口令校验算法。 */
    @Autowired private PasswordEncoder passwords;
    /** 仅读写真实方法周围设屏障，不替换持久返回值或锁。 */
    @MockitoSpyBean private JdbcAppRefreshTokenRepository refreshRepository;
    /** 用户锁和密码更新只设调度屏障，继续执行真实SQL。 */
    @MockitoSpyBean private JdbcAppUserRepository userRepository;
    /** 改密原事务项目许可入口只用于公布PID，不替换许可结果。 */
    @MockitoSpyBean private AppProjectWriteGuard writeGuard;
    /** 共享库专用runner不能写本类以外数据库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 本片无通知发送，不允许全局领取改变夹具事实。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotifications;
    /** 无关任务调度在专库中停用。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTasks;
    /** 无关命令超时扫描在专库中停用。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommands;
    /** 无关聚合回补在专库中停用。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfill;
    /** 仅回收本类种子，不清全表或其他夹具Redis键。 */
    private final List<Fixture> fixtures = new ArrayList<>();
    /** 同用户跨项目例只增加项目关系，不重复删除共享用户/租户。 */
    private final List<UUID> additionalProjects = new ArrayList<>();

    /** 证明真实APP连接和独占数据库，而非owner身份绕过隔离。 */
    @BeforeEach
    void checkDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("app_session_concurrency");
    }

    /** 两事务都读取旧根后按A提交、B继续排序；只能签发一个后继，复用必须持久撤销整族。 */
    @Test
    void concurrentRotationsHaveOneSuccessAndReuseRevokesWholeFamily() throws Exception {
        Fixture f = fixture();
        AppIssuedSession original = login(f);
        AppIssuedSession separate = login(f);
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch secondRead = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicReference<DatabaseTransaction> firstTransaction = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> secondTransaction = new AtomicReference<>();
        JdbcAppRefreshTokenRepository target = AopTestUtils.getUltimateTargetObject(refreshRepository);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<AppRefreshToken> result = (Optional<AppRefreshToken>) invocation.callRealMethod();
            int ordinal = reads.incrementAndGet();
            if (ordinal <= 2) {
                assertThat(result).isPresent();
                assertThat(result.orElseThrow().isRotated()).isFalse();
                assertThat(result.orElseThrow().revokedAt()).isNull();
                AtomicReference<DatabaseTransaction> transaction = ordinal == 1 ? firstTransaction : secondTransaction;
                transaction.set(appTransaction());
                (ordinal == 1 ? firstRead : secondRead).countDown();
                await(ordinal == 1 ? releaseFirst : releaseSecond);
            }
            return result;
        }).when(target).findByHash(any(byte[].class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> rotate(original.refreshToken()));
            await(firstRead);
            Future<Outcome> second = executor.submit(() -> rotate(original.refreshToken()));
            await(secondRead);
            assertIndependent(firstTransaction.get(), secondTransaction.get());
            releaseFirst.countDown();
            Outcome firstResult = first.get(8, TimeUnit.SECONDS);
            assertThat(firstResult.failure()).isNull();
            assertThat(firstResult.session()).isNotNull();
            releaseSecond.countDown();
            Outcome secondResult = second.get(8, TimeUnit.SECONDS);
            int successes = (firstResult.session() == null ? 0 : 1) + (secondResult.session() == null ? 0 : 1);
            assertThat(successes).as("两个真实事务不能都用同一个旧根签发成功").isEqualTo(1);
            assertInvalid(secondResult.failure());
            assertInvalid(catchThrowable(() -> sessions.rotate(original.refreshToken())));
            assertInvalid(catchThrowable(() -> sessions.rotate(firstResult.session().refreshToken())));
            try (Connection observer = owner()) {
                assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=? AND revoked_at IS NOT NULL", f.projectId())).isEqualTo(2);
            }
            // 同用户另一次登录属于另一族，整族撤销不得扩张为注销所有设备。
            assertThat(sessions.rotate(separate.refreshToken()).refreshToken()).isNotBlank();
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            shutdown(executor);
        }
    }

    /** 后继已插入但未提交时注销：旧路径可先提交，新锁路径必须真实等待；两者最终均不能留下可用后继。 */
    @Test
    void logoutDuringUncommittedRotationLeavesNoUsableSuccessor() throws Exception {
        Fixture f = fixture();
        AppIssuedSession original = login(f);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch releaseInsert = new CountDownLatch(1);
        CountDownLatch logoutLocated = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        AtomicReference<DatabaseTransaction> rotatingTransaction = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> logoutTransaction = new AtomicReference<>();
        JdbcAppRefreshTokenRepository target = AopTestUtils.getUltimateTargetObject(refreshRepository);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AppRefreshToken record = invocation.getArgument(0);
            if (record.projectId().equals(f.projectId()) && paused.compareAndSet(false, true)) {
                rotatingTransaction.set(appTransaction());
                inserted.countDown();
                await(releaseInsert);
            }
            return null;
        }).when(target).save(any(AppRefreshToken.class), any(byte[].class));
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // 轮换首次定位先于insert屏障；这里只捕获随后的注销事务，不替换查到的旧记录。
            if (inserted.getCount() == 0 && logoutTransaction.compareAndSet(null, appTransaction())) {
                logoutLocated.countDown();
            }
            return result;
        }).when(target).findByHash(any(byte[].class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> rotating = executor.submit(() -> rotate(original.refreshToken()));
            await(inserted);
            try (Connection observer = owner()) {
                assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=?", f.projectId())).isEqualTo(1);
            }
            Future<?> logout = executor.submit(() -> withoutTransaction(() -> {
                sessions.revoke(original.refreshToken());
                return null;
            }));
            await(logoutLocated);
            assertIndependent(rotatingTransaction.get(), logoutTransaction.get());
            boolean waitsForRotation = completedOrBlockedBy(logout, logoutTransaction.get(), rotatingTransaction.get());
            if (!waitsForRotation) {
                // 旧实现的注销确实已提交，观察者只能看到根；未提交后继不是它的UPDATE快照成员。
                logout.get(3, TimeUnit.SECONDS);
                try (Connection observer = owner()) {
                    assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=? AND revoked_at IS NOT NULL", f.projectId())).isEqualTo(1);
                }
            }
            releaseInsert.countDown();
            Outcome rotated = rotating.get(8, TimeUnit.SECONDS);
            assertThat(rotated.failure()).isNull();
            assertThat(rotated.session()).isNotNull();
            logout.get(8, TimeUnit.SECONDS);
            try (Connection observer = owner()) {
                assertThat(number(observer, """
                        SELECT count(*) FROM app_refresh_token
                         WHERE project_id=? AND revoked_at IS NULL
                           AND replaced_by IS NULL AND expires_at>clock_timestamp()
                        """, f.projectId())).as("注销完成后不得有迟提交逃逸的可用后继").isZero();
                assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=? AND revoked_at IS NOT NULL", f.projectId())).isEqualTo(2);
            }
            assertInvalid(catchThrowable(() -> sessions.rotate(rotated.session().refreshToken())));
        } finally {
            releaseInsert.countDown();
            shutdown(executor);
        }
    }

    /** 同用户跨两项目并发改密也必须在用户轴串行；第二请求锁后读取新散列，不能覆盖成功者。 */
    @Test
    void concurrentPasswordChangesAcrossProjectsAcceptOldPasswordOnce() throws Exception {
        Fixture f = fixture();
        Fixture secondProject = additionalProject(f);
        AppIssuedSession original = login(f);
        AppIssuedSession otherProjectSession = login(secondProject);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean();
        AtomicReference<DatabaseTransaction> firstTransaction = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> secondTransaction = new AtomicReference<>();
        JdbcAppUserRepository users = AopTestUtils.getUltimateTargetObject(userRepository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (pauseOnce.compareAndSet(false, true)) {
                firstTransaction.set(appTransaction());
                updated.countDown();
                await(releaseUpdate);
            }
            return result;
        }).when(users).updatePassword(eq(f.tenantId()), eq(f.userId()), any(String.class), any(Instant.class));
        capturePasswordEntry(secondProject, secondTransaction, secondEntered);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> catchThrowable(() -> changePassword(f, FIRST_PASSWORD)));
            await(updated);
            Future<Throwable> second = executor.submit(() -> catchThrowable(() -> changePassword(secondProject, SECOND_PASSWORD)));
            await(secondEntered);
            assertIndependent(firstTransaction.get(), secondTransaction.get());
            assertThat(completedOrBlockedBy(second, secondTransaction.get(), firstTransaction.get()))
                    .as("跨项目改密必须被同一个用户锁阻塞").isTrue();
            releaseUpdate.countDown();
            assertThat(first.get(8, TimeUnit.SECONDS)).isNull();
            assertBusinessCode(second.get(8, TimeUnit.SECONDS), 60008);
            assertPassword(f, FIRST_PASSWORD);
            assertInvalid(catchThrowable(() -> sessions.rotate(original.refreshToken())));
            assertInvalid(catchThrowable(() -> sessions.rotate(otherProjectSession.refreshToken())));
            try (Connection observer = owner()) {
                assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE app_user_id=? AND revoked_at IS NOT NULL", f.userId())).isEqualTo(2);
            }
        } finally {
            releaseUpdate.countDown();
            shutdown(executor);
        }
    }

    /** 登录尚未锁读用户时改密已提交；恢复登录必须读取新散列并拒绝旧密码，不能创建新族。 */
    @Test
    void passwordChangeBeforeLoginLockRejectsOldPasswordWithoutNewFamily() throws Exception {
        Fixture f = fixture();
        AppIssuedSession original = login(f);
        CountDownLatch beforeLock = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean();
        AtomicReference<DatabaseTransaction> loginTransaction = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> passwordTransaction = new AtomicReference<>();
        CountDownLatch passwordEntered = new CountDownLatch(1);
        JdbcAppUserRepository users = AopTestUtils.getUltimateTargetObject(userRepository);
        doAnswer(invocation -> {
            if (pauseOnce.compareAndSet(false, true)) {
                loginTransaction.set(appTransaction());
                beforeLock.countDown();
                await(releaseLogin);
            }
            return invocation.callRealMethod();
        }).when(users).lockByTenantAndUsername(f.tenantId(), "alice");
        capturePasswordEntry(f, passwordTransaction, passwordEntered);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> login = executor.submit(() -> catchThrowable(() -> login(f)));
            await(beforeLock);
            changePassword(f, FIRST_PASSWORD);
            await(passwordEntered);
            assertIndependent(loginTransaction.get(), passwordTransaction.get());
            assertPassword(f, FIRST_PASSWORD);
            releaseLogin.countDown();
            assertBusinessCode(login.get(8, TimeUnit.SECONDS), 60006);
            try (Connection observer = owner()) {
                assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=?", f.projectId())).isEqualTo(1);
            }
            assertInvalid(catchThrowable(() -> sessions.rotate(original.refreshToken())));
            assertThat(withoutTransaction(() -> authentication.login(f.projectKey(), "alice", FIRST_PASSWORD, null)).refreshToken()).isNotBlank();
        } finally {
            releaseLogin.countDown();
            shutdown(executor);
        }
    }

    /** 登录先持用户锁并写未提交族时改密必须等待；改密提交后刚签发的族也被撤销。 */
    @Test
    void loginHoldingUserLockCommitsBeforePasswordChangeRevokesItsFamily() throws Exception {
        Fixture f = fixture();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        CountDownLatch passwordEntered = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean();
        AtomicReference<DatabaseTransaction> loginTransaction = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> passwordTransaction = new AtomicReference<>();
        JdbcAppRefreshTokenRepository target = AopTestUtils.getUltimateTargetObject(refreshRepository);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (pauseOnce.compareAndSet(false, true)) {
                loginTransaction.set(appTransaction());
                inserted.countDown();
                await(releaseLogin);
            }
            return null;
        }).when(target).save(any(AppRefreshToken.class), any(byte[].class));
        capturePasswordEntry(f, passwordTransaction, passwordEntered);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AppIssuedSession> login = executor.submit(() -> login(f));
            await(inserted);
            try (Connection observer = owner()) {
                assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=?", f.projectId())).isZero();
            }
            Future<?> change = executor.submit(() -> changePassword(f, FIRST_PASSWORD));
            await(passwordEntered);
            assertIndependent(loginTransaction.get(), passwordTransaction.get());
            assertThat(completedOrBlockedBy(change, passwordTransaction.get(), loginTransaction.get()))
                    .as("改密必须等待登录所持用户锁").isTrue();
            releaseLogin.countDown();
            AppIssuedSession issued = login.get(8, TimeUnit.SECONDS);
            change.get(8, TimeUnit.SECONDS);
            assertPassword(f, FIRST_PASSWORD);
            try (Connection observer = owner()) {
                assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=? AND revoked_at IS NOT NULL", f.projectId())).isEqualTo(1);
            }
            assertInvalid(catchThrowable(() -> sessions.rotate(issued.refreshToken())));
        } finally {
            releaseLogin.countDown();
            shutdown(executor);
        }
    }

    /** 复用撤销在独立事务SQL失败时不得伪装60007；部分更新回滚，恢复后可真正提交整族撤销。 */
    @Test
    void reuseRevocationSqlFailureRollsBackSeparateTransactionAndCanRetry() throws Exception {
        Fixture f = fixture();
        AppIssuedSession original = login(f);
        AppIssuedSession successor = sessions.rotate(original.refreshToken());
        List<String> before = tokenRows(f);
        AtomicBoolean inject = new AtomicBoolean(true);
        AtomicReference<DatabaseTransaction> outerTransaction = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> innerTransaction = new AtomicReference<>();
        JdbcAppRefreshTokenRepository target = AopTestUtils.getUltimateTargetObject(refreshRepository);
        doAnswer(invocation -> {
            outerTransaction.compareAndSet(null, appTransaction());
            return invocation.callRealMethod();
        }).when(target).findByHash(any(byte[].class));
        doAnswer(invocation -> {
            innerTransaction.compareAndSet(null, appTransaction());
            Object result = invocation.callRealMethod();
            if (inject.compareAndSet(true, false)) {
                // 必须先执行真实UPDATE再由同连接SQL失败，才能证明REQUIRES_NEW撤销不会部分提交。
                jdbc.queryForObject("SELECT 1 / 0", Integer.class);
            }
            return result;
        }).when(target).revokeFamily(any(UUID.class), any(Instant.class));
        Throwable failure = catchThrowable(() -> sessions.rotate(original.refreshToken()));
        assertThat(failure).isInstanceOf(DataAccessException.class).isNotInstanceOf(BusinessException.class);
        assertThat(sqlState(failure)).isEqualTo("22012");
        assertIndependent(outerTransaction.get(), innerTransaction.get());
        assertThat(tokenRows(f)).isEqualTo(before);
        assertInvalid(catchThrowable(() -> sessions.rotate(original.refreshToken())));
        try (Connection observer = owner()) {
            assertThat(number(observer, "SELECT count(*) FROM app_refresh_token WHERE project_id=? AND revoked_at IS NOT NULL", f.projectId())).isEqualTo(2);
        }
        assertInvalid(catchThrowable(() -> sessions.rotate(successor.refreshToken())));
    }

    /** 锁后重读只接受真实可写RC事务；无事务、只读与RR不能悄悄削弱仲裁或新增令牌事实。 */
    @Test
    void unsupportedTransactionBoundariesRejectWithoutSessionWrites() throws Exception {
        Fixture f = fixture();
        AppIssuedSession original = login(f);
        List<String> before = tokenRows(f);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertSessionBoundary(catchThrowable(() -> userRepository.lockByIdAndTenant(f.tenantId(), f.userId())),
                "用户会话锁必须加入已有非只读事务");
        assertSessionBoundary(catchThrowable(() -> userRepository.lockByTenantAndUsername(f.tenantId(), "alice")),
                "用户会话锁必须加入已有非只读事务");
        assertThat(tokenRows(f)).isEqualTo(before);

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        Throwable readOnlyFailure = catchThrowable(() -> readOnly.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(jdbc.queryForObject("SELECT current_setting('transaction_read_only')", String.class)).isEqualTo("on");
            userRepository.lockByIdAndTenant(f.tenantId(), f.userId());
        }));
        assertSessionBoundary(readOnlyFailure, "用户会话锁必须加入已有非只读事务");
        assertThat(tokenRows(f)).isEqualTo(before);

        TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Throwable repositorySnapshotFailure = catchThrowable(() -> repeatableRead.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class))
                    .isEqualTo("repeatable read");
            userRepository.lockByIdAndTenant(f.tenantId(), f.userId());
        }));
        assertSessionBoundary(repositorySnapshotFailure, "用户会话锁要求非只读READ COMMITTED原事务");
        assertThat(tokenRows(f)).isEqualTo(before);

        Throwable repeatableReadFailure = catchThrowable(() -> repeatableRead.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class)).isEqualTo("repeatable read");
            // 调用真实轮换代理，证明传播加入RR时也不能写child，再以旧快照自称满足锁后复验。
            sessions.rotate(original.refreshToken());
        }));
        assertSessionBoundary(repeatableReadFailure, "项目SHARE写许可要求READ COMMITTED或READ UNCOMMITTED事务");
        assertThat(tokenRows(f)).isEqualTo(before);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(sessions.rotate(original.refreshToken()).refreshToken()).isNotBlank();
    }

    /** 登录仍经过生产代理的密码校验，不向其注入测试事务或租户上下文。 */
    private AppIssuedSession login(Fixture fixture) {
        return withoutTransaction(() -> authentication.login(fixture.projectKey(), "alice", PASSWORD, null));
    }

    /** 入口仅使用可信夹具身份，生产代理仍负责项目许可、用户锁、旧密码验证与全用户撤销。 */
    private void changePassword(Fixture f, String newPassword) {
        withoutTransaction(() -> {
            passwordService.changePassword(f.userId(), f.tenantId(), f.projectId(), PASSWORD, newPassword);
            return null;
        });
    }

    /** 既有项目许可之前公布APP事务，再继续真实调用，便于观察后续用户锁等待。 */
    private void capturePasswordEntry(Fixture f, AtomicReference<DatabaseTransaction> transaction,
                                       CountDownLatch entered) {
        AppProjectWriteGuard target = AopTestUtils.getUltimateTargetObject(writeGuard);
        doAnswer(invocation -> {
            transaction.set(appTransaction());
            entered.countDown();
            return invocation.callRealMethod();
        }).when(target).requireWritable(f.tenantId(), f.projectId());
    }

    /** 查询已提交散列并使用真实编码器匹配，不以更新时间或成功响应代替密码事实。 */
    private void assertPassword(Fixture f, String expected) throws SQLException {
        try (Connection observer = owner(); PreparedStatement query = observer.prepareStatement(
                "SELECT password_hash FROM app_user WHERE tenant_id=? AND id=?")) {
            parameters(query, f.tenantId(), f.userId());
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(passwords.matches(expected, rows.getString(1))).isTrue();
                assertThat(passwords.matches(PASSWORD, rows.getString(1))).isFalse();
            }
        }
    }

    /** 完整快照观察撤销前后原事实，能捕捉只更新一部分令牌的错误提交。 */
    private List<String> tokenRows(Fixture f) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection observer = owner(); PreparedStatement query = observer.prepareStatement(
                "SELECT row_to_json(t)::text FROM app_refresh_token t WHERE project_id=? ORDER BY id")) {
            parameters(query, f.projectId());
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return List.copyOf(result);
    }

    /** 错误源必须为真实SQLSTATE，不能把测试注入的任意Java异常当作数据库回滚证据。 */
    private String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    /** Spring仓储异常翻译后的精确根因，不能把业务60007或SQL锁超时当作事务边界拒绝。 */
    private void assertSessionBoundary(Throwable failure, String message) {
        assertThat(failure).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseExactlyInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(message);
    }

    /** 捕获业务终态供主线程断言；基础设施异常不能冒充正确的认证拒绝。 */
    private Outcome rotate(String token) {
        try { return new Outcome(withoutTransaction(() -> sessions.rotate(token)), null); }
        catch (Throwable failure) { return new Outcome(null, failure); }
    }

    /** 被测业务使用自己的APP代理事务，线程入口不得继承其他身份。 */
    private <T> T withoutTransaction(Supplier<T> action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        return action.get();
    }

    /** 同一APP连接读取PID与真实事务ID，证明屏障没有切换到观察者事务。 */
    private DatabaseTransaction appTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        return jdbc.queryForObject("SELECT current_user,pg_backend_pid(),txid_current()", (row, ignored) -> {
            assertThat(row.getString(1)).isEqualTo(APP_ROLE);
            return new DatabaseTransaction(row.getInt(2), row.getLong(3));
        });
    }

    /** 独立PID和事务ID共同排除同连接串行调用的伪并发证据。 */
    private void assertIndependent(DatabaseTransaction first, DatabaseTransaction second) {
        assertThat(first.pid()).isNotEqualTo(second.pid());
        assertThat(first.transactionId()).isNotEqualTo(second.transactionId());
    }

    /** 只接受操作真实完成或PG确认指定持有者阻塞，不以Future未完成或固定sleep推断锁语义。 */
    private boolean completedOrBlockedBy(Future<?> operation, DatabaseTransaction waiter,
                                         DatabaseTransaction holder) throws Exception {
        try (Connection observer = owner(); PreparedStatement statement = observer.prepareStatement("""
                SELECT pg_backend_pid(), ?=ANY(pg_blocking_pids(?)),
                       EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)
                """)) {
            parameters(statement, holder.pid(), waiter.pid(), waiter.pid());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                if (operation.isDone()) {
                    operation.get(1, TimeUnit.SECONDS);
                    return false;
                }
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotIn(waiter.pid(), holder.pid());
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return true;
                }
                Thread.sleep(5);
            }
        }
        throw new AssertionError("注销既未完成，也没有被指定轮换APP事务阻塞的真实PG证据");
    }

    /** 仅业务60007算预期拒绝，锁超时/线程超时/SQL错误一律失败。 */
    private void assertInvalid(Throwable failure) {
        assertBusinessCode(failure, 60007);
    }

    /** 不同登录、改密、刷新失败保留各自冻结业务码，不接受基础设施异常冒充拒绝。 */
    private void assertBusinessCode(Throwable failure, int code) {
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

    /** 第二项目复用同租户App用户，真实角色与OWNER齐全，验证仲裁轴不能误用project。 */
    private Fixture additionalProject(Fixture original) throws SQLException {
        Fixture f = new Fixture(original.tenantId(), Uuid7.generate(), original.userId(), original.ownerId());
        additionalProjects.add(f.projectId());
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            update(c, "INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?, ?, '会话并发第二项目', ?)", f.projectId(), f.tenantId(), f.projectKey());
            update(c, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), f.projectId(), f.ownerId());
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

    /** finally先释放屏障，再有界回收线程，防止下个测试继承在途事务。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(12, TimeUnit.SECONDS)).isTrue();
    }

    /** 仅按外键顺序清理本类唯一夹具，不清共享Redis或其他项目。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            for (UUID projectId : additionalProjects) {
                update(c, "DELETE FROM app_refresh_token WHERE project_id=?", projectId);
                update(c, "DELETE FROM app_user_role WHERE project_id=?", projectId);
                update(c, "DELETE FROM sys_project_member WHERE project_id=?", projectId);
                update(c, "DELETE FROM sys_project WHERE id=?", projectId);
            }
            for (Fixture f : fixtures) {
                update(c, "DELETE FROM app_refresh_token WHERE project_id=?", f.projectId());
                update(c, "DELETE FROM app_user_role WHERE project_id=?", f.projectId());
                update(c, "DELETE FROM app_user WHERE id=?", f.userId());
                update(c, "DELETE FROM sys_project_member WHERE project_id=?", f.projectId());
                update(c, "DELETE FROM sys_project WHERE id=?", f.projectId());
                update(c, "DELETE FROM sys_tenant_member WHERE tenant_id=?", f.tenantId());
                update(c, "DELETE FROM sys_tenant WHERE id=?", f.tenantId());
                update(c, "DELETE FROM sys_account WHERE id=?", f.ownerId());
            }
            c.commit();
        }
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
        @Bean DynamicPropertyRegistrar sessionDatabase() {
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

    /** @param pid APP连接PID @param transactionId 数据库真实事务ID */
    private record DatabaseTransaction(int pid, long transactionId) { }

    /** @param session 成功返回的新会话 @param failure 原始失败，必须在主线程验证业务码 */
    private record Outcome(AppIssuedSession session, Throwable failure) { }

    /** @param tenantId 租户 @param projectId 项目 @param userId App用户 @param ownerId 控制台OWNER */
    private record Fixture(UUID tenantId, UUID projectId, UUID userId, UUID ownerId) {
        /** 唯一项目键隔离密码登录的真实限流计数。 */
        String projectKey() { return "concurrent_" + projectId.toString().replace("-", ""); }
    }
}
