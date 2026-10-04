package com.things.link.bootstrap.device.registration;

import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * D-028并发验收：架构§8.1的租户共享设备存量必须在真实租户锁后判定。
 * 两个DATA事务都由PostgreSQL阻塞图证明已进入锁队列，不以同时启动线程推断实际竞争。
 * 随机租户拥有两个项目；所有业务写入均以APP角色进行，无HTTP身份或伪造配额端口。
 */
@DisplayName("D-028 网关注册的真实租户配额竞争")
class GatewaySubDeviceRegistrationQuotaConcurrencyTests extends AbstractIntegrationTest {

    /** 独立连接读取JSONB全行事实，防止仅断言返回值而遗漏重复设备或错误事务提交。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 真实事务代理承担注册、inbox与回执原子性，不直接调用未代理对象。 */
    @Autowired private DeviceTopologyIngestionService ingestionService;
    /** 与真实服务共享同一事务连接，仅用于公布工作线程PID和验证应用角色。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 在进入服务前建立真实事务以观察PID；异常必须跨出该边界后才捕获。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** 两项目各有一台网关，共享最后一个设备名额；后到者只能形成quota_exceeded失败回执。 */
    @Test
    void twoProjectsOfSameOwnerCompeteForExactlyOneRemainingDeviceSlot() throws Exception {
        Fixture fixture = fixture();
        assertInitialQuota(fixture);
        DeviceTopologyMessage firstRequest = registerMessage(fixture, fixture.first(), "cross_project_first");
        DeviceTopologyMessage secondRequest = registerMessage(fixture, fixture.second(), "cross_project_second");

        RegistrationResults results = compete(fixture, firstRequest, secondRequest);

        JsonNode firstState = snapshot(fixture, fixture.first());
        JsonNode secondState = snapshot(fixture, fixture.second());
        JsonNode firstReply = replyFor(firstState, firstRequest.messageId());
        JsonNode secondReply = replyFor(secondState, secondRequest.messageId());
        String firstCode = firstReply.path("errorCode").isNull() ? "" : firstReply.path("errorCode").asString();
        String secondCode = secondReply.path("errorCode").isNull() ? "" : secondReply.path("errorCode").asString();
        assertSoftly(softly -> {
            // 两个消息均应完成业务判定并提交，失败回执不能被抛异常/重试替代。
            softly.assertThat(results.firstFailure()).as("第一注册事务应正常完成").isNull();
            softly.assertThat(results.secondFailure()).as("第二注册事务应提交稳定的配额判定").isNull();
            softly.assertThat(firstState.get("usage").get("tenant_used_value").asLong()).isEqualTo(3);
            softly.assertThat(secondState.get("usage").get("tenant_used_value").asLong()).isEqualTo(3);
            softly.assertThat(firstState.get("devices").size() + secondState.get("devices").size()).isEqualTo(3);
            softly.assertThat(firstState.get("topology").size() + secondState.get("topology").size()).isEqualTo(1);
            softly.assertThat(firstState.get("inbox")).hasSize(1);
            softly.assertThat(secondState.get("inbox")).hasSize(1);
            softly.assertThat(firstState.get("outbox")).hasSize(1);
            softly.assertThat(secondState.get("outbox")).hasSize(1);
            softly.assertThat(List.of(firstReply.path("status").asString(), secondReply.path("status").asString()))
                    .containsExactlyInAnyOrder("SUCCESS", "FAILED");
            softly.assertThat(List.of(firstCode, secondCode))
                    .containsExactlyInAnyOrder("", "quota_exceeded");
        });
        assertReplyIdentity(firstReply, firstRequest);
        assertReplyIdentity(secondReply, secondRequest);
        assertTopologyMatchesDevices(firstState);
        assertTopologyMatchesDevices(secondState);
    }

