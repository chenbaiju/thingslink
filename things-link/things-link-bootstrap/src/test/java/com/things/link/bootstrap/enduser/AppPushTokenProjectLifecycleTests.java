package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppPushTokenService;
import com.things.link.enduser.application.EncryptedPushToken;
import com.things.link.enduser.application.PushTokenCipher;
import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.infrastructure.persistence.JdbcAppPushTokenRepository;
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
import org.mockito.stubbing.Answer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR0064决策4、ADR0051及S12-P0-5c2c2：PUSH安装写入在原事务持有当前项目许可。
 * 项目只控制请求资格；安装身份和AES-GCM AAD仍是tenant/user/installation，不虚构项目归属。
 */
class AppPushTokenProjectLifecycleTests extends AbstractIntegrationTest {

    /** 初始厂商明文仅在测试内存与加解密边界使用，不写日志或审计。 */
    private static final String OLD_TOKEN = "lifecycle-huawei-original";
    /** 轮换同时改变provider与token，防止旧AAD下可解密被误认为新信封正确。 */
    private static final String NEW_TOKEN = "lifecycle-oppo-rotated";
    /** 唯一公开App写入口；每次调用由其原生产代理开启真实事务。 */
    @Autowired private AppPushTokenService pushTokens;
    /** 真实AES-GCM Bean，不替换密钥或加密算法。 */
    @Autowired private PushTokenCipher cipher;
    /** OWNER删除必须实际冻结项目并提交。 */
    @Autowired private ProjectService projectService;
    /** 观察APP角色物理连接与事务内信封。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 仅控制OWNER删除提交，App写操作不得被测试外层事务包裹。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 合法用户种子只保存生产格式密码哈希。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 许可SQL前公开实际PID但不替换真实锁结果。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实insert/activate/revoke后注入屏障或内部异常，证明完整信封写入与回滚。 */
    @MockitoSpyBean private JdbcAppPushTokenRepository repository;
    /** 两项目共享同一用户及安装，另一个用户使用相同installationId检验用户轴隔离。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 既有安装的原身份与首次创建时间，原位轮换/恢复/撤销均不得改写。 */
    private PushRow original;
    /** 对照用户安装全行快照，用于证明当前用户操作未扩大范围。 */
    private List<String> otherFacts;

    /** 独占租户与两个ACTIVE项目；真实注册另一个用户同installation的安装作为对照。 */
    @BeforeEach
    void prepare() throws SQLException {
        seedFixture();
        register(fixture.tenantId(), fixture.projectId(), fixture.otherUserId(), AppPushToken.Provider.HUAWEI, "other-user-token");
        otherFacts = installationFacts(fixture.otherUserId());
    }

    /** 初建、provider轮换、吊销幂等和重新激活都复用唯一事实，AES-GCM按当前AAD真实解密。 */
    @Test
    void activeRegistrationRotationRevocationAndRecoveryPreserveStableIdentity() throws Exception {
        registerOriginal();
        PushRow first = row(fixture.userId());
        assertEnvelope(first, AppPushToken.Provider.HUAWEI, OLD_TOKEN, AppPushToken.Status.ACTIVE);
        register(fixture.tenantId(), fixture.projectId(), fixture.userId(), AppPushToken.Provider.OPPO, NEW_TOKEN);
        PushRow rotated = row(fixture.userId());
        assertStableIdentity(first, rotated);
        assertThat(rotated.nonce()).isNotEqualTo(first.nonce());
        assertThat(rotated.cipherText()).isNotEqualTo(first.cipherText());
        assertEnvelope(rotated, AppPushToken.Provider.OPPO, NEW_TOKEN, AppPushToken.Status.ACTIVE);
        revoke(fixture.tenantId(), fixture.projectId(), fixture.userId());
        PushRow revoked = row(fixture.userId());
        assertStableIdentity(first, revoked);
        assertEnvelope(revoked, AppPushToken.Provider.OPPO, NEW_TOKEN, AppPushToken.Status.REVOKED);
        assertThat(revoked.cipherText()).isEqualTo(rotated.cipherText());
        assertThat(revoked.nonce()).isEqualTo(rotated.nonce());
        List<String> before = facts();
        revoke(fixture.tenantId(), fixture.projectId(), fixture.userId());
        assertThat(facts()).isEqualTo(before);
        register(fixture.tenantId(), fixture.projectId(), fixture.userId(), AppPushToken.Provider.HUAWEI, OLD_TOKEN);
        PushRow recovered = row(fixture.userId());
        assertStableIdentity(first, recovered);
        assertThat(recovered.nonce()).isNotEqualTo(revoked.nonce());
        assertEnvelope(recovered, AppPushToken.Provider.HUAWEI, OLD_TOKEN, AppPushToken.Status.ACTIVE);
        assertOtherUntouched();
        assertInvisibleWithoutScope();
    }

