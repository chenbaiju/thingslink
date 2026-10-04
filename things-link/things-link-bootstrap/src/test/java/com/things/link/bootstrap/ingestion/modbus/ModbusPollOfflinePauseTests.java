package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusPollService;
import com.things.link.device.infrastructure.persistence.JdbcDeviceRepository;
import com.things.link.device.infrastructure.persistence.JdbcDeviceTypeRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * S12-P0-4d / D-029：真实 APP 事务只为当前在线且仍属平台驱动协议的网关追加轮询意图。
 * 离线判断不撤销既有 Outbox；租约到期取消本地关联，恢复后的当前周期不追赶历史次数。
 */
@DisplayName("Modbus 离线暂停及恢复边界")
class ModbusPollOfflinePauseTests extends AbstractModbusPollIntegrationTest {

    /** 通过服务公开事务入口保留领取、项目范围及真实 Outbox 的完整路径。 */
    @Autowired private ModbusPollService pollService;
    /** 只在一个测试的真实状态读取之后注入数据库故障，其他调用保留实际查询。 */
    @MockitoSpyBean private JdbcDeviceRepository deviceRepository;
    /** 类型查询故障与正常查无类型不同，必须单独验证不被吞掉。 */
    @MockitoSpyBean private JdbcDeviceTypeRepository typeRepository;

    /** 互斥仍按单批一个点位执行；两个扫描将各点暂停到自己的下一周期而不发送。 */
    @Test
    void offlineGatewayPausesEachDuePointWithoutAppendingRequests() throws Exception {
        Fixture fixture = fixture(points(2));
        pushConfig(fixture);
        Instant before = databaseNow();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        Instant after = databaseNow();
        assertPaused(fixture, 2, before, after);
        assertRequestCount(fixture, 0);
        // 暂停后没有新到期行，不能把离线视为连续失败而立即重试。
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
    }

