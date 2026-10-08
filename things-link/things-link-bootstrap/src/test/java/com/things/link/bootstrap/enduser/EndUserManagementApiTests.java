package com.things.link.bootstrap.enduser;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 终端用户管理的端到端测试（S11-1b）。
 *
 * <h2>授权判定在两个角色上区分</h2>
 * 预置/分配/停用/恢复/改角色要求项目 OWNER / ADMIN（{@code canManageMembers()}），
 * 读列表与设备概览任意成员可看。非成员访问统一 404（不泄露项目是否存在），
 * 成员但角色不足才 403（60002）。
 *
 * <h2>为什么放在 bootstrap</h2>
 * 预置在项目归属租户下写 {@code app_user}，要真实走注册/登录拿令牌（iam）与
 * 建项目（project）；enduser 模块不能反向依赖二者，故放到聚合模块。
 *
 * <h2>清场策略</h2>
 * {@code app_user} 走租户轴 RLS、两张项目事实表走项目轴 RLS，直接 {@code DELETE} 会因
 * fail-closed 命中 0 行。因此按 {@code EndUserIsolationIntegrationTests} 同款做法：
 * 在 {@link #cleanup()} 里先借 {@link TenantContext} 建立上下文，再逆外键序删表。
 */
@AutoConfigureMockMvc
@DisplayName("终端用户管理（S11-1b）")
class EndUserManagementApiTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String OWNER_EMAIL = "owner-enduser@example.com";
    private static final String OPERATOR_EMAIL = "operator-enduser@example.com";
    private static final String OUTSIDER_EMAIL = "outsider-enduser@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthRateLimiter rateLimiter;

    /** 夹具放宽走与生产相同的 CAS 绑定与提交后缓存失效，不改任何生产守卫。 */
    @Autowired
    private QuotaPolicyAssignmentService quotaPolicyAssignmentService;

    private String ownerToken;
    private String operatorToken;
    private String outsiderToken;
    private UUID projectId;
    private UUID ownerTenantId;

    /** 本用例创建的项目（归属 ownerTenant），清场时逐一建立项目上下文删事实。 */
    private final Set<UUID> createdProjects = new LinkedHashSet<>();

    @BeforeEach
    void seed() throws Exception {
        rateLimiter.clear();
        ownerToken = register(OWNER_EMAIL);
        operatorToken = register(OPERATOR_EMAIL);
        outsiderToken = register(OUTSIDER_EMAIL);

        projectId = projectIdOf(createProject(ownerToken));
        createdProjects.add(projectId);
        ownerTenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);

        // 本组权限管理夹具需一个外部席位；专用容量用例在关系建立后显式切回FREE。
        useStandardPlan(ownerTenantId);
        // operator 成为项目 OPERATOR：读接口可过，写接口应 403。
        assertThat(addMember(projectId, "OPERATOR").getResponse().getStatus())
                .isEqualTo(200);
    }

    @AfterEach
    void cleanup() {
        try {
            for (UUID pid : createdProjects) {
                TenantContext.set(new TenantScope(ownerTenantId, pid, Uuid7.generate()));
                jdbcTemplate.update("DELETE FROM app_user_device WHERE project_id = ?", pid);
                jdbcTemplate.update("DELETE FROM app_user_role WHERE project_id = ?", pid);
            }
            TenantContext.set(new TenantScope(ownerTenantId, null, Uuid7.generate()));
            jdbcTemplate.update("DELETE FROM app_user WHERE tenant_id = ?", ownerTenantId);
        } finally {
            TenantContext.clear();
        }
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");
    }

    /** S14-R4b：真实认证HTTP层保留额度拒绝消息及缺投影503，不混成500或用户名冲突。 */
    @Test
    void provisioningCapacityErrorsRemainActionableAtHttpBoundary() throws Exception {
        jdbcTemplate.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", ownerTenantId);
        for (int i = 0; i < 3; i++) {
            assertThat(provision(ownerToken, "quota-" + i, "secret123", null).getResponse().getStatus()).isEqualTo(200);
        }
        var full = provision(ownerToken, "quota-four", "secret123", null).getResponse();
        assertThat(full.getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(full.getContentAsString()).get("code").asInt()).isEqualTo(60058);
        assertThat(JSON.readTree(full.getContentAsString()).get("message").asString()).contains("扩容后重试");
        jdbcTemplate.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?", ownerTenantId);
        var missing = provision(ownerToken, "quota-five", "secret123", null).getResponse();
        assertThat(missing.getStatus()).isEqualTo(503);
        assertThat(JSON.readTree(missing.getContentAsString()).get("code").asInt()).isEqualTo(50048);
    }

    /** 精确找回无角色账号，仅管理者可读，响应不含口令或其他项目角色。 */
    @Test
    void lookupRecoversUnassignedIdentityAndOnlyCurrentProjectAssignment() throws Exception {
        UUID id = provisionEndUser(ownerToken, "lookup-alice");
        String path = "/api/v1/projects/" + projectId + "/end-users/lookup";
        var found = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .param("username", "  LOOKUP-Alice ")).andReturn().getResponse();
        assertThat(found.getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(found.getContentAsString());
        assertThat(body.get("id").asString()).isEqualTo(id.toString());
        assertThat(body.get("role").isNull()).isTrue();
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("password")).isFalse();
        assertThat(JSON.readTree(list(ownerToken, projectId).getResponse().getContentAsString()).get("items")).isEmpty();
        UUID sibling = projectIdOf(createProject(ownerToken));
        createdProjects.add(sibling);
        assertThat(mockMvc.perform(post("/api/v1/projects/" + sibling + "/end-users/" + id + "/role")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"APP_ADMIN\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        var onlyCurrent = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .param("username", "lookup-alice")).andReturn().getResponse();
        assertThat(JSON.readTree(onlyCurrent.getContentAsString()).get("role").isNull()).isTrue();
        assign(ownerToken, id, "OBSERVER");
        suspend(ownerToken, id);
        var assigned = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .param("username", "lookup-alice")).andReturn().getResponse();
        assertThat(JSON.readTree(assigned.getContentAsString()).get("roleStatus").asString()).isEqualTo("DISABLED");
        var forbidden = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                .param("username", "lookup-alice")).andReturn().getResponse();
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(JSON.readTree(forbidden.getContentAsString()).get("code").asInt()).isEqualTo(60002);
        assertThat(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                .param("username", "lookup-alice")).andReturn().getResponse().getStatus()).isEqualTo(404);
        var missing = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .param("username", "other-tenant-or-missing")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(missing.getContentAsString()).get("code").asInt()).isEqualTo(60001);
        assertThat(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .param("username", " ")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- 预置

    @Test
    @DisplayName("OWNER 预置账号：落在项目归属租户，用户名规范化，尚未分配角色")
    void provisionCreatesTenantUserWithoutRole() throws Exception {
        MvcResult result = provision(ownerToken, "  Alice ", "secret123", " 张三 ");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("username").asString()).isEqualTo("alice");
        assertThat(body.get("displayName").asString()).isEqualTo("张三");
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("role").isNull()).as("预置只建账号，不分配角色").isTrue();

        UUID appUserId = UUID.fromString(body.get("id").asString());
        // app_user 受租户轴 RLS 保护，直查必须借 TenantContext 建立租户上下文。
        TenantContext.set(new TenantScope(ownerTenantId, null, Uuid7.generate()));
        try {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT tenant_id FROM app_user WHERE id = ?", UUID.class, appUserId))
                    .as("app_user 落在项目归属租户，而不是调用者自己的租户")
                    .isEqualTo(ownerTenantId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("预置参数校验：空白用户名与过短口令返回 400")
    void provisionValidatesInput() throws Exception {
        assertThat(provision(ownerToken, "   ", "secret123", null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(provision(ownerToken, "alice", "short", null).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("租户内用户名唯一：重复预置返回 409 与 60003")
    void provisionDuplicateUsernameIs409() throws Exception {
        provision(ownerToken, "alice", "secret123", null);
        MvcResult again = provision(ownerToken, "alice", "secret123", null);

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(again)).isEqualTo(60003);
    }

    @Test
    @DisplayName("OPERATOR 不能预置账号，返回 403 与 60002")
    void operatorCannotProvision() throws Exception {
        MvcResult result = provision(operatorToken, "alice", "secret123", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(60002);
    }

    @Test
    @DisplayName("非成员预置账号返回 404，不泄露项目是否存在")
    void outsiderCannotProvision() throws Exception {
        assertThat(provision(outsiderToken, "alice", "secret123", null).getResponse().getStatus())
                .isEqualTo(404);
    }

    // ---------------------------------------------------------------- 分配角色

    @Test
    @DisplayName("分配角色后列表带回项目角色与租户级状态")
    void assignRoleThenListShowsAssignment() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");

        assertThat(assign(ownerToken, appUserId, "OPERATOR").getResponse().getStatus())
                .isEqualTo(204);

        JsonNode items = listItems(ownerToken, projectId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("username").asString()).isEqualTo("alice");
        assertThat(items.get(0).get("role").asString()).isEqualTo("OPERATOR");
        assertThat(items.get(0).get("roleStatus").asString()).isEqualTo("ACTIVE");
        assertThat(items.get(0).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("重复分配返回 409 与 60004")
    void assignDuplicateIs409() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");
        assign(ownerToken, appUserId, "OPERATOR");

        MvcResult again = assign(ownerToken, appUserId, "MAINTAINER");

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(again)).isEqualTo(60004);
    }

    @Test
    @DisplayName("给不存在的终端用户分配角色返回 404 与 60001")
    void assignUnknownUserIs404() throws Exception {
        MvcResult result = assign(ownerToken, UUID.randomUUID(), "OPERATOR");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo(60001);
    }

    @Test
    @DisplayName("OPERATOR 不能分配角色，返回 403 与 60002")
    void operatorCannotAssign() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");

        MvcResult result = assign(operatorToken, appUserId, "OPERATOR");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(60002);
    }

    // ---------------------------------------------------------------- 改角色 / 停用 / 恢复

    @Test
    @DisplayName("改角色后列表立即反映新角色")
    void updateRoleTakesEffect() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");
        assign(ownerToken, appUserId, "OPERATOR");

        assertThat(updateRole(ownerToken, appUserId, "MAINTAINER").getResponse().getStatus())
                .isEqualTo(204);

        assertThat(listItems(ownerToken, projectId).get(0).get("role").asString())
                .isEqualTo("MAINTAINER");
    }

    @Test
    @DisplayName("对未分配角色的用户改角色返回 404 与 60005")
    void updateRoleOnUnassignedUserIs404() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");

        MvcResult result = updateRole(ownerToken, appUserId, "MAINTAINER");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo(60005);
    }

    @Test
    @DisplayName("停用与恢复只改项目级 roleStatus，不改租户级 status")
    void suspendAndRestoreToggleRoleStatus() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");
        assign(ownerToken, appUserId, "OPERATOR");

        assertThat(suspend(ownerToken, appUserId).getResponse().getStatus()).isEqualTo(204);
        JsonNode suspended = listItems(ownerToken, projectId).get(0);
        assertThat(suspended.get("roleStatus").asString()).isEqualTo("DISABLED");
        assertThat(suspended.get("status").asString())
                .as("项目级停用不碰租户级登录状态")
                .isEqualTo("ACTIVE");

        assertThat(restore(ownerToken, appUserId).getResponse().getStatus()).isEqualTo(204);
        assertThat(listItems(ownerToken, projectId).get(0).get("roleStatus").asString())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("停用项目角色原子关闭有效设备关系，恢复角色不复活旧授权")
    void suspendClosesActiveDeviceBindingsWithoutRestoringThem() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");
        assign(ownerToken, appUserId, "OPERATOR");
        UUID activeDeviceId = UUID.randomUUID();
        UUID alreadyClosedDeviceId = UUID.randomUUID();

        withProjectScope(() -> {
            insertDeviceBinding(appUserId, activeDeviceId, "PRIMARY", "ACTIVE");
            insertDeviceBinding(appUserId, alreadyClosedDeviceId, "READ_ONLY", "CLOSED");
        });

        assertThat(suspend(ownerToken, appUserId).getResponse().getStatus()).isEqualTo(204);
        assertThat(bindingStatuses(appUserId)).containsOnly("CLOSED");

        assertThat(restore(ownerToken, appUserId).getResponse().getStatus()).isEqualTo(204);
        assertThat(bindingStatuses(appUserId))
                .as("恢复项目角色不能绕过 ADR 0037 的绑定流程复活旧授权")
                .containsOnly("CLOSED");
    }

    @Test
    @DisplayName("无管理权限的停用请求被拒绝且不关闭设备关系")
    void unauthorizedSuspendLeavesDeviceBindingsActive() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");
        assign(ownerToken, appUserId, "OPERATOR");
        withProjectScope(() -> insertDeviceBinding(
                appUserId, UUID.randomUUID(), "MEMBER", "ACTIVE"));

        MvcResult result = suspend(operatorToken, appUserId);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(60002);
        assertThat(bindingStatuses(appUserId)).containsExactly("ACTIVE");
    }

    // ---------------------------------------------------------------- 列表 / 设备概览

    @Test
    @DisplayName("新项目列表为空，任意成员可读")
    void emptyListAndOperatorCanRead() throws Exception {
        assertThat(JSON.readTree(list(ownerToken, projectId).getResponse().getContentAsString())
                .get("items")).isEmpty();
        // 项目 OPERATOR（来自其他租户的协作者）也能读列表。
        assertThat(list(operatorToken, projectId).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("列表游标分页：limit=2 时首页 hasMore，末页收口")
    void listPaginatesWithCursor() throws Exception {
        for (String username : new String[]{"alice", "bob", "carol"}) {
            assign(ownerToken, provisionEndUser(ownerToken, username), "OPERATOR");
        }

        JsonNode first = JSON.readTree(list(ownerToken, projectId, null, 2)
                .getResponse().getContentAsString());
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        String cursor = first.get("nextCursor").asString();

        JsonNode second = JSON.readTree(list(ownerToken, projectId, cursor, 2)
                .getResponse().getContentAsString());
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("设备绑定概览在绑定流程落地前返回空列表")
    void devicesReturnsEmptyList() throws Exception {
        UUID appUserId = provisionEndUser(ownerToken, "alice");
        assign(ownerToken, appUserId, "OPERATOR");

        JsonNode body = JSON.readTree(devices(ownerToken, appUserId)
                .getResponse().getContentAsString());
        assertThat(body).isEmpty();
    }

    // ---------------------------------------------------------------- 隔离

    @Test
    @DisplayName("另一个项目读不到本项目的终端用户")
    void anotherProjectCannotReadThisProjectUsers() throws Exception {
        assign(ownerToken, provisionEndUser(ownerToken, "alice"), "OPERATOR");
        // 本用例需要在同一租户下再建一个项目，超过新租户默认 PLAN_R1_FREE 的 projects_max = 1
        // （S14-2b 的真实守卫）。夹具显式切到合法 STANDARD 模板（最多5个项目）；
        // 保留生产额度守卫，不用legacy缺投影绕过。
        useStandardPlan(ownerTenantId);

        UUID otherProject = projectIdOf(createProject(ownerToken));
        createdProjects.add(otherProject);

        JsonNode items = listItems(ownerToken, otherProject);
        assertThat(items)
                .as("列表按项目轴驱动，同租户下另一项目也看不到 alice")
                .isEmpty();
    }

    @Test
    @DisplayName("非成员读列表返回 404 而不是 403，不泄露项目是否存在")
    void nonMemberCannotProbeProjectExistence() throws Exception {
        MvcResult existing = list(outsiderToken, projectId);
        assertThat(existing.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(existing)).isEqualTo(50001);

        MvcResult fabricated = list(outsiderToken, UUID.randomUUID());
        assertThat(fabricated.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(fabricated)).isEqualTo(codeOf(existing));
    }

    @Test
    @DisplayName("未认证访问全部返回 401")
    void requiresAuthentication() throws Exception {
        String base = "/api/v1/projects/" + projectId + "/end-users";
        UUID appUserId = UUID.randomUUID();

        assertThat(mockMvc.perform(get(base)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post(base)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret123"}"""))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post(base + "/" + appUserId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"OPERATOR"}"""))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(patch(base + "/" + appUserId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"OPERATOR"}"""))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post(base + "/" + appUserId + "/suspend"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post(base + "/" + appUserId + "/restore"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get(base + "/" + appUserId + "/devices"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- 辅助方法

    private String register(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();
        return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString();
    }

    /**
     * 把夹具租户切到合法 STANDARD 模板（{@code projects_max = 5}），满足跨项目用例的
     * 项目数/设备数/终端用户数冻结上限，仅供确需更多资源的用例使用。
     *
     * @param tenantId 夹具租户 ID
     */
    private void useStandardPlan(UUID tenantId) {
        UUID standardPolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = 'PLAN_R1_STANDARD'", UUID.class);
        long assignmentVersion = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
        quotaPolicyAssignmentService.assign(tenantId, standardPolicyId, assignmentVersion);
    }

    private MvcResult createProject(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"厂区环境监测","region":"sh-1"}"""))
                .andReturn();
    }

    private static UUID projectIdOf(MvcResult result) throws Exception {
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .get("id").asString());
    }

    private MvcResult addMember(UUID pid, String role) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + pid + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"%s"}""".formatted(OPERATOR_EMAIL, role)))
                .andReturn();
    }

    private MvcResult provision(String token, String username, String password, String displayName)
            throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/end-users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","displayName":"%s"}"""
                                .formatted(username, password, displayName)))
                .andReturn();
    }

    private UUID provisionEndUser(String token, String username) throws Exception {
        MvcResult result = provision(token, username, "secret123", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .get("id").asString());
    }

    private MvcResult assign(String token, UUID appUserId, String role) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/end-users/" + appUserId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"%s"}""".formatted(role)))
                .andReturn();
    }

    private MvcResult updateRole(String token, UUID appUserId, String role) throws Exception {
        return mockMvc.perform(patch("/api/v1/projects/" + projectId + "/end-users/" + appUserId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"%s"}""".formatted(role)))
                .andReturn();
    }

    private MvcResult suspend(String token, UUID appUserId) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/end-users/" + appUserId + "/suspend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult restore(String token, UUID appUserId) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/end-users/" + appUserId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult list(String token, UUID pid) throws Exception {
        return list(token, pid, null, 50);
    }

    private MvcResult list(String token, UUID pid, String cursor, int limit) throws Exception {
        var request = get("/api/v1/projects/" + pid + "/end-users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("limit", String.valueOf(limit));
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode listItems(String token, UUID pid) throws Exception {
        return JSON.readTree(list(token, pid).getResponse().getContentAsString()).get("items");
    }

    private MvcResult devices(String token, UUID appUserId) throws Exception {
        return mockMvc.perform(get("/api/v1/projects/" + projectId + "/end-users/" + appUserId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    /** 在目标项目 RLS 上下文内执行测试数据准备。 */
    private void withProjectScope(Runnable action) {
        TenantContext.set(new TenantScope(ownerTenantId, projectId, Uuid7.generate()));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** 插入一条设备关系；device 无跨模块外键，随机 ID 足以验证 enduser 状态机。 */
    private void insertDeviceBinding(UUID appUserId, UUID deviceId, String role, String status) {
        jdbcTemplate.update("""
                INSERT INTO app_user_device
                    (id, tenant_id, project_id, app_user_id, device_id, relation_role, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Uuid7.generate(), ownerTenantId, projectId, appUserId, deviceId, role, status);
    }

    /** 读取目标用户全部关系状态，查询本身同样受项目 RLS 保护。 */
    private java.util.List<String> bindingStatuses(UUID appUserId) {
        TenantContext.set(new TenantScope(ownerTenantId, projectId, Uuid7.generate()));
        try {
            return jdbcTemplate.queryForList("""
                    SELECT status
                      FROM app_user_device
                     WHERE project_id = ? AND app_user_id = ?
                     ORDER BY created_at, id
                    """, String.class, projectId, appUserId);
        } finally {
            TenantContext.clear();
        }
    }

    private static int codeOf(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }
}
