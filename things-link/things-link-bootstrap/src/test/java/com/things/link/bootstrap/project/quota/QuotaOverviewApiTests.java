package com.things.link.bootstrap.project.quota;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S7-2 配额概览的真实 HTTP、RLS 与跨租户协作验收。
 *
 * <p>这组测试刻意让协作者属于另一个租户：若共享池直接按 JWT tenantId 查询，会得到
 * 协作者自己的空用量；若 SECURITY DEFINER 函数信任路径参数，又会形成跨项目枚举入口。
 * 因此这里同时钉住租户共享求和、项目贡献、路径与已选项目一致性、以及 RLS 直写约束。
 */
@AutoConfigureMockMvc
@DisplayName("S7-2 项目配额与 UTC 日用量")
class QuotaOverviewApiTests extends AbstractIntegrationTest {

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试账号固定口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** HTTP 调用入口。 */
    @Autowired
    private MockMvc mockMvc;
    /** 夹具事实写入与约束核对入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 注册限流器；每次多账号注册前清理以消除全局状态干扰。 */
    @Autowired
    private AuthRateLimiter rateLimiter;
    /** 本类写入过日用量的租户；共享 Testcontainers 数据库结束用例后必须按租户 RLS 清理。 */
    private final Set<UUID> usageTenantIds = new HashSet<>();

    /** 防止直接种子数据留下线程范围，影响同容器后续用例。 */
    @AfterEach
    void clearTenantContext() {
        try {
            for (UUID tenantId : usageTenantIds) {
                TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
                jdbcTemplate.update("DELETE FROM sys_usage_counter_daily");
            }
            usageTenantIds.clear();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 当前项目贡献与同租户另一项目贡献必须只计一次，并允许跨租户 VIEWER 读取。
     *
     * @throws Exception 认证、项目切换或 HTTP 调用失败
     */
    @Test
    void viewerSeesCurrentContributionAndOwnerTenantSharedPoolWithoutOtherProjectDetails() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Actor owner = registerAndLogin("s7-quota-owner-" + nonce + "@example.com");
        UUID projectA = createProject(owner, "S7 配额项目甲");
        owner = switchProject(owner, projectA);
        createDevice(owner, projectA, "quota-a-" + nonce, "配额设备甲");

        // S14-2b 起 FREE 的 projects_max=1 在真实创建入口生效；本用例验的是共享池跨项目求和，
        // 第二个自有项目因此直接作为既有事实落库，既不绕过也不改写生产守卫。
        UUID projectB = seedOwnedProject(owner, "S7 配额项目乙");
        Actor ownerB = switchProject(owner, projectB);
        createDevice(ownerB, projectB, "quota-b-" + nonce, "配额设备乙");

        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        recordUsage(owner, projectA, today, "UPLINK_MESSAGE", 25);
        recordUsage(owner, projectB, today, "UPLINK_MESSAGE", 75);

        Actor collaborator = registerAndLogin("s7-quota-viewer-" + nonce + "@example.com");
        addMember(projectA, collaborator.accountId(), "VIEWER");
        collaborator = switchProject(collaborator, projectA);

        MvcResult result = quota(collaborator, projectA);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("projectId").asString()).isEqualTo(projectA.toString());
        assertThat(body.get("windowStart").asString()).endsWith("T00:00:00Z");
        assertThat(body.get("windowEnd").asString()).endsWith("T00:00:00Z");
        assertThat(body.toString()).doesNotContain(projectB.toString()).doesNotContain(owner.tenantId().toString());
        // S14-2a 起注册默认绑定的有效策略是 product-revision-1 的 PLAN_R1_FREE 冻结模板
        // （3 设备 / 上行 700 条每日），不再是 S7 遗留的 FREE 运行时模板（100 / 1,000,000）。
        // 本用例验的是概览投影口径，因此按新租户实际生效的冻结值断言。
        assertMetric(body.get("project").get("deviceCount"), "DEVICE_COUNT", 1, 2, 3L);
        assertMetric(findMetric(body.get("project").get("dailyMetrics"), "UPLINK_MESSAGE"),
                "UPLINK_MESSAGE", 25, 100, 700L);
        assertMetric(findMetric(body.get("tenantSharedPool").get("dailyMetrics"), "UPLINK_MESSAGE"),
                "UPLINK_MESSAGE", 100, 100, 700L);
    }