    /** 已提交的发送意图保留，离线后仅取消超时请求的本地关联，不再追加第二次尝试。 */
    @Test
    void goingOfflineBeforeTimeoutPreservesOldOutboxAndClearsInFlightRequest() throws Exception {
        Fixture fixture = schedule(1, "ONLINE");
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isEqualTo(1);
        UUID originalRequest = activeRequestId(fixture);
        List<String> committedOutbox = outboxSnapshot(fixture);
        setStatus(fixture, "OFFLINE");
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_modbus_poll SET lease_until = now() - interval '1 second' WHERE project_id = ? AND status = 'IN_FLIGHT'",
                    fixture.projectId())).isEqualTo(1);
        }
        Instant before = databaseNow();
        assertThat(dataTransaction(() -> pollService.scanTimeout(100))).isEqualTo(1);
        Instant after = databaseNow();
        assertPaused(fixture, 1, before, after);
        assertThat(outboxSnapshot(fixture)).containsExactlyElementsOf(committedOutbox);
        assertRequestCount(fixture, 1);
        List<String> pausedPoll = pollSnapshot(fixture);
        ModbusResponse lateResponse = new ModbusResponse(originalRequest, fixture.tenantId(), fixture.projectId(),
                fixture.gatewayId(), ModbusResponse.Status.SUCCESS, List.of(0, 0), null, databaseNow(), "offline-late-response");
        assertThat(dataTransaction(() -> pollService.handleResponse(lateResponse))).isEmpty();
        assertThat(pollSnapshot(fixture)).containsExactlyElementsOf(pausedPoll);
        assertThat(dataTransaction(() -> pollService.scanTimeout(100))).isZero();
    }

    /** 同批不同租户项目独立判断在线事实，离线网关不能阻断另一网关的合法发送。 */
    @Test
    void mixedOnlineAndOfflineGatewaysOnlySendForTheOnlineProject() throws Exception {
        Fixture offline = schedule(1, "OFFLINE");
        Fixture online = schedule(1, "ONLINE");
        Instant before = databaseNow();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isEqualTo(1);
        Instant after = databaseNow();
        assertPaused(offline, 1, before, after);
        assertRequestCount(offline, 0);
        assertInFlight(online, 1);
        assertRequestCount(online, 1);
    }

    /** 状态恢复本身不补发；显式推进本例数据库到期事实后只发送当前一轮，仍保持网关互斥。 */
    @Test
    void reconnectWaitsForTheNextDuePeriodWithoutCatchingUpMissedRequests() throws Exception {
        Fixture fixture = schedule(2, "OFFLINE");
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        setStatus(fixture, "ONLINE");
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_modbus_poll SET next_poll_at = now() - interval '1 hour' WHERE project_id = ?",
                    fixture.projectId())).isEqualTo(2);
        }
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isEqualTo(1);
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        assertInFlight(fixture, 1);
        assertRequestCount(fixture, 1);
    }

    /** INACTIVE 是真实设备状态；不能因它不同于 OFFLINE 就允许未连接过的网关发送。 */
    @Test
    void neverConnectedGatewayIsPausedWithoutProducingAnOutboxEvent() throws Exception {
        Fixture fixture = schedule(1, "INACTIVE");
        Instant before = databaseNow();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        assertPaused(fixture, 1, before, databaseNow());
        assertRequestCount(fixture, 0);
    }

    /** 同 kind 协议切换是合法事实变化；历史物化行不能继续为非平台驱动网关生成请求。 */
    @Test
    void changingTheCurrentGatewayProtocolPausesPreviouslyMaterializedPolls() throws Exception {
        Fixture fixture = schedule(1, "ONLINE");
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, """
                    UPDATE dev_type SET access_protocol = 'STANDARD_GATEWAY'
                     WHERE id = (SELECT device_type_id FROM dev_device WHERE id = ?)
                    """, fixture.gatewayId())).isEqualTo(1);
        }
        Instant before = databaseNow();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        assertPaused(fixture, 1, before, databaseNow());
        assertRequestCount(fixture, 0);
    }

    /** 状态查询失败不能伪装离线；真实 PostgreSQL 故障须传播首因并回滚领取，恢复后可重领。 */
    @Test
    void failedLiveDeviceQueryRollsBackThePollAndScopeAndCanBeRetried() throws Exception {
        Fixture fixture = schedule(1, "ONLINE");
        List<String> oldPoll = pollSnapshot(fixture);
        List<String> oldOutbox = outboxSnapshot(fixture);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        JdbcDeviceRepository target = AopTestUtils.getUltimateTargetObject(deviceRepository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // 先确认真实读取已获当前项目范围，再令同一 PostgreSQL 事务失败。
            assertThat(jdbcTemplate.queryForObject("SELECT current_setting('app.project_id', true)", String.class))
                    .isEqualTo(fixture.projectId().toString());
            if (failOnce.getAndSet(false)) jdbcTemplate.queryForObject("SELECT 1 / 0", Integer.class);
            return result;
        }).when(target).findById(eq(fixture.projectId()), eq(fixture.gatewayId()));

        Throwable failure = catchThrowable(() -> dataTransaction(() -> pollService.scanDue(100)));
        assertThat(failure).isNotNull();
        assertThat(failOnce).isFalse();
        assertThat(rootSqlState(failure)).isEqualTo("22012");
        assertThat(pollSnapshot(fixture)).containsExactlyElementsOf(oldPoll);
        assertThat(outboxSnapshot(fixture)).containsExactlyElementsOf(oldOutbox);
        assertThat(dataTransaction(() -> jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event", Integer.class))).isZero();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isEqualTo(1);
        assertInFlight(fixture, 1);
        assertRequestCount(fixture, 1);
    }

    /** 合法关闭拓扑及投影后软删，旧物化仍存在但不能绕过设备或类型的软删过滤。 */
    @Test
    void softDeletedGatewayOrTypeCannotSendFromItsOldSchedule() throws Exception {
        Fixture deletedDevice = schedule(1, "ONLINE");
        Fixture deletedType = schedule(1, "ONLINE");
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            for (Fixture fixture : List.of(deletedDevice, deletedType)) {
                assertThat(execute(owner, "UPDATE dev_topo SET unbound_at = now() WHERE project_id = ? AND unbound_at IS NULL",
                        fixture.projectId())).isEqualTo(1);
                assertThat(execute(owner, "UPDATE dev_device SET gateway_id = NULL WHERE id = ?", fixture.subDeviceId())).isEqualTo(1);
            }
            assertThat(execute(owner, "UPDATE dev_device SET deleted_at = now() WHERE id = ?", deletedDevice.gatewayId())).isEqualTo(1);
            assertThat(execute(owner, "UPDATE dev_type SET deleted_at = now() WHERE id = (SELECT device_type_id FROM dev_device WHERE id = ?)",
                    deletedType.gatewayId())).isEqualTo(1);
            owner.commit();
        }
        Instant before = databaseNow();
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isZero();
        Instant after = databaseNow();
        for (Fixture fixture : List.of(deletedDevice, deletedType)) {
            assertPaused(fixture, 1, before, after);
            assertRequestCount(fixture, 0);
        }
    }

    /** 类型解析也是权限内真实查询；SQL失败不能冒充非平台协议并提交暂停状态。 */
    @Test
    void failedLiveTypeQueryRollsBackInsteadOfSilentlyPausing() throws Exception {
        Fixture fixture = schedule(1, "ONLINE");
        UUID typeId;
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT device_type_id FROM dev_device WHERE id = ?")) {
            parameters(statement, fixture.gatewayId());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                typeId = result.getObject(1, UUID.class);
            }
        }
        List<String> oldPoll = pollSnapshot(fixture);
        List<String> oldOutbox = outboxSnapshot(fixture);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        JdbcDeviceTypeRepository target = AopTestUtils.getUltimateTargetObject(typeRepository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (failOnce.getAndSet(false)) jdbcTemplate.queryForObject("SELECT 1 / 0", Integer.class);
            return result;
        }).when(target).findById(eq(fixture.projectId()), eq(typeId));
        Throwable failure = catchThrowable(() -> dataTransaction(() -> pollService.scanDue(100)));
        assertThat(failure).isNotNull();
        assertThat(failOnce).isFalse();
        assertThat(rootSqlState(failure)).isEqualTo("22012");
        assertThat(pollSnapshot(fixture)).containsExactlyElementsOf(oldPoll);
        assertThat(outboxSnapshot(fixture)).containsExactlyElementsOf(oldOutbox);
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isEqualTo(1);
        assertRequestCount(fixture, 1);
    }

    /** 仍由控制面真实物化；owner 只改变测试独占网关的实际连接状态。 */
    private Fixture schedule(int count, String status) throws SQLException {
        Fixture fixture = fixture(points(count));
        setStatus(fixture, status);
        pushConfig(fixture);
        return fixture;
    }

    /** 不触及其他测试实体，不伪造 poll 身份或 Outbox；连接事件链已由其独立测试覆盖。 */
    private void setStatus(Fixture fixture, String status) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_device SET status = ? WHERE id = ?", status, fixture.gatewayId())).isEqualTo(1);
        }
    }

    /** DATA 路由先于事务创建，连接以非 owner APP 身份执行并在成功后恢复空范围。 */
    private <T> T dataTransaction(Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertEmptyScope();
                T result = action.get();
                assertEmptyScope();
                return result;
            });
        }
    }

    /** SQL 范围和线程范围都要核验，不能仅依赖线程已清理推断借出连接没有遗留授权。 */
    private void assertEmptyScope() {
        assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.tenant_id', true), '')", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.project_id', true), '')", String.class)).isEmpty();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 取数据库墙钟窗口，周期断言不混用 JVM 时钟或固定睡眠。 */
    private Instant databaseNow() throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement("SELECT clock_timestamp()");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getTimestamp(1).toInstant();
        }
    }

    /** 暂停必须清除旧关联并按每点自己的周期推迟，不能统一固定延时或仍留在过期队列。 */
    private void assertPaused(Fixture fixture, int expectedPoints, Instant before, Instant after) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, """
                    SELECT count(*) FROM dev_modbus_poll
                     WHERE project_id = ? AND status = 'IDLE' AND attempt = 0 AND request_id IS NULL
                       AND lease_until IS NULL
                       AND next_poll_at = updated_at + polling_interval_ms * interval '1 millisecond'
                       AND next_poll_at >= ?::timestamptz + polling_interval_ms * interval '1 millisecond'
                       AND next_poll_at <= ?::timestamptz + polling_interval_ms * interval '1 millisecond'
                    """, fixture.projectId(), Timestamp.from(before), Timestamp.from(after))).isEqualTo(expectedPoints);
        }
    }

    /** 请求数只统计当前项目的 ModbusRequest，不把此前配置下发计入轮询发送。 */
    private void assertRequestCount(Fixture fixture, int expected) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = ?",
                    fixture.projectId(), ModbusRequest.EVENT_TYPE)).isEqualTo(expected);
        }
    }

    /** 恢复发送仍是一次尝试和一条在途关联，不能以数量正确掩盖补发或重复关联。 */
    private void assertInFlight(Fixture fixture, int expected) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, """
                    SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IN_FLIGHT'
                     AND attempt = 1 AND request_id IS NOT NULL AND lease_until > now()
                    """, fixture.projectId())).isEqualTo(expected);
        }
    }

    /** 从真实在途行取得关联 ID，以迟到响应验证暂停后旧关联已被取消。 */
    private UUID activeRequestId(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT request_id FROM dev_modbus_poll WHERE project_id = ? AND status = 'IN_FLIGHT'")) {
            parameters(statement, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                UUID requestId = rows.getObject(1, UUID.class);
                assertThat(requestId).isNotNull();
                assertThat(rows.next()).isFalse();
                return requestId;
            }
        }
    }

    /** 比较全部轮询字段，回滚断言包含租约及原到期时间。 */
    private List<String> pollSnapshot(Fixture fixture) throws SQLException {
        return snapshot(fixture, "SELECT row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ? ORDER BY id");
    }

    /** 既有 Outbox 应逐字段不变，离线暂停既不撤销也不重写已提交的发送意图。 */
    private List<String> outboxSnapshot(Fixture fixture) throws SQLException {
        return snapshot(fixture, "SELECT row_to_json(e)::text FROM sys_outbox_event e WHERE project_id = ? ORDER BY id");
    }

    /** owner 独立连接观察最终提交或回滚结果，不从当前 APP 快照推断持久事实。 */
    private List<String> snapshot(Fixture fixture, String sql) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            parameters(statement, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getString(1));
                return List.copyOf(result);
            }
        }
    }

    /** 只沿首因链查 SQLSTATE，恢复范围的次级失败不能覆盖原始数据库错误。 */
    private String rootSqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }
}
