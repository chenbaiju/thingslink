package com.things.link.bootstrap.device.model;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * G1-C1b 契约测试：自定义数据流 V1 控制面已下线（PS-039 TECHNICAL_DEFERRED）。
 *
 * <p>旧公开路径（/device-types/{typeId}/data-streams）必须整体不可达——不是「前端隐藏而接口仍可写」，
 * 否则会继续写出永不被消费的幽灵配置（G-13 要消除的问题）。本测试钉死：路径返回 404（无映射），
 * 且一旦未来有人把它复活（返回成功），本测试立刻失败，迫使 S15 显式决定端点形态（复用则同步更新契约与测试，
 * 不能无意识恢复旧路径）。</p>
 */
@AutoConfigureMockMvc
@DisplayName("G1-C1b 数据流控制面下线（旧路径 404 + 存量事实保留）")
class DataStreamControlPlaneOfflineTests extends AbstractIntegrationTest {

    /** JSON 编解码器。 */ private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试口令满足当前 10 位最低强度。 */ private static final String PASSWORD = "correct-horse-battery-staple";
    /** MockMvc 真实过滤器链入口。 */ @Autowired private MockMvc mockMvc;
    /** 用于准备项目与读取存量数据。 */ @Autowired private JdbcTemplate jdbcTemplate;
    /** 注册/登录限流器有进程级状态，每个测试必须清理。 */ @Autowired private AuthRateLimiter rateLimiter;
    /** OWNER 登录态。 */ private Login owner;

    /** 每个测试从干净的项目开始。 */
    @BeforeEach
    void seed() throws Exception {
        rateLimiter.clear();
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");
        owner = registerAndLogin("owner-offline@example.com");
    }

    /** 旧公开数据流路径对 READ 与 WRITE 均不可达（404，无映射）。 */
    @Test
    void dataStreamEndpointsAreGone() throws Exception {
        UUID projectId = createProject(owner, "下线项目");
        Login scoped = switchProject(owner, projectId);
        UUID typeId = createdTypeId(scoped, projectId, "offline_type");
        String base = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/data-streams";

        assertThat(mockMvc.perform(get(base)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(post(base)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"streamKey":"stream","name":"旧客户端流","format":"JSON",\
                                 "mqttTopicAdvanced":false}
                                """))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(put(base + "/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"streamKey":"stream","name":"旧客户端流","format":"JSON",\
                                 "mqttTopicAdvanced":false}
                                """))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(delete(base + "/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    /** 已存在于数据库的存量数据流行（升级保留下来的）在控制面下线后仍不被物理删除。 */
    @Test
    void existingRowsArePreservedAfterControlPlaneOffline() throws Exception {
        UUID projectId = createProject(owner, "存量保留项目");
        UUID typeId = createdTypeId(switchProject(owner, projectId), projectId, "legacy_keep");
        // 直接插一条存量行模拟历史数据：用容器超级用户连接（表 owner，绕过项目 RLS）——与应用账号不同，
        // 它等价于 Flyway 迁移账号在升级时写入的历史事实，而不是「绕过被测接口的新写路径」。
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO dev_data_stream
                         (id, tenant_id, project_id, device_type_id, stream_key, name, format)
                     VALUES (?, ?, ?, ?, 'legacy_keep', '存量', 'JSON')
                     """)) {
            statement.setObject(1, Uuid7.generate());
            statement.setObject(2, tenantId);
            statement.setObject(3, projectId);
            statement.setObject(4, typeId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        // 应用账号连接无项目上下文，RLS fail-closed 看不到 dev_data_stream；改用容器超级用户
        // 读库验证「物理保留」这一事实，而非应用可见性。
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM dev_data_stream WHERE device_type_id = ?")) {
            statement.setObject(1, typeId);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("存量数据流行在控制面下线后必须物理保留（供 S15 迁移）").isEqualTo(1);
            }
        }
    }

    /** 注册、标记邮箱验证并登录。 */
    private Login registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn();
        assertThat(registered.getResponse().getStatus()).isEqualTo(204);
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn();
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        return new Login(body.get("accessToken").asString(), refresh);
    }

    /** 创建项目并返回 ID。 */
    private UUID createProject(Login login, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(name)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 切换项目会轮换访问令牌。 */
    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .cookie(new Cookie("tc_refresh", login.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(projectId)))
                .andReturn();
        return new Login(JSON.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString(), login.refreshToken());
    }

    /** 创建类型并提取 ID。 */
    private UUID createdTypeId(Login login, UUID projectId, String typeKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeKey":"%s","name":"%s","deviceKind":"DIRECT",\
                                 "payloadProtocol":"STANDARD","networkType":"WIFI"}
                                """.formatted(typeKey, typeKey)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 登录态所需的两种凭据。 @param accessToken 访问令牌 @param refreshToken 刷新令牌值 */
    private record Login(String accessToken, String refreshToken) { }
}
