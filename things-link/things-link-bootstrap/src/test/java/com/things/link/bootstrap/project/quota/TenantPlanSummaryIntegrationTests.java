package com.things.link.bootstrap.project.quota;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.EntitlementAdjustmentRequest;
import com.things.link.project.application.TenantEntitlementAdjustmentService;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.TenantOrder;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S14-2c 租户套餐摘要的真实 HTTP、RLS 与跨租户协作验收（架构文档 §3.2 / §6）。
 *
 * <p>S14-4c 追加第五件事：租户能在同一读取面看到**运行时有效额度**与扩容/人工调整的溯源，
 * 且目录冻结值与运行时有效值并列而不互相冒充，人工调整只暴露原因、不暴露操作人账号。
 *
 * <p>本类钉住四件事：同一租户的成员能在**既有**项目配额接口上读到本租户套餐摘要（FREE 修订版、
 * ACTIVE 订阅、无终点服务期、冻结额度 1 项目 / 3 设备 / 上行 700 + 下行 300）并与真实用量并列；
 * 非成员按既有口径 404；跨租户协作者只保留既有共享池投影，读不到任何订阅事实；摘要里没有成交价、
 * 订单或其他租户事实，参考价只作展示。
 *
 * <p>授权没有放宽：套餐摘要端口自身的 SQL 要求 {@code app_current_tenant()} 等于项目归属租户，
 * 因此协作者即使通过了项目成员校验也拿不到摘要。
 */
@AutoConfigureMockMvc
@DisplayName("S14-2c/S14-4c 租户套餐摘要与有效额度")
class TenantPlanSummaryIntegrationTests extends AbstractIntegrationTest {

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
    /** S14-4a 真实购买路径：买一个资源包证明有效额度与溯源都跟随。 */
    @Autowired
    private TenantResourcePackageService tenantResourcePackageService;
    /** S14-4b 真实运营调整路径：证明人工调整同样出现在溯源里。 */
    @Autowired
    private TenantEntitlementAdjustmentService tenantEntitlementAdjustmentService;

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
     * 租户 OWNER 读到 FREE 修订版、ACTIVE 长期订阅、冻结额度与真实用量。
     *
     * @throws Exception 认证、项目切换或 HTTP 调用失败
     */
    @Test
    void ownerSeesFreeSubscriptionSummaryWithPerpetualServiceAndRealUsage() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Actor owner = registerAndLogin("s14-2c-owner-" + nonce + "@example.com");
        UUID projectId = createProject(owner, "S14-2c 套餐项目");
        owner = switchProject(owner, projectId);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        recordUsage(owner, projectId, today, "UPLINK_MESSAGE", 123);
        recordUsage(owner, projectId, today, "DOWNLINK_MESSAGE", 7);

