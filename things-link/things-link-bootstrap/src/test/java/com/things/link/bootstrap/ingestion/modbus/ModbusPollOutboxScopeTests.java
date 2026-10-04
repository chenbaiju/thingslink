package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusPollService;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * S12-P0-4b / D-118：无 HTTP 身份的 DATA 扫描必须按真实物化归属追加项目级 Outbox。
 * 每个项目只有一个在线网关和一个点位，避免把后续 D-117 互斥及 D-029 离线契约混入范围验收。
 */
@DisplayName("Modbus 后台 Outbox 项目范围与异常恢复")
class ModbusPollOutboxScopeTests extends AbstractModbusPollIntegrationTest {

    /** 调用已有公开事务入口，不能以手工 SQL 或 mock 返回值代替领取及发送。 */
    @Autowired private ModbusPollService pollService;
    /** 成功路径真实插入，仅在回滚测试中第二次实际追加后触发数据库故障。 */
    @MockitoSpyBean private JdbcTransactionalOutboxRepository outboxRepository;

    /** 匿名 DATA 外层事务被逐 poll 新事务挂起；两个租户仍各自提交发送意图且外层范围保持为空。 */
    @Test
    void anonymousDueScanWritesBothProjectsAndRestoresEmptyScope() throws Exception {
        List<Fixture> fixtures = onlineSchedules();
        anonymousDataTransaction(() -> {
            assertThat(pollService.scanDue(100)).isEqualTo(2);
            assertEmptyDatabaseScope();
            return null;
        });
        for (Fixture fixture : fixtures) assertRequests(fixture, 1, 1);
        assertFreshAnonymousTransactionIsEmpty();
    }

    /** 过期仅修改本例租约，不等待墙钟；两个独立重试事务的新 requestId 必须仍落入各自项目。 */
    @Test
    void anonymousTimeoutRetriesBothProjectsAndRestoresEmptyScope() throws Exception {
        List<Fixture> fixtures = onlineSchedules();
        anonymousDataTransaction(() -> pollService.scanDue(100));
        expireOwnLeases(fixtures);
        anonymousDataTransaction(() -> {
            assertThat(pollService.scanTimeout(100)).isEqualTo(2);
            assertEmptyDatabaseScope();
            return null;
        });
        for (Fixture fixture : fixtures) assertRequests(fixture, 2, 2);
        assertFreshAnonymousTransactionIsEmpty();
    }

    /** 外层事务回滚不能撤销逐 poll 提交；挂起恢复后原 SQL 范围与空 ThreadLocal 都必须保持不变。 */
    @Test
    void innerPollCommitsSurviveOuterRollbackAndPreserveIncomingScope() throws Exception {
        List<Fixture> fixtures = onlineSchedules();
        Fixture incomingScope = fixture(List.of());
        anonymousDataTransactionRolledBack(() -> {
            setDatabaseScope(incomingScope);
            assertThat(pollService.scanDue(100)).isEqualTo(2);
            assertDatabaseScope(incomingScope);
            // 独立 owner 连接在外层结束前已可见两次提交，排除服务仍加入外层事务的假阳性。
            for (Fixture fixture : fixtures) assertRequests(fixture, 1, 1);
        });
        for (Fixture fixture : fixtures) assertRequests(fixture, 1, 1);
        expireOwnLeases(fixtures);
        anonymousDataTransactionRolledBack(() -> {
            setDatabaseScope(incomingScope);
            assertThat(pollService.scanTimeout(100)).isEqualTo(2);
            assertDatabaseScope(incomingScope);
            for (Fixture fixture : fixtures) assertRequests(fixture, 2, 2);
        });
        for (Fixture fixture : fixtures) assertRequests(fixture, 2, 2);
        assertFreshAnonymousTransactionIsEmpty();
    }

