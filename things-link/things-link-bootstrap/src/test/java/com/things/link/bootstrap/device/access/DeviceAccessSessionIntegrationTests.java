package com.things.link.bootstrap.device.access;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostgreSQL 下的接入配置代次与会话归属：配置代次只在真正变化时递增、建立会话接管既有会话、
 * 旧归属实例的心跳被拒、并发建立由数据库仲裁、存量设备不得建立新协议会话。
 */
class DeviceAccessSessionIntegrationTests extends AbstractIntegrationTest {

    /** 真实接入会话端口。 */
    @Autowired
    private DeviceAccessSessionPort sessionPort;

    /** 真实连接诊断读端口。 */
    @Autowired
    private com.things.link.device.application.DeviceAccessDiagnosticsPort diagnosticsPort;

    /** 配置写入仓储（开通协议）；接入配置的运维入口属控制面，测试以显式项目范围驱动它。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 应用角色连接，用于在事务内建立项目范围后驱动配置写入。 */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 显式事务，保证 RLS 范围在连接首次取出前已设置。 */
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** 本类独占夹具，测试结束后按依赖顺序回收。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 不残留夹具事实；清理失败不掩盖断言结果。 */
    @AfterEach
    void clearFixtures() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                execute(owner, "DELETE FROM dev_connection WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 存量设备（没有配置行）使用 MQTT：新协议连接一律拒绝。 */
    @Test
    void legacyDeviceWithoutBindingRejectsNewProtocol() throws SQLException {
        Fixture fixture = seed();

        DeviceAccessSessionPort.Establishment rejection = establish(fixture, TransportProtocol.TCP, "conn-1", "node-a");

        assertThat(rejection).isEqualTo(new DeviceAccessSessionPort.Establishment.Rejected(
                DeviceAccessSessionPort.Rejection.PROTOCOL_MISMATCH));
        assertThat(activeCount(fixture)).isZero();
    }

    /** 配置代次只在协议或心跳变化时递增：重复提交同一配置是幂等重放。 */
    @Test
    void configVersionIncrementsOnlyOnRealChange() {
        Fixture fixture = seed();

        assertThat(bind(fixture, TransportProtocol.TCP, 30).configVersion()).isEqualTo(1);
        assertThat(bind(fixture, TransportProtocol.TCP, 30).configVersion()).as("同一配置重放不改代次").isEqualTo(1);
        assertThat(bind(fixture, TransportProtocol.TCP, 60).configVersion()).isEqualTo(2);
        assertThat(bind(fixture, TransportProtocol.COAP, 60).configVersion()).isEqualTo(3);
        assertThat(bind(fixture, TransportProtocol.COAP, 60).configVersion()).isEqualTo(3);
    }

    /** 建立会话写入代次与归属；第二次建立接管第一次，旧实例心跳与关闭都被拒。 */
    @Test
    void newSessionTakesOverPriorSessionAndRejectsStaleOwner() throws SQLException {
        Fixture fixture = seed();
        bind(fixture, TransportProtocol.TCP, 30);

        DeviceAccessSessionPort.Established first = established(establish(fixture, TransportProtocol.TCP, "conn-1",
                "node-a"));
        assertThat(first.generation()).isEqualTo(1);
        assertThat(first.configVersion()).isEqualTo(1);
        assertThat(first.replacedPriorSession()).isFalse();
        assertThat(sessionPort.touch(fixture.tenantId(), fixture.projectId(), fixture.deviceId(), first.rowId(),
                "node-a")).isTrue();

        DeviceAccessSessionPort.Established second = established(establish(fixture, TransportProtocol.TCP, "conn-2",
                "node-b"));
        assertThat(second.generation()).as("会话代次在设备内单调递增").isEqualTo(2);
        assertThat(second.replacedPriorSession()).isTrue();
        assertThat(disconnectReason(first.rowId())).isEqualTo("replaced_by_new_session");
        assertThat(sessionPort.touch(fixture.tenantId(), fixture.projectId(), fixture.deviceId(), first.rowId(),
                "node-a")).as("旧归属实例的心跳必须被拒").isFalse();
        assertThat(sessionPort.close(fixture.tenantId(), fixture.projectId(), fixture.deviceId(), first.rowId(),
                "node-a", "client_disconnect")).as("被接管的实例不能关闭新会话").isFalse();

        DeviceAccessSessionPort.Active active = sessionPort.active(fixture.tenantId(), fixture.projectId(),
                fixture.deviceId()).orElseThrow();
        assertThat(active.sessionId()).isEqualTo("conn-2");
        assertThat(active.ownerInstance()).isEqualTo("node-b");
        assertThat(active.generation()).isEqualTo(2);
        assertThat(active.lastSeenAt()).as("建立即视为首次活动").isNotNull();

        assertThat(sessionPort.close(fixture.tenantId(), fixture.projectId(), fixture.deviceId(), second.rowId(),
                "node-b", "client_disconnect")).isTrue();
        assertThat(sessionPort.active(fixture.tenantId(), fixture.projectId(), fixture.deviceId())).isEmpty();
        assertThat(activeCount(fixture)).isZero();
    }

    /** 协议不一致或配置关闭时拒绝建立，且不得影响既有活跃会话。 */
    @Test
    void mismatchOrDisabledRejectsWithoutClosingActiveSession() throws SQLException {
        Fixture fixture = seed();
        bind(fixture, TransportProtocol.TCP, 30);
        DeviceAccessSessionPort.Established established = established(establish(fixture, TransportProtocol.TCP,
                "conn-1", "node-a"));

        assertThat(establish(fixture, TransportProtocol.HTTP, "http-1", "node-a"))
                .isEqualTo(new DeviceAccessSessionPort.Establishment.Rejected(
                        DeviceAccessSessionPort.Rejection.PROTOCOL_MISMATCH));
        disableBinding(fixture);
        assertThat(establish(fixture, TransportProtocol.TCP, "conn-2", "node-b"))
                .isEqualTo(new DeviceAccessSessionPort.Establishment.Rejected(
                        DeviceAccessSessionPort.Rejection.DISABLED));

        assertThat(activeCount(fixture)).as("配置不允许不等于接管既有会话").isEqualTo(1);
        assertThat(fragmentCount(established.rowId())).isEqualTo(0);
    }

    /** 并发建立由数据库仲裁：只留下一个活跃会话，且只有一个实例报告接管。 */
    @Test
    void concurrentEstablishmentsAreArbitratedByDatabase() throws Exception {
        Fixture fixture = seed();
        bind(fixture, TransportProtocol.TCP, 30);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<DeviceAccessSessionPort.Establishment>> tasks = List.of(
                    () -> awaitThenEstablish(start, fixture, "conn-a", "node-a"),
                    () -> awaitThenEstablish(start, fixture, "conn-b", "node-b"));
            List<Future<DeviceAccessSessionPort.Establishment>> futures = tasks.stream()
                    .map(pool::submit).toList();
            start.countDown();
            List<DeviceAccessSessionPort.Established> established = new ArrayList<>();
            for (Future<DeviceAccessSessionPort.Establishment> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS))
                        .isInstanceOf(DeviceAccessSessionPort.Establishment.Allowed.class);
                established.add(established(future.get()));
            }
            assertThat(activeCount(fixture)).as("并发建立只留下一个活跃会话").isEqualTo(1);
            assertThat(established).extracting(DeviceAccessSessionPort.Established::generation)
                    .containsExactlyInAnyOrder(1L, 2L);
            assertThat(established).filteredOn(DeviceAccessSessionPort.Established::replacedPriorSession)
                    .as("只有一个实例接管了既有会话").hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 连接诊断：在线与最近活动分列，断开后保留原因，且不返回任何凭据字段。 */
    @Test
    void diagnosticsSeparateOnlineFromLastActivity() throws SQLException {
        Fixture fixture = seed();
        assertThat(diagnosticsPort.connection(fixture.tenantId(), fixture.projectId(), fixture.deviceId()))
                .as("存量设备诊断按 MQTT 代次 0 呈现")
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.protocol()).isEqualTo(TransportProtocol.MQTT);
                    assertThat(snapshot.configVersion()).isZero();
                    assertThat(snapshot.enabled()).isTrue();
                    assertThat(snapshot.online()).isFalse();
                    assertThat(snapshot.lastSeenAt()).isNull();
                });

        bind(fixture, TransportProtocol.TCP, 30);
        DeviceAccessSessionPort.Established established = established(establish(fixture, TransportProtocol.TCP,
                "conn-1", "node-a"));

        assertThat(diagnosticsPort.connection(fixture.tenantId(), fixture.projectId(), fixture.deviceId()))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.protocol()).isEqualTo(TransportProtocol.TCP);
                    assertThat(snapshot.configVersion()).isEqualTo(1);
                    assertThat(snapshot.online()).isTrue();
                    assertThat(snapshot.sessionId()).isEqualTo("conn-1");
                    assertThat(snapshot.ownerInstance()).isEqualTo("node-a");
                    assertThat(snapshot.generation()).isEqualTo(1);
                    assertThat(snapshot.connectedAt()).isNotNull();
                    assertThat(snapshot.lastSeenAt()).isNotNull();
                });

        assertThat(sessionPort.close(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                established.rowId(), "node-a", "client_disconnect")).isTrue();
        assertThat(diagnosticsPort.connection(fixture.tenantId(), fixture.projectId(), fixture.deviceId()))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.online()).as("断开后不得再显示在线").isFalse();
                    assertThat(snapshot.lastDisconnectReason()).isEqualTo("client_disconnect");
                    assertThat(snapshot.lastDisconnectedAt()).isNotNull();
                });
    }

    /** 诊断只对项目内存在的设备返回事实，越域设备返回空而不是空快照。 */
    @Test
    void diagnosticsReturnEmptyForUnknownDevice() {
        Fixture fixture = seed();

        assertThat(diagnosticsPort.connection(fixture.tenantId(), fixture.projectId(), UUID.randomUUID())).isEmpty();
    }

    /**
     * 等待起跑信号后建立会话。
     *
     * @param start 起跑信号
     * @param fixture 独占夹具
     * @param sessionId 会话标识
     * @param ownerInstance 归属实例
     * @return 建立结果
     * @throws InterruptedException 等待被中断
     */
    private DeviceAccessSessionPort.Establishment awaitThenEstablish(CountDownLatch start, Fixture fixture,
                                                                    String sessionId, String ownerInstance)
            throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        return establish(fixture, TransportProtocol.TCP, sessionId, ownerInstance);
    }

    /**
     * 执行一次会话建立。
     *
     * @param fixture 独占夹具
     * @param protocol 协议
     * @param sessionId 会话标识
     * @param ownerInstance 归属实例
     * @return 建立结果
     */
    private DeviceAccessSessionPort.Establishment establish(Fixture fixture, TransportProtocol protocol,
                                                            String sessionId, String ownerInstance) {
        return sessionPort.establish(new DeviceAccessSessionPort.EstablishmentRequest(fixture.tenantId(),
                fixture.projectId(), fixture.deviceId(), protocol, sessionId, ownerInstance, "203.0.113.7", protocol==TransportProtocol.TCP?30_000L:null));
    }

    /**
     * 从建立结果中取出会话事实。
     *
     * @param establishment 建立结果
     * @return 会话事实
     */
    private static DeviceAccessSessionPort.Established established(DeviceAccessSessionPort.Establishment establishment) {
        assertThat(establishment).isInstanceOf(DeviceAccessSessionPort.Establishment.Allowed.class);
        return ((DeviceAccessSessionPort.Establishment.Allowed) establishment).established();
    }

    /**
     * 写入生效接入配置。
     *
     * @param fixture 独占夹具
     * @param protocol 协议
     * @param heartbeatSeconds 心跳周期
     * @return 写入后的配置
     */
    private DeviceAccessBinding bind(Fixture fixture, TransportProtocol protocol, Integer heartbeatSeconds) {
        return inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(),
                fixture.deviceId(), protocol, heartbeatSeconds));
    }

    /**
     * 在夹具项目范围内执行一次应用角色调用。
     *
     * @param fixture 独占夹具
     * @param work 范围内动作
     * @param <T> 返回值类型
     * @return 动作结果
     */
    private <T> T inProject(Fixture fixture, java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    fixture.tenantId().toString());
            jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                    fixture.projectId().toString());
            return work.get();
        });
    }

    /**
     * 关闭接入配置，模拟运营停用。
     *
     * @param fixture 独占夹具
     * @throws SQLException 更新失败
     */
    private void disableBinding(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE dev_access_binding SET enabled = false,config_version=config_version+1 WHERE device_id = ?", fixture.deviceId());
        }
    }

    /**
     * 统计设备当前活跃会话数。
     *
     * @param fixture 独占夹具
     * @return 活跃会话数
     * @throws SQLException 查询失败
     */
    private int activeCount(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT count(*) FROM dev_connection WHERE device_id = ? AND disconnected_at IS NULL")) {
            statement.setObject(1, fixture.deviceId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * 读取会话行的断开原因。
     *
     * @param rowId 会话行 ID
     * @return 断开原因；仍活跃时为 null
     * @throws SQLException 查询失败
     */
    private String disconnectReason(UUID rowId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT disconnect_reason FROM dev_connection WHERE id = ?")) {
            statement.setObject(1, rowId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /**
     * 统计夹具设备上残留的历史片段，用于确认“拒绝”没有顺手断开既有会话。
     *
     * @param rowId 会话行 ID
     * @return 该会话是否已被标记断开（0 表示仍活跃）
     * @throws SQLException 查询失败
     */
    private int fragmentCount(UUID rowId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT count(*) FROM dev_connection WHERE id = ? AND disconnected_at IS NOT NULL")) {
            statement.setObject(1, rowId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * 播种独占租户、项目、直连设备类型与设备。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败直接终止用例
     */
    private Fixture seed() {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '会话代次独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '会话代次项目', ?)",
                    fixture.projectId(), fixture.tenantId(), "ax1e_" + fixture.projectId().toString()
                            .replace("-", "").substring(24));
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax1e_type', '会话代次类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax1e_device', '会话代次设备', 'OFFLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
        } catch (SQLException exception) {
            throw new IllegalStateException("会话代次夹具播种失败", exception);
        }
        return fixture;
    }

    /**
     * owner 连接用于夹具播种与独立旁观。
     *
     * @return 调用方负责关闭的连接
     * @throws SQLException 连接失败
     */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * 参数化执行夹具语句。
     *
     * @param connection owner 连接
     * @param sql 语句
     * @param arguments 参数
     * @throws SQLException 执行失败
     */
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }

    /**
     * 独占夹具身份。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceId 直连设备
     * @param typeId 设备类型
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId) {
    }
}
