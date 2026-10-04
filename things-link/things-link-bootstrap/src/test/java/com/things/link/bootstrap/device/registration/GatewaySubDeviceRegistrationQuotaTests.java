package com.things.link.bootstrap.device.registration;

import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.application.ThingModelVersionService;
import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.domain.QuotaOverviewRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.doThrow;

/**
 * D-028真实数据面反例：架构§8.1/§8.2规定设备共享存量达到硬限后不得通过网关继续新增。
 * 直接调用生产拓扑事务服务，不设置HTTP身份；真实模型发布使越额新增时的INITIAL历史也可观测。
 * 每例独占租户、项目和策略，完整保留业务及消息事实，不清空其他测试数据或修改公共FREE策略。
 */
@DisplayName("D-028 网关注册的租户设备存量硬限")
class GatewaySubDeviceRegistrationQuotaTests extends AbstractIntegrationTest {

    /** JSONB全行快照用于同时核对身份、版本历史、拓扑、凭据与事务消息，避免仅凭回执判断。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 新注册请求只能通过真实拓扑状态机产生设备、历史、绑定、inbox与回执。 */
    @Autowired private DeviceTopologyIngestionService ingestionService;
    /** 用生产发布服务建立具体版本，避免手工版本行使INITIAL路径成为无效夹具。 */
    @Autowired private ThingModelVersionService versionService;
    /** 网关种子复用生产设备仓储，注册中的子设备仍由拓扑服务独立创建。 */
    @Autowired private DeviceRepository deviceRepository;
    /** 显式数据面配额端口用于证明无效二元组返回失败而不是合法0/0。 */
    @Autowired private ProjectQuotaService projectQuotaService;
    /** 仅故障例代理真实策略仓储，证明不可判定时整体回滚而不伪造配额业务回执。 */
    @MockitoSpyBean private QuotaOverviewRepository quotaOverviewRepository;
    /** 在种子服务事务中验证真实数据库角色，防止owner执行掩盖应用权限问题。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 类型发布与网关创建共享真实应用事务，失败时不保留半成品种子。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** 已有一台网关用满一台额度时，子设备注册必须业务拒绝且不产生设备、INITIAL或拓扑。 */
    @Test
    void hardLimitRejectsNewSubDeviceWithoutHttpContextAndPreservesBusinessFacts() throws Exception {
        Fixture fixture = fixture(1);
        JsonNode before = snapshot(fixture);
        assertUsage(before, 1, 1);
        assertThat(before.get("business").get("devices")).hasSize(1);
        assertThat(before.get("business").get("history")).isEmpty();
        assertThat(before.get("business").get("topology")).isEmpty();
        assertThat(before.get("inbox")).isEmpty();
        assertThat(before.get("outbox")).isEmpty();
        DeviceTopologyMessage request = registerMessage(fixture, "quota_new_sub");

        Throwable failure = catchThrowable(() -> ingestWithoutHttpContext(request));

        JsonNode after = snapshot(fixture);
        JsonNode reply = replyFor(after, request.messageId());
        assertSoftly(softly -> {
            // 业务配额拒绝沿既有ack+FAILED回执合同提交；异常重试不等价于稳定的硬限拒绝。
            softly.assertThat(failure).as("配额耗尽是确定性业务拒绝").isNull();
            softly.assertThat(after.get("business")).as("越限不得新增设备/INITIAL/拓扑/凭据或改写已有事实")
                    .isEqualTo(before.get("business"));
            softly.assertThat(after.get("usage")).as("真实租户与项目存量不增加").isEqualTo(before.get("usage"));
            softly.assertThat(after.get("inbox")).as("拒绝也应提交消息去重事实").hasSize(1);
            softly.assertThat(after.get("outbox")).as("仅形成一次失败回执").hasSize(1);
            softly.assertThat(reply.path("requestId").asString()).isEqualTo(request.messageId().toString());
            softly.assertThat(reply.path("status").asString()).isEqualTo("FAILED");
            softly.assertThat(reply.path("errorCode").asString()).isEqualTo("quota_exceeded");
            softly.assertThat(reply.path("tenantId").asString()).isEqualTo(fixture.tenantId().toString());
            softly.assertThat(reply.path("projectId").asString()).isEqualTo(fixture.projectId().toString());
            softly.assertThat(reply.path("gatewayId").asString()).isEqualTo(fixture.gatewayId().toString());
        });
    }

