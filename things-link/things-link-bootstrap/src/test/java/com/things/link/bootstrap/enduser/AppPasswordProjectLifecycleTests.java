package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppAuthenticationService;
import com.things.link.enduser.application.AppPasswordService;
import com.things.link.enduser.application.AppSessionService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * ADR0064决策4、ADR0036及S12-P0-5c2c1：改密须在原事务持有当前项目许可，同时原子撤销用户全部项目会话。
 * 真实登录、APP角色连接与OWNER删除证明生命周期排序，不以HTTP快照或控制台actor代替App业务边界。
 */
class AppPasswordProjectLifecycleTests extends AbstractIntegrationTest {

    /** 真实登录及原口令校验共用合法口令，持久层只保存编码值。 */
    private static final String OLD_PASSWORD = "secret123";
    /** 修改后必须与旧口令不同，才能检验哈希更新与撤销不是空操作。 */
    private static final String NEW_PASSWORD = "changed456";
    /** 用户名在随机租户内唯一，形成真实登录范围。 */
    private static final String USERNAME = "password_actor";
    /** 同租户无关用户用于证明全会话撤销仍受用户轴约束。 */
    private static final String OTHER_USERNAME = "password_bystander";
    /** 被测生产代理独立开启原业务事务。 */
    @Autowired private AppPasswordService passwords;
    /** 真实登录解析projectKey、校验密码与项目角色，生成合法refresh事实。 */
    @Autowired private AppAuthenticationService authentication;
    /** 成功改密后验证旧refresh实际被拒绝，不只查看数据库标志。 */
    @Autowired private AppSessionService sessions;
    /** OWNER删除走真实领域服务，保留冻结下的事实。 */
    @Autowired private ProjectService projectService;
    /** 验证真实APP事务PID、密码更新和五秒JDBC预算。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 只控制删除事务提交，不为改密提供外层测试事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 密码哈希种子与真实服务使用同一算法。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 仅按本例精确键清理登录限流，不清除其他测试状态。 */
    @Autowired private StringRedisTemplate redis;
    /** 许可SQL执行前仅捕获PID，继续调用真实方法。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实全会话撤销SQL之后设置屏障/故障，不能以mock成功伪装持久化。 */
    @MockitoSpyBean private JdbcAppRefreshTokenRepository refreshTokens;
    /** 两个项目属于同租户，同一App用户分别持有合法角色；第二用户仅在当前项目登录。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 当前项目真实登录生成的refresh明文，仅在内存中用于验证撤销。 */
    private String currentRefresh;
    /** 同用户其他项目会话也必须在改密时撤销。 */
    private String secondRefresh;
    /** 对照用户所有原事实必须保持，不依赖“总撤销条数”推断隔离。 */
    private List<String> otherFacts;

