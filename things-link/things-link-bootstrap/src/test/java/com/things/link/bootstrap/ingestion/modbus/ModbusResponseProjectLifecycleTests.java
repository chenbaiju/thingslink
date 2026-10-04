package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusPollService;
import com.things.link.device.application.ModbusResponseAcceptanceService;
import com.things.link.device.infrastructure.persistence.JdbcModbusPollRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ADR0064决策3：真实响应接纳在请求归属之后持项目许可，删除及超时不能留下部分完成或交付事实。
 * 许可、事务、版本均为真实实现；spy只观察调用顺序、公布真实连接PID或在真实append之后设置确定性屏障。
 */
class ModbusResponseProjectLifecycleTests extends AbstractModbusPollIntegrationTest {

    /** 与已发布轮询属性匹配的真实版本快照，避免缺版本掩盖项目生命周期边界。 */
    private static final String MODEL_SNAPSHOT = """
            {"properties":{"temperature_0":{"dataType":"NUMBER","accessType":"REPORT"}},"events":{},"commands":{}}
            """;
    /** 只观察平台轮询接纳新增的normalized事件。 */
    private static final String EVENT_TYPE = "DEVICE_MODBUS_NORMALIZED";
    /** 生产三秒事务入口，没有测试外层事务代替它。 */
    @Autowired private ModbusResponseAcceptanceService acceptance;
    /** 首次请求由真实在线扫描创建。 */
    @Autowired private ModbusPollService pollService;
    /** 删除必须经过真实OWNER权限检查及业务软删。 */
    @Autowired private ProjectService projectService;
    /** 从持久事件核验版本、requestId及解码值。 */
    @Autowired private ObjectMapper mapper;
    /** 查询真实执行后才公布PID，不伪造返回的轮询行。 */
    @MockitoSpyBean private JdbcModbusPollRepository pollRepository;
    /** 只观察错误归属是否提前调用许可，所有许可返回及事务均执行真实实现。 */
    @MockitoSpyBean private ProjectLifecycleAccessService projectLifecycle;
    /** append真实执行后暂停，观察其物理事务持锁直到提交。 */
    @MockitoSpyBean private JdbcTransactionalOutboxRepository outboxRepository;

    /** 项目删除提交后SUCCESS与ERROR均不能推进CAS，完整poll及旧两条Outbox保持原样。 */
    @ParameterizedTest(name = "删除后{0}不得完成轮询")
    @EnumSource(ModbusResponse.Status.class)
    void deletedProjectIgnoresResponseWithoutChangingAnyPollOrOutboxField(ModbusResponse.Status status) throws Exception {
        OwnedFixture owned = inFlight();
        ModbusResponse response = response(owned.fixture(), status);
        Snapshot before = snapshot(owned.fixture());
        deleteAsOwner(owned);
        assertDeleted(owned.fixture());
        accept(response);
        assertThat(snapshot(owned.fixture())).isEqualTo(before);
        assertNoNormalized(owned.fixture());
    }