    /** 一次合法注册用满额度后，新messageId确认同一已绑定deviceKey仍成功，不能把幂等当新增拒绝。 */
    @Test
    void existingBoundDeviceKeyRemainsIdempotentWhenTenantQuotaIsFull() throws Exception {
        Fixture fixture = fixture(2);
        JsonNode initial = snapshot(fixture);
        assertUsage(initial, 1, 2);
        DeviceTopologyMessage firstRequest = registerMessage(fixture, "quota_existing_sub");
        ingestWithoutHttpContext(firstRequest);
        JsonNode registered = snapshot(fixture);
        assertUsage(registered, 2, 2);
        assertThat(replyFor(registered, firstRequest.messageId()).get("status").asString()).isEqualTo("SUCCESS");
        assertThat(registered.get("business").get("devices")).hasSize(2);
        assertThat(registered.get("business").get("topology")).hasSize(1);
        assertThat(registered.get("business").get("history")).hasSize(1);
        assertThat(registered.get("business").get("credentials")).isEmpty();
        JsonNode child = deviceByKey(registered, "quota_existing_sub");
        assertThat(child.get("gateway_id").asString()).isEqualTo(fixture.gatewayId().toString());
        assertThat(child.get("thing_model_version_id").asString()).isEqualTo(fixture.subVersionId().toString());
        JsonNode initialHistory = registered.get("business").get("history").get(0);
        assertThat(initialHistory.get("device_id").asString()).isEqualTo(child.get("id").asString());
        assertThat(initialHistory.get("transition_type").asString()).isEqualTo("INITIAL");
        assertThat(initialHistory.get("to_model_version_id").asString()).isEqualTo(fixture.subVersionId().toString());
        DeviceTopologyMessage retry = registerMessage(fixture, "quota_existing_sub");
        assertThat(retry.messageId()).isNotEqualTo(firstRequest.messageId());

        ingestWithoutHttpContext(retry);

        JsonNode after = snapshot(fixture);
        assertThat(after.get("business")).isEqualTo(registered.get("business"));
        assertThat(after.get("usage")).isEqualTo(registered.get("usage"));
        assertThat(after.get("inbox")).hasSize(2);
        assertThat(after.get("outbox")).hasSize(2);
        assertThat(replyFor(after, retry.messageId()).get("status").asString()).isEqualTo("SUCCESS");
        assertThat(replyFor(after, firstRequest.messageId())).isEqualTo(replyFor(registered, firstRequest.messageId()));
    }

    /** 可信二元组必须在已有key快速路径前校验，不能向另一个真实租户伪造成功回执或留下inbox。 */
    @Test
    void mismatchedTenantCannotReuseExistingDeviceKeyToBypassOwnershipValidation() throws Exception {
        Fixture fixture = fixture(2);
        Fixture foreign = fixture(1);
        DeviceTopologyMessage firstRequest = registerMessage(fixture, "quota_existing_sub");
        ingestWithoutHttpContext(firstRequest);
        JsonNode before = snapshot(fixture);
        JsonNode foreignBefore = snapshot(foreign);
        assertUsage(before, 2, 2);
        assertThat(replyFor(before, firstRequest.messageId()).get("status").asString()).isEqualTo("SUCCESS");
        DeviceTopologyMessage mismatched = new DeviceTopologyMessage(Uuid7.generate(), foreign.tenantId(),
                fixture.projectId(), fixture.gatewayId(), DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER,
                "quota_existing_sub", "配额测试子设备", "quota_sub_type", Instant.now(),
                "quota-mismatch-" + UUID.randomUUID());

        Throwable quotaScopeFailure = catchThrowable(() ->
                projectQuotaService.deviceQuotaStatus(foreign.tenantId(), fixture.projectId()));
        Throwable failure = catchThrowable(() -> ingestWithoutHttpContext(mismatched));

        JsonNode after = snapshot(fixture);
        JsonNode foreignAfter = snapshot(foreign);
        assertSoftly(softly -> {
            softly.assertThat(quotaScopeFailure).as("显式配额函数必须把无效二元组返回为空并由服务拒绝")
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(failure).as("确权二元组不匹配沿既有可信策略端口拒绝，不是可确认的业务注册")
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(after).as("非法归属必须回滚inbox与任何回执，已有设备与拓扑亦不变").isEqualTo(before);
            softly.assertThat(foreignAfter).as("伪造信封涉及的另一真实租户完整事实不变").isEqualTo(foreignBefore);
        });
    }

