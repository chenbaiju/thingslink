package com.things.link.bootstrap.project.lifecycle;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P0-5b0 / ADR0064：生命周期能力本身以真实APP连接查询及持SHARE锁，停止资格不能只靠无锁快照。
 * owner UPDATE仅构造项目状态提交/回滚顺序，不冒充业务OWNER删除入口的授权验收。
 */
class ProjectLifecycleAccessIntegrationTests extends AbstractIntegrationTest {

    /** 被测公开端口由真实Spring事务代理提供，不能mock状态判断或锁结果。 */
    @Autowired private ProjectLifecycleAccessService lifecycle;
    /** APP连接用于真实角色/PID/SQL范围观察。 */
    @Autowired private JdbcTemplate jdbc;
    /** 写许可MANDATORY要求由调用业务事务持有锁直至提交。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** 合法状态和不一致deleted组合均由真实PG事实驱动，归档只能读。 */
    @ParameterizedTest(name = "{0} deleted={1}：read={2} write={3}")
    @CsvSource({
            "ACTIVE, false, true, true",
            "ARCHIVED, false, true, false",
            "DELETING, false, false, false",
            "DELETING, true, false, false",
            "ACTIVE, true, false, false",
            "ARCHIVED, true, false, false"
    })
    void evaluatesRealProjectStates(String state, boolean deleted, boolean readable, boolean writable) throws Exception {
        Fixture fixture = fixture(state, deleted);
        dataTransaction(false, status -> {
            var policy = lifecycle.snapshot(fixture.tenantId(), fixture.projectId());
            assertThat(policy.readAllowed()).isEqualTo(readable);
            assertThat(policy.writeAllowed()).isEqualTo(writable);
            assertThat(lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId())).isEqualTo(writable);
            return null;
        });
    }

    /** 已存在项目的错误tenant和不存在项目都返回相同拒绝，不能泄露或扩大归属。 */
    @Test
    void rejectsMismatchedTenantAndMissingProject() throws Exception {
        Fixture fixture = fixture("ACTIVE", false);
        dataTransaction(false, status -> {
            var wrongTenant = lifecycle.snapshot(Uuid7.generate(), fixture.projectId());
            var missing = lifecycle.snapshot(fixture.tenantId(), Uuid7.generate());
            assertThat(wrongTenant.readAllowed()).isFalse();
            assertThat(wrongTenant.writeAllowed()).isFalse();
            assertThat(missing.readAllowed()).isFalse();
            assertThat(missing.writeAllowed()).isFalse();
            assertThat(lifecycle.lockActiveForWrite(Uuid7.generate(), fixture.projectId())).isFalse();
            assertThat(lifecycle.lockActiveForWrite(fixture.tenantId(), Uuid7.generate())).isFalse();
            return null;
        });
    }

    /** 无事务不能瞬间释放许可，只读事务不能假装已经保留业务写锁。 */
    @Test
    void requiresAnExistingWritableTransaction() throws Exception {
        Fixture fixture = fixture("ACTIVE", false);
        Throwable noTransaction = catchThrowable(() -> lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId()));
        assertThat(noTransaction).isInstanceOf(IllegalTransactionStateException.class);
        Throwable readOnly = catchThrowable(() -> dataTransaction(true, status ->
                lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId())));
        assertThat(readOnly).isInstanceOf(IllegalStateException.class);
        assertThat(projectRow(fixture)).contains("\"status\":\"ACTIVE\"");
    }


    /** RR与SERIALIZABLE不能保证等待后的成员新快照，必须拒绝且不得改写项目事实或外层隔离。 */
    @ParameterizedTest(name = "隔离级 {0} 拒绝SHARE许可")
    @ValueSource(ints = {TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE})
    void rejectsSnapshotIsolationWithoutChangingProject(int isolation) throws Exception {
        Fixture fixture = fixture("ACTIVE", false);
        String before = projectRow(fixture);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(isolation);
            Throwable failure = catchThrowable(() -> transaction.execute(status -> {
                int actual = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)
                        Connection::getTransactionIsolation);
                assertThat(actual).isEqualTo(isolation);
                lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId());
                return null;
            }));
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            // @Repository会翻译外层异常；根因仍必须是许可端口对实际连接隔离级的精确拒绝。
            assertThat(root).isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("项目SHARE写许可要求READ COMMITTED或READ UNCOMMITTED事务");
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        }
        assertThat(projectRow(fixture)).isEqualTo(before);
    }

    /** ADR0067公开拒绝包装保留同一真实事务，归档分类不向调用域暴露内部枚举。 */
    @ParameterizedTest
    @CsvSource({"ACTIVE, false, 0", "ARCHIVED, false, 50017", "DELETING, true, 50001"})
    void requiredWritePermitUsesRealStateAndPreservesFacts(String state, boolean deleted, int code) throws Exception {
        Fixture fixture = fixture(state, deleted);
        String before = projectRow(fixture);
        Throwable failure = catchThrowable(() -> dataTransaction(false, status -> {
            int pid = backendId();
            lifecycle.requireActiveForWrite(fixture.tenantId(), fixture.projectId());
            assertThat(backendId()).isEqualTo(pid);
            return null;
        }));
        if (code == 0) assertThat(failure).isNull();
        else {
            assertThat(failure).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(code);
        }
        assertThat(projectRow(fixture)).isEqualTo(before);
    }

    /** 包装方法仍为MANDATORY：不能因为内部复用boolean入口就漏掉实际代理事务前置。 */
    @Test
    void requiredWritePermitRejectsMissingReadOnlyAndWrongTenant() throws Exception {
        Fixture fixture = fixture("ACTIVE", false);
        assertThat(catchThrowable(() -> lifecycle.requireActiveForWrite(fixture.tenantId(), fixture.projectId())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> dataTransaction(true, status -> {
            lifecycle.requireActiveForWrite(fixture.tenantId(), fixture.projectId());
            return null;
        }))).isInstanceOf(IllegalStateException.class);
        Throwable wrongTenant = catchThrowable(() -> dataTransaction(false, status -> {
            lifecycle.requireActiveForWrite(Uuid7.generate(), fixture.projectId());
            return null;
        }));
        assertThat(wrongTenant).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) wrongTenant).errorCode().code()).isEqualTo(50001);
    }

    /** 无HTTP身份的可信二元组可查，且既有不同SQL范围不能被端口偷偷覆盖或清空。 */
    @Test
    void trustsExplicitPairWithoutLeakingOrReplacingSqlScope() throws Exception {
        Fixture fixture = fixture("ACTIVE", false);
        Fixture other = fixture("ACTIVE", false);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            var policy = lifecycle.snapshot(fixture.tenantId(), fixture.projectId());
            assertThat(policy.readAllowed()).isTrue();
            assertThat(policy.writeAllowed()).isTrue();
        }
        dataTransaction(false, status -> {
            assertSqlScope("", "");
            jdbc.queryForMap("SELECT set_config('app.tenant_id', ?, true), set_config('app.project_id', ?, true)",
                    other.tenantId().toString(), other.projectId().toString());
            assertThat(lifecycle.snapshot(fixture.tenantId(), fixture.projectId()).readAllowed()).isTrue();
            assertThat(lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId())).isTrue();
            assertSqlScope(other.tenantId().toString(), other.projectId().toString());
            assertThat(TenantContext.current()).isEmpty();
            assertThat(RlsScopeContext.current()).isEmpty();
            return null;
        });
        dataTransaction(false, status -> {
            assertSqlScope("", "");
            return null;
        });
    }

    /** APP许可先持有SHARE，删除状态UPDATE必须等待业务事务提交，不能提前冻结。 */
    @Test
    void grantedShareBlocksDeletionUntilBusinessTransactionCommits() throws Exception {
        Fixture fixture = fixture("ACTIVE", false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> deleting = dataTransaction(false, status -> {
                assertThat(lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId())).isTrue();
                int holderPid = backendId();
                CompletableFuture<Integer> deletingPid = new CompletableFuture<>();
                Future<?> update = executor.submit(() -> {
                    try (Connection owner = ownerConnection()) {
                        owner.setAutoCommit(false);
                        deletingPid.complete(backendId(owner));
                        updateDeleting(owner, fixture);
                        owner.commit();
                    } catch (Exception exception) {
                        deletingPid.completeExceptionally(exception);
                        throw new IllegalStateException(exception);
                    }
                });
                try {
                    assertBlockedBy(deletingPid.get(5, TimeUnit.SECONDS), holderPid, update);
                    assertThat(projectRow(fixture)).contains("\"status\":\"ACTIVE\"");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return update;
            });
            deleting.get(10, TimeUnit.SECONDS);
            assertThat(projectRow(fixture)).contains("\"status\":\"DELETING\"");
            dataTransaction(false, status -> {
                assertThat(lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId())).isFalse();
                return null;
            });
        } finally {
            stop(executor);
        }
    }

    /** IAM签发取得账号约束的项目代次及SHARE后，删除必须等待签发事务提交。 */
    @Test
    void projectTokenGenerationLockBlocksDeletionUntilIssuanceTransactionCommits() throws Exception {
        MemberFixture fixture = memberFixture();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> deleting = dataTransaction(false, status -> {
                OptionalLong generation = lifecycle.lockActiveGenerationForProjectToken(
                        fixture.accountId(), fixture.project().projectId());
                assertThat(generation).hasValue(0L);
                int holderPid = backendId();
                CompletableFuture<Integer> deletingPid = new CompletableFuture<>();
                Future<?> update = executor.submit(() -> {
                    try (Connection owner = ownerConnection()) {
                        owner.setAutoCommit(false);
                        deletingPid.complete(backendId(owner));
                        updateDeletingAndAdvanceGeneration(owner, fixture.project());
                        owner.commit();
                    } catch (Exception exception) {
                        deletingPid.completeExceptionally(exception);
                        throw new IllegalStateException(exception);
                    }
                });
                try {
                    assertBlockedBy(deletingPid.get(5, TimeUnit.SECONDS), holderPid, update);
                    assertThat(projectRow(fixture.project())).contains("\"status\":\"ACTIVE\"");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return update;
            });
            deleting.get(10, TimeUnit.SECONDS);
            assertThat(projectRow(fixture.project())).contains("\"status\":\"DELETING\"");
            assertThat(projectRow(fixture.project())).contains("\"lifecycle_generation\":1");
        } finally {
            stop(executor);
        }
    }

    /** 删除先持有项目锁时，许可不能读旧ACTIVE放行；提交后重新判断并返回false。 */
    @Test
    void deletionFirstMakesWaitingPermitRejectAfterCommit() throws Exception {
        assertDeletionOutcome(true);
    }

    /** 相同真实等待若删除回滚，则旧ACTIVE仍有效，许可应恢复成功而非永久锁死。 */
    @Test
    void deletionRollbackReleasesLockAndAllowsWaitingPermit() throws Exception {
        assertDeletionOutcome(false);
    }

    /** 使用现有JDBC预算取消真实行锁等待，不设置被测事务超时，也不将SQL故障伪装成false。 */
    @Test
    void lockTimeoutPropagatesAndLeavesProjectUnchangedThenRecovers() throws Exception {
        assertThat(jdbc.getQueryTimeout()).as("真实bootstrap/test配置沿用5秒JDBC语句预算").isEqualTo(5);
        Fixture fixture = fixture("ACTIVE", false);
        String before = projectRow(fixture);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                try (PreparedStatement query = holder.prepareStatement("SELECT id FROM sys_project WHERE id = ? FOR UPDATE")) {
                    query.setObject(1, fixture.projectId());
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
                Future<Boolean> waiting = submitPermit(executor, fixture, waitingPid);
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), backendId(holder), waiting);
                Throwable failure = catchThrowable(() -> waiting.get(12, TimeUnit.SECONDS));
                assertThat(isTimeout(failure)).as("必须是真实SQL/事务预算超时，实际异常=%s", failure).isTrue();
                assertThat(projectRow(fixture)).isEqualTo(before);
                holder.rollback();
                boolean recovered = dataTransaction(false, status -> lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId()));
                assertThat(recovered).isTrue();
                assertThat(projectRow(fixture)).isEqualTo(before);
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 删除提交与回滚复用同一阻塞图前置，区别仅在真实事务最终结果。 */
    private void assertDeletionOutcome(boolean commit) throws Exception {
        Fixture fixture = fixture("ACTIVE", false);
        String before = projectRow(fixture);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try {
                updateDeleting(owner, fixture);
                CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
                Future<Boolean> waiting = submitPermit(executor, fixture, waitingPid);
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), backendId(owner), waiting);
                if (commit) owner.commit();
                else owner.rollback();
                assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(!commit);
                if (commit) assertThat(projectRow(fixture)).contains("\"status\":\"DELETING\"");
                else assertThat(projectRow(fixture)).isEqualTo(before);
            } finally {
                owner.rollback();
                stop(executor);
            }
        }
    }

    /** APP等待者公布其真实事务PID，许可持锁直到此业务事务结束。 */
    private Future<Boolean> submitPermit(ExecutorService executor, Fixture fixture, CompletableFuture<Integer> pid) {
        return executor.submit(() -> {
            try {
                return dataTransaction(false, status -> {
                    pid.complete(backendId());
                    return lifecycle.lockActiveForWrite(fixture.tenantId(), fixture.projectId());
                });
            } catch (RuntimeException | Error failure) {
                pid.completeExceptionally(failure);
                throw failure;
            }
        });
    }

    /** 显式READ COMMITTED并验证真实APP角色，防止owner绕过或快照隔离改变等待后重判合同。 */
    private <T> T dataTransaction(boolean readOnly, Function<TransactionStatus, T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setReadOnly(readOnly);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return transaction.execute(status -> {
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                assertThat(backendId()).isPositive();
                return action.apply(status);
            });
        }
    }

    /** 每例最小合法租户/项目，能力级测试不构造不存在的账号OWNER或生产删除审计。 */
    private Fixture fixture(String state, boolean deleted) throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate());
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try (PreparedStatement tenant = owner.prepareStatement("INSERT INTO sys_tenant(id,name) VALUES (?, '项目许可租户')")) {
                tenant.setObject(1, fixture.tenantId());
                tenant.executeUpdate();
            }
            try (PreparedStatement project = owner.prepareStatement("""
                    INSERT INTO sys_project(id,tenant_id,name,project_key,status,deleted_at)
                    VALUES (?, ?, '项目许可夹具', ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
                    """)) {
                project.setObject(1, fixture.projectId());
                project.setObject(2, fixture.tenantId());
                project.setString(3, "lifecycle_" + fixture.projectId().toString().replace("-", ""));
                project.setString(4, state);
                project.setBoolean(5, deleted);
                project.executeUpdate();
            }
            owner.commit();
        }
        return fixture;
    }

    /** 构造真实ACTIVE成员，使凭据签发锁不能依靠owner连接或伪造账号绕过成员条件。 */
    private MemberFixture memberFixture() throws SQLException {
        Fixture project = fixture("ACTIVE", false);
        UUID accountId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try (PreparedStatement account = owner.prepareStatement("""
                    INSERT INTO sys_account(id,email,password_hash,display_name)
                    VALUES (?, ?, '{noop}unused', 'IAM签发锁账号')
                    """)) {
                account.setObject(1, accountId);
                account.setString(2, accountId + "@example.com");
                account.executeUpdate();
            }
            try (PreparedStatement tenantMember = owner.prepareStatement("""
                    INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)
                    """)) {
                tenantMember.setObject(1, Uuid7.generate());
                tenantMember.setObject(2, project.tenantId());
                tenantMember.setObject(3, accountId);
                tenantMember.executeUpdate();
            }
            try (PreparedStatement projectMember = owner.prepareStatement("""
                    INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')
                    """)) {
                projectMember.setObject(1, Uuid7.generate());
                projectMember.setObject(2, project.projectId());
                projectMember.setObject(3, accountId);
                projectMember.executeUpdate();
            }
            owner.commit();
        }
        return new MemberFixture(accountId, project);
    }

    /** 能力级以实际UPDATE持项目排他锁，不声称已执行ProjectService.delete的OWNER授权。 */
    private void updateDeleting(Connection owner, Fixture fixture) throws SQLException {
        try (PreparedStatement update = owner.prepareStatement("UPDATE sys_project SET status = 'DELETING', deleted_at = now() WHERE id = ?")) {
            update.setObject(1, fixture.projectId());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    /** IAM场景同步推进删除代次，证明SHARE同时保护状态冻结与旧凭据永久失效事实。 */
    private void updateDeletingAndAdvanceGeneration(Connection owner, Fixture fixture) throws SQLException {
        try (PreparedStatement update = owner.prepareStatement("""
                UPDATE sys_project
                   SET status = 'DELETING', deleted_at = now(), lifecycle_generation = lifecycle_generation + 1
                 WHERE id = ?
                """)) {
            update.setObject(1, fixture.projectId());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    /** 独立observer证明真实等待及三个不同PID；不用sleep猜测线程时序。 */
    private void assertBlockedBy(int waitingPid, int holderPid, Future<?> operation) throws Exception {
        assertThat(waitingPid).isNotEqualTo(holderPid);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            query.setInt(1, holderPid);
            query.setInt(2, waitingPid);
            query.setInt(3, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(holderPid);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) {
                    throw new AssertionError("操作未等待指定项目锁便已结束");
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到项目锁等待");
    }

    /** 项目行完整快照用于证明失败及回滚没有留下生命周期半更新。 */
    private String projectRow(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT row_to_json(p)::text FROM sys_project p WHERE id = ?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** 当前APP事务PID。 */
    private int backendId() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** owner及observer连接PID与APP事务分别观测。 */
    private int backendId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()"); ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    /** 只接受真实SQL取消或Spring预算映射，不将任意业务异常视为超时。 */
    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransactionTimedOutException) return true;
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 端口不得在项目豁免RLS的情况下仍改写进入连接的范围。 */
    private void assertSqlScope(String tenant, String project) {
        assertThat(jdbc.queryForObject("SELECT COALESCE(current_setting('app.tenant_id',true),'')", String.class)).isEqualTo(tenant);
        assertThat(jdbc.queryForObject("SELECT COALESCE(current_setting('app.project_id',true),'')", String.class)).isEqualTo(project);
    }

    /** owner只构造状态或旁观已提交事实，许可永远由APP调用。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 有界回收，调用前已释放持锁事务，避免测试失败后遗留工作线程。 */
    private void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    /** 所有线程入口无HTTP身份，测试结束仍显式清除当前线程上下文。 */
    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        RlsScopeContext.clear();
    }

    /** @param tenantId 项目实际租户 @param projectId 项目隔离轴 */
    private record Fixture(UUID tenantId, UUID projectId) { }

    /** @param accountId 真实ACTIVE项目成员 @param project 成员可签发凭据的项目 */
    private record MemberFixture(UUID accountId, Fixture project) { }
}
