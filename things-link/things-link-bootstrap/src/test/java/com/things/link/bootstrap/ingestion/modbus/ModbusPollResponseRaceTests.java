package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusPollService;
import com.things.link.device.domain.ModbusPoll;
import com.things.link.device.infrastructure.persistence.JdbcModbusPollRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

/**
 * S12-P0-4e / D-120：响应读取与完成之间允许真实重试提交，旧快照不得清除新请求或重复产出属性。
 * spy 仅在真实读取之后暂停线程，不替换数据库结果；所有业务写入仍走公开事务入口。
 */
@DisplayName("Modbus 响应与重试竞态")
class ModbusPollResponseRaceTests extends AbstractModbusPollIntegrationTest {

    /** 匿名 DATA 入口必须保留真实领取、在线资格和 Outbox 事务。 */
    @Autowired private ModbusPollService pollService;
    /** 解包后的目标只设置读取屏障，避免对 Spring 事务代理本身做 stubbing。 */
    @MockitoSpyBean private JdbcModbusPollRepository pollRepository;

    /** 旧 SUCCESS 即使已读取旧行，也不能抹掉后来提交的 request2 或产出旧属性。 */
    @Test
    void staleSuccessCannotCompleteTheCommittedRetry() throws Exception {
        assertOldResponseCannotCompleteRetry(ModbusResponse.Status.SUCCESS);
    }

    /** ERROR 使用相同关联边界，不能按调度行 ID 释放后来提交的新请求。 */
    @Test
    void staleErrorCannotReleaseTheCommittedRetry() throws Exception {
        assertOldResponseCannotCompleteRetry(ModbusResponse.Status.ERROR);
    }

    /** 旧请求已失效时连短数组也应幂等吸收，不能先解码再发现 CAS 不再适用。 */
    @Test
    void staleShortDataIsIgnoredAfterTheCommittedRetry() throws Exception {
        assertOldResponseCannotCompleteRetry(ModbusResponse.Status.SUCCESS, List.of(0));
    }

