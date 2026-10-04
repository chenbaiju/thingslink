package com.things.link.bootstrap.project.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.testing.AbstractIntegrationTest;
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
 * 项目成员管理的端到端测试（S1 切片 5d）。
 *
 * <h2>这是四个项目角色第一次真正被区分开的地方</h2>
 * 在此之前，把 {@code RolePermissions} 里四个角色的权限集合改成完全一样，
 * 所有测试仍然全绿 —— 因为它们本来就一样。本类的一组 403 断言是第一批
 * <b>会因为角色判定被删掉而变红</b>的测试。
 *
 * <h2>为什么放在 bootstrap</h2>
 * 与 {@code ProjectApiTests} 同理：要走真实注册/登录才能拿令牌，而那在 iam；
 * project 的测试不能依赖 iam（会形成循环依赖）。bootstrap 依赖全部模块。
 *
 * <h2>最重要的一条是「移除成员不等于删除账号」</h2>
 * 需求原话是「删除的用户则失去与该项目的关联，账户并没有删除」。
 * {@link #removedMemberKeepsAccountAndOtherProjects()} 是这句话唯一的守卫：
 * 若哪天有人把移除实现成删账号，只有它会变红。
 */
@AutoConfigureMockMvc
@DisplayName("项目成员管理（S1 切片 5d）")
class ProjectMemberApiTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String MEMBER_EMAIL = "member@example.com";
    private static final String OUTSIDER_EMAIL = "outsider@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 注册按来源 IP 限流，而 MockMvc 的 remoteAddr 是固定的 ——
     * 本类每个用例都要注册三个账号，不清计数就会 429。
     *
     * <p>这是内存限流器「进程级全局状态」第三次影响测试（前两次是登录限流与项目接口）。
     * 不是测试写法问题，是这个实现本身的性质：换成 Redis 后限流状态随实例外移，
     * 这类干扰才会真正消失。
     */
    @Autowired
    private AuthRateLimiter rateLimiter;

    /**
     * 直接注入仓储，只为验证一条<b>走不到的第二道防线</b>
     * （见 {@link #repositoryRefusesToDeleteOwnerEvenWhenCalledDirectly()}）。
     *
     * <p>其余用例一律走 HTTP —— 直接调服务层会绕过安全过滤链与
     * {@code TenantScopeFilter}，而那两处正是隔离真正落地的地方。
     * ArchUnit 的分层规则不约束测试类（它们排除了测试字节码），
     * 但这不是「测试可以随便跨层」的许可：跨层要有明确的理由，这里的理由是
     * 「被测的东西在 HTTP 层不可达」。
     */
    @Autowired
    private com.things.link.project.domain.ProjectRepository projectRepository;

    /** 项目所有者的令牌。 */
    private String ownerToken;

    /** 被邀请者的令牌。每个用例开始时他还<b>不是</b>任何项目的成员。 */
    private String memberToken;

    /** 局外人的令牌：既不是项目成员，也从未被邀请。 */
    private String outsiderToken;

    private UUID memberAccountId;
    private UUID outsiderAccountId;

    /** 由 owner 创建的项目。 */
    private UUID projectId;

    @BeforeEach
    void seed() throws Exception {
        // project_member 引用 account，必须先删
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");

        ownerToken = register(OWNER_EMAIL);
        memberToken = register(MEMBER_EMAIL);
        outsiderToken = register(OUTSIDER_EMAIL);

        jdbcTemplate.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD') WHERE id=(SELECT tenant_id FROM sys_tenant_member WHERE account_id=?)", accountIdOf(OWNER_EMAIL));
        memberAccountId = accountIdOf(MEMBER_EMAIL);
        outsiderAccountId = accountIdOf(OUTSIDER_EMAIL);

        projectId = UUID.fromString(JSON.readTree(
                createProject(ownerToken).getResponse().getContentAsString())
                .get("id").asString());
    }

    /** S14-R4d：成员API给出明确额度恢复建议，拒绝不创建成员。 */
    @Test
    void freeExternalSeatAndMissingProjectionErrorsRemainActionable() throws Exception {
        UUID tenant = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id=?", UUID.class, projectId);
        jdbcTemplate.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", tenant);
        var full = invite(ownerToken, MEMBER_EMAIL, "VIEWER").getResponse();
        assertThat(full.getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(full.getContentAsString()).get("code").asInt()).isEqualTo(50049);
        assertThat(JSON.readTree(full.getContentAsString()).get("message").asString()).contains("扩容后重试");
        jdbcTemplate.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?", tenant);
        var unavailable = invite(ownerToken, MEMBER_EMAIL, "VIEWER").getResponse();
        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(JSON.readTree(unavailable.getContentAsString()).get("code").asInt()).isEqualTo(50048);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_project_member WHERE project_id=?", Long.class, projectId)).isEqualTo(1);
    }

    // ---------- 辅助方法 ----------

    /**
     * 注册一个账号、标记测试邮箱已验证，并返回访问令牌。
     *
     * <p>每次注册前清一次限流计数：三次注册来自同一个 remoteAddr，会撞上限流。
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
        return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private UUID accountIdOf(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
    }

    private MvcResult createProject(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"厂区环境监测","region":"sh-1"}"""))
                .andReturn();
    }

    private MvcResult invite(String token, String email, String role) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"%s"}""".formatted(email, role)))
                .andReturn();
    }

    private MvcResult updateRole(String token, UUID accountId, String role) throws Exception {
        return mockMvc.perform(patch("/api/v1/projects/" + projectId + "/members/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"%s"}""".formatted(role)))
                .andReturn();
    }

    private MvcResult removeMember(String token, UUID accountId) throws Exception {
        return mockMvc.perform(delete("/api/v1/projects/" + projectId + "/members/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult transferOwnership(String token, UUID accountId) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId
                        + "/members/" + accountId + "/transfer-owner")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult leaveProject(String token) throws Exception {
        return mockMvc.perform(delete("/api/v1/projects/" + projectId + "/members/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult listMembers(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/projects/" + projectId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private JsonNode listProjects(String token) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    private static int codeOf(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    // ---------- 查看 ----------

    @Test
    @DisplayName("新建项目只有创建者一个成员，且是 OWNER")
    void newProjectHasOnlyTheOwner() throws Exception {
        MvcResult result = listMembers(ownerToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode members = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("email").asString()).isEqualTo(OWNER_EMAIL);
        assertThat(members.get(0).get("role").asString()).isEqualTo("OWNER");
    }

    /**
     * 成员列表的邮箱与显示名来自 iam 的账号表，经
     * {@code AccountDirectory} 端口取回（project 不能依赖 iam）。
     * 这条同时验证了那个依赖反转确实接通了 —— 端口没接上的话这里会是 null。
     */
    @Test
    @DisplayName("成员列表带回邮箱与显示名（跨模块端口已接通）")
    void memberListCarriesAccountIdentity() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "OPERATOR");

        JsonNode members = JSON.readTree(listMembers(ownerToken).getResponse().getContentAsString());

        assertThat(members).hasSize(2);
        JsonNode invited = members.get(1);
        assertThat(invited.get("email").asString()).isEqualTo(MEMBER_EMAIL);
        assertThat(invited.get("displayName").asString())
                .as("显示名由注册时从邮箱推导，端口没接上的话这里会是 null")
                .isEqualTo("member");
        assertThat(invited.get("accountId").asString()).isEqualTo(memberAccountId.toString());
    }

    @Test
    @DisplayName("项目内任何角色都能看成员列表")
    void everyRoleCanReadMembers() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "VIEWER");

        assertThat(listMembers(memberToken).getResponse().getStatus())
                .as("藏起成员名单只会让人去问管理员，而管理员会截图发群里")
                .isEqualTo(200);
    }

    // ---------- 邀请 ----------

    /**
     * 校准后模型的核心形态：被邀请者<b>来自其他租户</b>（他注册时有了自己的租户），
     * 邀请之后照样能看到这个项目。
     */
    @Test
    @DisplayName("邀请后对方能在自己的项目列表里看到该项目（跨租户）")
    void inviteeSeesTheProject() throws Exception {
        assertThat(listProjects(memberToken)).as("邀请前看不到").isEmpty();

        MvcResult result = invite(ownerToken, MEMBER_EMAIL, "OPERATOR");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        JsonNode projects = listProjects(memberToken);
        assertThat(projects).hasSize(1);
        assertThat(projects.get(0).get("myRole").asString())
                .as("角色是 (账号, 项目) 的属性：同一个项目，甲是 OWNER，乙是 OPERATOR")
                .isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("邮箱大小写不敏感")
    void inviteEmailIsCaseInsensitive() throws Exception {
        assertThat(invite(ownerToken, MEMBER_EMAIL.toUpperCase(), "VIEWER")
                .getResponse().getStatus())
                .as("区分大小写的话，用 Foo@x.com 注册的人永远无法被 foo@x.com 邀请")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("邀请邮箱会去掉前后空格后再查已注册账号")
    void inviteEmailIsTrimmedBeforeLookup() throws Exception {
        MvcResult result = invite(ownerToken, "  " + MEMBER_EMAIL + "  ", "VIEWER");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                          FROM sys_project_member
                         WHERE project_id = ?
                           AND account_id = ?
                           AND role = 'VIEWER'
                        """, Integer.class, projectId, memberAccountId))
                .as("服务端也要兜住 API 直调里的空格输入，不能只依赖前端 v-model.trim")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("邀请未注册的邮箱返回 404 与 50010")
    void rejectsUnregisteredEmail() throws Exception {
        MvcResult result = invite(ownerToken, "nobody@example.com", "VIEWER");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo(50010);
    }

    @Test
    @DisplayName("重复邀请同一个人返回 409 与 50011")
    void rejectsDuplicateMember() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "VIEWER");
        MvcResult again = invite(ownerToken, MEMBER_EMAIL, "ADMIN");

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(again)).isEqualTo(50011);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM sys_project_member WHERE account_id = ?",
                String.class, memberAccountId))
                .as("失败的邀请不能顺手改掉已有成员的角色")
                .isEqualTo("VIEWER");
    }

    /**
     * 每个项目只有一个 OWNER，由创建项目产生。允许通过邀请分配 OWNER 的话，
     * 要么撞上部分唯一索引报 500，要么真的造出第二个所有者。
     */
    @Test
    @DisplayName("不能邀请为 OWNER，返回 400 与 50012")
    void rejectsInvitingAsOwner() throws Exception {
        MvcResult result = invite(ownerToken, MEMBER_EMAIL, "OWNER");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(50012);
    }

    @Test
    @DisplayName("不能邀请自己，返回 400 与 50014")
    void rejectsInvitingSelf() throws Exception {
        MvcResult result = invite(ownerToken, OWNER_EMAIL, "ADMIN");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result))
                .as("撞唯一索引报「已是成员」也算拦住了，但提示会让人困惑")
                .isEqualTo(50014);
    }

    @Test
    @DisplayName("邮箱格式非法返回 400")
    void validatesEmailFormat() throws Exception {
        assertThat(invite(ownerToken, "not-an-email", "VIEWER").getResponse().getStatus())
                .isEqualTo(400);
    }

    // ---------- 角色差异：这一组是本切片的核心 ----------

    @Test
    @DisplayName("ADMIN 可以邀请成员")
    void adminCanInvite() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "ADMIN");

        assertThat(invite(memberToken, OUTSIDER_EMAIL, "VIEWER").getResponse().getStatus())
                .isEqualTo(200);
    }

    /**
     * <b>本类第一条会因为角色判定被删掉而变红的断言。</b>
     *
     * <p>把 {@code ProjectMemberService.requireMemberManager} 里的角色检查去掉，
     * 这条和下面几条会立刻失败；在 5d 之前没有任何测试能做到这一点，
     * 因为四个角色的权限集合完全相同。
     */
    @Test
    @DisplayName("OPERATOR 不能邀请成员，返回 403 与 50002")
    void operatorCannotInvite() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "OPERATOR");

        MvcResult result = invite(memberToken, OUTSIDER_EMAIL, "VIEWER");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(50002);
    }

    @Test
    @DisplayName("VIEWER 不能移除成员，也不能改角色")
    void viewerCannotManageMembers() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "VIEWER");
        invite(ownerToken, OUTSIDER_EMAIL, "OPERATOR");

        assertThat(removeMember(memberToken, outsiderAccountId).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(updateRole(memberToken, outsiderAccountId, "VIEWER")
                .getResponse().getStatus())
                .isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM sys_project_member WHERE account_id = ?",
                String.class, outsiderAccountId))
                .as("被拒绝的请求不能留下任何副作用")
                .isEqualTo("OPERATOR");
    }

    // ---------- 改角色 ----------

    @Test
    @DisplayName("改角色后立即生效")
    void updateRoleTakesEffect() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "VIEWER");

        assertThat(updateRole(ownerToken, memberAccountId, "ADMIN")
                .getResponse().getStatus()).isEqualTo(204);

        assertThat(listProjects(memberToken).get(0).get("myRole").asString())
                .as("角色每次回库查，不放进令牌 —— 否则降级要等令牌过期才生效")
                .isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("不能改 OWNER 的角色，返回 403 与 50013")
    void cannotDemoteOwner() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "ADMIN");
        UUID ownerAccountId = accountIdOf(OWNER_EMAIL);

        MvcResult result = updateRole(memberToken, ownerAccountId, "VIEWER");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(50013);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM sys_project_member WHERE account_id = ?",
                String.class, ownerAccountId))
                .as("一个被入侵的 ADMIN 账号若能降级 OWNER，就能把项目彻底夺走")
                .isEqualTo("OWNER");
    }

    @Test
    @DisplayName("不能把成员改成 OWNER，返回 400 与 50012")
    void cannotPromoteToOwner() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "VIEWER");

        MvcResult result = updateRole(ownerToken, memberAccountId, "OWNER");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(50012);
    }

    // ---------- 转让所有权 ----------

    @Test
    @DisplayName("OWNER 可以把项目所有权转让给项目内其他成员")
    void ownerCanTransferOwnership() throws Exception {
        UUID ownerAccountId = accountIdOf(OWNER_EMAIL);
        invite(ownerToken, MEMBER_EMAIL, "OPERATOR");

        MvcResult result = transferOwnership(ownerToken, memberAccountId);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM sys_project_member WHERE project_id = ? AND account_id = ?",
                String.class, projectId, memberAccountId))
                .as("被转让者成为新的项目所有者")
                .isEqualTo("OWNER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM sys_project_member WHERE project_id = ? AND account_id = ?",
                String.class, projectId, ownerAccountId))
                .as("原 OWNER 继续留在项目中，但降为 ADMIN")
                .isEqualTo("ADMIN");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                          FROM sys_project_member
                         WHERE project_id = ?
                           AND role = 'OWNER'
                        """, Integer.class, projectId))
                .as("数据库事实仍然是一个项目只有一个 OWNER")
                .isEqualTo(1);

        assertThat(listProjects(ownerToken).get(0).get("myRole").asString()).isEqualTo("ADMIN");
        assertThat(listProjects(memberToken).get(0).get("myRole").asString()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("非 OWNER 不能转让项目所有权，返回 403 与 50003")
    void nonOwnerCannotTransferOwnership() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "ADMIN");

        MvcResult result = transferOwnership(memberToken, outsiderAccountId);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(50003);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_project_member WHERE role = 'OWNER'",
                Integer.class))
                .as("失败的转让不能改变现有所有者")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不能把项目所有权转让给自己，返回 400 与 50014")
    void cannotTransferOwnershipToSelf() throws Exception {
        MvcResult result = transferOwnership(ownerToken, accountIdOf(OWNER_EMAIL));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(50014);
    }

    @Test
    @DisplayName("不能把项目所有权转让给非成员，返回 404")
    void cannotTransferOwnershipToNonMember() throws Exception {
        MvcResult result = transferOwnership(ownerToken, outsiderAccountId);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    /**
     * ADMIN 把自己降成 VIEWER 之后，就再没有入口能撤销这个动作了。
     */
    @Test
    @DisplayName("不能改自己的角色，返回 400 与 50014")
    void cannotChangeOwnRole() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "ADMIN");

        MvcResult result = updateRole(memberToken, memberAccountId, "OPERATOR");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(50014);
    }

    @Test
    @DisplayName("对不是成员的账号改角色返回 404")
    void updateRoleOnNonMemberReturns404() throws Exception {
        assertThat(updateRole(ownerToken, outsiderAccountId, "VIEWER")
                .getResponse().getStatus()).isEqualTo(404);
    }

    // ---------- 移除 ----------

    /**
     * <b>本类最重要的一条。</b>
     *
     * <p>需求原话：「删除的用户则失去与该项目的关联，账户并没有删除」。
     * 这条是那句话唯一的守卫 —— 若哪天有人把移除实现成删账号，只有它会变红。
     */
    @Test
    @DisplayName("移除成员只解除关联：账号仍在、仍能登录、其他项目不受影响")
    void removedMemberKeepsAccountAndOtherProjects() throws Exception {
        // 被移除者自己也有一个项目，用来验证「只失去这一个项目的关联」
        UUID ownProjectId = UUID.fromString(JSON.readTree(
                createProject(memberToken).getResponse().getContentAsString())
                .get("id").asString());
        invite(ownerToken, MEMBER_EMAIL, "OPERATOR");
        assertThat(listProjects(memberToken)).hasSize(2);

        assertThat(removeMember(ownerToken, memberAccountId).getResponse().getStatus())
                .isEqualTo(204);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_account WHERE email = ?", Integer.class, MEMBER_EMAIL))
                .as("移出项目不是删账号")
                .isEqualTo(1);

        rateLimiter.clear();
        assertThat(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}"""
                                .formatted(MEMBER_EMAIL, PASSWORD)))
                .andReturn().getResponse().getStatus())
                .as("被移出项目的人照样能登录")
                .isEqualTo(200);

        JsonNode remaining = listProjects(memberToken);
        assertThat(remaining).as("只失去被移出的那个项目").hasSize(1);
        assertThat(remaining.get(0).get("id").asString()).isEqualTo(ownProjectId.toString());
    }

    @Test
    @DisplayName("移除后该成员不再出现在成员列表里")
    void removedMemberDisappearsFromList() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "OPERATOR");
        assertThat(JSON.readTree(listMembers(ownerToken).getResponse().getContentAsString()))
                .hasSize(2);

        removeMember(ownerToken, memberAccountId);

        assertThat(JSON.readTree(listMembers(ownerToken).getResponse().getContentAsString()))
                .hasSize(1);
    }

    @Test
    @DisplayName("被移除的成员立刻失去该项目的访问权")
    void removedMemberLosesAccessImmediately() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "ADMIN");
        removeMember(ownerToken, memberAccountId);

        MvcResult result = listMembers(memberToken);

        assertThat(result.getResponse().getStatus())
                .as("手里的令牌还没过期，但成员关系每次回库查 —— 移出必须立即生效")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("不能移除 OWNER，返回 403 与 50013")
    void cannotRemoveOwner() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "ADMIN");

        MvcResult result = removeMember(memberToken, accountIdOf(OWNER_EMAIL));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(50013);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_project_member WHERE role = 'OWNER'", Integer.class))
                .as("OWNER 是项目最后的管理入口，删掉就再没人能管这个项目了")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不能移除自己，返回 400 与 50014")
    void cannotRemoveSelf() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "ADMIN");

        MvcResult result = removeMember(memberToken, memberAccountId);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(50014);
    }

    /**
     * 仓储层的 {@code role <> 'OWNER'} 是<b>第二道防线</b>：上层的
     * {@code requireModifiableTarget} 已经拦住了 OWNER，所以走 HTTP 的测试永远
     * 碰不到它 —— 把那个条件从 SQL 里删掉，上面所有用例仍然全绿。
     *
     * <p>这正是冗余防线的性质：它不改变任何可观察行为，因而也没有任何端到端测试
     * 会保护它。于是这里<b>绕过服务层直接调仓储</b>，让它有一条属于自己的断言。
     * 否则它会在某次「这个条件看起来多余」的重构里被顺手删掉，
     * 而删掉的那一刻不会有任何症状 —— 直到上层判定哪天被改错。
     */
    @Test
    @DisplayName("仓储层直接调用也删不掉 OWNER（第二道防线）")
    void repositoryRefusesToDeleteOwnerEvenWhenCalledDirectly() throws Exception {
        UUID ownerAccountId = accountIdOf(OWNER_EMAIL);

        assertThat(projectRepository.removeMember(projectId, ownerAccountId))
                .as("上层判定被绕过或写错时，数据库这一步仍然不该把所有者删掉")
                .isZero();
        assertThat(projectRepository.updateMemberRole(projectId, ownerAccountId,
                com.things.link.shared.authz.ProjectRole.VIEWER))
                .as("降级同理 —— 项目失去 OWNER 与被删掉 OWNER 的后果一样")
                .isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM sys_project_member WHERE account_id = ?",
                String.class, ownerAccountId))
                .isEqualTo("OWNER");
    }

    @Test
    @DisplayName("移除不是成员的账号返回 404")
    void removeNonMemberReturns404() throws Exception {
        assertThat(removeMember(ownerToken, outsiderAccountId).getResponse().getStatus())
                .isEqualTo(404);
    }

    // ---------- 主动退出 ----------

    @Test
    @DisplayName("非 OWNER 可以主动退出项目，只删除自己的成员绑定")
    void nonOwnerCanLeaveProject() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "OPERATOR");

        MvcResult result = leaveProject(memberToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_project_member WHERE project_id = ? AND account_id = ?",
                Integer.class, projectId, memberAccountId))
                .as("退出项目只解除自己的项目绑定")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_account WHERE id = ?",
                Integer.class, memberAccountId))
                .as("退出项目不是注销账号")
                .isEqualTo(1);
        assertThat(listProjects(memberToken)).as("退出后项目列表里不再有该项目").isEmpty();
    }

    @Test
    @DisplayName("OWNER 不能主动退出项目，返回 400 与 50016")
    void ownerCannotLeaveProject() throws Exception {
        MvcResult result = leaveProject(ownerToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(50016);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_project_member WHERE project_id = ? AND role = 'OWNER'",
                Integer.class, projectId))
                .as("项目必须始终保留 OWNER")
                .isEqualTo(1);
    }

    // ---------- 审计 ----------

    @Test
    @DisplayName("邀请、改角色、移出成员都会写持久审计")
    void memberChangesAreAudited() throws Exception {
        UUID ownerAccountId = accountIdOf(OWNER_EMAIL);

        assertThat(invite(ownerToken, MEMBER_EMAIL, "VIEWER").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(updateRole(ownerToken, memberAccountId, "OPERATOR").getResponse().getStatus())
                .isEqualTo(204);
        assertThat(removeMember(ownerToken, memberAccountId).getResponse().getStatus())
                .isEqualTo(204);

        assertAudit("project.member.invited", ownerAccountId, memberAccountId, "role", "VIEWER");
        assertAudit("project.member.role_updated", ownerAccountId,
                memberAccountId, "newRole", "OPERATOR");
        assertAudit("project.member.removed", ownerAccountId, memberAccountId, "oldRole", "OPERATOR");
    }

    @Test
    @DisplayName("转让所有权与主动退出都会写持久审计")
    void ownershipTransferAndLeaveAreAudited() throws Exception {
        UUID ownerAccountId = accountIdOf(OWNER_EMAIL);

        invite(ownerToken, MEMBER_EMAIL, "VIEWER");
        invite(ownerToken, OUTSIDER_EMAIL, "OPERATOR");

        assertThat(transferOwnership(ownerToken, memberAccountId).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(leaveProject(outsiderToken).getResponse().getStatus())
                .isEqualTo(204);

        assertAudit("project.member.ownership_transferred", ownerAccountId,
                memberAccountId, "newOwnerNewRole", "OWNER");
        assertAudit("project.member.left", outsiderAccountId,
                outsiderAccountId, "oldRole", "OPERATOR");
    }

    @Test
    @DisplayName("被拒绝的成员管理请求不写审计")
    void rejectedMemberChangesAreNotAudited() throws Exception {
        invite(ownerToken, MEMBER_EMAIL, "OPERATOR");

        MvcResult result = invite(memberToken, OUTSIDER_EMAIL, "VIEWER");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(auditCount("project.member.invited", outsiderAccountId))
                .as("失败请求没有实际成员变更，不能留下误导性的审计记录")
                .isZero();
    }

    /**
     * S12-P0-5d2：真实HTTP须把归档写拒绝映射为50017/403，不能靠无权或无效目标碰巧拒绝。
     * 复用本类真实注册登录夹具；OWNER、已注册邀请对象和有效非OWNER目标在归档前均成立。
     */
    @Test
    @DisplayName("归档项目的邀请、改角色及移除均返回50017且项目成员审计不变")
    void archivedProjectRejectsAllMemberManagementHttpWritesWithoutChanges() throws Exception {
        assertThat(invite(ownerToken, MEMBER_EMAIL, "VIEWER").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.update("UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", projectId))
                .isEqualTo(1);
        String projectBefore = jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM sys_project p WHERE id = ?", String.class, projectId);
        List<String> membersBefore = jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ? ORDER BY id",
                String.class, projectId);
        List<String> auditsBefore = jdbcTemplate.queryForList(
                "SELECT row_to_json(a)::text FROM sys_audit_log a WHERE project_id = ? ORDER BY id",
                String.class, projectId);
        assertThat(membersBefore).hasSize(2);
        assertThat(auditsBefore).isNotEmpty();

        // 三个请求均有合法OWNER授权；被邀请者尚未加入，改角色和移除目标均为现存VIEWER。
        for (MvcResult result : List.of(invite(ownerToken, OUTSIDER_EMAIL, "VIEWER"),
                updateRole(ownerToken, memberAccountId, "OPERATOR"), removeMember(ownerToken, memberAccountId))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(codeOf(result)).isEqualTo(50017);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM sys_project p WHERE id = ?", String.class, projectId))
                .isEqualTo(projectBefore);
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ? ORDER BY id",
                String.class, projectId)).isEqualTo(membersBefore);
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(a)::text FROM sys_audit_log a WHERE project_id = ? ORDER BY id",
                String.class, projectId)).isEqualTo(auditsBefore);
    }

    /**
     * S12-P0-5d3：归档拒绝同时覆盖所有权转让与主动退出，必须返回真实HTTP 50017/403。
     * 复用真实登录OWNER与已加入的VIEWER，不能把无权转让或OWNER退出的既有拒绝当作归档门禁。
     */
    @Test
    @DisplayName("归档项目的转让与主动退出均返回50017且项目成员审计不变")
    void archivedProjectRejectsTransferAndLeaveHttpWritesWithoutChanges() throws Exception {
        assertThat(invite(ownerToken, MEMBER_EMAIL, "VIEWER").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.update("UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", projectId))
                .isEqualTo(1);
        String projectBefore = jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM sys_project p WHERE id = ?", String.class, projectId);
        List<String> membersBefore = jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ? ORDER BY id",
                String.class, projectId);
        List<String> auditsBefore = jdbcTemplate.queryForList(
                "SELECT row_to_json(a)::text FROM sys_audit_log a WHERE project_id = ? ORDER BY id",
                String.class, projectId);
        assertThat(membersBefore).hasSize(2);
        assertThat(auditsBefore).isNotEmpty();

        // 转让由真实OWNER指向有效VIEWER；主动退出由同一有效VIEWER本人发起。
        for (MvcResult result : List.of(transferOwnership(ownerToken, memberAccountId), leaveProject(memberToken))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(codeOf(result)).isEqualTo(50017);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM sys_project p WHERE id = ?", String.class, projectId))
                .isEqualTo(projectBefore);
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id = ? ORDER BY id",
                String.class, projectId)).isEqualTo(membersBefore);
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(a)::text FROM sys_audit_log a WHERE project_id = ? ORDER BY id",
                String.class, projectId)).isEqualTo(auditsBefore);
    }

    // ---------- 越界与未认证 ----------

    /**
     * <b>返回 404 而不是 403。</b>
     *
     * <p>403 等于确认「这个项目存在，只是你进不去」——攻击者拿一份 UUID 逐个试
     * 就能枚举出平台上有哪些项目。与登录不区分「账号不存在」和「口令错误」同理。
     */
    @Test
    @DisplayName("非成员访问项目返回 404 而不是 403，不泄露项目是否存在")
    void nonMemberCannotProbeProjectExistence() throws Exception {
        MvcResult existing = listMembers(outsiderToken);
        assertThat(existing.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(existing)).isEqualTo(50001);

        // 一个纯属虚构的项目 ID：响应必须与上面完全一致，
        // 否则「存在但没权限」和「不存在」就能被区分出来
        MvcResult fabricated = mockMvc.perform(
                        get("/api/v1/projects/" + UUID.randomUUID() + "/members")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                .andReturn();

        assertThat(fabricated.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(fabricated))
                .as("两种情况的响应必须一致，否则 404/403 的区分就成了项目枚举通道")
                .isEqualTo(codeOf(existing));
    }

    @Test
    @DisplayName("非成员不能邀请人进别人的项目")
    void nonMemberCannotInvite() throws Exception {
        MvcResult result = invite(outsiderToken, MEMBER_EMAIL, "ADMIN");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_project_member WHERE project_id = ?",
                Integer.class, projectId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("未认证访问全部返回 401")
    void requiresAuthentication() throws Exception {
        String base = "/api/v1/projects/" + projectId + "/members";

        assertThat(mockMvc.perform(get(base)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(post(base)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"x@example.com","role":"ADMIN"}"""))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(delete(base + "/" + memberAccountId))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post(base + "/" + memberAccountId + "/transfer-owner"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(delete(base + "/me"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * 项目 ID 走路径而不取令牌里的 {@code pid}，因此必须验证：<b>没选项目也能管成员</b>。
     * 若哪天有人改成读令牌，这条会变红 —— 而那个改动会让「管理项目 B 的成员」
     * 必须先切到项目 B，每改一个成员都要整页重载一次。
     */
    @Test
    @DisplayName("未切换到该项目也能管理它的成员（项目 ID 取自路径，不取令牌）")
    void projectIdComesFromPathNotToken() throws Exception {
        // ownerToken 是注册时签发的，那时还没有任何项目，令牌里没有 pid
        assertThat(invite(ownerToken, MEMBER_EMAIL, "OPERATOR").getResponse().getStatus())
                .as("授权判定是 findRole(路径里的 projectId, 当前账号)，与令牌里写的无关")
                .isEqualTo(200);
    }

    /**
     * 统计某个目标账号的某类审计记录。
     *
     * <p>失败请求不应写审计，因此只需要按动作和目标数记录数；
     * 不额外限定 actor，避免测试把“谁试图越权”误当成业务事实。
     *
     * @param action          动作编码
     * @param targetAccountId 目标账号 ID
     * @return 审计记录数量
     */
    private int auditCount(String action, UUID targetAccountId) {
        return jdbcTemplate.queryForObject("""
                        SELECT count(*)
                          FROM sys_audit_log
                         WHERE project_id = ?
                           AND target_type = 'project_member'
                           AND target_id = ?
                           AND action = ?
                        """, Integer.class, projectId, targetAccountId, action);
    }

    /**
     * 断言一次成员变更已写入可查询的审计记录。
     *
     * <p>详情字段用 PostgreSQL 的 jsonb 函数取值，而不是比对 JSON 字符串；
     * 这样测试保护的是结构，不受序列化字段顺序影响。
     *
     * @param action          动作编码
     * @param actorAccountId  行为人账号 ID
     * @param targetAccountId 目标账号 ID
     * @param detailKey       details 中要验证的键
     * @param detailValue     details 中要验证的值
     */
    private void assertAudit(String action, UUID actorAccountId, UUID targetAccountId,
                             String detailKey, String detailValue) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT count(*)
                          FROM sys_audit_log
                         WHERE project_id = ?
                           AND target_type = 'project_member'
                           AND target_id = ?
                           AND action = ?
                           AND actor_account_id = ?
                           AND jsonb_extract_path_text(details, ?) = ?
                        """, Integer.class, projectId, targetAccountId,
                action, actorAccountId, detailKey, detailValue);

        assertThat(count)
                .as("成员变更必须留下可查询的持久审计 action=%s target=%s", action, targetAccountId)
                .isEqualTo(1);
    }

}
