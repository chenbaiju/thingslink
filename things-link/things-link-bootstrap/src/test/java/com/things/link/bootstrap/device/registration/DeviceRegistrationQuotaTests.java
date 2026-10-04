package com.things.link.bootstrap.device.registration;

import com.things.link.device.application.DeviceRegistrationService;
import com.things.link.device.application.ThingModelVersionService;
import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.project.domain.QuotaOverviewRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S12-P0-3a公开动态注册的真实HTTP/事务反例：架构§8.1/§8.2要求所有新增入口共享租户设备硬限。
 * 缺口§2.2的重复注册仍返回冲突；每例使用独立策略与项目，检查设备、凭据和INITIAL历史的完整事实。
 */
@AutoConfigureMockMvc
@DisplayName("S12-P0-3a 公开动态注册设备配额")
class DeviceRegistrationQuotaTests extends AbstractIntegrationTest {

    /** JSONB全行快照包含软删除记录，防止错误实现用补偿删除掩盖已经发生的写入。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 无账号Authorization的真实HTTP入口，覆盖全局异常映射和公开端点安全链。 */
    @Autowired private MockMvc mockMvc;
    /** 故障反例直接调用事务代理，避免全局异常处理器的日志干扰回滚判定。 */
    @Autowired private DeviceRegistrationService registrationService;
    /** 生产版本发布产生可绑定版本，确保注册路径实际执行INITIAL写入。 */
    @Autowired private ThingModelVersionService versionService;
    /** 仅准备已有设备事实；待测设备必须经公开注册路径创建。 */
    @Autowired private DeviceRepository deviceRepository;
    /** 验证真实策略与有效存量构成HARD_LIMIT或DEGRADED，不伪造配额服务返回值。 */
    @Autowired private ProjectQuotaService quotaService;
    /** 只在故障边界代理底层真实查询，策略计算和设备写事务始终使用生产实现。 */
    @MockitoSpyBean private QuotaOverviewRepository quotaOverviewRepository;
    /** 类型版本与已有设备夹具使用真实APP角色事务，保持模型历史和配额投影一致。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** 达到硬限和超过降级水位均禁止新注册，不能发出token或留下设备/INITIAL/凭据。 */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void hardLimitAndDegradedRejectWithoutWritingAnyRegistrationFact(int existingDevices) throws Exception {
        Fixture fixture = fixture(1L, existingDevices);
        assertThat(quotaService.deviceQuotaStatus(fixture.tenantId(), fixture.projectId()))
                .isEqualTo(existingDevices == 1 ? QuotaStatus.HARD_LIMIT : QuotaStatus.DEGRADED);
        JsonNode before = snapshot(fixture);

        MvcResult result = register(fixture, fixture.productSecret(), "new_device");

        assertError(result, 429, 30035);
        assertThat(snapshot(fixture)).as("拒绝不得增加任何设备、凭据、版本历史或用量").isEqualTo(before);
    }

    /** 额度足够时成功只产生一个设备、一个INITIAL和一个摘要凭据，明文只随本次响应返回。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {1})
    void availableQuotaReturnsOneTokenAndStoresOnlyItsHash(Long deviceLimit) throws Exception {
        Fixture fixture = fixture(deviceLimit, 0);

        MvcResult result = register(fixture, fixture.productSecret(), "new_device");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode response = body(result);
        String token = response.path("accessToken").asString();
        assertThat(token).matches("[0-9a-f]{64}");
        assertThat(response.path("deviceKey").asString()).isEqualTo("new_device");
        assertSuccessfulFacts(fixture, response.path("deviceId").asString(), token);
    }

    /** 缺口§2.2要求重复key明确冲突；额度尚余和恰好用满时都不得改签或新增凭据。 */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void duplicateDeviceKeyRemainsConflictEvenAtHardLimit(long deviceLimit) throws Exception {
        Fixture fixture = fixture(deviceLimit, 0);
        MvcResult first = register(fixture, fixture.productSecret(), "same_device");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode before = snapshot(fixture);
        assertThat(before.path("devices")).hasSize(1);
        assertThat(before.path("credentials")).hasSize(1);
        assertThat(before.path("history")).hasSize(1);

        MvcResult duplicate = register(fixture, fixture.productSecret(), "same_device");

        assertError(duplicate, 409, 30021);
        assertThat(snapshot(fixture)).as("重复请求不轮换token、不新增INITIAL或改变存量").isEqualTo(before);
        assertThat(duplicate.getResponse().getContentAsString())
                .doesNotContain(body(first).path("accessToken").asString());
    }