    /**
     * 用量行必须随 tenant RLS 和复合外键同时受约束。
     *
     * @throws Exception HTTP 夹具创建失败
     */
    @Test
    void usageRowsRejectCrossTenantProjectAndHideWhenTenantContextMissing() throws Exception {
        Actor ownerA = registerAndLogin("s7-quota-rls-a-" + UUID.randomUUID() + "@example.com");
        UUID projectA = createProject(ownerA, "S7 隔离项目甲");
        Actor ownerB = registerAndLogin("s7-quota-rls-b-" + UUID.randomUUID() + "@example.com");
        UUID projectB = createProject(ownerB, "S7 隔离项目乙");

        TenantContext.set(new TenantScope(ownerA.tenantId(), projectA, ownerA.accountId()));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO sys_usage_counter_daily
                        (id, tenant_id, project_id, usage_date, metric, used_value)
                    VALUES (?, ?, ?, ?, 'UPLINK_MESSAGE', 1)
                    """, Uuid7.generate(), ownerA.tenantId(), projectB, LocalDate.now(java.time.ZoneOffset.UTC)))
                    .rootCause().hasMessageContaining("foreign key");
        } finally {
            TenantContext.clear();
        }
        recordUsage(ownerA, projectA, LocalDate.now(java.time.ZoneOffset.UTC), "UPLINK_MESSAGE", 1);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM sys_usage_counter_daily", UUID.class)).isEmpty();

        // 日用量的 project_id 只负责拆分贡献，安全边界必须仍是租户；否则共享池会被错误收窄到当前项目。
        assertThat(jdbcTemplate.queryForList("""
                SELECT policyname || ':' || coalesce(qual, '') || ':' || coalesce(with_check, '')
                  FROM pg_policies
                 WHERE schemaname = 'public'
                   AND tablename = 'sys_usage_counter_daily'
                """, String.class))
                .singleElement()
                .asString()
                .contains("tenant_isolation")
                .contains("tenant_id = app_current_tenant()")
                .doesNotContain("app_current_project()");
    }

    /**
     * 路径项目不是 JWT 已选项目时返回 404，不能借路径切换数据范围。
     *
     * @throws Exception HTTP 夹具创建失败
     */
    @Test
    void rejectsPathProjectDifferentFromSelectedProject() throws Exception {
        Actor owner = registerAndLogin("s7-quota-path-" + UUID.randomUUID() + "@example.com");
        UUID selected = createProject(owner, "S7 已选项目");
        // S14-2b：FREE 只允许一个自有项目，第二个项目按既有事实落库，专注验路径与已选项目一致性。
        UUID other = seedOwnedProject(owner, "S7 其他项目");
        owner = switchProject(owner, selected);

        MvcResult result = quota(owner, other);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(50001);
    }

    /** @param actor 已选项目的登录主体 @param projectId 路径项目 @return HTTP 结果 */
    private MvcResult quota(Actor actor, UUID projectId) throws Exception {
        return mockMvc.perform(get("/api/v1/projects/" + projectId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken()))
                .andReturn();
    }

    /**
     * 通过真实认证链路创建已验证账号。
     *
     * @param email 测试邮箱
     * @return 账号、租户和会话令牌
     */
    private Actor registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        UUID accountId = jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_tenant_member WHERE account_id = ?", UUID.class, accountId);
        return new Actor(accountId, tenantId, body.get("accessToken").asString(), refresh);
    }

    /** @param actor 登录主体 @param name 项目名称 @return 新项目 ID */
    private UUID createProject(Actor actor, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(name)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /**
     * S14-2b：把一个既有自有项目与其 OWNER 成员关系直接落库。
     *
     * <p>FREE 策略的 {@code projects_max = 1} 已在真实创建入口生效；本类验的是配额投影，
     * 需要同租户多项目作为既有事实。这里只造夹具，不绕过也不放宽生产守卫。</p>
     *
     * @param actor 项目归属账号与租户
     * @param name 项目名
     * @return 新项目 ID
     */
    private UUID seedOwnedProject(Actor actor, String name) {
        UUID projectId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, projectId, actor.tenantId(), name,
                "s7q" + projectId.toString().replace("-", "").substring(0, 15));
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'OWNER')
                """, Uuid7.generate(), projectId, actor.accountId());
        return projectId;
    }

    /** @param actor 当前会话 @param projectId 要选定的项目 @return 轮换后的会话 */
    private Actor switchProject(Actor actor, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                        .cookie(new Cookie("tc_refresh", actor.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(projectId)))
                .andReturn();
        String refresh = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElse(actor.refreshToken());
        return new Actor(actor.accountId(), actor.tenantId(),
                JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asString(), refresh);
    }

    /** 通过设备 HTTP API 建立真实 dev_device 事实。 */
    private void createDevice(Actor actor, UUID projectId, String key, String name) throws Exception {
        assertThat(mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"%s\",\"name\":\"%s\"}".formatted(key, name)))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
    }

    /** 使用已授权租户范围写入项目归属的 UTC 日用量事实。 */
    private void recordUsage(Actor owner, UUID projectId, LocalDate date, String metric, long value) {
        usageTenantIds.add(owner.tenantId());
        TenantContext.set(new TenantScope(owner.tenantId(), projectId, owner.accountId()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO sys_usage_counter_daily
                        (id, tenant_id, project_id, usage_date, metric, used_value)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Uuid7.generate(), owner.tenantId(), projectId, date, metric, value);
        } finally {
            TenantContext.clear();
        }
    }

    /** 直接增加协作成员，仅作为授权夹具。 */
    private void addMember(UUID projectId, UUID accountId, String role) {
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, ?)",
                Uuid7.generate(), projectId, accountId, role);
    }

    /** 比对单个指标的编码、当前显示用量、共享池用量和策略上限。 */
    private static void assertMetric(JsonNode metric, String code, long used, long tenantUsed, Long limit) {
        assertThat(metric.get("metric").asString()).isEqualTo(code);
        assertThat(metric.get("used").asLong()).isEqualTo(used);
        assertThat(metric.get("limit").asLong()).isEqualTo(limit);
        // 项目范围和共享池范围都采用同一份剩余额度，避免误显示成项目独享余量。
        assertThat(metric.get("remaining").asLong()).isEqualTo(limit - tenantUsed);
    }

    /** 在指标数组中按冻结编码定位元素。 */
    private static JsonNode findMetric(JsonNode metrics, String code) {
        for (JsonNode metric : metrics) if (code.equals(metric.get("metric").asString())) return metric;
        throw new AssertionError("缺少指标 " + code);
    }

    /** 测试账号身份、租户归属与会话凭据。 */
    private record Actor(UUID accountId, UUID tenantId, String accessToken, String refreshToken) {
    }
}
