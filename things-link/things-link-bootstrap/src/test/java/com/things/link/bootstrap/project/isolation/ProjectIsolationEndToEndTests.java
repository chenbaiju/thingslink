package com.things.link.bootstrap.project.isolation;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 项目隔离的<b>端到端</b>验证（S1 切片 5c 第 2 步）。
 *
 * <h2>这是 ADR 0012 校准留下的最后一块拼图</h2>
 * 换轴时项目还没有入口（令牌里没有 {@code pid}），所以只能在数据库层证明策略生效，
 * 证明不了真实请求路径。有了项目选择器才补得上这条：
 *
 * <pre>
 * 登录 → 切项目 → 令牌 pid → TenantScopeFilter → TenantContext.projectId
 *      → TenantAwareDataSource 写 app.project_id → RLS 策略 → 查询结果被裁剪
 * </pre>
 *
 * <h2>探针接口刻意不带项目条件</h2>
 * {@code SELECT * FROM project_http_probe} 没有 {@code WHERE project_id = ?}。
 * 要测的正是<b>应用层漏写项目条件时，RLS 这道防线还在不在</b>。
 * 带上条件的话，测试通过只能说明「我记得写 where」。
 */
@AutoConfigureMockMvc
@DisplayName("项目隔离端到端（ADR 0012）")
class ProjectIsolationEndToEndTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** 探针接口。<b>刻意不写项目过滤条件</b>，见类注释。 */
    @TestConfiguration
    static class ProbeConfiguration {

        @RestController
        static class ProjectProbeController {

            private final JdbcTemplate jdbcTemplate;

            ProjectProbeController(JdbcTemplate jdbcTemplate) {
                this.jdbcTemplate = jdbcTemplate;
            }

            @GetMapping("/test-only/project-probe")
            List<String> notes() {
                // 没有 WHERE project_id = ? —— 唯一的隔离来源是 RLS
                return jdbcTemplate.queryForList(
                        "SELECT note FROM project_http_probe ORDER BY note", String.class);
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthRateLimiter rateLimiter;

    /** S14-2b 测试专用额度放宽：复用生产 CAS 与提交后缓存失效，不改任何生产守卫或冻结模板。 */
    @Autowired
    private com.things.link.project.application.QuotaPolicyAssignmentService quotaPolicyAssignmentService;

    /** 生产控制台JWT解码器，用于核对项目代次声明而不复制签名解析实现。 */
    @Autowired
    @Qualifier("jwtDecoder")
    private JwtDecoder jwtDecoder;

    private String tokenOfA;
    private UUID accountId;
    private UUID tenantId;
    private UUID projectOne;
    private UUID projectTwo;

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

        tokenOfA = register("alice@example.com");
        accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_account WHERE email='alice@example.com'", UUID.class);
        tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant LIMIT 1", UUID.class);
        // S14-2b：FREE 冻结 projects_max=1，而本类需要同一租户的两个项目来证明项目轴 RLS。
        // 绑定现有STANDARD冻结模板，保留真实项目额度守卫。
        widenFixtureProjectQuota(tenantId);
        projectOne = createProject(tokenOfA, "项目一");
        projectTwo = createProject(tokenOfA, "项目二");

        insertProbe(tenantId, projectOne, "项目一的数据");
        insertProbe(tenantId, projectTwo, "项目二的数据");
    }

    /** 仅将夹具租户绑定现有STANDARD模板，满足双项目矩阵，不修改冻结模板或生产守卫。 */
    private void widenFixtureProjectQuota(UUID fixtureTenantId) {
        UUID standardPolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = 'PLAN_R1_STANDARD'", UUID.class);
        long assignmentVersion = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, fixtureTenantId);
        quotaPolicyAssignmentService.assign(fixtureTenantId, standardPolicyId, assignmentVersion);
    }

    /**
     * 注册一个账号、标记邮箱已验证，并返回访问令牌。
     *
     * <p>注册返回 204 且**不签发令牌**：邮箱验证完成前不能进入控制台（ADR 0013）。
     * 所以拿令牌必须再走一次登录。
     *
     * <p>这里直接改 {@code email_verified_at}，而不是驱动真实的「收信 → 点链接」流程 ——
     * 本类验证的是项目轴 RLS 在真实请求路径上是否生效，邮箱验证链路已有
     * {@code EmailVerificationApiTests} 专门覆盖。
     */
    private String register(String email) throws Exception {
        rateLimiter.clear();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    private UUID createProject(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","region":"sh-1"}""".formatted(name)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(
                JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /**
     * 造种子数据同样受 RLS 的 {@code WITH CHECK} 约束（测试连的是应用角色），
     * 所以必须先设好项目上下文 —— 连造数据都绕不过策略，这本身就是策略生效的证据。
     */
    private void insertProbe(UUID tenantId, UUID projectId, String note) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO project_http_probe (id, tenant_id, project_id, note)
                    VALUES (?, ?, ?, ?)
                    """, Uuid7.generate(), tenantId, projectId, note);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 切换项目并返回新的访问令牌。
     *
     * <p><b>两样凭据都要带</b>：
     * <ul>
     *   <li>访问令牌 —— 服务端要知道「你是谁」才能查成员关系</li>
     *   <li>刷新令牌 Cookie —— 项目选择写在它上面，轮换时要找到当前会话</li>
     * </ul>
     * 只带 Cookie 会得到 401：这个接口不在放行清单里，它是受保护的。
     */
    private String switchProject(String accessToken, String refreshCookie, UUID projectId)
            throws Exception {
        String body = projectId == null
                ? """
                {"projectId":null}"""
                : """
                {"projectId":"%s"}""".formatted(projectId);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", refreshCookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    /** 走一次登录，同时拿到访问令牌与刷新令牌 Cookie。 */
    private String[] login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();

        String access = JSON.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
        String cookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("tc_refresh="))
                .map(h -> h.substring("tc_refresh=".length(), h.indexOf(';')))
                .findFirst().orElseThrow();
        return new String[]{access, cookie};
    }

    private List<String> probeAs(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/test-only/project-probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.convertValue(
                JSON.readTree(result.getResponse().getContentAsString()), List.class);
    }

    /**
     * <b>本类的核心断言。</b>同一个人、同一个租户，切到不同项目就只能看到那个项目的数据。
     *
     * <p>按租户轴隔离的话这条必然失败：两个项目属于同一个租户，租户上下文相同，
     * 两次查询会看到全部数据。这正是 ADR 0012 换轴要解决的问题。
     */
    @Test
    @DisplayName("切换项目后只能看到该项目的数据")
    void seesOnlyCurrentProjectRows() throws Exception {
        String[] session = login("alice@example.com");

        String tokenInProjectOne = switchProject(session[0], session[1], projectOne);
        assertThat(probeAs(tokenInProjectOne))
                .as("查询没有写项目条件，隔离完全靠 RLS —— 看到另一个项目的数据"
                        + "就说明「切项目 → pid → 会话变量 → 策略」这条链路某处断了")
                .containsExactly("项目一的数据");
    }

    @Test
    @DisplayName("两个项目属于同一租户，按租户轴隔离会失败，按项目轴才对")
    void isolatesProjectsWithinSameTenant() throws Exception {
        String[] session = login("alice@example.com");

        String inOne = switchProject(session[0], session[1], projectOne);
        List<String> visibleInOne = probeAs(inOne);

        // 切换会轮换刷新令牌，所以要重新登录拿一个可用的 Cookie
        String[] second = login("alice@example.com");
        String inTwo = switchProject(second[0], second[1], projectTwo);
        List<String> visibleInTwo = probeAs(inTwo);

        assertThat(visibleInOne).containsExactly("项目一的数据");
        assertThat(visibleInTwo)
                .as("同一个租户下的两个项目必须互相看不到 —— 这是租户轴做不到的")
                .containsExactly("项目二的数据");
    }

    /**
     * 还没选项目时，受项目 RLS 保护的数据一行也读不到。
     *
     * <p>这是 fail-closed 的直接体现，也是「登录后先去选项目」这个流程的技术依据。
     */
    @Test
    @DisplayName("未选择项目时一行也读不到（fail-closed）")
    void readsNothingBeforeSelectingProject() throws Exception {
        String[] session = login("alice@example.com");

        assertThat(probeAs(session[0]))
                .as("登录令牌里没有 pid，受保护的数据必须一行都看不到")
                .isEmpty();
    }

    /**
     * 项目选择必须<b>跨刷新存活</b>。
     *
     * <p>不存的话，访问令牌一过期，当前项目就在某个不确定的时刻丢失 ——
     * 取决于何时触发刷新，排查起来非常费劲。
     */
    @Test
    @DisplayName("刷新访问令牌后仍留在原项目")
    void projectSelectionSurvivesRefresh() throws Exception {
        String[] session = login("alice@example.com");
        switchProject(session[0], session[1], projectOne);

        // 切换轮换了刷新令牌，重新登录并切一次，拿到切换后的新 Cookie
        String[] second = login("alice@example.com");
        MvcResult switched = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + second[0])
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", second[1]))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s"}""".formatted(projectOne)))
                .andReturn();
        String cookieAfterSwitch = switched.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("tc_refresh="))
                .map(h -> h.substring("tc_refresh=".length(), h.indexOf(';')))
                .findFirst().orElseThrow();

        // 现在刷新，新的访问令牌应当仍带着 projectOne
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", cookieAfterSwitch)))
                .andReturn();
        String refreshedAccess = JSON.readTree(refreshed.getResponse().getContentAsString())
                .get("accessToken").asString();

        assertThat(probeAs(refreshedAccess))
                .as("刷新后当前项目丢了 —— 项目选择必须记在刷新令牌上，"
                        + "否则它会在一个不确定的时刻消失")
                .containsExactly("项目一的数据");
    }

    /**
     * 非成员切换必须按「项目不存在」处理，而不是「无权限」——
     * 后者等于告诉调用方「这个项目存在，只是你进不去」，可以用来枚举平台上的项目。
     */
    @Test
    @DisplayName("切换到非成员项目返回 404，不泄露项目是否存在")
    void rejectsSwitchingToForeignProject() throws Exception {
        String tokenOfB = register("bob@example.com");
        String[] sessionOfB = login("bob@example.com");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", sessionOfB[1]))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOfB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s"}""".formatted(projectOne)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(10004);
    }

    @Test
    @DisplayName("/me 返回当前项目，供前端刷新页面后恢复")
    void currentUserExposesCurrentProject() throws Exception {
        String[] session = login("alice@example.com");
        String token = switchProject(session[0], session[1], projectOne);

        MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        JsonNode body = JSON.readTree(me.getResponse().getContentAsString());
        assertThat(body.get("currentProjectId").asString()).isEqualTo(projectOne.toString());
    }

    /**
     * ADR0073：删除前零代访问/刷新凭据在恢复后不得复活；无项目会话可显式选择恢复项目取得新代次。
     * 该流程同时核对HTTP拒绝与刷新表无副作用，避免只看到401却遗漏轮换或整族撤销。
     */
    @Test
    @DisplayName("项目恢复后永久拒绝删除前凭据并允许无项目会话取得新代次")
    void oldProjectCredentialsStayRevokedAfterRestore() throws Exception {
        try {
            String[] projectSessionBase = login("alice@example.com");
            MvcResult switched = mockMvc.perform(post("/api/v1/auth/switch-project")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + projectSessionBase[0])
                            .cookie(new jakarta.servlet.http.Cookie("tc_refresh", projectSessionBase[1]))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"projectId":"%s"}""".formatted(projectOne)))
                    .andReturn();
            assertThat(switched.getResponse().getStatus()).isEqualTo(200);
            String oldProjectAccess = JSON.readTree(switched.getResponse().getContentAsString())
                    .get("accessToken").asString();
            String oldProjectRefresh = refreshCookie(switched);
            assertThat(((Number) jwtDecoder.decode(oldProjectAccess)
                    .getClaim("pgv")).longValue()).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT project_generation FROM sys_refresh_token
                     WHERE account_id=? AND project_id=? AND replaced_by IS NULL
                    """, Long.class, accountId, projectOne)).isZero();

            // 另一条未选择项目的会话不携带pid/pgv，恢复后它是取得新代次的显式入口。
            String[] noProjectSession = login("alice@example.com");
            MvcResult deleted = mockMvc.perform(delete("/api/v1/projects/{projectId}", projectOne)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldProjectAccess))
                    .andReturn();
            assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
            restoreProjectThroughPublicApi(noProjectSession[0], projectOne);

            long rowCountBefore = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sys_refresh_token WHERE account_id=?", Long.class, accountId);
            UUID oldFamily = jdbcTemplate.queryForObject("""
                    SELECT family_id FROM sys_refresh_token
                     WHERE account_id=? AND project_id=? AND replaced_by IS NULL
                    """, UUID.class, accountId, projectOne);
            long familyMutationsBefore = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM sys_refresh_token
                     WHERE family_id=? AND (replaced_by IS NOT NULL OR revoked_at IS NOT NULL)
                    """, Long.class, oldFamily);

            MvcResult rejectedAccess = mockMvc.perform(get("/test-only/project-probe")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldProjectAccess))
                    .andReturn();
            assertInvalidToken(rejectedAccess);

            MvcResult rejectedRefresh = mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(new jakarta.servlet.http.Cookie("tc_refresh", oldProjectRefresh)))
                    .andReturn();
            assertInvalidToken(rejectedRefresh);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sys_refresh_token WHERE account_id=?", Long.class, accountId))
                    .isEqualTo(rowCountBefore);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM sys_refresh_token
                     WHERE family_id=? AND (replaced_by IS NOT NULL OR revoked_at IS NOT NULL)
                    """, Long.class, oldFamily)).isEqualTo(familyMutationsBefore);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM sys_refresh_token
                     WHERE account_id=? AND project_id=?
                       AND replaced_by IS NULL AND revoked_at IS NULL
                    """, Long.class, accountId, projectOne)).isEqualTo(1L);

            MvcResult selectedAfterRestore = mockMvc.perform(post("/api/v1/auth/switch-project")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + noProjectSession[0])
                            .cookie(new jakarta.servlet.http.Cookie("tc_refresh", noProjectSession[1]))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"projectId":"%s"}""".formatted(projectOne)))
                    .andReturn();
            assertThat(selectedAfterRestore.getResponse().getStatus()).isEqualTo(200);
            String currentAccess = JSON.readTree(selectedAfterRestore.getResponse().getContentAsString())
                    .get("accessToken").asString();
            assertThat(((Number) jwtDecoder.decode(currentAccess).getClaim("pgv")).longValue()).isEqualTo(1L);
            assertThat(probeAs(currentAccess)).containsExactly("项目一的数据");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM sys_refresh_token
                     WHERE account_id=? AND project_id=? AND project_generation=1
                       AND replaced_by IS NULL AND revoked_at IS NULL
                    """, Long.class, accountId, projectOne)).isEqualTo(1L);
        } finally {
            cleanupGenerationFixture();
        }
    }

    /** 从真实Set-Cookie响应提取刷新令牌明文。 */
    private static String refreshCookie(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith("tc_refresh="))
                .map(header -> header.substring("tc_refresh=".length(), header.indexOf(';')))
                .findFirst().orElseThrow();
    }

    /** 统一断言安全过滤器与刷新接口沿用同一20020/401合同。 */
    private static void assertInvalidToken(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(20020);
    }

    /** 走5f2公开恢复事务，证明旧凭据拒绝不是测试直接改状态制造的假边界。 */
    private void restoreProjectThroughPublicApi(String projectlessAccessToken, UUID projectId) throws Exception {
        MvcResult restored = mockMvc.perform(post("/api/v1/projects/{projectId}/restore", projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + projectlessAccessToken))
                .andReturn();
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(restored.getResponse().getContentAsString()).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lifecycle_generation FROM sys_project WHERE id=?", Long.class, projectId))
                .isEqualTo(1L);
    }

    /** 精确清理本测试账号、项目、探针与会话，不触碰共享容器中的其他夹具。 */
    private void cleanupGenerationFixture() throws Exception {
        if (accountId == null || tenantId == null || projectOne == null || projectTwo == null) return;
        try (Connection owner = fixtureOwnerConnection()) {
            for (String sql : List.of(
                    "DELETE FROM project_http_probe WHERE project_id IN (?,?)",
                    "DELETE FROM sys_refresh_token WHERE account_id=?",
                    "DELETE FROM sys_project_member WHERE project_id IN (?,?)",
                    "DELETE FROM sys_project WHERE id IN (?,?)",
                    "DELETE FROM sys_tenant_member WHERE account_id=?",
                    "DELETE FROM sys_account WHERE id=?",
                    "DELETE FROM sys_tenant WHERE id=?")) {
                try (PreparedStatement statement = owner.prepareStatement(sql)) {
                    if (sql.contains("IN (?,?)")) {
                        statement.setObject(1, projectOne);
                        statement.setObject(2, projectTwo);
                    } else if (sql.endsWith("tenant WHERE id=?")) {
                        statement.setObject(1, tenantId);
                    } else {
                        statement.setObject(1, accountId);
                    }
                    statement.executeUpdate();
                }
            }
        }
    }

}