    /** 最小真实登录夹具包含同用户双项目会话与无关用户会话，不直接插入refresh哈希。 */
    @BeforeEach
    void prepare() throws SQLException {
        seedFixture();
        currentRefresh = authentication.login(fixture.projectKey(), USERNAME, OLD_PASSWORD, fixture.clientIp()).refreshToken();
        secondRefresh = authentication.login(fixture.secondProjectKey(), USERNAME, OLD_PASSWORD, fixture.clientIp()).refreshToken();
        authentication.login(fixture.projectKey(), OTHER_USERNAME, OLD_PASSWORD, fixture.clientIp());
        otherFacts = userFacts(fixture.otherUserId());
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_refresh_token WHERE app_user_id = ? AND revoked_at IS NULL", fixture.userId())).isEqualTo(2);
            assertThat(count(owner, "SELECT count(DISTINCT project_id) FROM app_refresh_token WHERE app_user_id = ?", fixture.userId())).isEqualTo(2);
        }
    }

    /** 当前项目允许改密后，两项目旧refresh都失效；用户轴撤销不会影响同租户其他用户。 */
    @Test
    void activePasswordChangeRevokesAllUserProjectsButPreservesOtherUser() throws Exception {
        change(fixture.tenantId(), OLD_PASSWORD, NEW_PASSWORD);
        assertChanged();
        List<String> before = facts();
        assertFailure(catchThrowable(() -> sessions.rotate(currentRefresh)), 60007);
        assertFailure(catchThrowable(() -> sessions.rotate(secondRefresh)), 60007);
        assertThat(facts()).isEqualTo(before);
    }

    /** 冻结、真实删除与错tenant均在改哈希及任何refresh撤销前拒绝。 */
    @ParameterizedTest
    @EnumSource(Denial.class)
    void lifecycleDenialPreservesCompleteUserAndRefreshFacts(Denial denial) throws Exception {
        List<String> before = facts();
        if (denial == Denial.DELETED) {
            deleteAsOwner();
            assertDeleted();
        } else if (denial == Denial.ARCHIVED) {
            try (Connection owner = ownerConnection()) {
                execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
            }
        }
        UUID tenantId = denial == Denial.WRONG_TENANT ? Uuid7.generate() : fixture.tenantId();
        assertFailure(catchThrowable(() -> change(tenantId, OLD_PASSWORD, NEW_PASSWORD)), denial == Denial.ARCHIVED ? 60022 : 60009);
        assertThat(facts()).isEqualTo(before);
    }

    /** 项目ACTIVE不代表跳过原口令和新口令长度校验，原业务码及零写入合同保持。 */
    @ParameterizedTest
    @EnumSource(InvalidPassword.class)
    void existingPasswordValidationStillRejectsWithoutWrites(InvalidPassword invalid) throws Exception {
        List<String> before = facts();
        assertFailure(catchThrowable(() -> change(fixture.tenantId(),
                invalid == InvalidPassword.OLD_INCORRECT ? "wrong123" : OLD_PASSWORD,
                invalid == InvalidPassword.NEW_TOO_SHORT ? "short" : NEW_PASSWORD)),
                invalid == InvalidPassword.OLD_INCORRECT ? 60008 : 10001);
        assertThat(facts()).isEqualTo(before);
    }

    /** 真实更新哈希并撤销两项目refresh后内部异常必须整体回滚，包括password_changed_at。 */
    @Test
    void internalFailureAfterRealRevocationRollsBackPasswordAndEverySession() throws Exception {
        List<String> before = facts();
        JdbcAppRefreshTokenRepository target = AopTestUtils.getUltimateTargetObject(refreshTokens);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(result).isEqualTo(2);
            assertRealWrite();
            throw new IllegalStateException("改密撤销后的受控内部故障");
        }).when(target).revokeAllForUser(eq(fixture.userId()), any(Instant.class));
        // 故障发生在@Repository内部，Spring保留根因并转换为数据访问异常；仍须整体回滚。
        assertThat(catchThrowable(() -> change(fixture.tenantId(), OLD_PASSWORD, NEW_PASSWORD)))
                .isExactlyInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("改密撤销后的受控内部故障");
        assertThat(facts()).isEqualTo(before);
    }

    /** 哈希与撤销真实写完仍未提交时，删除必须等待改密原事务释放项目SHARE许可。 */
    @Test
    void passwordWriteFirstBlocksOwnerDeletionUntilBusinessCommit() throws Exception {
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcAppRefreshTokenRepository target = AopTestUtils.getUltimateTargetObject(refreshTokens);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(result).isEqualTo(2);
            writerPid.complete(appPid());
            assertRealWrite();
            written.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(target).revokeAllForUser(eq(fixture.userId()), any(Instant.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writing = executor.submit(() -> change(fixture.tenantId(), OLD_PASSWORD, NEW_PASSWORD));
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
            assertChanged();
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** 删除先更新未提交时改密真实阻塞；删除提交后拒绝，用户与全部会话保持原事实。 */
    @Test
    void ownerDeletionFirstMakesWaitingPasswordChangeRejectAfterCommit() throws Exception {
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = capturePermitPid();
        CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> deleteTransaction(deletionPid, deleted, release));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> writing = executor.submit(() -> catchThrowable(() -> change(fixture.tenantId(), OLD_PASSWORD, NEW_PASSWORD)));
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

    /** 五秒预算取消项目锁SQL时传播57014并回滚，释放锁后用同一原口令可正常改密。 */
    @Test
    void projectPermitTimeoutRollsBackAllFactsAndRecovers() throws Exception {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<Throwable> writing = executor.submit(() -> catchThrowable(() -> change(fixture.tenantId(), OLD_PASSWORD, NEW_PASSWORD)));
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), holderPid, writing);
                Throwable failure = writing.get(10, TimeUnit.SECONDS);
                assertThat(isSqlTimeout(failure)).as("真实项目锁查询应沿原JDBC预算取消：%s", failure).isTrue();
                assertThat(failure).isNotInstanceOf(BusinessException.class);
                assertThat(facts()).isEqualTo(before);
                holder.rollback();
                change(fixture.tenantId(), OLD_PASSWORD, NEW_PASSWORD);
                assertChanged();
            } finally {
                holder.rollback();
                shutdown(executor);
            }
        }
    }

    /** tid/pid来自App声明；测试仅在错tenant场景破坏参数，保留正确RLS以独立证明显式许可校验。 */
    private void change(UUID tenantId, String oldPassword, String newPassword) {
        scoped(() -> {
            passwords.changePassword(fixture.userId(), tenantId, fixture.projectId(), oldPassword, newPassword);
            return null;
        });
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

    /** 精确原BusinessException保证原业务异常没有被包装成其他事务错误。 */
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

    /** 同一APP事务内证明密码及两项目会话已真实改变，不能用spy仅被调用代替SQL执行。 */
    private void assertRealWrite() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isPresent();
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, fixture.userId());
        assertThat(passwordEncoder.matches(NEW_PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, hash)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE id = ? AND password_changed_at IS NOT NULL", Integer.class, fixture.userId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id = ? AND revoked_at IS NOT NULL", Integer.class, fixture.userId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id = ? AND revoked_at IS NULL", Integer.class, fixture.otherUserId())).isEqualTo(1);
    }

    /** OWNER删除只冻结当前项目，另一项目继续ACTIVE，两项目角色事实均保留。 */
    private void assertDeleted() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL", fixture.secondProjectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_role WHERE app_user_id = ? AND status = 'ACTIVE'", fixture.userId())).isEqualTo(2);
        }
    }

    /** 独立owner只观察已提交结果：新哈希、变更时刻及两项目撤销同时存在，对照用户全行不变。 */
    private void assertChanged() throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT password_hash, password_changed_at FROM app_user WHERE id = ?")) {
            query.setObject(1, fixture.userId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(passwordEncoder.matches(NEW_PASSWORD, rows.getString(1))).isTrue();
                assertThat(passwordEncoder.matches(OLD_PASSWORD, rows.getString(1))).isFalse();
                assertThat(rows.getTimestamp(2)).isNotNull();
            }
            assertThat(count(owner, "SELECT count(*) FROM app_refresh_token WHERE app_user_id = ? AND revoked_at IS NOT NULL", fixture.userId())).isEqualTo(2);
            assertThat(count(owner, "SELECT count(*) FROM app_refresh_token WHERE app_user_id = ? AND revoked_at IS NULL", fixture.userId())).isZero();
        }
        assertThat(userFacts(fixture.otherUserId())).isEqualTo(otherFacts);
    }

    /** 完整用户与refresh行含哈希、时间、family/replaced_by；比较双方事实，避免只数行遗漏半提交。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>(userFacts(fixture.userId()));
        result.addAll(userFacts(fixture.otherUserId()));
        return List.copyOf(result);
    }

    /** 查询范围来自固定表白名单与绑定用户ID，不打印明文令牌或拼接用户输入。 */
    private List<String> userFacts(UUID userId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("app_user", "app_user_role", "app_refresh_token")) {
                String column = table.equals("app_user") ? "id" : "app_user_id";
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(t)::text FROM " + table + " t WHERE " + column + " = ? ORDER BY id")) {
                    query.setObject(1, userId);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) result.add(table + rows.getString(1));
                    }
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

    /** 生命周期确定拒绝，数据库故障另按原异常传播。 */
    private enum Denial {
        /** ARCHIVED保持只读。 */ ARCHIVED,
        /** OWNER真实删除已提交。 */ DELETED,
        /** 当前项目归属与显式tid不匹配。 */ WRONG_TENANT
    }

    /** 原改密校验失败仍应零写入，不能因门禁接线改变错误合同。 */
    private enum InvalidPassword {
        /** 原口令错误沿60008。 */ OLD_INCORRECT,
        /** 新口令不足八位沿10001。 */ NEW_TOO_SHORT
    }

    /** 只插入账号/OWNER/项目角色前置，refresh必须经真实登录创建。 */
    private void seedFixture() throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'App改密隔离租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '改密项目owner')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            for (UUID projectId : List.of(fixture.projectId(), fixture.secondProjectId())) {
                execute(owner, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) VALUES (?, ?, '改密测试项目', 'sh-1', ?)",
                        projectId, fixture.tenantId(), projectId.equals(fixture.projectId()) ? fixture.projectKey() : fixture.secondProjectKey());
                execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                        Uuid7.generate(), projectId, fixture.accountId());
            }
            for (UUID userId : List.of(fixture.userId(), fixture.otherUserId())) {
                execute(owner, "INSERT INTO app_user (id, tenant_id, username, password_hash, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                        userId, fixture.tenantId(), userId.equals(fixture.userId()) ? USERNAME : OTHER_USERNAME, passwordEncoder.encode(OLD_PASSWORD));
                execute(owner, "INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status) VALUES (?, ?, ?, ?, 'MAINTAINER', 'ACTIVE')",
                        Uuid7.generate(), fixture.tenantId(), fixture.projectId(), userId);
            }
            execute(owner, "INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status) VALUES (?, ?, ?, ?, 'MAINTAINER', 'ACTIVE')",
                    Uuid7.generate(), fixture.tenantId(), fixture.secondProjectId(), fixture.userId());
            owner.commit();
        }
    }

    /** 清理仅覆盖本例账号/两项目和精确登录限流键；项目删除产生的不可变审计保留至容器销毁。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM app_refresh_token WHERE app_user_id IN (?, ?)", fixture.userId(), fixture.otherUserId());
            execute(owner, "DELETE FROM app_user_role WHERE project_id IN (?, ?)", fixture.projectId(), fixture.secondProjectId());
            execute(owner, "DELETE FROM app_user WHERE id IN (?, ?)", fixture.userId(), fixture.otherUserId());
            execute(owner, "DELETE FROM sys_project_member WHERE project_id IN (?, ?)", fixture.projectId(), fixture.secondProjectId());
            execute(owner, "DELETE FROM sys_project WHERE id IN (?, ?)", fixture.projectId(), fixture.secondProjectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", fixture.tenantId(), fixture.accountId());
            execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
            execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.commit();
        }
        redis.delete(List.of("app:rl:identity:" + fixture.projectKey() + ":" + USERNAME,
                "app:rl:identity:" + fixture.secondProjectKey() + ":" + USERNAME,
                "app:rl:identity:" + fixture.projectKey() + ":" + OTHER_USERNAME,
                "app:rl:ip:" + fixture.clientIp()));
    }

    /** owner只用于种子、独立观察和清理，改密与删除始终通过真实APP角色与业务服务。 */
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

    /** @param tenantId 共同租户 @param projectId 当前改密项目 @param secondProjectId 同用户另一项目 @param accountId OWNER @param userId 改密App用户 @param otherUserId 对照用户 */
    private record Fixture(UUID tenantId, UUID projectId, UUID secondProjectId, UUID accountId, UUID userId, UUID otherUserId) {
        /** 全局唯一路由键供真实登录解析当前项目。 */
        private String projectKey() { return "app_password_" + projectId.toString().replace("-", ""); }
        /** 第二项目会话由独立projectKey登录签发，不伪造refresh项目字段。 */
        private String secondProjectKey() { return "app_password_" + secondProjectId.toString().replace("-", ""); }
        /** 每例独立文档IPv6地址，只用作真实登录限流维度，避免共享IP测试污染。 */
        private String clientIp() {
            return "2001:db8:" + userId.toString().replace("-", "").substring(8).replaceAll("(.{4})(?!$)", "$1:");
        }
    }
}
