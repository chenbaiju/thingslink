package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusPollService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * S12-P0-5b1 / ADR0064：轮询新请求和重试必须在原事务持有项目许可，删除后只能维护暂停。
 * 所有删除排序走真实OWNER ProjectService.delete；超时持锁只构造阻塞，未声称取消已提交外部消息。
 */
class ModbusPollProjectLifecycleTests extends AbstractModbusPollIntegrationTest {

    /** 实际扫描及重试的公开事务入口。 */
    @Autowired private ModbusPollService pollService;
    /** 真实OWNER删除保留授权和项目状态更新合同。 */
    @Autowired private ProjectService projectService;
    /** 只观察独立poll事务的真实append，不替换SQL或制造模拟成功。 */
    @MockitoSpyBean private JdbcTransactionalOutboxRepository outboxRepository;
    /** 只在真实许可SQL执行前公布当前独立poll事务PID，不替换许可结果。 */
    @MockitoSpyBean private ProjectLifecycleAccessService projectLifecycle;

    /** 删除先提交，原已物化ONLINE网关只能暂停，不得产生新的请求Outbox。 */
    @Test
    void committedOwnerDeletionPausesDuePollingAndPreservesOldConfiguration() throws Exception {
        OwnedFixture owned = ownedSchedule();
        Fixture fixture = owned.fixture();
        List<String> before = outboxRows(fixture);
        delete(owned);
        assertDeleted(fixture);
        assertThat(dataCall(() -> pollService.scanDue(100))).isZero();
        assertPaused(fixture);
        assertThat(outboxRows(fixture)).isEqualTo(before);
        assertRequestCount(fixture, 0);
    }

    /** 已发送请求的超时重试遇到删除也必须暂停，原请求和配置意图继续保留。 */
    @Test
    void committedOwnerDeletionStopsExpiredRetryWithoutRetractingOldRequests() throws Exception {
        OwnedFixture owned = ownedSchedule();
        Fixture fixture = owned.fixture();
        assertThat(dataCall(() -> pollService.scanDue(100))).isEqualTo(1);
        expire(fixture, 2);
        List<String> before = outboxRows(fixture);
        delete(owned);
        assertThat(dataCall(() -> pollService.scanTimeout(100))).isEqualTo(1);
        assertPaused(fixture);
        assertThat(outboxRows(fixture)).isEqualTo(before);
        assertRequestCount(fixture, 1);
    }