        MvcResult result = quota(owner, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        JsonNode plan = body.get("planSummary");
        assertThat(plan)
                .as("同一租户成员必须读到套餐摘要，而不是被当成跨租户协作者省略")
                .isNotNull();
        assertThat(plan.get("subscribedPlan").get("code").asString()).isEqualTo("FREE");
        assertThat(plan.get("subscribedPlan").get("name").asString()).isEqualTo("免费版");
        assertThat(plan.get("subscribedPlan").get("revision").asString()).isEqualTo("product-revision-1");
        assertThat(plan.get("subscribedPlan").get("revisionNo").asInt()).isEqualTo(1);
        assertThat(plan.get("effectivePlan").get("code").asString())
                .as("运行时指针当前绑定的就是 PLAN_R1_FREE 所属档位")
                .isEqualTo("FREE");
        assertThat(plan.get("effectiveMatchesSubscribed").asBoolean()).isTrue();
        assertThat(plan.get("subscriptionStatus").asString()).isEqualTo("ACTIVE");
        assertThat(plan.get("startsAt").asString()).isNotBlank();
        assertThat(plan.has("endsAt"))
                .as("FREE 长期有效档没有服务期终点；缺省表示无终点而不是加载失败")
                .isFalse();
        assertThat(plan.get("perpetual").asBoolean()).isTrue();
        assertThat(plan.get("billingPeriod").asString()).isEqualTo("NONE");
        assertThat(plan.get("renewalMode").asString()).isEqualTo("NONE");
        assertThat(plan.get("referencePriceCents").asLong())
                .as("参考价只作展示：FREE 的参考价是真实的 0")
                .isZero();
        assertThat(plan.get("referencePriceCurrency").asString()).isEqualTo("CNY");

        // 冻结额度使用目录的维度口径（编码 + 数值 + 单位 + 窗口），日额度是 1000 拆成上行 700 / 下行 300。
        assertFrozenLimit(plan, "PROJECTS_MAX", 1, "COUNT", "NONE");
        assertFrozenLimit(plan, "DEVICES_MAX", 3, "COUNT", "NONE");
        assertFrozenLimit(plan, "UPLINK_MESSAGE_DAILY", 700, "MESSAGE", "UTC_DAY");
        assertFrozenLimit(plan, "DOWNLINK_MESSAGE_DAILY", 300, "MESSAGE", "UTC_DAY");

        // DISABLED 权益必须保留且不携带数值额度：未交付能力不能被渲染成「0 次/日」。
        assertThat(capability(plan, "OTA").get("enabled").asBoolean()).isFalse();
        for (JsonNode capability : plan.get("capabilities")) {
            assertThat(capability.get("enforcement").asString()).isEqualTo("CATALOG_ONLY");
        }
        assertThat(capability(plan, "REST_API_WRITE").get("enabled").asBoolean()).isTrue();

        // 真实用量来自既有事实，且与冻结上限并列展示。
        assertMetric(findMetric(body.get("tenantSharedPool").get("dailyMetrics"), "UPLINK_MESSAGE"),
                "UPLINK_MESSAGE", 123, 700L);
        assertMetric(findMetric(body.get("project").get("dailyMetrics"), "UPLINK_MESSAGE"),
                "UPLINK_MESSAGE", 123, 700L);
        assertMetric(findMetric(body.get("tenantSharedPool").get("dailyMetrics"), "DOWNLINK_MESSAGE"),
                "DOWNLINK_MESSAGE", 7, 300L);
        assertThat(body.get("project").get("deviceCount").get("limit").asLong()).isEqualTo(3L);

        // 摘要不得携带成交价、订单或其他租户事实；参考价是唯一允许出现的价格字段。
        assertThat(body.toString())
                .doesNotContain("\"priceCents\"")
                .doesNotContain("sourceOrderId")
                .doesNotContain(owner.tenantId().toString());
        assertThat(body.toString()).contains("\"referencePriceCents\"");
    }

    /**
     * 同租户的非 OWNER 项目成员同样能读到套餐摘要。
     *
     * @throws Exception 认证、项目切换或 HTTP 调用失败
     */
    @Test
    void sameTenantProjectMemberCanReadPlanSummary() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Actor owner = registerAndLogin("s14-2c-tenant-owner-" + nonce + "@example.com");
        UUID projectId = createProject(owner, "S14-2c 同租户成员项目");
        owner = switchProject(owner, projectId);
        Actor member = joinSameTenant(owner.tenantId(), projectId, "s14-2c-member-" + nonce + "@example.com");

        MvcResult result = quota(member, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode plan = JSON.readTree(result.getResponse().getContentAsString()).get("planSummary");
        assertThat(plan).isNotNull();
        assertThat(plan.get("subscribedPlan").get("code").asString()).isEqualTo("FREE");
        assertThat(plan.get("subscriptionStatus").asString()).isEqualTo("ACTIVE");
    }

    /**
     * 跨租户协作者只保留既有共享池投影，读不到任何订阅事实。
     *
     * @throws Exception 认证、项目切换或 HTTP 调用失败
     */
    @Test
    void crossTenantCollaboratorKeepsOnlyExistingSharedPoolProjection() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Actor owner = registerAndLogin("s14-2c-collab-owner-" + nonce + "@example.com");
        UUID projectId = createProject(owner, "S14-2c 协作项目");
        owner = switchProject(owner, projectId);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        recordUsage(owner, projectId, today, "UPLINK_MESSAGE", 42);

        Actor collaborator = registerAndLogin("s14-2c-viewer-" + nonce + "@example.com");
        assertThat(collaborator.tenantId())
                .as("本用例的前提：协作者属于另一个租户，而不是归属租户的成员")
                .isNotEqualTo(owner.tenantId());
        addMember(projectId, collaborator.accountId(), "VIEWER");
        collaborator = switchProject(collaborator, projectId);