    /** 产品认证必须先于配额探测，满额租户上的错误产品密钥仍使用公开端点统一403/30026。 */
    @Test
    void invalidProductSecretIsDeniedBeforeQuotaQuery() throws Exception {
        Fixture fixture = fixture(1L, 1);
        JsonNode before = snapshot(fixture);
        doThrow(new DataAccessResourceFailureException("错误产品密钥不得访问配额投影"))
                .when(quotaOverviewRepository).findDeviceQuotaPolicy(fixture.tenantId(), fixture.projectId());

        MvcResult result = register(fixture, "0".repeat(64), "existing_0");

        assertError(result, 403, 30026);
        verify(quotaOverviewRepository, never()).findDeviceQuotaPolicy(fixture.tenantId(), fixture.projectId());
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 权威策略查询不可用必须失败关闭并回滚；查询恢复后同一key可重新注册，无半成品冲突。 */
    @Test
    void quotaRepositoryFailureRollsBackAndRegistrationCanRetry() throws Exception {
        Fixture fixture = fixture(1L, 0);
        JsonNode before = snapshot(fixture);
        doThrow(new DataAccessResourceFailureException("S12-P0-3a测试配额投影不可用"))
                .doCallRealMethod()
                .when(quotaOverviewRepository).findDeviceQuotaPolicy(fixture.tenantId(), fixture.projectId());
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();

        Throwable failure = catchThrowable(() -> registrationService.register(fixture.projectKey(),
                fixture.productKey(), fixture.productSecret(), "retry_device"));

        assertThat(failure).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(snapshot(fixture)).as("系统故障不提交任何设备、凭据、INITIAL或用量").isEqualTo(before);
        MvcResult retried = register(fixture, fixture.productSecret(), "retry_device");
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        JsonNode response = body(retried);
        assertSuccessfulFacts(fixture, response.path("deviceId").asString(), response.path("accessToken").asString());
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 公开注册无需账号、Broker密钥或伪造TenantContext，身份完全由项目/产品凭据确认。 */
    private MvcResult register(Fixture fixture, String productSecret, String deviceKey) throws Exception {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        return mockMvc.perform(post("/api/v1/emqx/register").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.createObjectNode().put("projectKey", fixture.projectKey())
                        .put("productKey", fixture.productKey()).put("productSecret", productSecret)
                        .put("deviceKey", deviceKey).toString())).andReturn();
    }

    /** 状态码、业务码与无token必须同时满足，避免仅看HTTP状态掩盖错误业务分支。 */
    private void assertError(MvcResult result, int status, int code) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(body(result).path("code").asInt()).isEqualTo(code);
        assertThat(body(result).has("accessToken")).isFalse();
    }

