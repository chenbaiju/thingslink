package com.things.link.bootstrap.ingestion.realtime;

import com.things.link.ingestion.application.RealtimeAuthorizationService;
import com.things.link.ingestion.application.RealtimeConnection;
import com.things.link.ingestion.application.RealtimePrincipal;
import com.things.link.ingestion.application.RealtimeProjectAccessDeniedException;
import com.things.link.ingestion.application.RealtimePropertyBatch;
import com.things.link.ingestion.application.RealtimeSubscriptionRegistry;
import com.things.link.ingestion.application.RealtimeSubscriptionService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PostgreSQL RLS与Redis租约下验收WebSocket成员、项目和设备持续授权。 */
class RealtimeWebSocketProjectLifecycleTests extends AbstractIntegrationTest {

    /** 生产项目/设备授权端口，不能用mock替代RLS结果。 */
    @Autowired
    private RealtimeAuthorizationService authorization;
    /** 生产SUBSCRIBE协议服务。 */
    @Autowired
    private RealtimeSubscriptionService subscriptions;
    /** 生产本机会话与真实Redis共享租约。 */
    @Autowired
    private RealtimeSubscriptionRegistry registry;
    /** 构造真实增量JSON值。 */
    @Autowired
    private ObjectMapper mapper;

    /** 每例建立的会话ID，用于失败路径也能释放真实Redis租约。 */
    private final List<String> connections = new ArrayList<>();
    /** 每例建立的数据库夹具，按依赖逆序清理。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 清理会话、线程范围与持久夹具，避免共享容器污染后续全量验证。 */
    @AfterEach
    void clean() throws SQLException {
        connections.forEach(registry::unregister);
        TenantContext.clear();
        try (Connection owner = fixtureOwnerConnection()) {
            for (Fixture fixture : fixtures.reversed()) {
                execute(owner, "DELETE FROM dev_device WHERE project_id=?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project_member WHERE project_id=?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id=?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant_member WHERE account_id=?", fixture.accountId());
                execute(owner, "DELETE FROM sys_account WHERE id=?", fixture.accountId());
                execute(owner, "DELETE FROM sys_tenant WHERE id IN (?,?)",
                        fixture.ownerTenantId(), fixture.collaboratorTenantId());
            }
        }
    }