    /** 第二个 poll 的 22012 只回滚当前事务；此前提交必须保留且恢复扫描只能处理失败项。 */
    @Test
    void databaseFailureAfterSecondRealAppendKeepsFirstCommitAndRollsBackOnlyCurrentPoll() throws Exception {
        List<Fixture> fixtures = onlineSchedules();
        orderDue(fixtures);
        List<List<String>> oldPolls = new ArrayList<>();
        List<List<String>> oldOutbox = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            oldPolls.add(pollSnapshot(fixture));
            oldOutbox.add(outboxSnapshot(fixture));
        }
        AtomicInteger realAppends = new AtomicInteger();
        // 配置 spy 时绕过 MANDATORY 事务代理；实际服务调用仍走原代理和真实追加事务。
        JdbcTransactionalOutboxRepository target = AopTestUtils.getUltimateTargetObject(outboxRepository);
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            invocation.callRealMethod();
            // 只针对当前两项目的真实 Modbus 请求；配置 Outbox 和其他测试事件不属于故障注入范围。
            if (ModbusRequest.EVENT_TYPE.equals(event.eventType())
                    && fixtures.stream().anyMatch(fixture -> fixture.projectId().equals(event.projectId()))
                    && realAppends.incrementAndGet() == 2) {
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = ?",
                        Integer.class, event.projectId(), ModbusRequest.EVENT_TYPE)).isEqualTo(1);
                jdbcTemplate.queryForObject("SELECT 1 / 0", Integer.class);
            }
            return null;
        }).when(target).append(any(OutboxEvent.class));

        Throwable failure = catchThrowable(() -> anonymousDataTransaction(() -> pollService.scanDue(100)));
        assertThat(failure).isNotNull();
        assertThat(realAppends).hasValue(2);
        assertThat(rootSqlState(failure)).isEqualTo("22012");
        Fixture committed = fixtures.get(0);
        Fixture rolledBack = fixtures.get(1);
        assertRequests(committed, 1, 1);
        assertThat(pollSnapshot(committed)).isNotEqualTo(oldPolls.get(0));
        assertThat(outboxSnapshot(committed)).isNotEqualTo(oldOutbox.get(0));
        assertThat(pollSnapshot(rolledBack)).containsExactlyElementsOf(oldPolls.get(1));
        assertThat(outboxSnapshot(rolledBack)).containsExactlyElementsOf(oldOutbox.get(1));
        List<String> committedPoll = pollSnapshot(committed);
        List<String> committedOutbox = outboxSnapshot(committed);
        assertFreshAnonymousTransactionIsEmpty();
        // 一次性故障解除后只能重新领取失败项；已提交项仍在途，不能被重复追加。
        anonymousDataTransaction(() -> {
            assertThat(pollService.scanDue(100)).isEqualTo(1);
            assertEmptyDatabaseScope();
            return null;
        });
        assertThat(pollSnapshot(committed)).containsExactlyElementsOf(committedPoll);
        assertThat(outboxSnapshot(committed)).containsExactlyElementsOf(committedOutbox);
        for (Fixture fixture : fixtures) assertRequests(fixture, 1, 1);
    }

    /** 合法控制面配置物化后，仅把本例网关标为 ONLINE；不会手工构造 poll 归属或请求。 */
    private List<Fixture> onlineSchedules() throws SQLException {
        List<Fixture> fixtures = List.of(fixture(points(1)), fixture(points(1)));
        for (Fixture fixture : fixtures) {
            try (Connection owner = ownerConnection()) {
                execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId());
            }
            pushConfig(fixture);
        }
        assertThat(fixtures.get(0).tenantId()).isNotEqualTo(fixtures.get(1).tenantId());
        return fixtures;
    }

    /** 路由必须先于事务创建，连接以非 owner APP 身份借出且没有线程项目身份。 */
    private <T> T anonymousDataTransaction(Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
                assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertEmptyDatabaseScope();
                T result = action.get();
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current()).isEmpty();
                return result;
            });
        }
    }

    /** 外层事务显式回滚，用于证明逐 poll 的 REQUIRES_NEW 提交不依赖调用方提交。 */
    private void anonymousDataTransactionRolledBack(ThrowingRunnable action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
                assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertEmptyDatabaseScope();
                try {
                    action.run();
                } catch (Exception exception) {
                    throw new IllegalStateException("外层事务内的测试动作失败", exception);
                }
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current()).isEmpty();
                status.setRollbackOnly();
            });
        }
    }

    /** 检查新借用事务的安全起点；不把连接池恰好复用某个 PID 当作本片完成条件。 */
    private void assertFreshAnonymousTransactionIsEmpty() {
        anonymousDataTransaction(() -> {
            assertEmptyDatabaseScope();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event", Integer.class)).isZero();
            return null;
        });
    }

    /** 空范围按 PostgreSQL 自定义 GUC 的空字符串语义核验，避免 null 与空串差异掩盖权限。 */
    private void assertEmptyDatabaseScope() {
        assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.tenant_id', true), '')", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.project_id', true), '')", String.class)).isEmpty();
    }

    /** 本例主动污染的是已借用连接的事务范围，明确不借助 RlsScopeContext。 */
    private void setDatabaseScope(Fixture fixture) {
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantId().toString());
        jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class, fixture.projectId().toString());
    }

    /** 调用结束必须还原进入 SQL 值，不能简单清空或从空 ThreadLocal 重建。 */
    private void assertDatabaseScope(Fixture fixture) {
        assertThat(jdbcTemplate.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class)).isEqualTo(fixture.tenantId().toString());
        assertThat(jdbcTemplate.queryForObject("SELECT current_setting('app.project_id', true)", String.class)).isEqualTo(fixture.projectId().toString());
    }

    /** 只改变当前测试已发送轮询的租约时间，下一次重试仍必须通过真实领取 SQL。 */
    private void expireOwnLeases(List<Fixture> fixtures) throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                assertThat(execute(owner, "UPDATE dev_modbus_poll SET lease_until = now() - interval '1 second' WHERE project_id = ? AND status = 'IN_FLIGHT'",
                        fixture.projectId())).isEqualTo(1);
            }
        }
    }

    /** 固定两个到期 poll 的领取顺序，使第二次真实追加故障精确命中第二个项目。 */
    private void orderDue(List<Fixture> fixtures) throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            for (int index = 0; index < fixtures.size(); index++) {
                assertThat(execute(owner, """
                        UPDATE dev_modbus_poll SET next_poll_at = now() - (? * interval '1 minute')
                         WHERE project_id = ? AND status = 'IDLE'
                        """, fixtures.size() - index, fixtures.get(index).projectId())).isEqualTo(1);
            }
            owner.commit();
        }
    }

    /** 逐条检查真实 JSON、Outbox 身份和当前 poll 请求关联，而非只比较 API 返回数量。 */
    private void assertRequests(Fixture fixture, int expectedEvents, int expectedAttempt) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement("""
                SELECT tenant_id, project_id, aggregate_type, aggregate_id, partition_key,
                       payload::jsonb ->> 'requestId' AS request_id,
                       payload::jsonb ->> 'tenantId' AS payload_tenant,
                       payload::jsonb ->> 'projectId' AS payload_project,
                       payload::jsonb ->> 'gatewayId' AS payload_gateway,
                       payload::jsonb ->> 'projectKey' AS project_key,
                       payload::jsonb ->> 'gatewayKey' AS gateway_key,
                       payload::jsonb ->> 'slaveAddress' AS slave_address,
                       payload::jsonb ->> 'functionCode' AS function_code,
                       payload::jsonb ->> 'registerAddress' AS register_address,
                       payload::jsonb ->> 'quantity' AS quantity
                  FROM sys_outbox_event WHERE project_id = ? AND event_type = ? ORDER BY id
                """)) {
            parameters(statement, fixture.projectId(), ModbusRequest.EVENT_TYPE);
            List<UUID> requestIds = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    assertThat(rows.getObject("tenant_id", UUID.class)).isEqualTo(fixture.tenantId());
                    assertThat(rows.getObject("project_id", UUID.class)).isEqualTo(fixture.projectId());
                    assertThat(rows.getString("aggregate_type")).isEqualTo("DEVICE_MODBUS");
                    assertThat(rows.getObject("aggregate_id", UUID.class)).isEqualTo(fixture.gatewayId());
                    assertThat(rows.getString("partition_key")).isEqualTo(fixture.gatewayId().toString());
                    assertThat(rows.getString("payload_tenant")).isEqualTo(fixture.tenantId().toString());
                    assertThat(rows.getString("payload_project")).isEqualTo(fixture.projectId().toString());
                    assertThat(rows.getString("payload_gateway")).isEqualTo(fixture.gatewayId().toString());
                    assertThat(rows.getString("project_key")).isEqualTo(fixture.projectKey());
                    assertThat(rows.getString("gateway_key")).isEqualTo("poll_gateway");
                    assertThat(rows.getInt("slave_address")).isEqualTo(2);
                    assertThat(rows.getString("function_code")).isEqualTo("READ_HOLDING_REGISTERS");
                    assertThat(rows.getInt("register_address")).isEqualTo(100);
                    assertThat(rows.getInt("quantity")).isEqualTo(2);
                    UUID requestId = UUID.fromString(rows.getString("request_id"));
                    assertThat(requestId.version()).isEqualTo(7);
                    requestIds.add(requestId);
                }
            }
            assertThat(requestIds).hasSize(expectedEvents).doesNotHaveDuplicates();
            assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IN_FLIGHT' AND attempt = ? AND request_id = ? AND lease_until > now()",
                    fixture.projectId(), expectedAttempt, requestIds.getLast())).isEqualTo(1);
        }
    }

    /** 回滚比较全部轮询字段，包括 lease、attempt、requestId 和时间，避免数量相同的假阳性。 */
    private List<String> pollSnapshot(Fixture fixture) throws SQLException {
        return snapshot(fixture, "SELECT row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ? ORDER BY id");
    }

    /** 配置 Outbox 的原始记录也必须保留，失败请求不能残留。 */
    private List<String> outboxSnapshot(Fixture fixture) throws SQLException {
        return snapshot(fixture, "SELECT row_to_json(e)::text FROM sys_outbox_event e WHERE project_id = ? ORDER BY id");
    }

    /** 固定查询只绑定当前夹具项目，独立 owner 连接观测最终提交事实。 */
    private List<String> snapshot(Fixture fixture, String sql) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            parameters(statement, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                List<String> rowsSnapshot = new ArrayList<>();
                while (rows.next()) rowsSnapshot.add(rows.getString(1));
                return rowsSnapshot;
            }
        }
    }

    /** 只沿 cause 主链判定最初错误；恢复失败可被附加但不能取代主因。 */
    private String rootSqlState(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root).isInstanceOf(SQLException.class);
        return ((SQLException) root).getSQLState();
    }

    /** 允许回滚外层事务测试在独立 owner 连接内执行可能抛出 SQL 异常的精确断言。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        /** 执行一次外层事务中的断言。 */
        void run() throws Exception;
    }
}
