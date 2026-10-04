package com.things.link.bootstrap.project.quota;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S14-2b 回归断言：FREE 冻结的 3 台设备硬限在既有设备创建入口上真实生效。
 *
 * <p>设备额度不是本片新增的守卫，而是 S14-2a 之后新租户默认绑定 {@code PLAN_R1_FREE} 的结果；
 * 本类只走真实注册 → 项目创建 → 设备创建链路，把「第 4 台被 30035 拒绝」钉成回归线，
 * 防止后续改动悄悄放宽冻结模板或绕过 {@code ProjectQuotaService} 的存量投影。
 */
@AutoConfigureMockMvc
@DisplayName("S14-2b FREE 设备数上限（回归）")
class TenantFreeDeviceQuotaIntegrationTests extends AbstractIntegrationTest {

    /** 真实 JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试账号固定口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** 真实 HTTP 入口。 */
    @Autowired
    private MockMvc mockMvc;
    /** 夹具事实写入与核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 注册限流器；同一 remoteAddr 连续注册前清理。 */
    @Autowired
    private AuthRateLimiter rateLimiter;

    /** 本用例租户与项目，用于显式回收。 */
    private UUID tenantId;
    /** 本用例项目。 */
    private UUID projectId;
    /** 本用例账号。 */
    private UUID accountId;

    /** 按依赖顺序回收共享容器夹具并清线程范围。 */
    @AfterEach
    void cleanUp() {
        try {
            if (tenantId != null && projectId != null) {
                TenantContext.set(new TenantScope(tenantId, projectId, accountId));
                jdbcTemplate.update("DELETE FROM dev_device WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);
            }
        } finally {
            TenantContext.clear();
        }
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
        }
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
        }
        if (accountId != null) {
            jdbcTemplate.update("DELETE FROM sys_account WHERE id = ?", accountId);
        }
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        }
    }

    @Autowired private com.things.link.project.application.QuotaPolicyAssignmentService assignments;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;

    /** 真实认证HTTP路径将缺投影映射为503/50047，不产生项目。 */
    @Test
    void projectCreationWithoutPlanProjectionReturnsUnavailable() throws Exception {
        Session session = registerAndLogin("r1-" + UUID.randomUUID() + "@example.com");
        UUID policy = jdbcTemplate.queryForObject("SELECT id FROM sys_quota_policy WHERE code='FREE'", UUID.class);
        long version = jdbcTemplate.queryForObject("SELECT quota_policy_assignment_version FROM sys_tenant WHERE id=?", Long.class, tenantId);
        transactions.executeWithoutResult(status -> assignments.assign(tenantId, policy, version));
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"缺额度项目\",\"region\":\"sh-1\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(50047);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_project WHERE tenant_id=?", Integer.class, tenantId)).isZero();
    }

    /** FREE 租户第 1-3 台设备成功，第 4 台由既有入口以 429/30035 拒绝。 */
    @Test
    void fourthDeviceIsRefusedThroughExistingDeviceCreationPath() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Session session = registerAndLogin("s14-2b-device-" + nonce + "@example.com");

        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"S14-2b 设备额度项目","region":"sh-1"}"""))
                .andReturn();
        JsonNode project = JSON.readTree(created.getResponse().getContentAsString());
        projectId = UUID.fromString(project.get("id").asString());
        session = switchProject(session, projectId);

        for (int index = 1; index <= 3; index++) {
            MvcResult device = createDevice(session.accessToken(), "free_device_" + index);
            assertThat(device.getResponse().getStatus()).as("第 %d 台设备应被允许", index).isEqualTo(201);
        }

        MvcResult fourth = createDevice(session.accessToken(), "free_device_4");

        assertThat(fourth.getResponse().getStatus()).isEqualTo(429);
        assertThat(JSON.readTree(fourth.getResponse().getContentAsString()).get("code").asInt())
                .as("FREE 的 3 台设备硬限必须沿用设备域既有 30035")
                .isEqualTo(30035);
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        try {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM dev_device WHERE project_id = ? AND deleted_at IS NULL
                    """, Integer.class, projectId))
                    .as("被拒的设备不能留下行")
                    .isEqualTo(3);
        } finally {
            TenantContext.clear();
        }
    }

    /** 走真实注册/登录链路并记录账号、租户 ID 与刷新 Cookie 以便回收与切项目。 */
    private Session registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        accountId = jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_tenant_member WHERE account_id = ?", UUID.class, accountId);
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        return new Session(JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString(),
                refresh);
    }

    /** 切换已选项目并返回轮换后的会话。 */
    private Session switchProject(Session session, UUID targetProjectId) throws Exception {
        MvcResult switched = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", session.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(targetProjectId)))
                .andReturn();
        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        return new Session(JSON.readTree(switched.getResponse().getContentAsString()).get("accessToken").asString(),
                session.refreshToken());
    }

    /** 走既有设备创建入口建一台无类型设备。 */
    private MvcResult createDevice(String token, String deviceKey) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"%s\",\"name\":\"FREE 设备\"}".formatted(deviceKey)))
                .andReturn();
    }

    /** 控制台会话的最小凭据。 */
    private record Session(String accessToken, String refreshToken) {
    }
}