    /** 同次扫描中归档项目被暂停，另一租户ACTIVE项目仍可独立提交，不能因单项目拒绝停止后续。 */
    @Test
    void archivedProjectPausesWhileAnotherActiveProjectSends() throws Exception {
        Fixture archived = ownedSchedule().fixture();
        Fixture active = ownedSchedule().fixture();
        List<String> archivedBefore = outboxRows(archived);
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", archived.projectId())).isEqualTo(1);
        }
        assertThat(dataCall(() -> pollService.scanDue(100))).isEqualTo(1);
        assertPaused(archived);
        assertThat(outboxRows(archived)).isEqualTo(archivedBefore);
        assertRequestCount(active, 1);
        assertInFlight(active, 1);
    }

    /** 扫描先持许可，真实删除必须等扫描事务及请求Outbox一起提交后才可提交冻结。 */
    @Test
    void scanningFirstHoldsPermissionUntilCommitAndBlocksRealDeletion() throws Exception {
        OwnedFixture owned = ownedSchedule();
        Fixture fixture = owned.fixture();
        CompletableFuture<Integer> scanningPid = new CompletableFuture<>();
        CountDownLatch releaseScan = new CountDownLatch(1);
        pauseAfterRequestAppend(fixture, scanningPid, releaseScan);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> scanning = submitScan(executor, false);
            int innerPollPid = scanningPid.get(5, TimeUnit.SECONDS);
            CompletableFuture<Integer> deletePid = new CompletableFuture<>();
            Future<?> deleting = executor.submit(() -> ownerTransaction(owned, () -> {
                deletePid.complete(backendId());
                projectService.delete(fixture.projectId());
                return null;
            }));
            assertBlockedBy(deletePid.get(5, TimeUnit.SECONDS), innerPollPid, deleting);
            assertRequestCount(fixture, 0);
            try (Connection owner = ownerConnection()) {
                assertThat(number(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL",
                        fixture.projectId())).isEqualTo(1);
            }
            releaseScan.countDown();
            assertThat(scanning.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            deleting.get(10, TimeUnit.SECONDS);
            assertDeleted(fixture);
            assertRequestCount(fixture, 1);
        } finally {
            releaseScan.countDown();
            stop(executor);
        }
    }

    /** 删除事务先更新项目但未提交，真实扫描必须等锁并在提交后重新判断为暂停。 */
    @Test
    void deletionFirstMakesWaitingScanPauseAfterCommit() throws Exception {
        OwnedFixture owned = ownedSchedule();
        Fixture fixture = owned.fixture();
        List<String> before = outboxRows(fixture);
        CompletableFuture<Integer> scanPid = capturePermitPid(fixture);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> scanning = ownerTransaction(owned, () -> {
                projectService.delete(fixture.projectId());
                int deletingPid = backendId();
                Future<Integer> operation = submitScan(executor, false);
                try {
                    assertBlockedBy(scanPid.get(5, TimeUnit.SECONDS), deletingPid, operation);
                    assertThat(outboxRows(fixture)).isEqualTo(before);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                return operation;
            });
            assertThat(scanning.get(10, TimeUnit.SECONDS)).isZero();
            assertDeleted(fixture);
            assertPaused(fixture);
            assertThat(outboxRows(fixture)).isEqualTo(before);
        } finally {
            stop(executor);
        }
    }

    /** 后一poll许可超时只回滚自身；此前独立poll已提交，解锁后只需恢复当前poll。 */
    @ParameterizedTest(name = "retry={0} 的当前poll许可超时独立回滚")
    @ValueSource(booleans = {false, true})
    void projectLockTimeoutRollsBackCurrentPollAndPreservesEarlierCommit(boolean retry) throws Exception {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
        Fixture first = ownedSchedule().fixture();
        Fixture blocked = ownedSchedule().fixture();
        if (retry) {
            assertThat(dataCall(() -> pollService.scanDue(100))).isEqualTo(2);
            expire(first, 3);
            expire(blocked, 2);
        } else {
            try (Connection owner = ownerConnection()) {
                execute(owner, "UPDATE dev_modbus_poll SET next_poll_at = now() - interval '2 minutes' WHERE project_id = ?", first.projectId());
                execute(owner, "UPDATE dev_modbus_poll SET next_poll_at = now() - interval '1 minute' WHERE project_id = ?", blocked.projectId());
            }
        }
        Snapshot firstBefore = snapshot(first);
        Snapshot blockedBefore = snapshot(blocked);
        AtomicBoolean firstAppend = new AtomicBoolean();
        JdbcTransactionalOutboxRepository target = AopTestUtils.getUltimateTargetObject(outboxRepository);
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            invocation.callRealMethod();
            if (event.eventType().equals(ModbusRequest.EVENT_TYPE) && event.projectId().equals(first.projectId())) firstAppend.set(true);
            return null;
        }).when(target).append(any(OutboxEvent.class));
        CompletableFuture<Integer> blockedPid = capturePermitPid(blocked);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                try (PreparedStatement query = holder.prepareStatement("SELECT id FROM sys_project WHERE id = ? FOR UPDATE")) {
                    query.setObject(1, blocked.projectId());
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                Future<Integer> scanning = submitScan(executor, retry);
                assertBlockedBy(blockedPid.get(5, TimeUnit.SECONDS),
                        (int) number(holder, "SELECT pg_backend_pid()"), scanning);
                assertThat(firstAppend.get()).as("前一poll应已在独立事务真实append并提交").isTrue();
                assertThat(snapshot(first)).isNotEqualTo(firstBefore);
                assertThat(snapshot(blocked)).isEqualTo(blockedBefore);
                Throwable failure = catchThrowable(() -> scanning.get(12, TimeUnit.SECONDS));
                assertThat(isTimeout(failure)).as("许可等待应保留真实SQL或事务预算首因：%s", failure).isTrue();
                assertThat(snapshot(first)).isNotEqualTo(firstBefore);
                assertThat(snapshot(blocked)).isEqualTo(blockedBefore);
                holder.rollback();
                assertThat(dataCall(() -> retry ? pollService.scanTimeout(100) : pollService.scanDue(100))).isEqualTo(1);
                assertRequestCount(first, retry ? 2 : 1);
                assertRequestCount(blocked, retry ? 2 : 1);
                assertInFlight(first, retry ? 2 : 1);
                assertInFlight(blocked, retry ? 2 : 1);
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 真实账号/租户成员/唯一OWNER让删除走正常授权，网关保持ONLINE并经真实配置物化。 */
    private OwnedFixture ownedSchedule() throws SQLException {
        Fixture fixture = fixture(points(1));
        UUID account = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '轮询删除owner')",
                    account, account + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), account);
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), account);
            execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId());
            owner.commit();
        }
        pushConfig(fixture);
        return new OwnedFixture(fixture, account);
    }

    /** 普通扫描无额外外层事务；生产服务的事务返回后独立owner才能观察提交事实。 */
    private <T> T dataCall(Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return action.get();
        }
    }

    /** 真实OWNER身份在连接借出前注入，外层事务只用于精确控制业务删除提交顺序。 */
    private <T> T ownerTransaction(OwnedFixture owned, Supplier<T> action) {
        Fixture fixture = owned.fixture();
        assertThat(TenantContext.current()).isEmpty();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), owned.accountId()));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                return action.get();
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** 普通删除也经过真实服务及授权，而非直接SQL改变deleted_at。 */
    private void delete(OwnedFixture owned) {
        ownerTransaction(owned, () -> {
            projectService.delete(owned.fixture().projectId());
            return null;
        });
    }

    /** 异步扫描不包测试外层事务，每个poll事务完全由生产服务建立。 */
    private Future<Integer> submitScan(ExecutorService executor, boolean retry) {
        return executor.submit(() -> dataCall(() -> retry ? pollService.scanTimeout(100) : pollService.scanDue(100)));
    }

    /** 在目标poll真实许可SQL执行前公布当前内层事务PID，调用仍进入真实实现。 */
    private CompletableFuture<Integer> capturePermitPid(Fixture fixture) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(projectLifecycle);
        doAnswer(invocation -> {
            UUID projectId = invocation.getArgument(1);
            if (fixture.projectId().equals(projectId)) {
                pid.complete(backendId());
            }
            return invocation.callRealMethod();
        }).when(target).lockActiveForWrite(any(UUID.class), any(UUID.class));
        return pid;
    }

    /** 真实请求append完成后、独立poll事务提交前暂停，用其PID证明项目许可仍被持有。 */
    private void pauseAfterRequestAppend(Fixture fixture,
                                         CompletableFuture<Integer> pid,
                                         CountDownLatch release) {
        JdbcTransactionalOutboxRepository target = AopTestUtils.getUltimateTargetObject(outboxRepository);
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            invocation.callRealMethod();
            if (ModbusRequest.EVENT_TYPE.equals(event.eventType())
                    && fixture.projectId().equals(event.projectId())
                    && pid.complete(backendId())) {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(target).append(any(OutboxEvent.class));
    }

    /** 独立PG阻塞图及未授予锁验证真等待，三个连接PID必须不同。 */
    private void assertBlockedBy(int waitingPid, int holderPid, Future<?> waiting) throws Exception {
        assertThat(waitingPid).isNotEqualTo(holderPid);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            parameters(query, holderPid, waitingPid, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(holderPid);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (waiting.isDone()) throw new AssertionError("未等待指定项目锁便已结束");
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到真实项目锁等待");
    }

    /** 暂停使用数据库同源周期时间，并保留原在线设备事实；不把offline播种成拒绝理由。 */
    private void assertPaused(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, """
                    SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IDLE'
                       AND request_id IS NULL AND lease_until IS NULL AND attempt = 0
                       AND next_poll_at = updated_at + polling_interval_ms * interval '1 millisecond'
                    """, fixture.projectId())).isEqualTo(1);
            assertThat(number(owner, "SELECT count(*) FROM dev_device WHERE id = ? AND status = 'ONLINE' AND deleted_at IS NULL",
                    fixture.gatewayId())).isEqualTo(1);
        }
    }

    /** 本片不绑定成员是否物理保留的后续5d实现，只验证项目实际删除提交。 */
    private void assertDeleted(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL",
                    fixture.projectId())).isEqualTo(1);
        }
    }

    /** 真请求关联及尝试次数与持久发送数量一起核验。 */
    private void assertInFlight(Fixture fixture, int attempt) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IN_FLIGHT' AND request_id IS NOT NULL AND attempt = ?",
                    fixture.projectId(), attempt)).isEqualTo(1);
        }
    }

    /** 只计新请求，不把早已提交的配置意图当作删除后业务写。 */
    private void assertRequestCount(Fixture fixture, int count) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = ?",
                    fixture.projectId(), ModbusRequest.EVENT_TYPE)).isEqualTo(count);
        }
    }

    /** 仅让本例租约过期并规定两项目重试顺序，不用sleep等待墙钟。 */
    private void expire(Fixture fixture, int seconds) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_modbus_poll SET lease_until = now() - ? * interval '1 second' WHERE project_id = ?",
                    seconds, fixture.projectId())).isEqualTo(1);
        }
    }

    /** 数据库首因只能为真实取消或事务预算超时，不能把任意错误当作拒绝成功。 */
    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransactionTimedOutException) return true;
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 原业务事务连接身份，用于证明双方互相阻塞而非复用同一事务。 */
    private int backendId() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 独立owner完整快照用于区分已提交poll与当前失败poll的事务边界。 */
    private Snapshot snapshot(Fixture fixture) throws SQLException {
        return new Snapshot(rows(fixture, "SELECT row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ? ORDER BY id"), outboxRows(fixture));
    }

    /** 旧配置及请求Outbox均应原样保留，不能只比较行数。 */
    private List<String> outboxRows(Fixture fixture) throws SQLException {
        return rows(fixture, "SELECT row_to_json(o)::text FROM sys_outbox_event o WHERE project_id = ? ORDER BY id");
    }

    /** 每次借独立连接读取已提交的本例项目事实。 */
    private List<String> rows(Fixture fixture, String sql) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(sql)) {
            parameters(query, fixture.projectId());
            List<String> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return List.copyOf(result);
        }
    }

    /** 调用前外层事务已结束或显式回滚，线程可在有界时间内退出。 */
    private void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    /** @param fixture 合法项目设备夹具 @param accountId 唯一真实OWNER */
    private record OwnedFixture(Fixture fixture, UUID accountId) { }

    /** @param polls 完整轮询行 @param outbox 完整发送意图 */
    private record Snapshot(List<String> polls, List<String> outbox) { }
}