    /** 四种安装状态路径均在生命周期拒绝后保留完整密文、nonce、keyId及所有身份时间字段。 */
    @ParameterizedTest(name = "{0}在{1}下拒绝且完整信封不变")
    @CsvSource({"REGISTER,ARCHIVED", "REGISTER,DELETED", "REGISTER,WRONG_TENANT",
            "ROTATE,ARCHIVED", "ROTATE,DELETED", "ROTATE,WRONG_TENANT",
            "RESTORE,ARCHIVED", "RESTORE,DELETED", "RESTORE,WRONG_TENANT",
            "REVOKE,ARCHIVED", "REVOKE,DELETED", "REVOKE,WRONG_TENANT"})
    void lifecycleDenialPrecedesEveryInstallationMutation(Operation operation, Denial denial) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        applyDenial(denial);
        UUID tenantId = denial == Denial.WRONG_TENANT ? Uuid7.generate() : fixture.tenantId();
        assertFailure(catchThrowable(() -> invoke(operation, tenantId)), denial == Denial.ARCHIVED ? 60022 : 60009);
        assertThat(facts()).isEqualTo(before);
    }

    /** 不存在与已撤销的revoke虽在ACTIVE幂等成功，冻结后仍必须先拒绝许可。 */
    @ParameterizedTest
    @CsvSource({"false,ARCHIVED", "false,DELETED", "true,ARCHIVED", "true,DELETED"})
    void revokeNoOpDoesNotBypassFrozenProject(boolean alreadyRevoked, Denial denial) throws Exception {
        if (alreadyRevoked) {
            registerOriginal();
            revoke(fixture.tenantId(), fixture.projectId(), fixture.userId());
        }
        List<String> before = facts();
        revoke(fixture.tenantId(), fixture.projectId(), fixture.userId());
        assertThat(facts()).isEqualTo(before);
        applyDenial(denial);
        assertFailure(catchThrowable(() -> revoke(fixture.tenantId(), fixture.projectId(), fixture.userId())),
                denial == Denial.ARCHIVED ? 60022 : 60009);
        assertThat(facts()).isEqualTo(before);
    }

    /** A冻结不撤销租户用户级安装；同用户在B取得许可后继续操作同一ID，AAD不能偷偷加入project。 */
    @ParameterizedTest
    @EnumSource(value = Denial.class, names = {"ARCHIVED", "DELETED"})
    void anotherActiveProjectCanStillUseSameInstallationAfterCurrentProjectFreezes(Denial denial) throws Exception {
        registerOriginal();
        PushRow first = row(fixture.userId());
        List<String> before = facts();
        applyDenial(denial);
        assertThat(facts()).isEqualTo(before);
        register(fixture.tenantId(), fixture.secondProjectId(), fixture.userId(), AppPushToken.Provider.OPPO, NEW_TOKEN);
        PushRow fromSecondProject = row(fixture.userId());
        assertStableIdentity(first, fromSecondProject);
        assertEnvelope(fromSecondProject, AppPushToken.Provider.OPPO, NEW_TOKEN, AppPushToken.Status.ACTIVE);
        revoke(fixture.tenantId(), fixture.secondProjectId(), fixture.userId());
        assertEnvelope(row(fixture.userId()), AppPushToken.Provider.OPPO, NEW_TOKEN, AppPushToken.Status.REVOKED);
        register(fixture.tenantId(), fixture.secondProjectId(), fixture.userId(), AppPushToken.Provider.HUAWEI, OLD_TOKEN);
        assertStableIdentity(first, row(fixture.userId()));
        assertEnvelope(row(fixture.userId()), AppPushToken.Provider.HUAWEI, OLD_TOKEN, AppPushToken.Status.ACTIVE);
        assertOtherUntouched();
    }

    /** 自定义内部异常避免把@Repository对IllegalStateException的翻译误当业务失败；真实SQL已执行但整体回滚。 */
    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REGISTER", "ROTATE", "REVOKE"})
    void internalFailureAfterRealRepositoryWriteRollsBackCompleteEnvelope(Operation operation) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        installWriteProbe(operation, () -> { throw new InjectedFailure(); });
        assertThat(catchThrowable(() -> invoke(operation, fixture.tenantId())))
                .isExactlyInstanceOf(InjectedFailure.class).hasMessage("PUSH真实写入后的受控内部故障");
        assertThat(facts()).isEqualTo(before);
    }

    /** 注册/吊销真实完成未提交时，OWNER删除受原事务SHARE许可阻挡，旁观连接仍见旧完整事实。 */
    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REGISTER", "REVOKE"})
    void installationWriteFirstBlocksOwnerDeletionUntilBusinessCommit(Operation operation) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        installWriteProbe(operation, () -> {
            writerPid.complete(appPid());
            written.countDown();
            awaitRelease(release);
        });
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

    /** 删除先更新未提交时App许可实际阻塞，删除提交后原异常拒绝而无半信封或撤销事实。 */
    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REGISTER", "REVOKE"})
    void ownerDeletionFirstMakesWaitingInstallationWriteRejectAfterCommit(Operation operation) throws Exception {
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

    /** 两公开入口均沿真实五秒JDBC预算取消项目锁，释放后同一原安装请求可重新成功。 */
    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REGISTER", "REVOKE"})
    void projectPermitTimeoutRollsBackEnvelopeAndRecovers(Operation operation) throws Exception {
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
                assertThat(isSqlTimeout(failure)).as("真实项目锁应按原JDBC预算取消：%s", failure).isTrue();
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

    /** 生命周期测试前只通过真实服务建立所需ACTIVE/REVOKED起点，不伪造密文或状态。 */
    private void prepareOperation(Operation operation) throws SQLException {
        if (operation != Operation.REGISTER) {
            registerOriginal();
            if (operation == Operation.RESTORE) revoke(fixture.tenantId(), fixture.projectId(), fixture.userId());
            original = row(fixture.userId());
        }
    }

    /** 初始HUAWEI实例仅由真实加密服务落库。 */
    private void registerOriginal() {
        register(fixture.tenantId(), fixture.projectId(), fixture.userId(), AppPushToken.Provider.HUAWEI, OLD_TOKEN);
    }

    /** ROTATE和RESTORE均通过同一公开register入口，REVOKE保持独立事务合同。 */
    private void invoke(Operation operation, UUID tenantId) {
        if (operation == Operation.REVOKE) revoke(tenantId, fixture.projectId(), fixture.userId());
        else register(tenantId, fixture.projectId(), fixture.userId(), AppPushToken.Provider.OPPO, NEW_TOKEN);
    }

    /** 可信项目来自App请求；错误tenant场景仅破坏显式参数，保持正确RLS检查许可是否独立。 */
    private void register(UUID tenantId, UUID projectId, UUID userId, AppPushToken.Provider provider, String plaintext) {
        scoped(projectId, () -> pushTokens.register(tenantId, projectId, userId, fixture.installationId(), provider, plaintext));
    }

    /** 吊销不携带厂商密文，也不能拿安装不存在作为绕过项目许可的理由。 */
    private void revoke(UUID tenantId, UUID projectId, UUID userId) {
        scoped(projectId, () -> pushTokens.revoke(tenantId, projectId, userId, fixture.installationId()));
    }

    /** 真实App数据范围独立于控制台actor，测试不得包额外业务事务。 */
    private void scoped(UUID projectId, Runnable action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), projectId));
        try { action.run(); }
        finally {
            RlsScopeContext.clear();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TenantContext.current()).isEmpty();
        }
    }

    /** 只拦截本例主用户的真实写入，其他用户或后台操作不受测试故障影响。 */
    private void installWriteProbe(Operation operation, Runnable afterWrite) {
        JdbcAppPushTokenRepository target = AopTestUtils.getUltimateTargetObject(repository);
        Answer<Object> probe = invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(result).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TenantContext.current()).isEmpty();
            PushRow written = jdbcTemplate.queryForObject("SELECT * FROM app_push_token WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?",
                    (rows, index) -> mapRow(rows), fixture.tenantId(), fixture.userId(), fixture.installationId());
            assertEnvelope(written, operation == Operation.REVOKE ? AppPushToken.Provider.HUAWEI : AppPushToken.Provider.OPPO,
                    operation == Operation.REVOKE ? OLD_TOKEN : NEW_TOKEN,
                    operation == Operation.REVOKE ? AppPushToken.Status.REVOKED : AppPushToken.Status.ACTIVE);
            afterWrite.run();
            return result;
        };
        if (operation == Operation.REGISTER) {
            doAnswer(probe).when(target).insert(argThat(token -> token != null && token.appUserId().equals(fixture.userId())), any(EncryptedPushToken.class));
        } else if (operation == Operation.REVOKE) {
            doAnswer(probe).when(target).revoke(eq(fixture.tenantId()), eq(fixture.userId()), eq(fixture.installationId()), any(Instant.class));
        } else {
            doAnswer(probe).when(target).activate(argThat(token -> token != null && token.appUserId().equals(fixture.userId())), any(EncryptedPushToken.class));
        }
    }

    /** 屏障失败明确中断测试，不能遗留等待的事务工作线程。 */
    private void awaitRelease(CountDownLatch release) {
        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("安装写入屏障被中断", failure);
        }
    }

    /** 归档可直接设只读种子；删除必须沿真实OWNER业务流程。 */
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

    /** OWNER删除只冻结A；B保持ACTIVE，安装不属于任何单个项目而不能被自动撤销。 */
    private void assertDeleted() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL", fixture.secondProjectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_role WHERE app_user_id = ? AND status = 'ACTIVE'", fixture.userId())).isEqualTo(2);
        }
    }

    /** 从独立owner检查提交后状态；原位变更不得替换身份或首次创建时间。 */
    private void assertSuccess(Operation operation) throws SQLException {
        PushRow current = row(fixture.userId());
        assertEnvelope(current, operation == Operation.REVOKE ? AppPushToken.Provider.HUAWEI : AppPushToken.Provider.OPPO,
                operation == Operation.REVOKE ? OLD_TOKEN : NEW_TOKEN,
                operation == Operation.REVOKE ? AppPushToken.Status.REVOKED : AppPushToken.Status.ACTIVE);
        if (original != null) assertStableIdentity(original, current);
        assertOtherUntouched();
    }

    /** 每个信封需满足真实加密、身份AAD与状态约束，明文不作为数据库列或失败输出内容。 */
    private void assertEnvelope(PushRow row, AppPushToken.Provider provider, String plaintext, AppPushToken.Status status) {
        assertThat(row).isNotNull();
        assertThat(row.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(row.appUserId()).isEqualTo(fixture.userId());
        assertThat(row.installationId()).isEqualTo(fixture.installationId());
        assertThat(row.provider()).isEqualTo(provider);
        assertThat(row.status()).isEqualTo(status);
        assertThat(row.keyId()).isNotBlank();
        assertThat(row.nonce()).hasSize(12);
        assertThat(row.cipherText().length).isGreaterThanOrEqualTo(17);
        assertThat(row.createdAt()).isNotNull();
        assertThat(row.updatedAt()).isNotNull();
        if (status == AppPushToken.Status.REVOKED) assertThat(row.revokedAt()).isNotNull();
        else assertThat(row.revokedAt()).isNull();
        AppPushToken metadata = new AppPushToken(row.id(), row.tenantId(), row.appUserId(), row.installationId(),
                row.provider(), row.status(), row.createdAt(), row.updatedAt(), row.revokedAt());
        // 只报告真假，避免断言失败将厂商token明文写入测试日志。
        assertThat(cipher.decrypt(metadata, new EncryptedPushToken(row.cipherText(), row.nonce(), row.keyId())).equals(plaintext)).isTrue();
    }

    /** 身份不变量包括首次创建时间，不能以删除重建冒充轮换或恢复。 */
    private void assertStableIdentity(PushRow first, PushRow next) {
        assertThat(next.id()).isEqualTo(first.id());
        assertThat(next.createdAt()).isEqualTo(first.createdAt());
        assertThat(next.tenantId()).isEqualTo(first.tenantId());
        assertThat(next.appUserId()).isEqualTo(first.appUserId());
        assertThat(next.installationId()).isEqualTo(first.installationId());
    }

    /** 请求结束后清空RLS必须看不到任何安装，不误用owner读结果宣称APP隔离正常。 */
    private void assertInvisibleWithoutScope() {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_push_token WHERE tenant_id = ?", Integer.class, fixture.tenantId())).isZero();
    }

    /** 相同installationId的其他用户事实保持不变，三轴唯一身份不能退化为客户端ID全局唯一。 */
    private void assertOtherUntouched() throws SQLException {
        assertThat(installationFacts(fixture.otherUserId())).isEqualTo(otherFacts);
    }

    /** 独立owner读取提交信封并检查唯一性，不能只取第一行掩盖重复实例。 */
    private PushRow row(UUID appUserId) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT * FROM app_push_token WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?")) {
            query.setObject(1, fixture.tenantId());
            query.setObject(2, appUserId);
            query.setObject(3, fixture.installationId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                PushRow result = mapRow(rows);
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 测试局部完整信封投影，生产领域查询仍不携带密文/nonce。 */
    private PushRow mapRow(ResultSet rows) throws SQLException {
        java.sql.Timestamp revokedAt = rows.getTimestamp("revoked_at");
        return new PushRow(rows.getObject("id", UUID.class), rows.getObject("tenant_id", UUID.class),
                rows.getObject("app_user_id", UUID.class), rows.getObject("installation_id", UUID.class),
                AppPushToken.Provider.valueOf(rows.getString("provider")), rows.getBytes("token_cipher"),
                rows.getBytes("token_nonce"), rows.getString("key_id"), AppPushToken.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(), rows.getTimestamp("updated_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    /** 比较两名用户所有安装全行，包括密文、nonce及时间字段，生命周期项目审计不混入安装断言。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>(installationFacts(fixture.userId()));
        result.addAll(installationFacts(fixture.otherUserId()));
        return List.copyOf(result);
    }

    /** 行JSON对bytea使用数据库编码，不存明文，也不会遗漏轮换时被悄悄改写的字段。 */
    private List<String> installationFacts(UUID userId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT row_to_json(t)::text FROM app_push_token t WHERE tenant_id = ? AND app_user_id = ? ORDER BY id")) {
            query.setObject(1, fixture.tenantId());
            query.setObject(2, userId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
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

    /** 注册覆盖首次写入、既有轮换与已撤销恢复，撤销沿独立公开入口。 */
    private enum Operation {
        /** 不存在行，走insert。 */ REGISTER,
        /** ACTIVE行更新信封，走activate。 */ ROTATE,
        /** REVOKED行恢复，仍走activate。 */ RESTORE,
        /** ACTIVE行撤销，保持信封。 */ REVOKE
    }

    /** 错tenant仍保留正确RLS，用于证明项目二元组匹配不是RLS意外挡住。 */
    private enum Denial {
        /** 归档只读60022。 */ ARCHIVED,
        /** OWNER真实删除60009。 */ DELETED,
        /** 项目tenant不匹配60009。 */ WRONG_TENANT
    }

    /** 自定义异常不属于SQL异常翻译范围，注入点只证明真实写后原事务回滚。 */
    private static final class InjectedFailure extends RuntimeException {
        /** 本地测试异常的稳定序列化版本。 */
        private static final long serialVersionUID = 1L;
        /** 固定非敏感消息不携带厂商token。 */
        private InjectedFailure() { super("PUSH真实写入后的受控内部故障"); }
    }

    /** @param id 稳定事实ID @param tenantId 租户 @param appUserId App用户 @param installationId 安装ID @param provider 当前AAD厂商 @param cipherText 密文与tag @param nonce GCM随机数 @param keyId 密钥版本 @param status 生命周期 @param createdAt 首次创建 @param updatedAt 最后修改 @param revokedAt 撤销时间 */
    private record PushRow(UUID id, UUID tenantId, UUID appUserId, UUID installationId,
                           AppPushToken.Provider provider, byte[] cipherText, byte[] nonce, String keyId,
                           AppPushToken.Status status, Instant createdAt, Instant updatedAt, Instant revokedAt) { }

    /** 只插入账号/OWNER/项目角色前置，安装信封必须由真实服务加密创建。 */
    private void seedFixture() throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'App安装隔离租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '安装项目owner')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            for (UUID projectId : List.of(fixture.projectId(), fixture.secondProjectId())) {
                execute(owner, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) VALUES (?, ?, '安装测试项目', 'sh-1', ?)",
                        projectId, fixture.tenantId(), projectId.equals(fixture.projectId()) ? fixture.projectKey() : fixture.secondProjectKey());
                execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                        Uuid7.generate(), projectId, fixture.accountId());
            }
            for (UUID userId : List.of(fixture.userId(), fixture.otherUserId())) {
                execute(owner, "INSERT INTO app_user (id, tenant_id, username, password_hash, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                        userId, fixture.tenantId(), userId.equals(fixture.userId()) ? "push_actor" : "push_bystander", passwordEncoder.encode("secret123"));
                execute(owner, "INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status) VALUES (?, ?, ?, ?, 'MAINTAINER', 'ACTIVE')",
                        Uuid7.generate(), fixture.tenantId(), fixture.projectId(), userId);
            }
            execute(owner, "INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status) VALUES (?, ?, ?, ?, 'MAINTAINER', 'ACTIVE')",
                    Uuid7.generate(), fixture.tenantId(), fixture.secondProjectId(), fixture.userId());
            owner.commit();
        }
    }

    /** 清理本例独占身份；不扫描全局安装、不调用告警派发，不删除不可变项目审计。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM app_push_token WHERE tenant_id = ? AND app_user_id IN (?, ?)", fixture.tenantId(), fixture.userId(), fixture.otherUserId());
            execute(owner, "DELETE FROM app_user_role WHERE project_id IN (?, ?)", fixture.projectId(), fixture.secondProjectId());
            execute(owner, "DELETE FROM app_user WHERE id IN (?, ?)", fixture.userId(), fixture.otherUserId());
            execute(owner, "DELETE FROM sys_project_member WHERE project_id IN (?, ?)", fixture.projectId(), fixture.secondProjectId());
            execute(owner, "DELETE FROM sys_project WHERE id IN (?, ?)", fixture.projectId(), fixture.secondProjectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", fixture.tenantId(), fixture.accountId());
            execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
            execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.commit();
        }
    }

    /** owner只用于种子、独立观察和清理，安装写入与删除始终通过真实APP角色与业务服务。 */
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

    /** @param tenantId 租户 @param projectId 当前请求项目A @param secondProjectId 其他项目B @param accountId OWNER @param userId App用户 @param otherUserId 对照用户 @param installationId 双用户共用客户端安装ID */
    private record Fixture(UUID tenantId, UUID projectId, UUID secondProjectId, UUID accountId,
                           UUID userId, UUID otherUserId, UUID installationId) {
        /** 真实OWNER项目路由保持唯一。 */
        private String projectKey() { return "app_push_" + projectId.toString().replace("-", ""); }
        /** B项目属于同租户，用于证明安装并不绑定当前project。 */
        private String secondProjectKey() { return "app_push_" + secondProjectId.toString().replace("-", ""); }
    }
}
