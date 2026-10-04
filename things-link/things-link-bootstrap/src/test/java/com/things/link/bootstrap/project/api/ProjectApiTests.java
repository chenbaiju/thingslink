package com.things.link.bootstrap.project.api;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 项目接口的端到端测试（S1 切片 5c 第 1 步）。
 *
 * <h2>为什么放在 bootstrap 而不是 project 模块</h2>
 * 测试要走真实登录才能拿到令牌，而登录在 iam。project 的测试<b>不能依赖 iam</b> ——
 * iam 已经依赖 project，反过来就是循环，Maven 会直接拒绝（测试作用域也不例外）。
 * bootstrap 依赖全部模块，是唯一能同时用到两边的地方。
 *
 * <h2>本类最要紧的一组断言是「看不到别人的项目」</h2>
 * {@code project} 与 {@code project_member} 两张表<b>豁免了 RLS</b>（ADR 0012：
 * 它们正是用来确定「能进哪些项目」的，加策略就是先有鸡还是先有蛋）。
 * 也就是说这里<b>没有数据库层的兜底</b>，隔离完全靠 SQL 里的
 * {@code m.account_id = ?}。漏掉那个条件的后果是把全平台的项目列给所有人，
 * 而且不会有任何症状：接口 200、页面正常渲染。
 */
