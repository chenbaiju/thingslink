package com.things.link.iam.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 菜单下发接口的端到端测试。
 *
 * <p>与 {@link com.things.link.iam.application.MenuServiceTests} 分工不同：那边测
 * 过滤逻辑本身，这边测<b>真实 HTTP 链路</b> —— 令牌校验、回库解析用户、JSON 字段名。
 * 字段名尤其需要真跑一遍：它们由前端模板定死，写错不会有任何编译或运行时错误，
 * 只表现为「某个开关不起作用」。
 */
@AutoConfigureMockMvc
@DisplayName("菜单下发（S1 切片 3）")
class MenuEndpointTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 限流器是进程级单例，计数会跨测试类累计。不清的话，几个共用测试邮箱的类
     * 合起来就会撞上限额，表现为一堆与限流无关的用例莫名 429 —— 排查时极容易
     * 怀疑到被测逻辑上去。
     */
    @Autowired
    private AuthRateLimiter rateLimiter;

    private UUID accountId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        rateLimiter.clear();

        // 本类自己会建项目，且同一个 Testcontainers 容器在模块内所有测试类之间共享。
        // 删除顺序永远是「先删引用方，再删被引用方」
        jdbcTemplate.update("DELETE FROM sys_project_member");
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");

        UUID tenantId = Uuid7.generate();
        accountId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "测试租户");
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name, email_verified_at)
                VALUES (?, ?, ?, ?, now())
                """, accountId, "owner@example.com", passwordEncoder.encode(PASSWORD), "测试所有者");
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_member (id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, Uuid7.generate(), tenantId, accountId);

        // 造一个项目并把当前账号设为 OWNER。
        // 菜单现在按**项目角色**过滤（ADR 0012），没有项目就没有角色，
        // 也就看不到任何需要权限的菜单
        projectId = Uuid7.generate();
        jdbcTemplate.update(
                "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, ?, ?)",
                projectId, tenantId, "测试项目", "testmenu");
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'OWNER')
                """, Uuid7.generate(), projectId, accountId);
    }

    /**
     * 走真实登录拿令牌，而不是在测试里自己签一个。
     *
     * <p>自签令牌会绕过「登录签发的声明」与「接口读取的声明」是否对得上这一环 ——
     * 而那正是最容易出错的地方（少一个 tid 声明，登录照常成功，菜单接口 500）。
     *
     * @return 访问令牌
     */
    private String login() throws Exception {
        return loginSession()[0];
    }

    /**
     * 登录并同时返回访问令牌与刷新令牌 Cookie。
     *
     * <p>切换项目两样都要：访问令牌用来查成员关系，Cookie 用来找到会话并轮换。
     */
    private String[] loginSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","password":"%s"}""".formatted(PASSWORD)))
                .andReturn();

        String access = JSON.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
        String cookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("tc_refresh="))
                .map(h -> h.substring("tc_refresh=".length(), h.indexOf(';')))
                .findFirst().orElseThrow();
        return new String[]{access, cookie};
    }

    /**
     * 登录并进入测试项目，返回带 pid 的访问令牌。
     *
     * <p>需要权限的菜单只有在选定项目后才会出现 —— 这是 ADR 0012 之后的正常流程。
     */
    private String loginAndEnterProject() throws Exception {
        String[] session = loginSession();

        MvcResult switched = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session[0])
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", session[1]))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s"}""".formatted(projectId)))
                .andReturn();

        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(switched.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    /**
     * 在菜单树里按路由名递归查找节点。
     *
     * @param tree 菜单树或子树
     * @param name 路由名
     * @return 匹配的节点
     */
    private static JsonNode findByName(JsonNode tree, String name) {
        for (JsonNode node : tree) {
            if (name.equals(node.path("name").asString(""))) {
                return node;
            }
            if (node.has("children")) {
                JsonNode found = findByName(node.get("children"), name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private MvcResult fetchMenus(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/system/menus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    /**
     * <b>ADR 0012 之后最重要的行为变化。</b>
     *
     * <p>菜单按项目角色过滤，而登录时不选项目 —— 所以刚登录只能看到不需要权限的
     * 菜单（项目、异常页），概要要等进入项目之后才出现。
     *
     * <p>这是刻意的 fail-closed，与 RLS 同一个思路：没有项目上下文就没有项目内的
     * 任何东西。它也顺带给用户一个明确的引导 —— 先去选项目。
     */
    @Test
    @DisplayName("未选择项目时看不到需要权限的菜单")
    void hidesPermissionedMenusBeforeEnteringProject() throws Exception {
        JsonNode tree = JSON.readTree(fetchMenus(login()).getResponse().getContentAsString());

        assertThat(findByName(tree, "Dashboard"))
                .as("没有项目就没有项目角色，概要需要 dashboard:read，应当被过滤掉")
                .isNull();
        assertThat(findByName(tree, "Project"))
                .as("项目菜单不需要权限 —— 否则用户永远没法进到第一个项目里")
                .isNotNull();
    }

    /**
     * 被移出项目后，菜单必须立即退回未选项目的状态。
     *
     * <p>这条守的是「角色不放进令牌」这个决定：放进去的话，对方手里的令牌在剩余
     * 有效期内仍会按原角色渲染菜单。
     */
    @Test
    @DisplayName("被移出项目后菜单立即退回，不等令牌过期")
    void reflectsMembershipRemovalImmediately() throws Exception {
        String token = loginAndEnterProject();
        assertThat(findByName(
                JSON.readTree(fetchMenus(token).getResponse().getContentAsString()), "Dashboard"))
                .isNotNull();

        jdbcTemplate.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);

        assertThat(findByName(
                JSON.readTree(fetchMenus(token).getResponse().getContentAsString()), "Dashboard"))
                .as("角色每次回库查，移出项目必须立即生效")
                .isNull();
    }

    @Test
    @DisplayName("未认证访问返回 401 与统一错误结构")
    void requiresAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/system/menus")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        // 20020：登录已失效。由 authenticationEntryPoint 返回，
        // 在 GlobalExceptionHandler 作用范围之外，所以值得单独断言一次
        assertThat(body.get("code").asInt()).isEqualTo(20020);
        assertThat(body.get("traceId").asString()).isNotBlank();
    }

    @Test
    @DisplayName("OWNER 拿到概要与异常页，层级与路径符合前端约定")
    void returnsMenuTreeForOwner() throws Exception {
        MvcResult result = fetchMenus(loginAndEnterProject());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode tree = JSON.readTree(result.getResponse().getContentAsString());

        JsonNode dashboard = findByName(tree, "Dashboard");
        assertThat(dashboard.get("name").asString()).isEqualTo("Dashboard");
        assertThat(dashboard.get("path").asString()).isEqualTo("/dashboard");
        assertThat(dashboard.get("meta").get("title").asString())
                .as("下发 i18n key 而非中文文案，否则切语言时菜单不会跟着变")
                .isEqualTo("menus.dashboard.title");

        JsonNode workbench = dashboard.get("children").get(0);
        assertThat(workbench.get("name").asString()).isEqualTo("Workbench");
        assertThat(workbench.get("path").asString()).isEqualTo("workbench");
        assertThat(workbench.get("component").asString()).isEqualTo("/dashboard/workbench");
        assertThat(workbench.get("meta").get("title").asString()).isEqualTo("menus.dashboard.workbench");
        assertThat(workbench.get("meta").get("fixedTab").asBoolean()).isTrue();

        JsonNode overview = findByName(tree, "Overview");
        assertThat(overview.get("path").asString())
                .as("子级路径不带前导斜杠，前端会拼接父路径；带了会拼出 //dashboard/overview")
                .isEqualTo("overview");
        assertThat(overview.get("component").asString()).isEqualTo("/dashboard/overview");
        assertThat(overview.get("meta").get("fixedTab").asBoolean()).isFalse();

        JsonNode groups = findByName(tree, "DeviceGroups");
        assertThat(groups.get("path").asString()).isEqualTo("groups");
        assertThat(groups.get("component").asString()).isEqualTo("/device/groups");
        assertThat(groups.get("meta").get("title").asString()).isEqualTo("menus.device.groups");
        assertThat(groups.get("meta").get("authList").size()).isEqualTo(3);

        JsonNode messages = findByName(tree, "DeviceMessages");
        assertThat(messages.get("path").asString()).isEqualTo("messages");
        assertThat(messages.get("component").asString()).isEqualTo("/device/messages");
        assertThat(messages.get("meta").get("title").asString()).isEqualTo("menus.device.messages");
        assertThat(messages.get("meta").has("authList")).isFalse();

        JsonNode alarmRules = findByName(tree, "AlarmRules");
        assertThat(alarmRules.get("path").asString()).isEqualTo("rules");
        assertThat(alarmRules.get("component").asString()).isEqualTo("/alarm/rules");
        assertThat(alarmRules.get("meta").get("authList").size()).isEqualTo(3);

        JsonNode alarmHistory = findByName(tree, "AlarmHistory");
        assertThat(alarmHistory.get("path").asString()).isEqualTo("history");
        assertThat(alarmHistory.get("component").asString()).isEqualTo("/alarm/history");
        assertThat(alarmHistory.get("meta").get("authList").size()).isEqualTo(2);

        JsonNode notificationGroups = findByName(tree, "AlarmNotificationGroups");
        assertThat(notificationGroups.get("path").asString()).isEqualTo("notification-groups");
        assertThat(notificationGroups.get("component").asString()).isEqualTo("/alarm/notification-groups");
        assertThat(notificationGroups.get("meta").get("title").asString())
                .isEqualTo("menus.alarm.notificationGroups");
        assertThat(notificationGroups.get("meta").get("authList").size()).isEqualTo(3);

        JsonNode notificationTemplates = findByName(tree, "AlarmNotificationTemplates");
        assertThat(notificationTemplates.get("path").asString()).isEqualTo("notification-templates");
        assertThat(notificationTemplates.get("component").asString()).isEqualTo("/alarm/notification-templates");
        assertThat(notificationTemplates.get("meta").get("title").asString())
                .isEqualTo("menus.alarm.notificationTemplates");
        assertThat(notificationTemplates.get("meta").get("authList").size()).isEqualTo(3);
    }

    /** 告警读取、规则配置和实例处置在真实 HTTP 菜单中也必须保持角色分离。 */
    @Test
    @DisplayName("告警菜单按当前项目角色过滤操作按钮")
    void filtersAlarmActionsByCurrentProjectRole() throws Exception {
        String token = loginAndEnterProject();

        jdbcTemplate.update("UPDATE sys_project_member SET role = 'VIEWER' WHERE project_id = ?", projectId);
        JsonNode viewerTree = JSON.readTree(fetchMenus(token).getResponse().getContentAsString());
        assertThat(findByName(viewerTree, "AlarmRules").get("meta").has("authList")).isFalse();
        assertThat(findByName(viewerTree, "AlarmHistory").get("meta").has("authList")).isFalse();
        assertThat(findByName(viewerTree, "AlarmNotificationGroups").get("meta").has("authList")).isFalse();
        assertThat(findByName(viewerTree, "AlarmNotificationTemplates").get("meta").has("authList")).isFalse();

        jdbcTemplate.update("UPDATE sys_project_member SET role = 'OPERATOR' WHERE project_id = ?", projectId);
        JsonNode operatorTree = JSON.readTree(fetchMenus(token).getResponse().getContentAsString());
        assertThat(findByName(operatorTree, "AlarmRules").get("meta").has("authList")).isFalse();
        assertThat(findByName(operatorTree, "AlarmHistory").get("meta").get("authList").size()).isEqualTo(2);
        assertThat(findByName(operatorTree, "AlarmNotificationGroups").get("meta").has("authList")).isFalse();
        assertThat(findByName(operatorTree, "AlarmNotificationTemplates").get("meta").has("authList")).isFalse();
    }

    /**
     * {@code isHideTab} / {@code isFullPage} 的 JSON 名必须原样保留。Jackson 对
     * {@code isXxx} 形式的布尔属性有去掉前缀的历史习惯，一旦被写成 {@code hideTab}，
     * 前端读不到，异常页就会照常生成标签页 —— 不报错，只是行为不对。
     */
    @Test
    @DisplayName("isXxx 形式的布尔字段名未被 Jackson 改写")
    void preservesIsPrefixedFieldNames() throws Exception {
        JsonNode tree = JSON.readTree(fetchMenus(loginAndEnterProject()).getResponse().getContentAsString());

        // 按名字找而不是按下标：菜单顺序会随功能增加而变，
        // 按下标写的断言会在「加了一个不相关的菜单」时莫名其妙地失败
        JsonNode notFound = findByName(tree, "Exception404").get("meta");
        assertThat(notFound.has("isHideTab")).isTrue();
        assertThat(notFound.has("isFullPage")).isTrue();
        assertThat(notFound.get("isHideTab").asBoolean()).isTrue();
    }

    /**
     * 未设置的字段必须整个消失，而不是下发 {@code null}。前端 {@code meta} 会被原样
     * 塞进 Vue Router，显式的 null 与「未设置」在 JavaScript 里行为并不总是一致。
     */
    @Test
    @DisplayName("未设置的元信息字段不出现在响应里")
    void omitsUnsetFields() throws Exception {
        JsonNode tree = JSON.readTree(fetchMenus(loginAndEnterProject()).getResponse().getContentAsString());

        JsonNode dashboardMeta = findByName(tree, "Dashboard").get("meta");
        assertThat(dashboardMeta.has("isHide")).isFalse();
        assertThat(dashboardMeta.has("authList")).isFalse();
        assertThat(findByName(tree, "Overview").has("children"))
                .as("叶子节点不带空的 children 数组")
                .isFalse();
    }

    /**
     * 树已在服务端过滤完毕，「这个菜单需要什么权限」是内部信息。下发出去等于给
     * 任何登录用户一份完整的功能地图，包括他无权使用的部分。
     */
    @Test
    @DisplayName("不下发 requiredPermission")
    void doesNotLeakRequiredPermission() throws Exception {
        assertThat(fetchMenus(loginAndEnterProject()).getResponse().getContentAsString())
                .doesNotContain("requiredPermission", "dashboard:read");
    }

    @Test
    @DisplayName("/me 一并下发权限点集合")
    void currentUserCarriesPermissions() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndEnterProject()))
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("projectRole").asString()).isEqualTo("OWNER");
        assertThat(body.has("role"))
                .as("role 旧字段容易被误读成 sys_tenant_member.role，对外只保留 projectRole")
                .isFalse();
        assertThat(body.get("permissions").isArray()).isTrue();
        assertThat(body.get("permissions").toString())
                .as("权限集合按标识排序，新资源加入后不应让测试依赖某个权限恰好排第一")
                .contains("dashboard:read", "dashboard_definition:read", "dashboard_definition:manage",
                        "application:read", "application:manage",
                        "alarm:read", "alarm:manage", "alarm:maintain");
    }

    /** 真实/me响应必须按当前项目角色分离看板定义读写权限，不能只在内存授予表中成立。 */
    @Test
    @DisplayName("/me按项目角色分离看板定义读写权限")
    void currentUserSeparatesDashboardDefinitionReadAndManagePermissions() throws Exception {
        String token = loginAndEnterProject();

        for (String role : new String[]{"OWNER", "ADMIN", "OPERATOR", "VIEWER"}) {
            jdbcTemplate.update("UPDATE sys_project_member SET role = ? WHERE project_id = ?", role, projectId);
            MvcResult result = mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            Set<String> permissions = permissionCodes(JSON.readTree(result.getResponse().getContentAsString()));
            assertThat(permissions).contains("dashboard_definition:read");
            assertThat(permissions.contains("dashboard_definition:manage"))
                    .as("角色%s的看板定义管理权限", role)
                    .isEqualTo("OWNER".equals(role) || "ADMIN".equals(role));
        }
    }

    /** 未选择项目的真实/me响应不得下发任何看板定义权限。 */
    @Test
    @DisplayName("/me未选择项目时不下发看板定义权限")
    void currentUserWithoutProjectHasNoDashboardDefinitionPermissions() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(permissionCodes(JSON.readTree(result.getResponse().getContentAsString())))
                .doesNotContain("dashboard_definition:read", "dashboard_definition:manage");
    }

    /** 提取/me权限数组为精确集合，避免字符串包含关系掩盖read与manage混用。 */
    private static Set<String> permissionCodes(JsonNode response) {
        Set<String> permissions = new LinkedHashSet<>();
        response.path("permissions").forEach(permission -> permissions.add(permission.asString()));
        return permissions;
    }

    /**
     * 用户被移出租户后，未过期的令牌不得再换到菜单。令牌无法撤销，回库复核是唯一
     * 补救点。
     *
     * <p>这条同时是「菜单按令牌里的 role 声明来算」这种更省事实现的反例：那样实现
     * 的话本用例会返回 200，因为根本不会查库。目前它是唯一能证明<b>每次请求都回库
     * 复核</b>的断言 —— 四个内置角色权限相同，用「降级后菜单变窄」是证明不了的。
     */
    @Test
    @DisplayName("被移出租户后旧令牌拿不到菜单")
    void rejectsTokenAfterMembershipRemoved() throws Exception {
        String token = login();

        jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE account_id = ?", accountId);

        assertThat(fetchMenus(token).getResponse().getStatus()).isEqualTo(401);
    }

}