        MvcResult result = quota(collaborator, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("planSummary"))
                .as("架构文档 §6：跨租户协作者在项目页只看到共享池投影，不能读到归属租户的订阅事实")
                .isNull();
        // 既有共享池投影保持不变：协作者仍然能读到上一个切片就允许的用量与上限。
        assertMetric(findMetric(body.get("tenantSharedPool").get("dailyMetrics"), "UPLINK_MESSAGE"),
                "UPLINK_MESSAGE", 42, 700L);
        assertThat(body.toString())
                .doesNotContain(owner.tenantId().toString())
                .doesNotContain("\"priceCents\"")
                .doesNotContain("\"referencePriceCents\"")
                .doesNotContain("subscribedPlan")
                .doesNotContain("sourceOrderId");
    }

    /**
     * 非成员按既有口径返回 404，不因套餐摘要改变错误形状。
     *
     * @throws Exception 认证、项目切换或 HTTP 调用失败
     */
    @Test
    void nonMemberIsRefusedWithProjectNotFound() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Actor owner = registerAndLogin("s14-2c-nomember-owner-" + nonce + "@example.com");
        UUID projectId = createProject(owner, "S14-2c 非成员项目");
        owner = switchProject(owner, projectId);

        Actor stranger = registerAndLogin("s14-2c-stranger-" + nonce + "@example.com");
        UUID strangerProject = createProject(stranger, "S14-2c 陌生人项目");
        stranger = switchProject(stranger, strangerProject);

        MvcResult result = quota(stranger, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(50001);
    }

    /**
     * S14-4c：同一租户成员读到运行时有效额度与扩容/调整溯源，且与真实准入值逐值一致。
     *
     * @throws Exception 认证、项目切换或 HTTP 调用失败
     */
    @Test
    void tenantSeesRuntimeEffectiveQuotaAndGrantProvenanceNextToFrozenCatalogValues() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Actor owner = registerAndLogin("s14-4c-additions-" + nonce + "@example.com");
        UUID projectId = createProject(owner, "S14-4c 有效额度项目");
        owner = switchProject(owner, projectId);

        // 两条真实来源：S14-4a 的模拟购买 +2，S14-4b 的运营人工调整 +1。
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                owner.tenantId(), "DEVICES_MAX", 2, null);
        tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(order.id(), "sim-pkg-4c-1");
        Instant now = Instant.now();
        tenantEntitlementAdjustmentService.createAdjustment(owner.tenantId(),
                new EntitlementAdjustmentRequest("DEVICES_MAX", 1, now, now.plus(30, ChronoUnit.DAYS),
                        "工单 INC-2026-4C 补偿", owner.accountId(), "inc-4c-read-0001"));

        MvcResult result = quota(owner, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        JsonNode plan = body.get("planSummary");
        assertThat(plan).isNotNull();

        // 目录冻结值保持不变：有效值不得冒充「这个套餐就是 6 台」。
        assertFrozenLimit(plan, "DEVICES_MAX", 3, "COUNT", "NONE");
        // 运行时有效值 = 基础 3 + 包 2 + 调整 1，单位是运行时单位（存储按字节而不是目录的 MB/GB）。
        assertEffectiveLimit(plan, "DEVICES_MAX", 6, "COUNT", "NONE");
        assertEffectiveLimit(plan, "STORAGE_LIMIT", 100L * 1024 * 1024, "BYTE", "NONE");
        assertEffectiveLimit(plan, "PROJECTS_MAX", 1, "COUNT", "NONE");

        // 溯源两条：购买与人工调整各自一条，窗口与生效结论都由服务端给出。
        JsonNode additions = plan.get("additions");
        assertThat(additions.size()).as("购买与人工调整各一条溯源").isEqualTo(2);
        JsonNode purchase = findAddition(additions, "PURCHASE");
        assertThat(purchase.get("dimensionCode").asString()).isEqualTo("DEVICES_MAX");
        assertThat(purchase.get("amount").asLong()).isEqualTo(2L);
        assertThat(purchase.get("effectiveNow").asBoolean()).isTrue();
        assertThat(purchase.has("reason")).as("购买包没有原因字段").isFalse();
        JsonNode adjustment = findAddition(additions, "OPERATION_ADJUSTMENT");
        assertThat(adjustment.get("amount").asLong()).isEqualTo(1L);
        assertThat(adjustment.get("effectiveNow").asBoolean()).isTrue();
        assertThat(adjustment.get("reason").asString()).contains("INC-2026-4C");
        assertThat(adjustment.get("status").asString()).isEqualTo("ACTIVE");

        // 最小披露：原因对租户可见，操作人账号不随任何字段下发。
        assertThat(additions.toString()).doesNotContain(owner.accountId().toString());

        // 显示与强制在同一个响应里逐值一致：设备准入上限就是合成后的 6。
        assertThat(body.get("project").get("deviceCount").get("limit").asLong()).isEqualTo(6L);
        assertThat(body.get("tenantSharedPool").get("deviceCount").get("limit").asLong()).isEqualTo(6L);
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
        return login(email);
    }

    /**
     * 以真实登录链路换取会话；租户从 {@code sys_tenant_member} 读取，与生产签发口径一致。
     *
     * @param email 测试邮箱
     * @return 账号、租户和会话令牌
     */
    private Actor login(String email) throws Exception {
        rateLimiter.clear();
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

    /**
     * 让一个新账号加入既有租户并成为项目成员。
     *
     * <p>注册会先给账号建一个自有租户；这里把成员关系迁到项目归属租户，模拟真实的同租户成员，
     * 而不是跨租户协作者关系，然后用新租户重新登录取得令牌。
     *
     * @param ownerTenantId 项目归属租户 ID
     * @param projectId 目标项目 ID
     * @param email 新账号邮箱
     * @return 已选中目标项目的同租户成员会话
     */
    private Actor joinSameTenant(UUID ownerTenantId, UUID projectId, String email) throws Exception {
        Actor actor = registerAndLogin(email);
        jdbcTemplate.update("UPDATE sys_tenant_member SET tenant_id = ? WHERE account_id = ?",
                ownerTenantId, actor.accountId());
        actor = login(email);
        assertThat(actor.tenantId()).isEqualTo(ownerTenantId);
        addMember(projectId, actor.accountId(), "OPERATOR");
        return switchProject(actor, projectId);
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

    /** 直接增加项目成员，仅作为授权夹具。 */
    private void addMember(UUID projectId, UUID accountId, String role) {
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, ?)",
                Uuid7.generate(), projectId, accountId, role);
    }

    /** 在摘要里按冻结编码定位维度并逐字段比对。 */
    private static void assertFrozenLimit(JsonNode plan, String code, long value, String unit, String window) {
        JsonNode dimension = findDimension(plan, code);
        assertThat(dimension.get("value").asLong()).isEqualTo(value);
        assertThat(dimension.get("unit").asString()).isEqualTo(unit);
        assertThat(dimension.get("window").asString()).isEqualTo(window);
    }

    /** 在摘要的运行时有效维度数组里按编码定位维度并逐字段比对。 */
    private static void assertEffectiveLimit(JsonNode plan, String code, long value, String unit,
                                             String window) {
        JsonNode dimension = findEffectiveDimension(plan, code);
        assertThat(dimension.get("value").asLong())
                .as("运行时有效维度 %s 必须已含有效扩容与调整", code)
                .isEqualTo(value);
        assertThat(dimension.get("unit").asString()).isEqualTo(unit);
        assertThat(dimension.get("window").asString()).isEqualTo(window);
    }

    /** 在摘要的运行时有效维度数组里按编码定位元素。 */
    private static JsonNode findEffectiveDimension(JsonNode plan, String code) {
        assertThat(plan.get("effectiveQuotaDimensions"))
                .as("运行时绑定是可售套餐模板时必须给出有效额度投影")
                .isNotNull();
        for (JsonNode dimension : plan.get("effectiveQuotaDimensions")) {
            if (code.equals(dimension.get("code").asString())) return dimension;
        }
        throw new AssertionError("套餐摘要缺少运行时有效维度 " + code);
    }

    /** 在扩容/调整溯源数组里按来源定位元素。 */
    private static JsonNode findAddition(JsonNode additions, String source) {
        for (JsonNode addition : additions) {
            if (source.equals(addition.get("source").asString())) return addition;
        }
        throw new AssertionError("套餐摘要缺少来源为 " + source + " 的溯源行");
    }

    /** 在摘要的冻结维度数组里按编码定位元素。 */
    private static JsonNode findDimension(JsonNode plan, String code) {
        for (JsonNode dimension : plan.get("quotaDimensions")) {
            if (code.equals(dimension.get("code").asString())) return dimension;
        }
        throw new AssertionError("套餐摘要缺少冻结维度 " + code);
    }

    /** 在摘要的功能权益数组里按编码定位元素。 */
    private static JsonNode capability(JsonNode plan, String code) {
        for (JsonNode capability : plan.get("capabilities")) {
            if (code.equals(capability.get("code").asString())) return capability;
        }
        throw new AssertionError("套餐摘要缺少功能权益 " + code);
    }

    /** 比对单个指标的编码、当前显示用量和共享池上限。 */
    private static void assertMetric(JsonNode metric, String code, long used, Long limit) {
        assertThat(metric.get("metric").asString()).isEqualTo(code);
        assertThat(metric.get("used").asLong()).isEqualTo(used);
        assertThat(metric.get("limit").asLong()).isEqualTo(limit);
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
