package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.DeviceClaimService;
import com.things.link.enduser.application.DeviceTransferService;
import com.things.link.enduser.application.DeviceTransferIssuedToken;
import com.things.link.enduser.application.DeviceTransferResult;
import com.things.link.enduser.application.DeviceShareService;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
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
import static org.mockito.Mockito.doAnswer;

/**
 * ADR0064决策4及S12-P0-5c2b3：TRANSFER签发、消费与幂等回读必须在原事务取得项目写许可。
 * 真实PG、App RLS、OWNER删除和审计共同证明锁序；HTTP转移IP限流不参与本例业务验收。
 */
class AppTransferProjectLifecycleTests extends AbstractIntegrationTest {

    /** 身份种子只保存编码后的口令，不把服务层验收冒充登录API验收。 */
    private static final String PASSWORD = "secret123";
    /** PRIMARY夹具由真实CLAIM形成，不绕过唯一主控仲裁。 */
    @Autowired private DeviceClaimService claims;
    /** 被测入口必须调用原生产代理，保留consume的noRollbackFor合同。 */
    @Autowired private DeviceTransferService transfers;
    /** 既有MEMBER/READ_ONLY接收者由真实SHARE形成，验证TRANSFER原位提升。 */
    @Autowired private DeviceShareService shares;
    /** 真实OWNER删除负责冻结，不能只把deleted_at改成种子状态。 */
    @Autowired private ProjectService projectService;
    /** 观察真实APP事务与五秒查询预算。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 仅为OWNER删除控制提交，不包裹TRANSFER业务事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 用户种子沿用S11口令格式。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 许可前观察PID但继续执行原SQL，不模拟成功或拒绝。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实审计写入后设置屏障或故障，检验三类事实同事务提交/回滚。 */
    @MockitoSpyBean private AuditLogService audits;
    /** 随机身份隔离本例RLS关系及清理范围，不涉及全局扫描。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** TRANSFER明文仅在内存保留，数据库保存哈希。 */
    private String plaintext;
    /** 旧PRIMARY的数据库原身份和首次创建时间，转移降级不得替换或重写。 */
    private BindingIdentity previousPrimary;

    /** 两名ACTIVE App用户和真实CLAIM主控是TRANSFER最小前置，既有共享关系仅由对应成功场景补充。 */
    @BeforeEach
    void prepare() throws SQLException {
        seedFixture();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        String claim;
        try { claim = claims.issue(fixture.projectId(), fixture.deviceId()).token(); }
        finally { TenantContext.clear(); }
        scoped(() -> claims.consume(fixture.tenantId(), fixture.projectId(), fixture.userId(), claim));
        previousPrimary = bindingIdentity(fixture.userId());
        assertPrimary();
    }

    /** 未转移用户新建PRIMARY，MEMBER/READ_ONLY原位提升；旧主控原位降为MEMBER，重放保留所有事实。 */
    @ParameterizedTest
    @EnumSource(ReceiverState.class)
    void activeTransferPromotesReceiverAndPreservesBothBindingIdentities(ReceiverState state) throws Exception {
        BindingIdentity receiverBefore = null;
        if (state != ReceiverState.NONE) {
            String shared = scoped(() -> shares.issue(fixture.tenantId(), fixture.projectId(), fixture.userId(),
                    fixture.deviceId(), AppUserDevice.RelationRole.valueOf(state.name()))).token();
            scoped(() -> shares.consume(fixture.tenantId(), fixture.projectId(), fixture.recipientId(), shared));
            receiverBefore = bindingIdentity(fixture.recipientId());
        }
        plaintext = issue(fixture.tenantId()).token();
        assertIssued();
        DeviceTransferResult first = consume(fixture.tenantId());
        assertTransferred();
        BindingIdentity receiverAfter = bindingIdentity(fixture.recipientId());
        if (receiverBefore != null) assertThat(receiverAfter).isEqualTo(receiverBefore);
        assertThat(first.bindingId()).isEqualTo(receiverAfter.id());
        assertThat(first.relationRole()).isEqualTo(AppUserDevice.RelationRole.PRIMARY);
        List<String> before = facts();
        DeviceTransferResult replay = consume(fixture.tenantId());
        assertThat(replay.bindingId()).isEqualTo(first.bindingId());
        assertThat(replay.deviceId()).isEqualTo(first.deviceId());
        assertThat(replay.relationRole()).isEqualTo(first.relationRole());
        // 首响应Java可带纳秒，幂等回读按数据库微秒身份核对，不误把精度转换视为改写。
        assertThat(replay.createdAt()).isEqualTo(receiverAfter.createdAt());
        assertThat(facts()).isEqualTo(before);
    }

