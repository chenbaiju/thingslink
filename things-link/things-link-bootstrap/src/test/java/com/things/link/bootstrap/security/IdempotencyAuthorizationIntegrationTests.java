package com.things.link.bootstrap.security;

import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-142：通过真实嵌入式服务器验证幂等重放不能越过认证边界。
 *
 * <p>架构文档第7节要求应用授权与PG RLS同时生效，第11.1节的幂等支持不得取消该要求。
 * 不使用MockMvc、伪造Authentication、预置幂等缓存或放宽RLS；如果首次带键请求在RLS处失败，
 * 本测试保留该失败，不能把尚未执行到的匿名重放断言冒充已验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyAuthorizationIntegrationTests extends AbstractIntegrationTest {

    /** 固定测试口令只用于独占数据库账号，持久化仍通过生产PasswordEncoder哈希。 */
    private static final String PASSWORD = "Idempotency-Authorization-2026!";

    /** JSON用于真实HTTP协议和种子数据序列化，不替换Controller。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 继承的runner只面向共享数据库；本类少量请求沿用专库生产默认配额。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedSharedQuotaRunner;

    /** 随机端口必须来自已启动的真实嵌入式服务器。 */
    @Value("${local.server.port}")
    private int port;

    /** 运行连接用于确认真实APP角色，不能以owner夹具连接代替HTTP中的数据访问。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 夹具账号采用与生产登录一致的哈希算法。 */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 同一路径、正文和幂等键在移除Bearer后必须重新经历认证，不能返回先前私有属性。
     * 首次无键读取先确认合法账号、项目、设备和PG影子，因此后续首个失败可明确定位到带键链路。
     *
     * @throws Exception 真实HTTP、种子SQL或JSON解析失败时原样暴露原因
     */
    @Test
    @Timeout(60)
    void replayWithoutBearerCannotReturnAuthenticatedCurrentValues() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        Fixture fixture = seed();
        RestClient client = client();
        String bearer = loginAndSelectProject(client, fixture);
        String path = "/api/v1/projects/" + fixture.projectId() + "/devices/current-values/query";
        String body = JSON.writeValueAsString(Map.of("deviceIds", java.util.List.of(fixture.deviceId()),
                "propertyKeys", java.util.List.of("privateValue")));

        ResponseEntity<String> baseline = post(client, path, body, bearer, null, null);
        assertThat(baseline.getStatusCode().value()).as("真实合法当前值读取必须先成立：%s", baseline.getBody())
                .isEqualTo(200);
        assertPrivateValue(baseline, fixture);

        String key = "authorization-" + Uuid7.generate();
        ResponseEntity<String> first = post(client, path, body, bearer, null, key);
        // POST查询按ADR0101精确排除公共写幂等，仍必须完整进入当前授权与PG读取。
        assertThat(first.getStatusCode().value()).as("首次带幂等键请求必须成功：%s", first.getBody())
                .isEqualTo(200);
        assertPrivateValue(first, fixture);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_idempotency_record
                 WHERE idempotency_key = ? AND request_method = 'POST' AND request_path = ?
                """, Integer.class, key, path)).as("只读POST不得把私有响应送入公共幂等存储").isZero();

        ResponseEntity<String> anonymous = post(client, path, body, null, null, key);
        assertThat(anonymous.getStatusCode().value())
                .as("无Bearer重试不能重放认证请求的200正文：%s", anonymous.getBody()).isEqualTo(401);
        assertThat(anonymous.getHeaders().getFirst("Idempotency-Replayed")).isNull();
        assertThat(anonymous.getBody()).isNotNull().doesNotContain(fixture.privateValue())
                .doesNotContain(fixture.deviceId().toString());
        assertThat(JSON.readTree(anonymous.getBody()).path("code").asInt()).isEqualTo(20020);
    }

    /**
     * 真实登录后使用响应刷新Cookie切换项目，不直接签发令牌或在线程中预置TenantContext。
     *
     * @param client 不自动保留Cookie的HTTP客户端
     * @param fixture 已提交的合法账号与项目
     * @return 生产切换接口签发的项目Bearer值
     */
    private String loginAndSelectProject(RestClient client, Fixture fixture) {
        ResponseEntity<String> login = post(client, "/api/v1/auth/login",
                JSON.writeValueAsString(Map.of("email", fixture.email(), "password", PASSWORD)),
                null, null, null);
        // 认证响应可能含凭据，失败诊断只输出状态，不输出整份登录正文或Set-Cookie。
        assertThat(login.getStatusCode().value()).as("真实Console登录状态").isEqualTo(200);
        String cookie = login.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.split(";", 2)[0]).findFirst().orElseThrow();
        String loginToken = JSON.readTree(login.getBody()).path("accessToken").asString();
        ResponseEntity<String> selected = post(client, "/api/v1/auth/switch-project",
                JSON.writeValueAsString(Map.of("projectId", fixture.projectId())),
                "Bearer " + loginToken, cookie, null);
        assertThat(selected.getStatusCode().value()).as("真实Console项目选择状态").isEqualTo(200);
        return "Bearer " + JSON.readTree(selected.getBody()).path("accessToken").asString();
    }

    /**
     * 只创建随机UUID夹具；PG owner仅负责准备，业务读取仍走APP池和真实RLS。
     *
     * @return 精确追踪本次私有属性及账号的夹具
     * @throws SQLException 约束不满足时直接失败，不关闭任何生产约束
     */
    private Fixture seed() throws SQLException {
        UUID accountId = Uuid7.generate();
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), accountId,
                Uuid7.generate(), Uuid7.generate(), accountId + "@example.com", "private-" + Uuid7.generate());
        try (Connection connection = fixtureOwnerConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO sys_tenant(id,name) VALUES (?,'幂等授权反例租户')", fixture.tenantId());
            execute(connection, """
                    INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)
                    VALUES (?,?,?,'幂等授权反例账号',clock_timestamp())
                    """, fixture.accountId(), fixture.email(), passwordEncoder.encode(PASSWORD));
            execute(connection, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(connection, """
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key)
                    VALUES (?,?,'幂等授权反例项目','sh-1',?)
                    """, fixture.projectId(), fixture.tenantId(), "idem_" + fixture.projectId().toString().replace("-", ""));
            execute(connection, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(connection, """
                    INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status)
                    VALUES (?,?,?,'idempotency_type','幂等授权设备类型','DIRECT','STANDARD','WIFI','PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(connection, """
                    INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status)
                    VALUES (?,?,?,?,'idempotency_device','幂等授权设备','OFFLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(connection, """
                    INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,version)
                    VALUES (?,?,?,?::jsonb,'{"privateValue":"2026-09-05T00:00:00Z"}'::jsonb,1)
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(),
                    JSON.writeValueAsString(Map.of("privateValue", fixture.privateValue())));
            connection.commit();
        }
        return fixture;
    }

    /**
     * 显式HTTP客户端使用有界超时且不自动跟随重定向，避免空安全响应被其他页面成功掩盖。
     *
     * @return 指向随机测试端口的真实客户端
     */
    private RestClient client() {
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).requestFactory(requests).build();
    }

    /**
     * exchange保留非2xx状态和原始正文供精确断言，且仅按参数显式发送认证头或刷新Cookie。
     *
     * @param client 真实客户端 @param path 固定生产REST路径 @param body 原始请求JSON
     * @param bearer 可空Bearer头 @param cookie 可空刷新Cookie @param key 可空幂等键
     * @return 真实服务器状态、响应头及正文
     */
    private ResponseEntity<String> post(RestClient client, String path, String body,
                                        String bearer, String cookie, String key) {
        RestClient.RequestBodySpec request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (cookie != null) request.header(HttpHeaders.COOKIE, cookie);
        if (key != null) request.header("Idempotency-Key", key);
        return request.body(body).exchange((sent, received) -> ResponseEntity.status(received.getStatusCode())
                .headers(received.getHeaders())
                .body(new String(received.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** @param response 已确认200的当前值正文 @param fixture 私有属性来源，防止空成功构成假阳性 */
    private void assertPrivateValue(ResponseEntity<String> response, Fixture fixture) {
        JsonNode items = JSON.readTree(response.getBody()).path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("deviceId").asString()).isEqualTo(fixture.deviceId().toString());
        assertThat(items.get(0).path("values").path("privateValue").asString()).isEqualTo(fixture.privateValue());
    }

    /** @param connection 本类owner连接 @param sql 固定参数化SQL @param values 夹具值 @throws SQLException SQL失败 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    /**
     * @param tenantId 租户ID @param projectId 项目ID @param accountId 账号ID
     * @param typeId 类型ID @param deviceId 设备ID @param email 唯一邮箱 @param privateValue 可识别的私有事实
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId,
                           String email, String privateValue) { }
}