    /** 同一尚不存在的key同时排队后，后到消息必须重查已提交设备，而不是再占名额或重复INSERT。 */
    @Test
    void sameGatewayAndKeyWithDifferentMessagesCommitOneDeviceAndTwoSuccessReplies() throws Exception {
        Fixture fixture = fixture();
        assertInitialQuota(fixture);
        DeviceTopologyMessage firstRequest = registerMessage(fixture, fixture.first(), "shared_sub_key");
        DeviceTopologyMessage secondRequest = registerMessage(fixture, fixture.first(), "shared_sub_key");
        assertThat(firstRequest.messageId()).isNotEqualTo(secondRequest.messageId());

        RegistrationResults results = compete(fixture, firstRequest, secondRequest);

        JsonNode firstState = snapshot(fixture, fixture.first());
        JsonNode secondState = snapshot(fixture, fixture.second());
        JsonNode firstReply = replyFor(firstState, firstRequest.messageId());
        JsonNode secondReply = replyFor(firstState, secondRequest.messageId());
        assertSoftly(softly -> {
            softly.assertThat(results.firstFailure()).as("首次创建与绑定事务应提交").isNull();
            softly.assertThat(results.secondFailure()).as("等待后的相同key应成功幂等，不能重复INSERT导致事务失败").isNull();
            softly.assertThat(firstState.get("usage").get("tenant_used_value").asLong()).isEqualTo(3);
            softly.assertThat(firstState.get("devices")).hasSize(2);
            softly.assertThat(firstState.get("topology")).hasSize(1);
            softly.assertThat(secondState.get("devices")).hasSize(1);
            softly.assertThat(secondState.get("topology")).isEmpty();
            softly.assertThat(firstState.get("inbox")).hasSize(2);
            softly.assertThat(firstState.get("outbox")).hasSize(2);
            softly.assertThat(firstReply.path("status").asString()).isEqualTo("SUCCESS");
            softly.assertThat(secondReply.path("status").asString()).isEqualTo("SUCCESS");
            softly.assertThat(firstReply.path("errorCode").isNull()).isTrue();
            softly.assertThat(secondReply.path("errorCode").isNull()).isTrue();
        });
        assertReplyIdentity(firstReply, firstRequest);
        assertReplyIdentity(secondReply, secondRequest);
        assertTopologyMatchesDevices(firstState);
        // 相同messageId再次投递必须保持已提交回执与去重事实，不能把并发成功误作重复消费。
        ingestWithoutHttpContext(firstRequest);
        assertThat(snapshot(fixture, fixture.first())).isEqualTo(firstState);
    }