    /** 配额事实库不可判定时系统错误必须回滚inbox与全部副作用，恢复后同messageId仍可重试。 */
    @Test
    void quotaProjectionFailureRollsBackAndSameMessageCanRetry() throws Exception {
        Fixture fixture = fixture(2);
        JsonNode before = snapshot(fixture);
        DeviceTopologyMessage request = registerMessage(fixture, "quota_retry_sub");
        doThrow(new DataAccessResourceFailureException("D-028测试配额投影不可用"))
                .doCallRealMethod()
                .when(quotaOverviewRepository).findDeviceQuotaPolicy(fixture.tenantId(), fixture.projectId());

        Throwable failure = catchThrowable(() -> ingestWithoutHttpContext(request));

        JsonNode failed = snapshot(fixture);
        assertThat(failure).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(failed).as("系统故障不提交inbox、失败回执或任何设备副作用").isEqualTo(before);

        ingestWithoutHttpContext(request);

        JsonNode retried = snapshot(fixture);
        assertThat(retried.get("business").get("devices")).hasSize(2);
        assertThat(retried.get("business").get("topology")).hasSize(1);
        assertThat(retried.get("business").get("history")).hasSize(1);
        assertThat(retried.get("inbox")).hasSize(1);
        assertThat(retried.get("outbox")).hasSize(1);
        assertThat(replyFor(retried, request.messageId()).path("status").asString()).isEqualTo("SUCCESS");
    }