    /** ARCHIVED只允许普通读，轮询响应属于新业务写，不能完成请求或写normalized。 */
    @Test
    void archivedProjectIgnoresResponseWithoutCompletingPoll() throws Exception {
        OwnedFixture owned = inFlight();
        ModbusResponse response = response(owned.fixture(), ModbusResponse.Status.SUCCESS);
        Snapshot before = snapshot(owned.fixture());
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", owned.fixture().projectId())).isEqualTo(1);
        }
        accept(response);
        assertThat(snapshot(owned.fixture())).isEqualTo(before);
        assertNoNormalized(owned.fixture());
    }

    /** 错误归属即使带合法requestId也应先吸收；真实许可spy的调用观察补足错二元组查不到行而不阻塞的情况。 */
    @ParameterizedTest(name = "{0}错误在项目许可之前拒绝")
    @EnumSource(WrongIdentity.class)
    void rejectsWrongIdentityBeforeAttemptingAnyProjectPermit(WrongIdentity wrong) throws Exception {
        OwnedFixture owned = inFlight();
        Fixture other = fixture(points(1));
        Fixture fixture = owned.fixture();
        ModbusResponse original = response(fixture, ModbusResponse.Status.SUCCESS);
        ModbusResponse forged = new ModbusResponse(original.requestId(),
                wrong == WrongIdentity.TENANT ? other.tenantId() : fixture.tenantId(),
                wrong == WrongIdentity.PROJECT ? other.projectId() : fixture.projectId(),
                wrong == WrongIdentity.GATEWAY ? other.gatewayId() : fixture.gatewayId(),
                ModbusResponse.Status.SUCCESS, List.of(), null, original.receivedAt(), original.traceId());
        Snapshot before = snapshot(fixture);
        ProjectLifecycleAccessService lifecycleTarget = AopTestUtils.getUltimateTargetObject(projectLifecycle);
        clearInvocations(lifecycleTarget);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                lockProject(holder, fixture.projectId());
                lockProject(holder, other.projectId());
                Future<?> ignored = executor.submit(() -> accept(forged));
                // 锁阻挡合法项目许可；再检查真实调用次数，捕捉使用不存在的payload二元组提前查许可。
                ignored.get(1, TimeUnit.SECONDS);
                verify(lifecycleTarget, never()).lockActiveForWrite(any(UUID.class), any(UUID.class));
                assertThat(snapshot(fixture)).isEqualTo(before);
                assertNoNormalized(fixture);
            } finally {
                holder.rollback();
                shutdown(executor);
            }
        }
    }

    /** 响应先获许可并真实append后，OWNER删除必须等待同一接纳事务完成，独立连接不可提前看到normalized。 */
    @Test
    void responsePermitBlocksRealOwnerDeletionUntilNormalizedCommits() throws Exception {
        OwnedFixture owned = inFlight();
        Fixture fixture = owned.fixture();
        ModbusResponse response = response(fixture, ModbusResponse.Status.SUCCESS);
        Snapshot before = snapshot(fixture);
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> responsePid = new CompletableFuture<>();
        JdbcTransactionalOutboxRepository target = AopTestUtils.getUltimateTargetObject(outboxRepository);
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            invocation.callRealMethod();
            if (EVENT_TYPE.equals(event.eventType()) && fixture.projectId().equals(event.projectId())) {
                responsePid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE id = ?", Integer.class, event.id())).isEqualTo(1);
                appended.countDown();
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(target).append(any(OutboxEvent.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> responseFuture = executor.submit(() -> accept(response));
            assertThat(appended.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(snapshot(fixture)).isEqualTo(before);
            CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
            Future<?> deletion = executor.submit(() -> deleteTransaction(owned, deletionPid, null, null));
            assertBlockedBy(deletionPid.get(2, TimeUnit.SECONDS), responsePid.get(2, TimeUnit.SECONDS), deletion);
            assertThat(snapshot(fixture)).isEqualTo(before);
            release.countDown();
            responseFuture.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertAccepted(fixture, response);
            assertDeleted(fixture);
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    /** OWNER删除先写未提交时响应必须等待；删除提交后许可重新检查，完整poll/Outbox不变。 */
    @Test
    void responseWaitsForRealDeletionAndIsIgnoredAfterDeletionCommits() throws Exception {
        OwnedFixture owned = inFlight();
        Fixture fixture = owned.fixture();
        ModbusResponse response = response(fixture, ModbusResponse.Status.SUCCESS);
        Snapshot before = snapshot(fixture);
        CompletableFuture<Integer> responsePid = captureResponsePid(response);
        CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commitDelete = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> deleteTransaction(owned, deletionPid, deleted, commitDelete));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> responseFuture = executor.submit(() -> accept(response));
            assertBlockedBy(responsePid.get(2, TimeUnit.SECONDS), deletionPid.get(2, TimeUnit.SECONDS), responseFuture);
            commitDelete.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            responseFuture.get(5, TimeUnit.SECONDS);
            assertDeleted(fixture);
            assertThat(snapshot(fixture)).isEqualTo(before);
            assertNoNormalized(fixture);
        } finally {
            commitDelete.countDown();
            shutdown(executor);
        }
    }

    /** 项目许可锁等待消耗真实Acceptance三秒预算；失败无部分CAS/交付，解锁后同响应可接纳。 */
    @Test
    void projectPermitTimeoutRollsBackAndSameResponseRecoversAfterUnlock() throws Exception {
        OwnedFixture owned = inFlight();
        Fixture fixture = owned.fixture();
        ModbusResponse response = response(fixture, ModbusResponse.Status.SUCCESS);
        Snapshot before = snapshot(fixture);
        CompletableFuture<Integer> responsePid = captureResponsePid(response);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid = (int) number(holder, "SELECT pg_backend_pid()");
                lockProject(holder, fixture.projectId());
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> accept(response)));
                assertBlockedBy(responsePid.get(2, TimeUnit.SECONDS), holderPid, waiting);
                Throwable failure = waiting.get(8, TimeUnit.SECONDS);
                assertThat(isTransactionTimeout(failure)).as("生产三秒事务应取消真实项目锁等待，实际=%s", failure).isTrue();
                assertThat(snapshot(fixture)).isEqualTo(before);
                assertNoNormalized(fixture);
                holder.rollback();
                accept(response);
                assertAccepted(fixture, response);
            } finally {
                holder.rollback();
                shutdown(executor);
            }
        }
    }

    /** 真实查询完成后只公布物理连接PID，使阻塞图能定位响应而非凭固定sleep推测。 */
    private CompletableFuture<Integer> captureResponsePid(ModbusResponse response) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        JdbcModbusPollRepository target = AopTestUtils.getUltimateTargetObject(pollRepository);
        doAnswer(invocation -> {
            Object poll = invocation.callRealMethod();
            pid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            return poll;
        }).when(target).findByRequestId(response.requestId());
        return pid;
    }

    /** 只给响应入口设置生产DATA路由，三秒事务完全由真实Spring acceptance代理建立。 */
    private void accept(ModbusResponse response) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            acceptance.accept(response);
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    /** 合法OWNER及在线设备先经真实下发/扫描形成在途请求，版本使用真实INITIAL绑定。 */
    private OwnedFixture inFlight() throws Exception {
        Fixture fixture = fixture(points(1));
        UUID accountId = Uuid7.generate();
        UUID typeId;
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '响应删除owner')",
                    accountId, accountId + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), accountId);
            execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), accountId);
            assertThat(execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId())).isEqualTo(1);
            try (PreparedStatement query = owner.prepareStatement("SELECT device_type_id FROM dev_device WHERE id = ?")) {
                parameters(query, fixture.subDeviceId());
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    typeId = rows.getObject(1, UUID.class);
                }
            }
            owner.commit();
        }
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), typeId, fixture.subDeviceId(), MODEL_SNAPSHOT);
        pushConfig(fixture);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            assertThat(pollService.scanDue(100)).isPositive();
        }
        Snapshot prepared = snapshot(fixture);
        assertThat(prepared.polls()).hasSize(1);
        assertThat(prepared.outbox()).hasSize(2);
        return new OwnedFixture(fixture, accountId);
    }

    /** 删除顺序对照使用生产OWNER入口，不将手写SQL状态更改冒充删除验收。 */
    private void deleteAsOwner(OwnedFixture owned) {
        TenantContext.set(new TenantScope(owned.fixture().tenantId(), owned.fixture().projectId(), owned.accountId()));
        try {
            projectService.delete(owned.fixture().projectId());
        } finally {
            TenantContext.clear();
        }
    }

    /** 仅为公布PID/控制删除提交附真实外层事务，删除本身仍执行生产授权与SQL。 */
    private void deleteTransaction(OwnedFixture owned, CompletableFuture<Integer> pid,
                                   CountDownLatch deleted, CountDownLatch release) {
        TenantContext.set(new TenantScope(owned.fixture().tenantId(), owned.fixture().projectId(), owned.accountId()));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                pid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                projectService.delete(owned.fixture().projectId());
                if (deleted != null) {
                    deleted.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** 当前requestId取数据库事实，固定原响应时间用于确认成功路径未重建时间或版本。 */
    private ModbusResponse response(Fixture fixture, ModbusResponse.Status status) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT request_id FROM dev_modbus_poll WHERE project_id = ? AND status = 'IN_FLIGHT'")) {
            parameters(query, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new ModbusResponse(rows.getObject(1, UUID.class), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(),
                        status, status == ModbusResponse.Status.SUCCESS ? List.of(0, 0x3f80) : List.of(),
                        status == ModbusResponse.Status.ERROR ? "SLAVE_FAILURE" : null,
                        Instant.parse("2026-08-20T01:02:03.123456Z"), "0123456789abcdef0123456789abcdef");
            }
        }
    }

    /** 独立项目行排他锁阻止真实SHARE许可，不改项目状态或原请求。 */
    private void lockProject(Connection holder, UUID projectId) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM sys_project WHERE id = ? FOR UPDATE")) {
            parameters(lock, projectId);
            try (ResultSet rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    /** 三个不同物理连接及未授予锁证明真正等待指定持锁者，不以Future未完成代替数据库证据。 */
    private void assertBlockedBy(int waitingPid, int holderPid, Future<?> waiting) throws Exception {
        assertThat(waitingPid).isNotEqualTo(holderPid);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            parameters(query, holderPid, waitingPid, waitingPid);
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(holderPid);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (waiting.isDone()) throw new AssertionError("操作在等待指定项目锁之前已经结束");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定项目许可锁等待");
    }

    /** 独立连接确认实际软删已提交；不依赖未来成员保全切片是否保留成员行。 */
    private void assertDeleted(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL",
                    fixture.projectId())).isEqualTo(1);
        }
    }

    /** 已拒绝路径不能新增normalized，旧两条事件由完整快照另行保证保持原样。 */
    private void assertNoNormalized(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = ?",
                    fixture.projectId(), EVENT_TYPE)).isZero();
        }
    }

    /** 成功提交应同时可见IDLE与唯一完整事件，版本冻结仍由真实绑定解析产生。 */
    private void assertAccepted(Fixture fixture, ModbusResponse response) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IDLE' AND request_id IS NULL",
                    fixture.projectId())).isEqualTo(1);
            try (PreparedStatement query = owner.prepareStatement("SELECT payload FROM sys_outbox_event WHERE project_id = ? AND event_type = ?")) {
                parameters(query, fixture.projectId(), EVENT_TYPE);
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    StandardUplinkMessage message = mapper.readValue(rows.getString(1), StandardUplinkMessage.class);
                    assertThat(message.messageId()).isEqualTo(response.requestId());
                    assertThat(message.modelVersion()).isEqualTo("1.0.0");
                    assertThat(message.tenantId()).isEqualTo(fixture.tenantId());
                    assertThat(message.projectId()).isEqualTo(fixture.projectId());
                    assertThat(message.deviceId()).isEqualTo(fixture.subDeviceId());
                    assertThat(message.gatewayId()).isEqualTo(fixture.gatewayId());
                    assertThat(message.receivedAt()).isEqualTo(response.receivedAt());
                    assertThat(message.payload()).containsEntry("temperature_0", -2.25d);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    /** 全行快照包含时间、attempt和租约，不只比较状态或条数而漏掉部分写入。 */
    private Snapshot snapshot(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            return new Snapshot(rows(owner, "SELECT row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ? ORDER BY id", fixture),
                    rows(owner, "SELECT row_to_json(o)::text FROM sys_outbox_event o WHERE project_id = ? ORDER BY id", fixture));
        }
    }

    /** 只查询本例随机项目，避免共享容器其他测试数据影响完整行比较。 */
    private List<String> rows(Connection owner, String sql, Fixture fixture) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement(sql)) {
            parameters(query, fixture.projectId());
            List<String> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
            return List.copyOf(result);
        }
    }

    /** 只接受PG预算取消或Spring事务超时，不把任意数据库失败当作超时证据。 */
    private boolean isTransactionTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransactionTimedOutException) return true;
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 先由各测试释放持锁屏障/连接，再收束线程，防止失败泄漏异步写入。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 一次只破坏一个可信响应身份轴。 */
    private enum WrongIdentity {
        /** 租户轴。 */ TENANT,
        /** 项目轴。 */ PROJECT,
        /** 网关轴。 */ GATEWAY
    }

    /** @param fixture 独占真实项目及设备 @param accountId 唯一真实OWNER */
    private record OwnedFixture(Fixture fixture, UUID accountId) { }
    /** @param polls 完整poll事实 @param outbox 完整持久交付事实 */
    private record Snapshot(List<String> polls, List<String> outbox) { }
}