@AutoConfigureMockMvc
@DisplayName("项目接口（S1 切片 5c）")
class ProjectApiTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 注册按来源 IP 限流（5 分钟 3 次），而 MockMvc 的 remoteAddr 是固定的 ——
     * 本类每个用例都要注册两个账号，不清计数的话第二个用例起就会 429。
     *
     * <p>这已经是内存限流器的「进程级全局状态」第二次影响测试了（第一次是切片 4b
     * 的登录限流）。它不是测试写法问题，是这个实现本身的性质：
     * 换成 Redis 之后，限流状态会随实例外移，这类干扰才会真正消失。
     */
    @Autowired
    private AuthRateLimiter rateLimiter;

    private String tokenOfA;
    private String tokenOfB;
    private UUID accountOfB;

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

        // 走真实注册而不是直接插库：注册是「创建租户 + 账号 + 成员关系」的唯一入口，
        // 手工造数据会绕过它，测出来的东西与真实链路未必一致
        tokenOfA = register("alice@example.com");
        tokenOfB = register("bob@example.com");
        accountOfB = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_account WHERE email = ?", UUID.class, "bob@example.com");
    }

    /**
     * 注册一个账号、标记邮箱已验证，并返回访问令牌。
     *
     * <p>注册返回 204 且**不签发令牌**：邮箱验证完成前不能进入控制台（ADR 0013）。
     * 所以拿令牌必须再走一次登录。
     *
     * <p>这里直接改 {@code email_verified_at}，而不是驱动真实的「收信 → 点链接」流程。
     * 本类验证的是项目接口的语义，邮箱验证链路已有 {@code EmailVerificationApiTests}
     * 专门覆盖；在这里再走一遍要引入异步等待收信，每个用例都要多等两三封信，
     * 换来的只是重复覆盖。
     *
     * <p>每次注册前清一次限流计数：多次注册来自同一个 remoteAddr，会撞上限流。
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

    private MvcResult createProject(String token, String name) throws Exception {
        return createProject(token, name, "sh-1");
    }

    private MvcResult createProject(String token, String name, String region) throws Exception {
        return mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","region":"%s"}""".formatted(name, region)))
                .andReturn();
    }

    /** 创建时显式传 IANA 时区；一次性时间仍由调用方以 RFC3339 UTC 表达。 */
    private MvcResult createProject(String token, String name, String region, String timezone) throws Exception {
        return mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","region":"%s","timezone":"%s"}"""
                                .formatted(name, region, timezone)))
                .andReturn();
    }

    private MvcResult updateProject(String token, UUID projectId, String name) throws Exception {
        return mockMvc.perform(patch("/api/v1/projects/" + projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}""".formatted(name)))
                .andReturn();
    }

    private MvcResult deleteProject(String token, UUID projectId) throws Exception {
        return mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private JsonNode listProjects(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listProjectRegions(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/project-regions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static int codeOf(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    /** G3-LOCAL-11f：只准备读取规模，不认领创建配额或生产吞吐资格。 */
    @Test
    void fullArrayReturnsTwoHundredProjectsWithoutLeakingOtherAccounts() throws Exception {
        UUID seedId = UUID.fromString(JSON.readTree(createProject(tokenOfA, "list-seed").getResponse().getContentAsString()).get("id").asText());
        UUID owner = jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email='alice@example.com'", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key)
                SELECT gen_random_uuid(),tenant_id,'list-volume-'||n,region,timezone,replace(gen_random_uuid()::text,'-','')
                FROM sys_project CROSS JOIN generate_series(1,199) n WHERE id=?
                """, seedId);
        jdbcTemplate.update("""
                INSERT INTO sys_project_member(id,project_id,account_id,role)
                SELECT gen_random_uuid(),id,?,'OWNER' FROM sys_project WHERE name LIKE 'list-volume-%'
                """, owner);
        var visible = mockMvc.perform(get("/api/v1/projects").header(HttpHeaders.AUTHORIZATION,"Bearer "+tokenOfA)).andReturn();
        assertThat(visible.getResponse().getStatus()).isEqualTo(200);
        var values = JSON.readTree(visible.getResponse().getContentAsString());
        assertThat(values.isArray()).isTrue();
        assertThat(values.size()).isEqualTo(200);
        var ids = new java.util.HashSet<String>(); values.forEach(row -> ids.add(row.get("id").asText()));
        assertThat(ids).hasSize(200);
        var invisible = mockMvc.perform(get("/api/v1/projects").header(HttpHeaders.AUTHORIZATION,"Bearer "+tokenOfB)).andReturn();
        assertThat(invisible.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(invisible.getResponse().getContentAsString()).size()).isZero();
    }

    /** 真实HTTP/PG批量身份读取；不通过伪造HTTP响应或截断数组取得PASS。 */
    @Test
    void fullArrayReturnsOneThousandActiveMembersAndRejectsDisabledReader() throws Exception {
        UUID project = UUID.fromString(JSON.readTree(createProject(tokenOfA, "member-volume").getResponse().getContentAsString()).get("id").asText());
        jdbcTemplate.update("""
                INSERT INTO sys_account(id,email,password_hash,display_name)
                SELECT gen_random_uuid(),'list-member-'||n||'@example.com','unused-fixture-hash','List member '||n
                FROM generate_series(1,999) n
                """);
        jdbcTemplate.update("""
                INSERT INTO sys_project_member(id,project_id,account_id,role)
                SELECT gen_random_uuid(),?,id,'VIEWER' FROM sys_account WHERE email LIKE 'list-member-%'
                """,project);
        jdbcTemplate.update("INSERT INTO sys_project_member(id,project_id,account_id,role,status) VALUES (?,?,?,'VIEWER','DISABLED')",
                Uuid7.generate(),project,accountOfB);
        var result = mockMvc.perform(get("/api/v1/projects/"+project+"/members")
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+tokenOfA)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var values = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(values.isArray()).isTrue(); assertThat(values.size()).isEqualTo(1000);
        var ids = new java.util.HashSet<String>(); values.forEach(row -> ids.add(row.get("accountId").asText()));
        assertThat(ids).hasSize(1000).doesNotContain(accountOfB.toString());
        var denied = mockMvc.perform(get("/api/v1/projects/"+project+"/members")
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+tokenOfB)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("创建项目后，创建者是该项目的 OWNER")
    void creatorBecomesOwner() throws Exception {
        MvcResult result = createProject(tokenOfA, "厂区环境监测");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("name").asString()).isEqualTo("厂区环境监测");
        assertThat(body.get("region").asString())
                .as("区域是创建项目时的不可逆选择，不能继续由服务端静默默认")
                .isEqualTo("sh-1");
        assertThat(body.get("timezone").asString())
                .as("省略时区时仍应有确定的中国区 cron 解释，不依赖服务器默认时区")
                .isEqualTo("Asia/Shanghai");
        assertThat(body.get("myRole").asString()).isEqualTo("OWNER");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM sys_project_member", String.class))
                .isEqualTo("OWNER");
    }

    @Test
    @DisplayName("创建项目可选择合法 IANA 时区，未知时区在持久化前拒绝")
    void validatesProjectTimezoneAtCreation() throws Exception {
        MvcResult valid = createProject(tokenOfA, "乌鲁木齐项目", "sh-1", "Asia/Urumqi");
        assertThat(valid.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(valid.getResponse().getContentAsString());
        assertThat(body.get("timezone").asString()).isEqualTo("Asia/Urumqi");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT timezone FROM sys_project WHERE id = ?", String.class,
                UUID.fromString(body.get("id").asString()))).isEqualTo("Asia/Urumqi");

        MvcResult invalid = createProject(tokenOfA, "错误时区项目", "sh-1", "China/Unknown");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(invalid)).isEqualTo(10001);
    }

    @Test
    @DisplayName("区域目录由后端返回，包含地域、可用区和创建开关")
    void listsProjectRegions() throws Exception {
        JsonNode regions = listProjectRegions(tokenOfA);

        assertThat(regions).hasSizeGreaterThanOrEqualTo(12);
        JsonNode sh1 = null;
        JsonNode sh2 = null;
        for (JsonNode region : regions) {
            if ("sh-1".equals(region.get("code").asString())) {
                sh1 = region;
            }
            if ("sh-2".equals(region.get("code").asString())) {
                sh2 = region;
            }
        }

        assertThat(sh1).isNotNull();
        assertThat(sh1.get("name").asString()).isEqualTo("上海1区");
        assertThat(sh1.get("areaCode").asString()).isEqualTo("shanghai");
        assertThat(sh1.get("areaName").asString()).isEqualTo("上海");
        assertThat(sh1.get("projectCreationEnabled").asBoolean()).isTrue();

        assertThat(sh2).isNotNull();
        assertThat(sh2.get("projectCreationEnabled").asBoolean())
                .as("官方页禁用的可用区仍要返回给前端展示，但不能用于创建项目")
                .isFalse();
    }

    /**
     * 只建项目不建成员关系的话，会留下一个<b>谁也进不去的项目</b> ——
     * 没有成员就没人能在列表里看到它，也就没有入口能删。
     * 与注册时的「孤儿租户」是同一类问题，所以同样单独钉一条。
     */
    @Test
    @DisplayName("项目与成员关系同时存在，不留没有成员的孤儿项目")
    void projectAndMembershipAreCreatedTogether() throws Exception {
        createProject(tokenOfA, "厂区环境监测");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_project p
                 WHERE NOT EXISTS (SELECT 1 FROM sys_project_member m WHERE m.project_id = p.id)
                """, Integer.class))
                .as("存在没有任何成员的项目 —— 没人能看到它，也没有入口能删")
                .isZero();
    }

    /**
     * <b>本类最重要的一条。</b>
     *
     * <p>两张表都豁免 RLS，隔离完全靠 SQL 条件。这条断言就是那个条件唯一的守卫。
     */
    @Test
    @DisplayName("只能看到自己参与的项目，看不到别人的")
    void listsOnlyMyProjects() throws Exception {
        createProject(tokenOfA, "甲的项目");
        createProject(tokenOfB, "乙的项目");

        assertThat(listProjects(tokenOfA)).hasSize(1);
        assertThat(listProjects(tokenOfA).get(0).get("name").asString()).isEqualTo("甲的项目");

        assertThat(listProjects(tokenOfB)).hasSize(1);
        assertThat(listProjects(tokenOfB).get(0).get("name").asString())
                .as("这两张表没有 RLS 兜底，漏掉 account_id 条件就会列出全平台的项目")
                .isEqualTo("乙的项目");
    }

    /**
     * 校准后模型的核心形态：被邀请者来自<b>其他租户</b>，项目列表必须跨租户。
     *
     * <p>按租户隔离的话这条会失败 —— 而这正是把隔离轴从租户改到项目的原因
     * （ADR 0012）。
     */
    @Test
    @DisplayName("被邀请加入他人项目后，能在自己的列表里看到它（跨租户）")
    void listsProjectsFromOtherTenants() throws Exception {
        JsonNode created = JSON.readTree(
                createProject(tokenOfA, "甲的项目").getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.get("id").asString());

        // 直接插成员关系模拟邀请（邀请接口是第 4 步的事）。
        // 乙属于自己的租户，项目属于甲的租户 —— 这正是跨租户协作的形态
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'OPERATOR')
                """, Uuid7.generate(), projectId, accountOfB);

        JsonNode listOfB = listProjects(tokenOfB);

        assertThat(listOfB)
                .as("被邀请者与项目分属不同租户；按租户隔离的话这里会看不到，"
                        + "那正是 ADR 0012 换轴要解决的问题")
                .hasSize(1);
        assertThat(listOfB.get(0).get("name").asString()).isEqualTo("甲的项目");
        assertThat(listOfB.get(0).get("myRole").asString())
                .as("角色是 (账号, 项目) 的属性：同一个项目，甲是 OWNER，乙是 OPERATOR")
                .isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("OWNER 可以编辑项目名称，但区域保持创建时的值")
    void ownerCanRenameProjectWithoutChangingRegion() throws Exception {
        JsonNode created = JSON.readTree(
                createProject(tokenOfA, "旧名称", "sh-5").getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.get("id").asString());

        MvcResult result = updateProject(tokenOfA, projectId, "新名称");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("name").asString()).isEqualTo("新名称");
        assertThat(body.get("region").asString())
                .as("编辑项目只能改名称，区域是创建时绑定死的")
                .isEqualTo("sh-5");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT region FROM sys_project WHERE id = ?", String.class, projectId))
                .isEqualTo("sh-5");
    }

    @Test
    @DisplayName("编辑项目名称时请求体带 region/timezone 也不会改变调度基线")
    void updateIgnoresImmutableSettingsEvenIfClientSendsThem() throws Exception {
        JsonNode created = JSON.readTree(
                createProject(tokenOfA, "旧名称", "sh-5").getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.get("id").asString());

        MvcResult result = mockMvc.perform(patch("/api/v1/projects/" + projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新名称","region":"sh-1","timezone":"Asia/Urumqi"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT region FROM sys_project WHERE id = ?", String.class, projectId))
                .as("DTO 没有 region 字段；客户端多传也不能影响区域")
                .isEqualTo("sh-5");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT timezone FROM sys_project WHERE id = ?", String.class, projectId))
                .as("S7-4 不开放时区更新，避免活动 cron 未重算却已展示新时区")
                .isEqualTo("Asia/Shanghai");
    }

    @Test
    @DisplayName("只有 OWNER 可以编辑项目名称")
    void onlyOwnerCanRenameProject() throws Exception {
        JsonNode created = JSON.readTree(
                createProject(tokenOfA, "甲的项目").getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.get("id").asString());
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'ADMIN')
                """, Uuid7.generate(), projectId, accountOfB);

        MvcResult result = updateProject(tokenOfB, projectId, "乙改名");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(50003);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM sys_project WHERE id = ?", String.class, projectId))
                .isEqualTo("甲的项目");
    }

    @Test
    @DisplayName("项目仍有其他成员时不能删除")
    void cannotDeleteProjectWithInvitedMembers() throws Exception {
        JsonNode created = JSON.readTree(
                createProject(tokenOfA, "协作项目").getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.get("id").asString());
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'VIEWER')
                """, Uuid7.generate(), projectId, accountOfB);

        MvcResult result = deleteProject(tokenOfA, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo(50015);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM sys_project WHERE id = ?", Boolean.class, projectId))
                .as("还有被邀请成员时不能把项目删掉")
                .isTrue();
    }

    @Test
    @DisplayName("只有创建项目的 OWNER 可以删除项目")
    void onlyOwnerCanDeleteProject() throws Exception {
        JsonNode created = JSON.readTree(
                createProject(tokenOfA, "协作项目").getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.get("id").asString());
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'ADMIN')
                """, Uuid7.generate(), projectId, accountOfB);

        MvcResult result = deleteProject(tokenOfB, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(50003);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM sys_project WHERE id = ?", Boolean.class, projectId))
                .isTrue();
    }

    @Test
    @DisplayName("项目仅OWNER时可以删除，完整保留原成员且不删除账号")
    void ownerCanDeleteProjectWhenOnlyOwnerRemains() throws Exception {
        JsonNode created = JSON.readTree(
                createProject(tokenOfA, "可删除项目").getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.get("id").asString());
        String ownerBefore = jdbcTemplate.queryForObject(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ?", String.class, projectId);
        assertThat(JSON.readTree(ownerBefore).get("role").asString()).isEqualTo("OWNER");

        MvcResult result = deleteProject(tokenOfA, projectId);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(listProjects(tokenOfA))
                .as("软删除项目后，项目列表必须看不到它")
                .isEmpty();
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ?", String.class, projectId))
                .as("删除保全完整原OWNER行，不能只保留数量或重建一条不同身份记录")
                .containsExactly(ownerBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_account WHERE email = ?",
                Integer.class, "alice@example.com"))
                .as("删除项目不是删除创建者账号")
                .isEqualTo(1);
    }

    /** 5d4：原有效OWNER的两种项目写均按ARCHIVED只读拒绝，项目与成员完整事实不变。 */
    @Test
    @DisplayName("归档项目拒绝改名和删除并返回50017")
    void archivedProjectRejectsRenameAndDeleteWithoutChangingFacts() throws Exception {
        MvcResult created = createProject(tokenOfA, "归档项目");
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        UUID projectId = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString()).get("id").asString());
        assertThat(jdbcTemplate.update("UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", projectId)).isEqualTo(1);
        String projectBefore = jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM sys_project p WHERE id = ?", String.class, projectId);
        List<String> membersBefore = jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ? ORDER BY id", String.class, projectId);

        for (MvcResult denied : List.of(updateProject(tokenOfA, projectId, "不得写入"), deleteProject(tokenOfA, projectId))) {
            assertThat(denied.getResponse().getStatus()).isEqualTo(403);
            assertThat(codeOf(denied)).isEqualTo(50017);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM sys_project p WHERE id = ?", String.class, projectId)).isEqualTo(projectBefore);
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ? ORDER BY id", String.class, projectId))
                .isEqualTo(membersBefore);
        assertThat(listProjects(tokenOfA)).hasSize(1);
    }

    /**
     * ADR0073：删除递增代次后旧选中项目JWT在公共过滤器统一失效，保留OWNER不能恢复旧资格。
     * 删除后的新无项目会话仍可识别账号，并继续证明项目列表隐藏删除事实且OWNER关系完整保留。
     */
    @Test
    @DisplayName("删除保全OWNER后旧控制台令牌失效且无项目新会话仍可用")
    void retainedOwnerAfterRealDeletionDoesNotRestoreOldConsoleTokenAccess() throws Exception {
        MvcResult created = createProject(tokenOfA, "删除后资格");
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        UUID projectId = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString()).get("id").asString());
        String ownerBefore = jdbcTemplate.queryForObject(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ?", String.class, projectId);
        JsonNode unselectedMenus = readAuthenticatedJson(tokenOfA, "/api/v1/system/menus");

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\",\"password\":\"%s\"}".formatted(PASSWORD))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String loginToken = JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString();
        MvcResult selected = switchProject(loginToken, refreshCookie(login), projectId);
        assertThat(selected.getResponse().getStatus()).isEqualTo(200);
        String selectedToken = JSON.readTree(selected.getResponse().getContentAsString()).get("accessToken").asString();
        JsonNode activeUser = readAuthenticatedJson(selectedToken, "/api/v1/auth/me");
        assertThat(activeUser.get("projectRole").asString()).isEqualTo("OWNER");
        assertThat(activeUser.get("permissions")).isNotEmpty();
        assertThat(readAuthenticatedJson(selectedToken, "/api/v1/system/menus")).isNotEqualTo(unselectedMenus);
        assertThat(readAuthenticatedGet(selectedToken, "/api/v1/projects/" + projectId + "/device-types/search")
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(deleteProject(selectedToken, projectId).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ?", String.class, projectId))
                .containsExactly(ownerBefore);
        for (String path : List.of("/api/v1/projects",
                "/api/v1/projects/" + projectId + "/device-types/search")) {
            MvcResult rejected = readAuthenticatedGet(selectedToken, path);
            assertThat(rejected.getResponse().getStatus()).as(path).isEqualTo(401);
            assertThat(codeOf(rejected)).as(path).isEqualTo(20020);
            assertThat(rejected.getHandler()).as(path).isNull();
        }

        MvcResult projectlessLogin = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\",\"password\":\"%s\"}".formatted(PASSWORD))).andReturn();
        assertThat(projectlessLogin.getResponse().getStatus()).isEqualTo(200);
        String projectlessToken = JSON.readTree(projectlessLogin.getResponse().getContentAsString())
                .get("accessToken").asString();
        assertThat(listProjects(projectlessToken)).isEmpty();
        JsonNode projectlessUser = readAuthenticatedJson(projectlessToken, "/api/v1/auth/me");
        assertThat(projectlessUser.get("projectRole").isNull()).isTrue();
        assertThat(projectlessUser.get("permissions")).isEmpty();
        assertThat(projectlessUser.get("currentProjectId").isNull()).isTrue();
        assertThat(readAuthenticatedJson(projectlessToken, "/api/v1/system/menus")).isEqualTo(unselectedMenus);
    }

    @Test
    void userCenterMenuAndProfileRemainAvailableWithoutAProject() throws Exception {
        JsonNode menus = readAuthenticatedJson(tokenOfA, "/api/v1/system/menus");
        JsonNode center = java.util.stream.StreamSupport.stream(menus.spliterator(), false)
                .filter(node -> "UserCenter".equals(node.path("name").asString()))
                .findFirst().orElseThrow();
        assertThat(center.path("path").asString()).isEqualTo("/system/user-center");
        assertThat(center.path("component").asString()).isEqualTo("/system/user-center");
        assertThat(center.path("meta").path("isHide").asBoolean()).isTrue();
        assertThat(center.path("meta").path("isHideTab").asBoolean()).isTrue();
        JsonNode me = readAuthenticatedJson(tokenOfA, "/api/v1/auth/me");
        assertThat(me.path("email").asString()).isEqualTo("alice@example.com");
        assertThat(me.path("accountId").asString()).isNotEqualTo(accountOfB.toString());
        assertThat(me.path("tenantId").asString()).isNotBlank();
        assertThat(me.path("currentProjectId").isNull()).isTrue();
        assertThat(me.path("projectRole").isNull()).isTrue();
        assertThat(mockMvc.perform(get("/api/v1/system/menus")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/auth/me")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }

    /** 复用真实IAM切换合同，刷新Cookie由登录/上一切换响应取得，不自行构造签名令牌。 */
    private MvcResult switchProject(String accessToken, Cookie refresh, UUID projectId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/switch-project")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).cookie(refresh)
                .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
    }

    /** 每次轮换取响应的新Cookie；缺少它应立即失败，不能拿过期Cookie制造伪失权。 */
    private Cookie refreshCookie(MvcResult response) {
        String value = response.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith("tc_refresh="))
                .map(header -> header.substring("tc_refresh=".length(), header.indexOf(';'))).findFirst().orElseThrow();
        return new Cookie("tc_refresh", value);
    }

    /** 读请求保留真实安全链，调用方可精确断言原业务拒绝码。 */
    private MvcResult readAuthenticatedGet(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    /** 成功JSON读取必须先确认200，避免把限流或登录错误解析成业务事实。 */
    private JsonNode readAuthenticatedJson(String token, String path) throws Exception {
        MvcResult result = readAuthenticatedGet(token, path);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /**
     * tenantId 是计费归属，与调用方无关。返回它等于告诉每个协作者
     * 「这个项目属于哪个租户」—— 那是别人的组织信息。
     */
    @Test
    @DisplayName("响应不包含 tenantId")
    void doesNotExposeTenantId() throws Exception {
        assertThat(createProject(tokenOfA, "甲的项目").getResponse().getContentAsString())
                .doesNotContain("tenantId");
    }

    @Test
    @DisplayName("未认证访问返回 401")
    void requiresAuthentication() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/projects"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/project-regions"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"偷偷建一个"}"""))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(patch("/api/v1/projects/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"偷偷改名"}"""))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(delete("/api/v1/projects/" + UUID.randomUUID()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("项目名为空返回 400")
    void validatesName() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  "}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(10001);
    }

    @Test
    @DisplayName("项目区域不在目录或暂停创建时返回 400")
    void validatesRegion() throws Exception {
        MvcResult result = createProject(tokenOfA, "厂区环境监测", "sh-2");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(10001);

        MvcResult unknown = createProject(tokenOfA, "厂区环境监测", "bj-1");

        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(unknown.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(10001);
    }

    @Test
    @DisplayName("新注册账号的项目列表为空")
    void newAccountHasNoProjects() throws Exception {
        assertThat(listProjects(tokenOfA))
                .as("注册只创建租户，不创建项目 —— 用户登录后要自己建或被邀请")
                .isEmpty();
    }

}