    /** 拒绝须早于token新增/attempt计数/关系与转移审计；保留原BusinessException而非事务包装错误。 */
    @ParameterizedTest(name = "{0}在{1}下拒绝且转移事实不变")
    @CsvSource({"ISSUE,ARCHIVED", "ISSUE,DELETED", "ISSUE,WRONG_TENANT",
            "CONSUME,ARCHIVED", "CONSUME,DELETED", "CONSUME,WRONG_TENANT"})
    void lifecycleDenialPrecedesAllTransferEffects(Operation operation, Denial denial) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        applyDenial(denial);
        UUID tenantId = denial == Denial.WRONG_TENANT ? Uuid7.generate() : fixture.tenantId();
        assertFailure(catchThrowable(() -> invoke(operation, tenantId)), denial == Denial.ARCHIVED ? 60022 : 60009);
        assertThat(facts()).isEqualTo(before);
        assertPrimary();
    }

    /** 令牌已消费仍须先检查生命周期，不能用幂等分支返回冻结项目的既有转移授权。 */
    @ParameterizedTest
    @EnumSource(Denial.class)
    void consumedTransferReplayCannotBypassLifecycle(Denial denial) throws Exception {
        prepareOperation(Operation.CONSUME);
        consume(fixture.tenantId());
        List<String> before = facts();
        applyDenial(denial);
        UUID tenantId = denial == Denial.WRONG_TENANT ? Uuid7.generate() : fixture.tenantId();
        assertFailure(catchThrowable(() -> consume(tenantId)), denial == Denial.ARCHIVED ? 60022 : 60009);
        assertThat(facts()).isEqualTo(before);
        assertTransferred();
    }

    /** ACTIVE过期令牌继续提交有效哈希复核次数，新增门禁不能改变原noRollbackFor合同。 */
    @Test
    void expiredTransferStillCommitsAttemptWithoutConsumption() throws Exception {
        prepareOperation(Operation.CONSUME);
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    UPDATE app_device_bind_token SET created_at = clock_timestamp() - interval '20 minutes',
                           expires_at = clock_timestamp() - interval '10 minutes' WHERE project_id = ? AND purpose = 'TRANSFER'
                    """, fixture.projectId());
        }
        assertFailure(catchThrowable(() -> consume(fixture.tenantId())), 60016);
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND purpose = 'TRANSFER' AND attempt_count = 1 AND consumed_at IS NULL AND consumed_by_app_user_id IS NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND app_user_id = ?", fixture.projectId(), fixture.recipientId())).isZero();
            assertThat(count(owner, "SELECT count(*) FROM sys_audit_log WHERE project_id = ? AND action = 'enduser.device.transfer.completed'", fixture.projectId())).isZero();
        }
        assertPrimary();
    }

    /** 真实转移关系、consume CAS和completed审计都执行后，内部异常仍须一次回滚全部事实。 */
    @ParameterizedTest
    @EnumSource(ReceiverState.class)
    void internalFailureAfterRealCompletionRollsBackTokenBindingAndAudit(ReceiverState state) throws Exception {
        // 新建和两种原位提升均须证明失败恢复原角色，不能把成功分支外推为回滚证据。
        if (state != ReceiverState.NONE) {
            String shared = scoped(() -> shares.issue(fixture.tenantId(), fixture.projectId(), fixture.userId(),
                    fixture.deviceId(), AppUserDevice.RelationRole.valueOf(state.name()))).token();
            scoped(() -> shares.consume(fixture.tenantId(), fixture.projectId(), fixture.recipientId(), shared));
        }
        prepareOperation(Operation.CONSUME);
        List<String> before = facts();
        AuditLogService target = AopTestUtils.getUltimateTargetObject(audits);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0);
            if (fixture.projectId().equals(entry.projectId()) && "enduser.device.transfer.completed".equals(entry.action())) {
                assertRealWrite(Operation.CONSUME);
                throw new IllegalStateException("转移完成后的受控内部故障");
            }
            return null;
        }).when(target).record(any(AuditLogEntry.class));
        assertThat(catchThrowable(() -> consume(fixture.tenantId())))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("转移完成后的受控内部故障");
        assertThat(facts()).isEqualTo(before);
        assertIssued();
    }
    /** TRANSFER及对应审计真实写入但未提交，OWNER删除被同一业务事务项目SHARE许可阻挡。 */
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

    /** 删除先更新未提交时App许可真实等待；提交后拒绝原业务异常，TRANSFER token/关系/审计不能改变。 */
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

    /** 两入口均沿原5秒JDBC预算取消真实项目锁SQL，不能伪装业务失效；解锁后原签发/消费可恢复。 */
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

    /** 签发场景从零TRANSFER开始；消费场景只由真实PRIMARY预签发一枚未消费TRANSFER。 */
    private void prepareOperation(Operation operation) {
        if (operation == Operation.CONSUME) plaintext = issue(fixture.tenantId()).token();
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

    /** 签发者身份保持PRIMARY App用户，不借用控制台OWNER身份。 */
    private DeviceTransferIssuedToken issue(UUID tenantId) {
        return scoped(() -> transfers.issue(tenantId, fixture.projectId(), fixture.userId(), fixture.deviceId()));
    }

    /** 接收者拥有独立ACTIVE App角色，原服务代理独立开启consume事务。 */
    private DeviceTransferResult consume(UUID tenantId) {
        return scoped(() -> transfers.consume(tenantId, fixture.projectId(), fixture.recipientId(), plaintext));
    }

    /** 参数化操作不建立额外事务，签发与消费各自沿原合同提交。 */
    private void invoke(Operation operation, UUID tenantId) {
        if (operation == Operation.ISSUE) plaintext = issue(tenantId).token();
        else consume(tenantId);
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

    /** 先执行真实审计，再暂停原事务；旁观连接此时仍只能看到提交前事实。 */
    private void pauseAfterRealWrite(Operation operation, CompletableFuture<Integer> pid,
                                     CountDownLatch written, CountDownLatch release) {
        AuditLogService target = AopTestUtils.getUltimateTargetObject(audits);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0);
            String action = operation == Operation.ISSUE ? "enduser.device.transfer.issued" : "enduser.device.transfer.completed";
            if (fixture.projectId().equals(entry.projectId()) && action.equals(entry.action())) {
                pid.complete(appPid());
                assertRealWrite(operation);
                written.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(target).record(any(AuditLogEntry.class));
    }

    /** token、关系和审计已在同一真实APP事务可见，不以仅执行到方法入口冒充写入。 */
    private void assertRealWrite(Operation operation) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isPresent();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        String consumed = operation == Operation.CONSUME ? "IS NOT NULL" : "IS NULL";
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND purpose = 'TRANSFER' AND consumed_at " + consumed,
                Integer.class, fixture.projectId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user_device WHERE project_id = ? AND app_user_id = ? AND status = 'ACTIVE'",
                Integer.class, fixture.projectId(), fixture.recipientId())).isEqualTo(operation == Operation.CONSUME ? 1 : 0);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user_device WHERE id = ? AND app_user_id = ? AND status = 'ACTIVE' AND relation_role = ? AND created_at = ?",
                Integer.class, previousPrimary.id(), fixture.userId(), operation == Operation.ISSUE ? "PRIMARY" : "MEMBER",
                java.sql.Timestamp.from(previousPrimary.createdAt()))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user_device WHERE project_id = ? AND device_id = ? AND status = 'ACTIVE' AND relation_role = 'PRIMARY'",
                Integer.class, fixture.projectId(), fixture.deviceId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id = ? AND action = ?", Integer.class,
                fixture.projectId(), operation == Operation.ISSUE ? "enduser.device.transfer.issued" : "enduser.device.transfer.completed")).isEqualTo(1);
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

    /** OWNER删除仅冻结项目；两名App用户仍有ACTIVE角色，拒绝不能依赖角色被清除。 */
    private void assertDeleted() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_role WHERE project_id = ? AND status = 'ACTIVE'", fixture.projectId())).isEqualTo(2);
        }
    }

    /** PRIMARY是转移仲裁基础，生命周期拒绝不删除或改写此事实。 */
    private void assertPrimary() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND status = 'ACTIVE' AND relation_role = 'PRIMARY'", fixture.projectId(), fixture.userId(), fixture.deviceId())).isEqualTo(1);
        }
    }

    /** 新TRANSFER与签发审计同提交；接收者既有关系不影响签发，但原PRIMARY仍须保持。 */
    private void assertIssued() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND purpose = 'TRANSFER' AND target_role = 'PRIMARY' AND issued_by_app_user_id = ? AND attempt_count = 0 AND consumed_at IS NULL AND consumed_by_app_user_id IS NULL", fixture.projectId(), fixture.userId())).isEqualTo(1);
            assertThat(count(owner, """
                    SELECT count(*) FROM sys_audit_log a JOIN app_device_bind_token t ON t.id = a.target_id
                     WHERE a.project_id = ? AND a.action = 'enduser.device.transfer.issued' AND a.target_type = 'app_device_bind_token'
                       AND a.actor_account_id IS NULL AND a.details->>'actorType' = 'APP_USER'
                       AND a.details->>'actorId' = ? AND a.details->>'deviceId' = ?
                       AND (a.details->>'expiresAt')::timestamptz = t.expires_at
                    """, fixture.projectId(), fixture.userId().toString(), fixture.deviceId().toString())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM sys_audit_log WHERE project_id = ? AND action = 'enduser.device.transfer.completed'", fixture.projectId())).isZero();
        }
        assertPrimary();
    }

    /** 降旧/升新/消费与审计是一组事实；旧关系ID及created_at保留且全设备只有一个有效PRIMARY。 */
    private void assertTransferred() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_device_bind_token WHERE project_id = ? AND purpose = 'TRANSFER' AND target_role = 'PRIMARY' AND issued_by_app_user_id = ? AND attempt_count = 1 AND consumed_at IS NOT NULL AND consumed_by_app_user_id = ?", fixture.projectId(), fixture.userId(), fixture.recipientId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND status = 'ACTIVE' AND relation_role = 'PRIMARY'", fixture.projectId(), fixture.recipientId(), fixture.deviceId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND device_id = ? AND status = 'ACTIVE' AND relation_role = 'PRIMARY'", fixture.projectId(), fixture.deviceId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE id = ? AND app_user_id = ? AND status = 'ACTIVE' AND relation_role = 'MEMBER' AND created_at = ?", previousPrimary.id(), fixture.userId(), java.sql.Timestamp.from(previousPrimary.createdAt()))).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND device_id = ?", fixture.projectId(), fixture.deviceId())).isEqualTo(2);
            assertThat(count(owner, """
                    SELECT count(*) FROM sys_audit_log a JOIN app_user_device b ON b.id = a.target_id
                     WHERE a.project_id = ? AND a.target_type = 'app_user_device' AND a.action = 'enduser.device.transfer.completed'
                       AND a.actor_account_id IS NULL AND a.details->>'actorType' = 'APP_USER'
                       AND a.details->>'actorId' = ? AND a.details->>'deviceId' = ?
                       AND a.details->>'previousPrimaryAppUserId' = ? AND a.details->>'previousPrimaryBindingId' = ?
                       AND a.details->>'newPrimaryAppUserId' = ? AND a.details->>'newPrimaryBindingId' = b.id::text
                       AND a.details->>'previousPrimaryRole' = 'MEMBER'
                    """, fixture.projectId(), fixture.recipientId().toString(), fixture.deviceId().toString(),
                    fixture.userId().toString(), previousPrimary.id().toString(), fixture.recipientId().toString())).isEqualTo(1);
        }
    }

    /** 以真实PG微秒值记录关系身份，避免Java首响应的纳秒精度干扰原位更新验证。 */
    private BindingIdentity bindingIdentity(UUID userId) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT id, created_at FROM app_user_device WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND status = 'ACTIVE'")) {
            query.setObject(1, fixture.projectId());
            query.setObject(2, userId);
            query.setObject(3, fixture.deviceId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                BindingIdentity result = new BindingIdentity(rows.getObject("id", UUID.class), rows.getTimestamp("created_at").toInstant());
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 两入口分别验证其完整持久事实，签发不误用消费的断言。 */
    private void assertSuccess(Operation operation) throws SQLException {
        if (operation == Operation.ISSUE) assertIssued();
        else assertTransferred();
    }

    /** 完整token含attempt/consumed/时间/哈希，绑定及角色全行；比较全部转移审计，不混入项目管理事件。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("app_device_bind_token", "app_user_device", "app_user_role", "sys_audit_log")) {
                String audit = table.equals("sys_audit_log") ? " AND action IN ('enduser.device.transfer.issued', 'enduser.device.transfer.completed', 'enduser.device.share.issued', 'enduser.device.share.completed')" : "";
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

    /** 原位提升必须分别证明两种既有共享角色，NONE证明新建接收者分支。 */
    private enum ReceiverState {
        /** 尚无设备关系。 */ NONE,
        /** 已有可操作共享关系。 */ MEMBER,
        /** 已有只读共享关系。 */ READ_ONLY
    }

    /** @param id 关系原标识 @param createdAt 关系第一次创建的数据库时间 */
    private record BindingIdentity(UUID id, Instant createdAt) { }

    /** 转移两个公开入口必须分别获得许可，不能互相代替验收。 */
    private enum Operation {
        /** 原事务新建TRANSFER及签发审计。 */ ISSUE,
        /** 保留业务拒绝计数合同的转移消费。 */ CONSUME
    }

    /** 项目拒绝对应原App错误码，错tenant仍使用正确RLS范围以证明显式身份检查。 */
    private enum Denial {
        /** 项目只读。 */ ARCHIVED,
        /** OWNER真实冻结。 */ DELETED,
        /** JWT参数租户与项目不符。 */ WRONG_TENANT
    }

    /** 最小种子含合法OWNER、App角色及DIRECT设备，PRIMARY关系由真实CLAIM形成。 */
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
            execute(owner, "INSERT INTO app_user (id, tenant_id, username, password_hash, status) VALUES (?, ?, 'transfer_recipient', ?, 'ACTIVE')",
                    fixture.recipientId(), fixture.tenantId(), passwordEncoder.encode(PASSWORD));
            execute(owner, "INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status) VALUES (?, ?, ?, ?, 'MAINTAINER', 'ACTIVE')",
                    Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.recipientId());
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
            execute(owner, "DELETE FROM app_user WHERE id IN (?, ?)", fixture.userId(), fixture.recipientId());
            execute(owner, "DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", fixture.tenantId(), fixture.accountId());
            execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
            execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.commit();
        }
    }

    /** owner只用于种子、独立观察和清理，转移与删除始终通过真实APP角色与业务服务。 */
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

    /** @param tenantId 租户 @param projectId 项目 @param accountId 真实OWNER @param userId PRIMARY用户 @param recipientId 转移接收者 @param typeId 类型 @param deviceId 未绑定DIRECT设备 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID userId, UUID recipientId, UUID typeId, UUID deviceId) {
        /** PRIMARY夹具经真实OWNER签发CLAIM时使用独占项目路由键。 */
        private String projectKey() { return "app_transfer_" + projectId.toString().replace("-", ""); }
    }
}
