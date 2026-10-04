package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.device.infrastructure.emqx.EmqxAuthService;
import com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpAuthenticationFilter;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheInvalidationPublisher;
import com.things.link.support.cache.CacheResource;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实 PostgreSQL／Redis 下的设备面 HTTP 认证：凭据头、平面资格、撤销与轮换、认证面预算与退避，
 * 以及与管理面／Broker 回调的分离。
 *
 * <p>用真实依赖而不是替身：撤销走的是 device 域凭据缓存失效链路，预算状态在 Redis，活动事实在数据库——
 * 三者用替身都证明不了任何东西。测试探针控制器只在测试 classpath 上，用来把「认证通过」变成一个可直接断言的
 * 200，而不是靠 404 间接推断。</p>
 */
@AutoConfigureMockMvc
@Import(DeviceAccessHttpAuthenticationTests.ProbeConfiguration.class)
class DeviceAccessHttpAuthenticationTests extends AbstractKafkaIntegrationTest {

    /** 测试探针路径：只验证认证，不触碰消息总线。 */
    private static final String REPORT_PATH = "/device-access/v1/auth-probe";

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试设备密钥明文。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** MockMvc 客户端。 */
    @Autowired
    private MockMvc mockMvc;

    /** 接入配置仓储；测试以显式项目范围开通平面。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 缓存失效发布器，复现真实撤销链路。 */
    @Autowired
    private CacheInvalidationPublisher cacheInvalidationPublisher;

    /** 应用角色连接。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 显式事务，保证 RLS 范围在连接首次取出前已设置。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 本类独占夹具，测试结束后按依赖顺序回收。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 不残留夹具事实。 */
    @AfterEach
    void clearFixtures() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 缺少凭据头直接 401，且不查询任何接入事实。 */
    @Test
    void missingCredentialsAreRejectedBeforeAnyLookup() throws Exception {
        Fixture fixture = seed();
        bind(fixture, "HTTP");

        MvcResult result = mockMvc.perform(post(REPORT_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(result)).isEqualTo("AUTH_REQUIRED");
        assertThat(activityOf(fixture)).isNull();
    }

    /** 密钥错误一律 AUTH_FAILED，不区分项目、设备与密钥，避免公开端点变成设备枚举器。 */
    @Test
    void wrongSecretIsUniformAuthFailure() throws Exception {
        Fixture fixture = seed();
        bind(fixture, "HTTP");

        MvcResult result = request(fixture, "f".repeat(64)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(result)).isEqualTo("AUTH_FAILED");
        assertThat(activityOf(fixture)).isNull();
    }

    /** 凭据正确但设备未开通 HTTP 平面：按 DEVICE_DISABLED 拒绝，而不是伪装成密钥错误。 */
    @Test
    void credentialWithoutHttpPlaneIsRejectedAsDisabled() throws Exception {
        Fixture legacy = seed();
        MvcResult unbound = request(legacy, SECRET).andReturn();
        assertThat(unbound.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(unbound)).isEqualTo("DEVICE_DISABLED");

        Fixture tcpOnly = seed();
        bind(tcpOnly, "TCP");
        MvcResult wrongPlane = request(tcpOnly, SECRET).andReturn();
        assertThat(wrongPlane.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(wrongPlane)).isEqualTo("DEVICE_DISABLED");
        assertThat(activityOf(tcpOnly)).isNull();
    }

    /** 凭据与平面都正确：请求带着身份进入业务层，并记录最近活动（HTTP 无会话，只有活动）。 */
    @Test
    void validCredentialReachesDispatcherAndRecordsActivity() throws Exception {
        Fixture fixture = seed();
        bind(fixture, "HTTP");

        MvcResult result = request(fixture, SECRET).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("deviceId").asString()).isEqualTo(fixture.deviceId().toString());
        assertThat(body.get("tenantId").asString()).isEqualTo(fixture.tenantId().toString());
        assertThat(activityOf(fixture)).isNotNull();
    }

    /** 单元级：撤销/轮换后旧密钥立即失效，不需要等缓存 TTL 到期。 */
    @Test
    void rotatedCredentialIsRejectedImmediately() throws Exception {
        Fixture fixture = seed();
        bind(fixture, "HTTP");
        assertThat(request(fixture, SECRET).andReturn().getResponse().getStatus()).isEqualTo(200);

        long version = bumpCredentialVersion(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE dev_credential SET deleted_at = now() WHERE project_id = ? AND device_id = ?",
                    fixture.projectId(), fixture.deviceId());
        }
        cacheInvalidationPublisher.publish(new CacheInvalidationEvent(Uuid7.generate(),
                CacheResource.DEVICE_CREDENTIAL, CacheInvalidationOperation.REVOKE, fixture.deviceId(), null,
                version, 0L, Instant.now()));