    /** 跨租户成员在ACTIVE和ARCHIVED均可读；DELETING或成员移除必须权威拒绝且清理scope。 */
    @Test
    void authoritativeProjectAccessKeepsArchivedReadButRejectsDeletingAndRemovedMember() throws Exception {
        Fixture fixture = seed();
        RealtimePrincipal principal = fixture.principal();

        authorization.requireProjectAccess(principal);
        updateProjectStatus(fixture, "ARCHIVED");
        authorization.requireProjectAccess(principal);
        assertThat(TenantContext.current()).isEmpty();

        updateProjectStatus(fixture, "DELETING");
        assertThatThrownBy(() -> authorization.requireProjectAccess(principal))
                .isInstanceOf(RealtimeProjectAccessDeniedException.class);
        assertThat(TenantContext.current()).isEmpty();

        updateProjectStatus(fixture, "ACTIVE");
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project_member SET status='DISABLED' "
                    + "WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.accountId());
        }
        assertThatThrownBy(() -> authorization.requireProjectAccess(principal))
                .isInstanceOf(RealtimeProjectAccessDeniedException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    /** 已建立连接在下一次SUBSCRIBE观察到项目失权后须1008关闭、清旧订阅且恢复不复活。 */
    @Test
    void subscribeAfterDeletingRevokesSessionAndNeverRevivesAfterRecovery() throws Exception {
        Fixture fixture = seed();
        TestConnection connection = register(fixture);
        subscriptions.handle(connection, subscribe("initial", fixture.deviceId()));
        assertThat(connection.awaitMessage()).contains("SUBSCRIBED");

        updateProjectStatus(fixture, "DELETING");
        subscriptions.handle(connection, subscribe("after-delete", fixture.deviceId()));

        assertThat(connection.closed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(connection.closeCode).isEqualTo(1008);
        assertThat(connection.closeReason).isEqualTo("realtime authorization revoked");
        assertThat(registry.connectionCount()).isZero();
        assertThat(connection.pollMessage()).isNull();

        updateProjectStatus(fixture, "ACTIVE");
        registry.fanout(batch(fixture, "27.5"));
        assertThat(connection.pollMessage()).isNull();
        assertThat(registry.connectionCount()).isZero();
    }

    /** 真实设备归属错误只返回INVALID并保留此前合法订阅，ARCHIVED仍允许实时只读。 */
    @Test
    void deviceErrorKeepsOldSubscriptionAndArchivedMemberStillReceives() throws Exception {
        Fixture fixture = seed();
        TestConnection connection = register(fixture);
        subscriptions.handle(connection, subscribe("initial", fixture.deviceId()));
        assertThat(connection.awaitMessage()).contains("SUBSCRIBED");

        subscriptions.handle(connection, subscribe("missing-device", Uuid7.generate()));
        assertThat(connection.awaitMessage()).contains("ERROR", "INVALID_SUBSCRIPTION");
        updateProjectStatus(fixture, "ARCHIVED");
        subscriptions.handle(connection, subscribe("archived", fixture.deviceId()));
        assertThat(connection.awaitMessage()).contains("SUBSCRIBED", "archived");

        registry.fanout(batch(fixture, "28.0"));
        assertThat(connection.awaitMessage()).contains("PROPERTY_BATCH", "temperature", "28.0");
        assertThat(connection.closeCode).isNull();
    }

    /** 删除恢复后的高代项目永久拒绝零代旧连接；持续复核和注册均1008，新代连接正常。 */
    @Test
    void lifecycleGenerationRevokesOldConnectionsAndAllowsCurrentGeneration() throws Exception {
        Fixture fixture = seed();
        RealtimePrincipal oldPrincipal = fixture.principal();
        TestConnection established = register(fixture);
        subscriptions.handle(established, subscribe("initial-generation", fixture.deviceId()));
        assertThat(established.awaitMessage()).contains("SUBSCRIBED");

        updateProjectGeneration(fixture, 1L);
        subscriptions.handle(established, subscribe("stale-generation", fixture.deviceId()));

        assertThat(established.closed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(established.closeCode).isEqualTo(1008);
        assertThat(established.closeReason).isEqualTo("realtime authorization revoked");
        assertThat(registry.connectionCount()).isZero();

        TestConnection stale = new TestConnection("ws-stale-" + Uuid7.generate(), oldPrincipal);
        assertThat(registry.register(stale)).isFalse();
        assertThat(stale.closeCode).isEqualTo(1008);
        assertThat(stale.closeReason).isEqualTo("realtime authorization revoked");

        RealtimePrincipal currentPrincipal = fixture.principal(1L);
        authorization.requireProjectAccess(currentPrincipal);
        TestConnection current = new TestConnection("ws-current-" + Uuid7.generate(), currentPrincipal);
        assertThat(registry.register(current)).isTrue();
        connections.add(current.id());
        subscriptions.handle(current, subscribe("current-generation", fixture.deviceId()));
        assertThat(current.awaitMessage()).contains("SUBSCRIBED");
    }

    /** 先以真实项目授权模拟已通过握手，再进入生产注册表取得共享租约。 */
    private TestConnection register(Fixture fixture) {
        authorization.requireProjectAccess(fixture.principal());
        TestConnection connection = new TestConnection(
                "ws-lifecycle-" + Uuid7.generate(), fixture.principal());
        assertThat(registry.register(connection)).isTrue();
        connections.add(connection.id());
        return connection;
    }

    /** 建立owner项目、跨租户ADMIN成员与一台有效设备。 */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate());
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, 'WS OWNER'), (?, 'WS COLLABORATOR')",
                    fixture.ownerTenantId(), fixture.collaboratorTenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) "
                            + "VALUES (?, ?, '{noop}unused', 'WS ADMIN')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) "
                            + "VALUES (?,?,'WS生命周期项目','sh-1',?)",
                    fixture.projectId(), fixture.ownerTenantId(),
                    "ws_lifecycle_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,status) "
                            + "VALUES (?,?,?,'ws_device','WS设备','ONLINE')",
                    fixture.deviceId(), fixture.ownerTenantId(), fixture.projectId());
            owner.commit();
        }
        fixtures.add(fixture);
        return fixture;
    }

    /** 独立提交项目状态，模拟生命周期事务已对其他连接可见。 */
    private void updateProjectStatus(Fixture fixture, String status) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status=? WHERE id=?", status, fixture.projectId());
        }
    }

    /** 模拟删除后恢复为ACTIVE但代次已递增；不能把状态恢复误当作旧JWT复活。 */
    private void updateProjectGeneration(Fixture fixture, long generation) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET lifecycle_generation=? WHERE id=?",
                    generation, fixture.projectId());
        }
    }

    /** 构造一条单设备SUBSCRIBE协议帧。 */
    private static String subscribe(String requestId, UUID deviceId) {
        return """
                {"type":"SUBSCRIBE","requestId":"%s","subscriptions":[
                {"deviceId":"%s","propertyKeys":["temperature"]}]}
                """.formatted(requestId, deviceId);
    }

    /** 构造Redis解码后进入本机注册表的已提交增量。 */
    private RealtimePropertyBatch batch(Fixture fixture, String value) {
        return new RealtimePropertyBatch(fixture.projectId(), fixture.deviceId(),
                Instant.parse("2026-09-04T00:00:00Z"), 1, Uuid7.generate(), "1.0.0",
                Map.of("temperature", "NUMBER"), Map.of("temperature", mapper.readTree(value)));
    }

    /** 夹具SQL统一使用预编译参数。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    /** 项目owner tenant与JWT tenant故意不同，证明协作者不污染持久归属。 */
    private record Fixture(UUID ownerTenantId, UUID collaboratorTenantId, UUID projectId,
                           UUID accountId, UUID deviceId) {
        /** @return 与真实跨租户协作者JWT同形的实时身份 */
        private RealtimePrincipal principal() {
            return principal(0L);
        }

        /** @param generation 已验签JWT中的项目生命周期代次 @return 指定代次实时身份 */
        private RealtimePrincipal principal(long generation) {
            return new RealtimePrincipal(accountId, collaboratorTenantId, projectId, generation,
                    Instant.parse("2030-01-01T00:00:00Z"));
        }
    }

    /** 捕获生产异步sender输出和关闭边界。 */
    private static final class TestConnection implements RealtimeConnection {
        /** 会话ID。 */ private final String id;
        /** 已验签身份。 */ private final RealtimePrincipal principal;
        /** 实际异步输出。 */ private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        /** 关闭同步点。 */ private final CountDownLatch closed = new CountDownLatch(1);
        /** RFC关闭码。 */ private volatile Integer closeCode;
        /** 稳定关闭原因。 */ private volatile String closeReason;

        /** @param id 会话ID @param principal 已验签身份 */
        private TestConnection(String id, RealtimePrincipal principal) {
            this.id = id;
            this.principal = principal;
        }

        /** {@inheritDoc} */ @Override public String id() { return id; }
        /** {@inheritDoc} */ @Override public RealtimePrincipal principal() { return principal; }
        /** {@inheritDoc} */ @Override public void sendText(String payload) { messages.add(payload); }
        /** {@inheritDoc} */
        @Override public void close(int statusCode, String reason) {
            closeCode = statusCode;
            closeReason = reason;
            closed.countDown();
        }
        /** @return 两秒内下一帧 */
        private String awaitMessage() throws InterruptedException { return messages.poll(2, TimeUnit.SECONDS); }
        /** @return 短观察期下一帧 */
        private String pollMessage() throws InterruptedException { return messages.poll(200, TimeUnit.MILLISECONDS); }
    }
}