    /**
     * 独立APP闸门持有与生产仓储完全相同的租户advisory锁，两个消息必须先后真正等待该锁。
     * 观察器不借DATA池，避免池上限为2时测试自身饿死；所有失败清理先释放闸门再关闭线程。
     */
    private RegistrationResults compete(Fixture fixture, DeviceTopologyMessage firstRequest,
                                         DeviceTopologyMessage secondRequest) throws Exception {
        assertThat(AopUtils.isAopProxy(ingestionService)).isTrue();
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try (Connection gate = appConnection()) {
            gate.setAutoCommit(false);
            try {
                int gatePid = Integer.parseInt(text(gate, "SELECT pg_backend_pid()"));
                text(gate, "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 735::bigint))",
                        fixture.tenantId().toString());
                CompletableFuture<Integer> firstPid = new CompletableFuture<>();
                Future<Throwable> first = submitRegistration(requests, firstRequest, firstPid);
                int actualFirstPid = firstPid.get(5, TimeUnit.SECONDS);
                assertAdvisoryLockWait(actualFirstPid, gatePid, first);
                CompletableFuture<Integer> secondPid = new CompletableFuture<>();
                Future<Throwable> second = submitRegistration(requests, secondRequest, secondPid);
                int actualSecondPid = secondPid.get(5, TimeUnit.SECONDS);
                assertThat(actualSecondPid).isNotEqualTo(gatePid).isNotEqualTo(actualFirstPid);
                assertAdvisoryLockWait(actualSecondPid, actualFirstPid, second);
                gate.rollback();
                return new RegistrationResults(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                gate.rollback();
            }
        } finally {
            requests.shutdownNow();
            assertThat(requests.awaitTermination(25, TimeUnit.SECONDS)).as("并发注册线程必须有界退出").isTrue();
        }
    }

    /** 两个工作线程仅选择DATA连接池；服务自己设置事务RLS范围，不能借用伪造HTTP上下文完成配额查询。 */
    private Future<Throwable> submitRegistration(ExecutorService requests, DeviceTopologyMessage message,
                                                 CompletableFuture<Integer> pid) {
        return requests.submit(() -> {
            Throwable failure = catchThrowable(() -> {
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current()).isEmpty();
                try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.execute("SET LOCAL statement_timeout = '20s'");
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '15s'");
                        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                        pid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        ingestionService.ingest(message);
                        assertThat(TenantContext.current()).isEmpty();
                        assertThat(RlsScopeContext.current()).isEmpty();
                    });
                }
            });
            if (!pid.isDone()) {
                pid.completeExceptionally(failure == null ? new IllegalStateException("注册事务未公布PID") : failure);
            }
            return failure;
        });
    }

    /** 仅当等待者持有未授予的advisory锁且阻塞图包含指定持锁者/排队者时，才证明配额竞争前置成立。 */
    private void assertAdvisoryLockWait(int waitingPid, int blockingPid, Future<Throwable> request) throws Exception {
        assertThat(waitingPid).isNotEqualTo(blockingPid);
        try (Connection observer = appConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND NOT granted)
                """)) {
            parameters(query, blockingPid, waitingPid, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(blockingPid);
                    if (row.getBoolean(2) && row.getBoolean(3)) return;
                }
                if (request.isDone()) {
                    throw new AssertionError("注册请求未等待租户advisory锁便已结束", request.get());
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到注册事务 " + waitingPid + " 在advisory锁队列等待 " + blockingPid);
    }

    /** 顺序重放仍通过真实DATA服务事务，返回后不得留下HTTP身份或RLS线程范围。 */
    private void ingestWithoutHttpContext(DeviceTopologyMessage message) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            ingestionService.ingest(message);
        }
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 独占策略上限3且两个项目各有一台网关；草稿子类型隔离本片存量竞争，不重复模型发布验收。 */
    private Fixture fixture() throws SQLException {
        UUID tenantId = Uuid7.generate();
        UUID policyId = Uuid7.generate();
        ProjectFixture first = new ProjectFixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        ProjectFixture second = new ProjectFixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.setAutoCommit(false);
            try {
                execute(owner, "INSERT INTO public.sys_quota_policy (id, code, device_count_limit) VALUES (?, ?, 3)",
                        policyId, policyId.toString().replace("-", ""));
                execute(owner, "INSERT INTO public.sys_tenant (id, name, quota_policy_id) VALUES (?, '并发配额独占租户', ?)",
                        tenantId, policyId);
                for (ProjectFixture project : List.of(first, second)) {
                    execute(owner, "INSERT INTO public.sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '并发配额项目', ?)",
                            project.projectId(), tenantId, "quota_race_" + project.projectId().toString().replace("-", ""));
                }
                owner.commit();
            } finally {
                owner.rollback();
            }
        }
        for (ProjectFixture project : List.of(first, second)) {
            try (Connection application = scopedConnection(tenantId, project.projectId())) {
                try {
                    execute(application, """
                            INSERT INTO public.dev_type
                                (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                            VALUES (?, ?, ?, 'race_gateway_type', '并发网关类型', 'GATEWAY', 'STANDARD_GATEWAY', 'WIFI', 'DRAFT'),
                                   (?, ?, ?, 'race_sub_type', '并发子设备类型', 'SUB_DEVICE', 'STANDARD', 'WIFI', 'DRAFT')
                            """, project.gatewayTypeId(), tenantId, project.projectId(),
                            project.subTypeId(), tenantId, project.projectId());
                    execute(application, """
                            INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                            VALUES (?, ?, ?, ?, 'race_gateway', '并发配额网关', 'INACTIVE')
                            """, project.gatewayId(), tenantId, project.projectId(), project.gatewayTypeId());
                    application.commit();
                } finally {
                    application.rollback();
                }
            }
        }
        return new Fixture(tenantId, policyId, first, second);
    }

    /** 两个项目都通过实际投影读到租户存量2/上限3，不能误以项目各自1台尚有独立额度。 */
    private void assertInitialQuota(Fixture fixture) throws SQLException {
        for (ProjectFixture project : List.of(fixture.first(), fixture.second())) {
            JsonNode state = snapshot(fixture, project);
            assertThat(state.get("limit").asLong()).isEqualTo(3);
            assertThat(state.get("usage").get("project_used_value").asLong()).isEqualTo(1);
            assertThat(state.get("usage").get("tenant_used_value").asLong()).isEqualTo(2);
            assertThat(state.get("devices")).hasSize(1);
            assertThat(state.get("topology")).isEmpty();
            assertThat(state.get("inbox")).isEmpty();
            assertThat(state.get("outbox")).isEmpty();
        }
    }

    /** 两个请求以不同messageId进入相同生产注册路径，确权身份始终来自独占项目夹具。 */
    private DeviceTopologyMessage registerMessage(Fixture fixture, ProjectFixture project, String key) {
        return new DeviceTopologyMessage(Uuid7.generate(), fixture.tenantId(), project.projectId(), project.gatewayId(),
                DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER, key, "并发注册子设备", "race_sub_type",
                Instant.now(), "quota-race-" + UUID.randomUUID());
    }

    /** 独立APP连接只读取已提交事实；消息表额外显式project过滤，不依赖其是否启用RLS。 */
    private JsonNode snapshot(Fixture fixture, ProjectFixture project) throws SQLException {
        try (Connection connection = scopedConnection(fixture.tenantId(), project.projectId())) {
            return JSON.readTree(text(connection, """
                    SELECT jsonb_build_object(
                        'limit', (SELECT device_count_limit FROM public.sys_quota_policy WHERE id = ?),
                        'usage', (SELECT to_jsonb(u) FROM public.project_device_quota_usage() u),
                        'devices', COALESCE((SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM public.dev_device d), '[]'::jsonb),
                        'topology', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_topo t), '[]'::jsonb),
                        'inbox', COALESCE((SELECT jsonb_agg(to_jsonb(i) ORDER BY i.message_id) FROM public.sys_inbox_message i
                            WHERE i.project_id = ?), '[]'::jsonb),
                        'outbox', COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM public.sys_outbox_event o
                            WHERE o.project_id = ?), '[]'::jsonb))::text
                    """, fixture.policyId(), project.projectId(), project.projectId()));
        }
    }

    /** 回执缺失时返回空节点保留同轮软断言证据，最终仍必须验证两个requestId各自对应的真实回执。 */
    private JsonNode replyFor(JsonNode snapshot, UUID requestId) {
        for (JsonNode event : snapshot.get("outbox")) {
            if (TopologyReplyMessage.EVENT_TYPE.equals(event.get("event_type").asString())) {
                JsonNode reply = JSON.readTree(event.get("payload").asString());
                if (requestId.toString().equals(reply.path("requestId").asString())) return reply;
            }
        }
        return JSON.readTree("{}");
    }

    /** 成功与拒绝都必须对应真实请求归属，不能借另一项目的回执满足数量断言。 */
    private void assertReplyIdentity(JsonNode reply, DeviceTopologyMessage message) {
        assertThat(reply.path("requestId").asString()).isEqualTo(message.messageId().toString());
        assertThat(reply.path("tenantId").asString()).isEqualTo(message.tenantId().toString());
        assertThat(reply.path("projectId").asString()).isEqualTo(message.projectId().toString());
        assertThat(reply.path("gatewayId").asString()).isEqualTo(message.gatewayId().toString());
        assertThat(reply.path("subDeviceKey").asString()).isEqualTo(message.subDeviceKey());
    }

    /** 唯一拓扑必须指向真实新设备且与设备投影一致，排除只保住计数却遗留错误绑定。 */
    private void assertTopologyMatchesDevices(JsonNode state) {
        for (JsonNode topology : state.get("topology")) {
            assertThat(topology.get("unbound_at").isNull()).isTrue();
            JsonNode child = null;
            for (JsonNode device : state.get("devices")) {
                if (device.get("id").equals(topology.get("sub_device_id"))) child = device;
            }
            assertThat(child).as("拓扑子设备必须真实存在").isNotNull();
            assertThat(child.get("gateway_id")).isEqualTo(topology.get("gateway_device_id"));
        }
    }

    /** 原生APP观察连接不占用应用池，同时验证角色，避免owner权限掩盖生产数据面问题。 */
    private Connection appConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(text(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** APP种子和快照显式指定事务RLS范围；消息线程不复用这些连接或范围。 */
    private Connection scopedConnection(UUID tenantId, UUID projectId) throws SQLException {
        Connection connection = appConnection();
        try {
            connection.setAutoCommit(false);
            text(connection, "SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
            text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
            text(connection, "SELECT set_config('statement_timeout', '15s', true)");
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** 固定SQL模板使用参数绑定，写入事务的提交和回滚由上层显式管理。 */
    private void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            statement.executeUpdate();
        }
    }

    /** 单值查询必须实际返回一行，避免空作用域结果被当成零配额或空快照。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            statement.setQueryTimeout(3);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** UUID、数字与字符串保留JDBC类型，不把业务数据拼入SQL。 */
    private void parameters(PreparedStatement statement, Object... arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
    }

    /**
     * 同owner的两个项目共享唯一独占策略，跨项目竞争不会受其他测试存量影响。
     * @param tenantId 共享配额与advisory锁的所有者
     * @param policyId 上限固定为3的本例策略
     * @param first 首个网关所在项目
     * @param second 第二个网关所在项目
     */
    private record Fixture(UUID tenantId, UUID policyId, ProjectFixture first, ProjectFixture second) { }

    /**
     * 一个项目的合法网关/子类型和已存在网关，子设备仅允许生产注册路径创建。
     * @param projectId 项目RLS及回执归属
     * @param gatewayTypeId 网关类型真实主键
     * @param subTypeId 子设备类型真实主键
     * @param gatewayId 已认证网关身份
     */
    private record ProjectFixture(UUID projectId, UUID gatewayTypeId, UUID subTypeId, UUID gatewayId) { }

    /**
     * 两个事务越过提交/回滚边界后的真实结果，不能在事务内部吞掉异常后伪造正常结束。
     * @param firstFailure 首个注册事务失败，无异常时为NULL
     * @param secondFailure 第二个注册事务失败，无异常时为NULL
     */
    private record RegistrationResults(Throwable firstFailure, Throwable secondFailure) { }
}