    /** 数据面仅依赖确权消息信封；HTTP身份和借连接RLS线程范围均为空，服务自行建立事务内范围。 */
    private void ingestWithoutHttpContext(DeviceTopologyMessage message) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        ingestionService.ingest(message);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /**
     * 独立策略从创建时就带测试上限；owner只准备平台归属，APP写合法类型并通过生产服务发布模型/创建网关。
     * 子类型具体版本真实存在，注册成功便必然写INITIAL，不以未发布类型绕开版本副作用。
     */
    private Fixture fixture(long deviceLimit) throws SQLException {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID policyId = Uuid7.generate();
        UUID gatewayTypeId = Uuid7.generate();
        UUID subTypeId = Uuid7.generate();
        UUID gatewayId = Uuid7.generate();
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.setAutoCommit(false);
            try {
                execute(owner, "INSERT INTO public.sys_quota_policy (id, code, device_count_limit) VALUES (?, ?, ?)",
                        policyId, policyId.toString().replace("-", ""), deviceLimit);
                execute(owner, "INSERT INTO public.sys_tenant (id, name, quota_policy_id) VALUES (?, '网关注册配额租户', ?)",
                        tenantId, policyId);
                execute(owner, "INSERT INTO public.sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '网关注册配额项目', ?)",
                        projectId, tenantId, "quota_" + projectId.toString().replace("-", ""));
                owner.commit();
            } finally {
                owner.rollback();
            }
        }
        try (Connection application = scopedConnection(tenantId, projectId)) {
            try {
                execute(application, """
                        INSERT INTO public.dev_type
                            (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                        VALUES (?, ?, ?, 'quota_gateway_type', '配额测试网关类型', 'GATEWAY', 'STANDARD_GATEWAY', 'WIFI', 'DRAFT'),
                               (?, ?, ?, 'quota_sub_type', '配额测试子类型', 'SUB_DEVICE', 'STANDARD', 'WIFI', 'PUBLISHED')
                        """, gatewayTypeId, tenantId, projectId, subTypeId, tenantId, projectId);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        return inFixtureScope(tenantId, projectId, () -> new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            ThingModelVersion version = versionService.publish(projectId, subTypeId, "1.0.0", ThingModelVersion.ChangeLevel.MAJOR,
                    JSON.readTree("{\"properties\":{},\"events\":{},\"commands\":{}}"));
            deviceRepository.create(new Device(gatewayId, tenantId, projectId, gatewayTypeId, null, "quota_gateway",
                    "配额测试网关", null, Device.Status.INACTIVE, null, null, Instant.now()));
            return new Fixture(tenantId, projectId, policyId, gatewayId, version.id());
        }));
    }

    /** 注册身份字段只来自本例平台确权结果，deviceKey及类型标识才属于网关自报数据。 */
    private DeviceTopologyMessage registerMessage(Fixture fixture, String deviceKey) {
        return new DeviceTopologyMessage(Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(),
                DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER, deviceKey, "配额测试子设备", "quota_sub_type",
                Instant.now(), "quota-register-" + UUID.randomUUID());
    }

    /** 实际存量投影和已绑定真实策略共同证明前置处于硬限，不把手工累计计数当设备事实。 */
    private void assertUsage(JsonNode snapshot, long used, long limit) {
        assertThat(snapshot.get("usage").get("project_used_value").asLong()).isEqualTo(used);
        assertThat(snapshot.get("usage").get("tenant_used_value").asLong()).isEqualTo(used);
        assertThat(snapshot.get("business").get("policy").get("device_count_limit").asLong()).isEqualTo(limit);
    }

    /**
     * 所有行均由独立APP连接读取，业务全行快照与消息全行快照分开；拒绝允许ack/FAILED但不允许新增业务事实。
     * 显式project条件亦约束未启用项目RLS的inbox，防止共享容器中其他测试消息进入本例结果。
     */
    private JsonNode snapshot(Fixture fixture) throws SQLException {
        try (Connection connection = scopedConnection(fixture.tenantId(), fixture.projectId())) {
            return JSON.readTree(text(connection, """
                    SELECT jsonb_build_object(
                        'business', jsonb_build_object(
                            'tenant', (SELECT to_jsonb(t) FROM public.sys_tenant t WHERE t.id = ?),
                            'policy', (SELECT to_jsonb(q) FROM public.sys_quota_policy q WHERE q.id = ?),
                            'devices', COALESCE((SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM public.dev_device d), '[]'::jsonb),
                            'types', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_type t), '[]'::jsonb),
                            'versions', COALESCE((SELECT jsonb_agg(to_jsonb(v) ORDER BY v.id) FROM public.dev_thing_model_version v), '[]'::jsonb),
                            'history', COALESCE((SELECT jsonb_agg(to_jsonb(h) ORDER BY h.id) FROM public.dev_device_model_binding_history h), '[]'::jsonb),
                            'topology', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_topo t), '[]'::jsonb),
                            'credentials', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM public.dev_credential c), '[]'::jsonb)),
                        'usage', (SELECT to_jsonb(u) FROM public.project_device_quota_usage() u),
                        'inbox', COALESCE((SELECT jsonb_agg(to_jsonb(i) ORDER BY i.message_id) FROM public.sys_inbox_message i
                            WHERE i.project_id = ?), '[]'::jsonb),
                        'outbox', COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM public.sys_outbox_event o
                            WHERE o.project_id = ?), '[]'::jsonb))::text
                    """, fixture.tenantId(), fixture.policyId(), fixture.projectId(), fixture.projectId()));
        }
    }

    /** 按requestId匹配真实Outbox回执，缺失时给软断言空对象，保留同一次失败的其余快照证据。 */
    private JsonNode replyFor(JsonNode snapshot, UUID requestId) {
        for (JsonNode event : snapshot.get("outbox")) {
            if (TopologyReplyMessage.EVENT_TYPE.equals(event.get("event_type").asString())) {
                JsonNode reply = JSON.readTree(event.get("payload").asString());
                if (requestId.toString().equals(reply.path("requestId").asString())) {
                    return reply;
                }
            }
        }
        return JSON.readTree("{}");
    }

    /** 按业务键定位真实子设备，避免随机主键顺序把网关误认为新注册设备。 */
    private JsonNode deviceByKey(JsonNode snapshot, String deviceKey) {
        for (JsonNode device : snapshot.get("business").get("devices")) {
            if (deviceKey.equals(device.get("device_key").asString())) {
                return device;
            }
        }
        throw new AssertionError("注册后没有设备事实：" + deviceKey);
    }

    /** 仅种子发布使用RLS线程范围；返回前清理，实际消息消费不继承假造的HTTP租户上下文。 */
    private <T> T inFixtureScope(UUID tenantId, UUID projectId, Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        RlsScopeContext.set(new RlsScope(tenantId, projectId));
        try {
            return action.get();
        } finally {
            RlsScopeContext.clear();
        }
    }

    /** 新APP物理连接以显式RC及本例范围读取，不借用CONTROL池，也不能继承上一次连接的项目。 */
    private Connection scopedConnection(UUID tenantId, UUID projectId) throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(text(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            text(connection, "SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
            text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
            text(connection, "SELECT set_config('lock_timeout', '10s', true)");
            text(connection, "SELECT set_config('statement_timeout', '15s', true)");
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** 参数绑定保留UUID和数字类型，语句资源及时关闭，提交/回滚由调用者显式承担。 */
    private int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            return statement.executeUpdate();
        }
    }

    /** 单值查询必须得到真实一行，避免无范围空数据被误判为配额为零或业务不变。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 固定SQL模板逐项绑定，租户、项目和请求数据不经字符串拼接进入查询。 */
    private void parameters(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /**
     * 一次独占项目夹具的可信归属与发布版本，不依赖其他测试的账号或公共策略。
     * @param tenantId 租户共享配额的强制范围
     * @param projectId 消息与设备的项目归属及RLS范围
     * @param policyId 从创建时即带目标设备硬限的独立策略
     * @param gatewayId 已确权发布网关，同时计入租户有效设备数
     * @param subVersionId 真实发布的子类型具体版本，供INITIAL副作用核对
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID policyId, UUID gatewayId, UUID subVersionId) { }
}