    /** 使用与响应相同的JSON解析器，不把异常正文作为字符串子串匹配。 */
    private JsonNode body(MvcResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    /** 以真实主键绑定设备、INITIAL、凭据，且全行快照不得出现响应token或产品密钥明文。 */
    private void assertSuccessfulFacts(Fixture fixture, String deviceId, String token) throws Exception {
        JsonNode facts = snapshot(fixture);
        assertThat(facts.path("devices")).hasSize(1);
        assertThat(facts.path("credentials")).hasSize(1);
        assertThat(facts.path("history")).hasSize(1);
        assertThat(facts.path("devices").get(0).path("id").asString()).isEqualTo(deviceId);
        assertThat(facts.path("devices").get(0).path("thing_model_version_id").asString())
                .isEqualTo(fixture.versionId().toString());
        assertThat(facts.path("credentials").get(0).path("device_id").asString()).isEqualTo(deviceId);
        assertThat(facts.path("credentials").get(0).path("credential_hash").asString()).isEqualTo(sha256(token));
        assertThat(facts.path("history").get(0).path("device_id").asString()).isEqualTo(deviceId);
        assertThat(facts.path("history").get(0).path("transition_type").asString()).isEqualTo("INITIAL");
        assertThat(facts.path("history").get(0).path("to_model_version_id").asString())
                .isEqualTo(fixture.versionId().toString());
        assertThat(facts.path("usage").path("tenant_used_value").asLong()).isEqualTo(1);
        assertThat(facts.toString()).doesNotContain(token).doesNotContain(fixture.productSecret());
    }

    /**
     * 每例从独立策略起步；APP角色写已发布DIRECT类型且仅存随机产品密钥摘要，再由生产服务发布真实版本。
     * 超额历史夹具通过仓储准备，刻意模拟已有存量超过新限额，不通过待测入口制造前置状态。
     */
    private Fixture fixture(Long limit, int existingDevices) throws Exception {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID policyId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        String projectKey = "register_" + projectId.toString().replace("-", "");
        String productKey = "product_" + typeId.toString().replace("-", "");
        String productSecret = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO public.sys_quota_policy (id, code, device_count_limit) VALUES (?, ?, ?)",
                    policyId, policyId.toString().replace("-", ""), limit);
            execute(owner, "INSERT INTO public.sys_tenant (id, name, quota_policy_id) VALUES (?, '动态注册配额租户', ?)",
                    tenantId, policyId);
            execute(owner, "INSERT INTO public.sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '动态注册配额项目', ?)",
                    projectId, tenantId, projectKey);
            owner.commit();
        }
        try (Connection application = scopedConnection(tenantId, projectId)) {
            execute(application, """
                    INSERT INTO public.dev_type
                        (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type,
                         status, product_key, product_secret_hash)
                    VALUES (?, ?, ?, 'register_type', '动态注册配额类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED', ?, ?)
                    """, typeId, tenantId, projectId, productKey, sha256(productSecret));
            application.commit();
        }
        RlsScopeContext.set(new RlsScope(tenantId, projectId));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                ThingModelVersion version = versionService.publish(projectId, typeId, "1.0.0", ThingModelVersion.ChangeLevel.MAJOR,
                        JSON.readTree("{\"properties\":{},\"events\":{},\"commands\":{}}"));
                for (int index = 0; index < existingDevices; index++) {
                    deviceRepository.create(new Device(Uuid7.generate(), tenantId, projectId, typeId, null,
                            "existing_" + index, "已有设备", null, Device.Status.INACTIVE, null, null, Instant.now()));
                }
                return new Fixture(tenantId, projectId, projectKey, productKey, productSecret, version.id());
            });
        } finally {
            RlsScopeContext.clear();
        }
    }

    /** 项目RLS下读完整业务事实及权威存量，不用owner查询掩盖实际注册范围错误。 */
    private JsonNode snapshot(Fixture fixture) throws SQLException {
        try (Connection connection = scopedConnection(fixture.tenantId(), fixture.projectId())) {
            return JSON.readTree(text(connection, """
                    SELECT jsonb_build_object(
                        'devices', COALESCE((SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM public.dev_device d), '[]'::jsonb),
                        'credentials', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM public.dev_credential c), '[]'::jsonb),
                        'history', COALESCE((SELECT jsonb_agg(to_jsonb(h) ORDER BY h.id) FROM public.dev_device_model_binding_history h), '[]'::jsonb),
                        'types', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_type t), '[]'::jsonb),
                        'usage', (SELECT to_jsonb(u) FROM public.project_device_quota_usage() u))::text
                    """));
        }
    }

    /** 独立物理APP连接使用事务级范围；关闭即回滚读事务，不残留跨测试会话变量。 */
    private Connection scopedConnection(UUID tenantId, UUID projectId) throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(text(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            connection.setAutoCommit(false);
            text(connection, "SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
            text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** UUID及数字逐项绑定，避免固定测试SQL中混入字符串拼接。 */
    private void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            statement.executeUpdate();
        }
    }

    /** 单值查询必须返回一行，不能把缺失作用域误读成设备数零。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 保留数据库原始参数类型；资源生命周期由调用方try-with-resources控制。 */
    private void parameters(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /** 与生产凭据格式独立计算SHA-256，证明库存摘要对应响应随机量且不等于明文。 */
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 独立项目的认证输入仅驻留内存；持久化夹具不保存产品或设备密钥明文。
     * @param tenantId 配额owner租户
     * @param projectId 项目RLS作用域
     * @param projectKey 公开注册项目标识
     * @param productKey 已发布类型的产品标识
     * @param productSecret 本例内存中的随机产品密钥
     * @param versionId 生产发布服务创建的具体模型版本
     */
    private record Fixture(UUID tenantId, UUID projectId, String projectKey, String productKey,
                           String productSecret, UUID versionId) { }
}