    /** 当前有效请求解码失败必须回滚完成状态；修正后的同一响应仍可成功处理。 */
    @Test
    void currentShortDataRollsBackAndValidResponseCanStillComplete() throws Exception {
        Fixture fixture = onlineInFlight();
        PollState before = state(fixture);
        List<String> outboxBefore = outboxRows(fixture);
        ModbusResponse malformed = response(fixture, before.requestId(), ModbusResponse.Status.SUCCESS, List.of(0));

        assertThatThrownBy(() -> dataTransaction(() -> pollService.handleResponse(malformed)))
                .isInstanceOf(IndexOutOfBoundsException.class);

        assertThat(state(fixture)).isEqualTo(before);
        assertThat(outboxRows(fixture)).isEqualTo(outboxBefore);
        Optional<ModbusPollService.ResolvedModbusValue> recovered = dataTransaction(() ->
                pollService.handleResponse(response(fixture, before.requestId(), ModbusResponse.Status.SUCCESS)));
        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().messageId()).isEqualTo(before.requestId());
        assertThat(recovered.orElseThrow().value()).isEqualTo(-2.25d);
        assertThat(state(fixture).status()).isEqualTo("IDLE");
        assertThat(state(fixture).requestId()).isNull();
        assertThat(outboxRows(fixture)).isEqualTo(outboxBefore);
    }

    /** 两个事务同时读到 request1，只有实际赢得完成状态转换者可以返回属性。 */
    @Test
    void twoSuccessfulResponsesProduceOneResolvedValue() throws Exception {
        Fixture fixture = onlineInFlight();
        UUID requestId = state(fixture).requestId();
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch continueResponses = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> backends = new ConcurrentLinkedQueue<>();
        pauseRealLookup(requestId, bothRead, continueResponses, () -> backends.add(backendId()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ModbusPollService.ResolvedModbusValue>> first = executor.submit(() ->
                    dataTransaction(() -> pollService.handleResponse(response(fixture, requestId, ModbusResponse.Status.SUCCESS))));
            Future<Optional<ModbusPollService.ResolvedModbusValue>> second = executor.submit(() ->
                    dataTransaction(() -> pollService.handleResponse(response(fixture, requestId, ModbusResponse.Status.SUCCESS))));
            await(bothRead);
            assertThat(backends).hasSize(2).doesNotHaveDuplicates();
            continueResponses.countDown();
            List<Optional<ModbusPollService.ResolvedModbusValue>> results = List.of(await(first), await(second));
            assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(state(fixture).status()).isEqualTo("IDLE");
            assertThat(state(fixture).requestId()).isNull();
            assertRequestCount(fixture, 1);
        } finally {
            continueResponses.countDown();
            stop(executor);
        }
    }

    /** 单次正常响应仍需完成状态并解码，不能用一律丢弃来满足竞态测试。 */
    @Test
    void normalSuccessCompletesAndDecodesThenDuplicateIsEmpty() throws Exception {
        Fixture fixture = onlineInFlight();
        UUID requestId = state(fixture).requestId();
        ModbusResponse response = response(fixture, requestId, ModbusResponse.Status.SUCCESS);
        Optional<ModbusPollService.ResolvedModbusValue> resolved = dataTransaction(() -> pollService.handleResponse(response));
        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().subDeviceId()).isEqualTo(fixture.subDeviceId());
        assertThat(resolved.orElseThrow().messageId()).isEqualTo(requestId);
        assertThat(resolved.orElseThrow().propertyKey()).isEqualTo("temperature_0");
        assertThat(resolved.orElseThrow().value()).isEqualTo(-2.25d);
        assertThat(state(fixture).status()).isEqualTo("IDLE");
        assertThat(state(fixture).requestId()).isNull();
        assertThat(dataTransaction(() -> pollService.handleResponse(response))).isEmpty();
        assertRequestCount(fixture, 1);
    }

    /** 未知 requestId 不应修改当前有效请求，覆盖无匹配行的安全边界。 */
    @Test
    void unknownResponsePreservesTheCurrentRequest() throws Exception {
        Fixture fixture = onlineInFlight();
        PollState before = state(fixture);
        assertThat(dataTransaction(() -> pollService.handleResponse(
                response(fixture, Uuid7.generate(), ModbusResponse.Status.SUCCESS)))).isEmpty();
        assertThat(state(fixture)).isEqualTo(before);
        assertRequestCount(fixture, 1);
    }

    /** 原 SUCCESS/ERROR 对照保留完整寄存器输入，扩展边界不改变既有五项测试合同。 */
    private void assertOldResponseCannotCompleteRetry(ModbusResponse.Status responseStatus) throws Exception {
        assertOldResponseCannotCompleteRetry(responseStatus,
                responseStatus == ModbusResponse.Status.SUCCESS ? List.of(0, 0x3f80) : List.of());
    }

    /** A 读取旧关联后暂停；B 在不同连接真实重试提交；可传入短数组验证失效响应先幂等吸收。 */
    private void assertOldResponseCannotCompleteRetry(ModbusResponse.Status responseStatus, List<Integer> data) throws Exception {
        Fixture fixture = onlineInFlight();
        UUID originalRequest = state(fixture).requestId();
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_modbus_poll SET lease_until = now() - interval '1 second' WHERE project_id = ?",
                    fixture.projectId())).isEqualTo(1);
        }
        CountDownLatch responseRead = new CountDownLatch(1);
        CountDownLatch continueResponse = new CountDownLatch(1);
        AtomicInteger responseBackend = new AtomicInteger();
        pauseRealLookup(originalRequest, responseRead, continueResponse, () -> responseBackend.set(backendId()));
        AtomicInteger retryBackend = new AtomicInteger();
        JdbcModbusPollRepository target = AopTestUtils.getUltimateTargetObject(pollRepository);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<ModbusPoll> claimed = (Optional<ModbusPoll>) invocation.callRealMethod();
            retryBackend.set(backendId());
            return claimed;
        }).when(target).claimOneExpiredInFlight(100);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ModbusPollService.ResolvedModbusValue>> oldResponse = executor.submit(() ->
                    dataTransaction(() -> pollService.handleResponse(response(fixture, originalRequest, responseStatus, data))));
            await(responseRead);
            assertThat(scanWithoutOuterTransaction(() -> pollService.scanTimeout(100))).isEqualTo(1);
            assertThat(retryBackend.get()).isPositive().isNotEqualTo(responseBackend.get());
            PollState retry = state(fixture);
            assertThat(retry.status()).isEqualTo("IN_FLIGHT");
            assertThat(retry.requestId()).isNotNull().isNotEqualTo(originalRequest);
            assertThat(retry.attempt()).isEqualTo(2);
            assertRequestCount(fixture, 2);
            continueResponse.countDown();
            Optional<ModbusPollService.ResolvedModbusValue> resolved = await(oldResponse);
            assertThat(state(fixture)).isEqualTo(retry);
            assertThat(resolved).isEmpty();
            assertRequestCount(fixture, 2);
        } finally {
            continueResponse.countDown();
            stop(executor);
        }
    }

    /** 精确匹配本例 requestId；读取结果必须真实存在，屏障只控制交错时序。 */
    private void pauseRealLookup(UUID requestId, CountDownLatch read, CountDownLatch resume, Runnable observation) {
        JdbcModbusPollRepository target = AopTestUtils.getUltimateTargetObject(pollRepository);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<ModbusPoll> result = (Optional<ModbusPoll>) invocation.callRealMethod();
            assertThat(result).isPresent();
            observation.run();
            read.countDown();
            await(resume);
            return result;
        }).when(target).findByRequestId(requestId);
    }

    /** 在线网关及已发布点位合法物化后，由真实匿名扫描创建首个发送意图。 */
    private Fixture onlineInFlight() throws Exception {
        Fixture fixture = fixture(points(1));
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId())).isEqualTo(1);
        }
        pushConfig(fixture);
        assertThat(scanWithoutOuterTransaction(() -> pollService.scanDue(100))).isEqualTo(1);
        assertThat(state(fixture).status()).isEqualTo("IN_FLIGHT");
        assertThat(state(fixture).attempt()).isEqualTo(1);
        assertRequestCount(fixture, 1);
        return fixture;
    }

    /** 小端 FLOAT32 的 1.0 经 1.25/-3.50 转换为 -2.25，提供独立解码对照。 */
    private ModbusResponse response(Fixture fixture, UUID requestId, ModbusResponse.Status status) {
        return response(fixture, requestId, status, status == ModbusResponse.Status.SUCCESS ? List.of(0, 0x3f80) : List.of());
    }

    /** 只替换原始寄存器数据，保留真实关联及身份用于解码失败和过期竞态边界。 */
    private ModbusResponse response(Fixture fixture, UUID requestId, ModbusResponse.Status status, List<Integer> data) {
        return new ModbusResponse(requestId, fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), status, data,
                status == ModbusResponse.Status.ERROR ? "SLAVE_ERROR" : null, Instant.now(), "d120-response-race");
    }

    /** 低层响应状态机仍由测试提供真实事务；每次回收后不得残留租户或项目身份。 */
    private <T> T dataTransaction(Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                T result = action.get();
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current()).isEmpty();
                return result;
            });
        }
    }

    /** 扫描服务自行逐 poll 开启新事务；测试只提供 DATA 路由，避免额外占用连接池。 */
    private int scanWithoutOuterTransaction(Supplier<Integer> action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            int result = action.get();
            assertThat(TenantContext.current()).isEmpty();
            assertThat(RlsScopeContext.current()).isEmpty();
            return result;
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    /** 同时存活的 PostgreSQL PID 必须不同，避免测试实际复用同一个事务。 */
    private int backendId() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 独立 owner 只读本例行，比较完整状态可发现旧响应改变租约或下一周期。 */
    private PollState state(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT request_id, status, attempt, row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ?")) {
            parameters(statement, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                PollState state = new PollState(rows.getObject(1, UUID.class), rows.getString(2), rows.getInt(3), rows.getString(4));
                assertThat(rows.next()).isFalse();
                return state;
            }
        }
    }

    /** 独立读取本例全部 Outbox 行，防止相同数量掩盖失败路径对内容或时间的修改。 */
    private List<String> outboxRows(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT row_to_json(o)::text FROM sys_outbox_event o WHERE project_id = ? ORDER BY id")) {
            parameters(statement, fixture.projectId());
            List<String> snapshots = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) snapshots.add(rows.getString(1));
            }
            return List.copyOf(snapshots);
        }
    }

    /** 响应只推进关联，不应偷偷生成额外请求；重试的两个发送意图保留为独立事实。 */
    private void assertRequestCount(Fixture fixture, int count) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = ?",
                    fixture.projectId(), ModbusRequest.EVENT_TYPE)).isEqualTo(count);
        }
    }

    /** 超时明确失败，不用固定 sleep 猜测线程是否已运行。 */
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(15, TimeUnit.SECONDS)).as("并发读取屏障在限时内到达").isTrue();
    }

    /** 每个 Future 有界等待，异常直接传播以保留真实数据库首因。 */
    private static <T> T await(Future<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }

    /** finally 先释放业务屏障再停止线程，避免失败清理时持有未结束事务。 */
    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).as("响应线程全部退出").isTrue();
    }

    /** @param requestId 当前请求 @param status 当前阶段 @param attempt 当前尝试 @param completeRow 完整行快照 */
    private record PollState(UUID requestId, String status, int attempt, String completeRow) { }
}