        MvcResult rotated = request(fixture, SECRET).andReturn();
        assertThat(rotated.getResponse().getStatus()).as("旧密钥必须立即被拒").isEqualTo(401);
        assertThat(errorCode(rotated)).isEqualTo("AUTH_FAILED");
    }

    /** 同一设备超过定向认证预算后返回 429 并带 Retry-After，退避同样如此。 */
    @Test
    void exhaustedAuthBudgetReturnsRetryAfter() throws Exception {
        Fixture fixture = seed();
        bind(fixture, "HTTP");
        for (int index = 0; index < 5; index++) {
            assertThat(request(fixture, SECRET).andReturn().getResponse().getStatus())
                    .as("第 " + (index + 1) + " 次认证应在预算内").isEqualTo(200);
        }

        MvcResult limited = request(fixture, SECRET).andReturn();

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(errorCode(limited)).isEqualTo("RATE_LIMITED");
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
    }

    /** 管理面仍然只认 JWT：设备凭据头不会让它通过，也不能用设备身份访问管理端点。 */
    @Test
    void deviceCredentialDoesNotOpenManagementApi() throws Exception {
        Fixture fixture = seed();
        bind(fixture, "HTTP");

        MvcResult management = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/projects/" + fixture.projectId() + "/devices")
                        .header(DeviceAccessHttpAuthenticationFilter.DEVICE_KEY_HEADER, deviceKeyHeader(fixture))
                        .header(DeviceAccessHttpAuthenticationFilter.DEVICE_SECRET_HEADER, SECRET))
                .andReturn();

        assertThat(management.getResponse().getStatus())
                .as("设备凭据不得通过管理面认证")
                .isIn(401, 403);
    }

    /**
     * 执行一次设备面请求。
     *
     * @param fixture 独占夹具
     * @param secret 设备密钥明文
     * @return MockMvc 结果
     * @throws Exception 请求失败
     */
    private org.springframework.test.web.servlet.ResultActions request(Fixture fixture, String secret)
            throws Exception {
        return mockMvc.perform(post(REPORT_PATH)
                .header(DeviceAccessHttpAuthenticationFilter.DEVICE_KEY_HEADER, deviceKeyHeader(fixture))
                .header(DeviceAccessHttpAuthenticationFilter.DEVICE_SECRET_HEADER, secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    /**
     * 取错误响应中的稳定错误码。
     *
     * @param result MockMvc 结果
     * @return 错误码
     * @throws Exception 解析失败
     */
    private static String errorCode(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("errorCode").asString();
    }

    /**
     * 构造设备身份头值。
     *
     * @param fixture 独占夹具
     * @return {@code projectKey/deviceKey}
     */
    private static String deviceKeyHeader(Fixture fixture) {
        return fixture.projectKey() + "/ax2a_device";
    }

    /**
     * 在夹具项目范围内开通接入平面。
     *
     * @param fixture 独占夹具
     * @param protocol 协议名
     */
    private void bind(Fixture fixture, String protocol) {
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                com.things.link.shared.message.TransportProtocol.valueOf(protocol), 30));
    }

    /**
     * 读取接入配置上的最近活动时刻。
     *
     * @param fixture 独占夹具
     * @return 活动时刻；从未活动时为 null
     * @throws SQLException 查询失败
     */
    private Instant activityOf(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT last_activity_at FROM dev_access_binding WHERE device_id = ?")) {
            statement.setObject(1, fixture.deviceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                java.sql.Timestamp value = rows.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        }
    }

    /**
     * 递增设备凭据代次，模拟控制面轮换。
     *
     * @param fixture 独占夹具
     * @return 新代次
     * @throws SQLException 更新失败
     */
    private long bumpCredentialVersion(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "UPDATE dev_device SET credential_version = credential_version + 1 WHERE id = ?"
                             + " RETURNING credential_version")) {
            statement.setObject(1, fixture.deviceId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /**
     * 在夹具项目范围内执行一次应用角色调用。
     *
     * @param fixture 独占夹具
     * @param work 范围内动作
     * @param <T> 返回值类型
     * @return 动作结果
     */
    private <T> T inProject(Fixture fixture, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    fixture.tenantId().toString());
            jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                    fixture.projectId().toString());
            return work.get();
        });
    }

    /**
     * 播种独占租户、项目、直连设备与一机一密凭据。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "ax2a_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'HTTP接入独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, 'HTTP接入项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax2a_type', 'HTTP接入类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax2a_device', 'HTTP接入设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    sha256(SECRET));
        }
        return fixture;
    }

    /**
     * 计算与凭据校验一致的 SHA-256 十六进制摘要。
     *
     * @param input 明文
     * @return 摘要
     */
    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
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
     * 测试探针：把「认证通过」变成可断言的 200，并回显过滤器写入的请求属性。
     *
     * <p>刻意用独立路径而不是属性上报端点：认证切片不依赖消息总线，属性上报要真正上车才能返回 202，
     * 在这里用它会让「认证是否通过」的断言被总线可用性污染。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        /**
         * @return 设备面测试探针控制器
         */
        @Bean
        ProbeController deviceAccessProbeController() {
            return new ProbeController();
        }
    }

    /** 只在测试 classpath 上的探针；不注册到任何生产上下文。 */
    @RestController
    static class ProbeController {

        /**
         * 回显认证身份，证明请求确实带着过滤器写入的身份进入了业务层。
         *
         * @param request 当前请求
         * @return 身份字段
         */
        @PostMapping("/device-access/v1/auth-probe")
        java.util.Map<String, String> report(HttpServletRequest request) {
            Object identity = request.getAttribute(DeviceAccessHttpAuthenticationFilter.IDENTITY_ATTRIBUTE);
            assertThat(identity).isNotNull();
            var authenticated = (com.things.link.shared.message.AuthenticatedDeviceIdentity) identity;
            return java.util.Map.of(
                    "deviceId", authenticated.deviceId().toString(),
                    "tenantId", authenticated.tenantId().toString());
        }
    }

    /**
     * 独占夹具身份。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceId 设备
     * @param typeId 设备类型
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, String projectKey) {
    }
}
