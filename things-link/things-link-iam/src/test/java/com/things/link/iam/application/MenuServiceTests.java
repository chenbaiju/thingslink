package com.things.link.iam.application;

import com.things.link.iam.domain.Permission;
import com.things.link.iam.domain.RolePermissions;
import com.things.link.shared.authz.ProjectRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单过滤逻辑的单元测试。
 *
 * <p>不连数据库也不起上下文：过滤是纯函数，用集成测试跑它只会让反馈变慢，而且失败
 * 时要先排除数据库与安全链路才能定位到过滤本身。
 *
 * <h2>为什么直接调 {@code filter}</h2>
 * S1 切片 5d 之前四个内置角色权限集合完全相同，只通过角色去测的话，
 * <b>把 filter 的方法体整个删掉、直接返回全集，测试依然全绿</b>。现在成员管理
 * 已经有差异化权限，但直接构造权限集合仍然最能约束过滤纯函数本身，失败时不需要先
 * 排除登录、项目切换或数据库链路。
 */
@DisplayName("菜单过滤（S1 切片 3）")
class MenuServiceTests {
    @org.junit.jupiter.api.Test
    void reviewerMenuIsIndependentOfCommercialAndProjectRoles() {
        var menus = new MenuService();
        org.assertj.core.api.Assertions.assertThat(menus.permissionCodesFor(ProjectRole.OWNER, true))
                .doesNotContain("self_hosted:review");
        org.assertj.core.api.Assertions.assertThat(menus.menusFor(null, true).stream().map(MenuItem::name))
                .doesNotContain("SelfHostedReview");
        org.assertj.core.api.Assertions.assertThat(menus.permissionCodesFor(null, false, true))
                .contains("self_hosted:review").doesNotContain("commercial:adjust");
        org.assertj.core.api.Assertions.assertThat(menus.menusFor(null, false, true).stream().map(MenuItem::name))
                .contains("SelfHostedReview").doesNotContain("SelfHostedEnrollment");
    }

    @org.junit.jupiter.api.Test
    void commercialPermissionIsIndependentOfEveryProjectRole() {
        var menus = new MenuService();
        for (var role : com.things.link.shared.authz.ProjectRole.values()) {
            org.assertj.core.api.Assertions.assertThat(menus.permissionCodesFor(role)).doesNotContain("commercial:adjust");
            org.assertj.core.api.Assertions.assertThat(menus.menusFor(role).stream().map(MenuItem::name)).doesNotContain("CommercialOperations");
        }
        org.assertj.core.api.Assertions.assertThat(menus.permissionCodesFor(null, true)).contains("commercial:adjust");
        org.assertj.core.api.Assertions.assertThat(menus.menusFor(null, true).stream().map(MenuItem::name)).contains("CommercialOperations");
    }

    /** 集成管理按当前OWNER/ADMIN授予，不下放给公开Key或操作员。 */
    @ParameterizedTest @EnumSource(ProjectRole.class)
    void integrationManagementRequiresManagerRole(ProjectRole role) {
        assertThat(namesOf(new MenuService().menusFor(role)).contains("ProjectApiKeys")).isEqualTo(role.canManageMembers());
        assertThat(namesOf(new MenuService().menusFor(role)).contains("ProjectWebhooks")).isEqualTo(role.canManageMembers());
        assertThat(RolePermissions.of(role).contains(Permission.INTEGRATION_MANAGE)).isEqualTo(role.canManageMembers());
        assertThat(new MenuService().permissionCodesFor(role).contains("integration:manage")).isEqualTo(role.canManageMembers());
    }

    @Test
    void userCenterIsAvailableWithoutProjectOrPlatformPrivileges() {
        var service = new MenuService();
        var roles = new java.util.ArrayList<ProjectRole>();
        roles.add(null);
        roles.addAll(List.of(ProjectRole.values()));
        for (var role : roles) {
            var item = service.menusFor(role, false, false).stream()
                    .filter(menu -> menu.name().equals("UserCenter")).findFirst().orElseThrow();
            assertThat(item.path()).isEqualTo("/system/user-center");
            assertThat(item.component()).isEqualTo("/system/user-center");
            assertThat(item.meta().title()).isEqualTo("menus.system.userCenter");
            assertThat(item.meta().isHide()).isTrue();
            assertThat(item.meta().isHideTab()).isTrue();
            assertThat(item.requiredPermission()).isNull();
        }
    }

    private final MenuService menuService = new MenuService();

    /** 管理菜单只授管理角色，日志权限不附送源码权限。 */
    @ParameterizedTest @EnumSource(ProjectRole.class)
    void ruleManagementIsSeparatedFromExecutionRead(ProjectRole role) {
        var permissions = RolePermissions.of(role);
        var names = namesOf(MenuService.filter(MenuCatalog.all(), permissions));
        assertThat(names).contains("RuleExecutions");
        if (role == ProjectRole.OWNER || role == ProjectRole.ADMIN) {
            assertThat(permissions).contains(Permission.RULE_MANAGE); assertThat(names).contains("MessageRules", "RuleScenes");
        } else {
            assertThat(permissions).doesNotContain(Permission.RULE_MANAGE); assertThat(names).doesNotContain("MessageRules", "RuleScenes");
        }
    }

    /**
     * 递归收集路由名，便于断言整棵树的内容。
     *
     * @param items 菜单节点
     * @return 全部路由名
     */
    private static List<String> namesOf(List<MenuItem> items) {
        return items.stream()
                .flatMap(item -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(item.name()),
                        item.children() == null
                                ? java.util.stream.Stream.<String>empty()
                                : namesOf(item.children()).stream()))
                .toList();
    }

    @Test
    @DisplayName("拥有全部权限时下发完整菜单树")
    void keepsEverythingWhenAllPermissionsGranted() {
        List<MenuItem> menus = MenuService.filter(
                MenuCatalog.all(), Set.of(Permission.values()));

        assertThat(namesOf(menus))
                .contains("Dashboard", "Overview", "DeviceGroups", "DeviceMessages",
                        "Alarm", "AlarmRules", "AlarmHistory", "AlarmNotificationGroups",
                        "AlarmNotificationTemplates", "Task", "TaskJobs", "PlanCatalog", "SystemStatus",
                        "Exception", "Exception404");
    }

    /**
     * 这条是整个过滤机制的核心断言：没有 {@code dashboard:read} 就看不到概要。
     */
    @Test
    @DisplayName("缺少权限点时，对应菜单及其子节点一并消失")
    void dropsNodesWithoutPermission() {
        List<MenuItem> menus = MenuService.filter(MenuCatalog.all(), Set.of());

        assertThat(namesOf(menus))
                .as("父节点被过滤时子节点不能残留 —— 否则前端会注册一条无父级的路由")
                .doesNotContain("Dashboard", "Overview");
    }

    /**
     * 异常页不设权限。它是「没权限」时的落点，自身再要求权限会形成死循环。
     */
    @Test
    @DisplayName("无权限的用户仍能看到异常页")
    void keepsExceptionPagesForEveryone() {
        List<MenuItem> menus = MenuService.filter(MenuCatalog.all(), Set.of());

        assertThat(namesOf(menus)).contains("Exception", "Exception403", "Exception404");
    }

    @Test
    @DisplayName("子节点被过滤光的目录不再下发")
    void dropsDirectoriesLeftEmpty() {
        MenuItem directory = new MenuItem(
                "OnlyRestrictedChildren", "/restricted", "/index/index",
                new MenuItem.Meta("t", null, null, null, null, null, null, null),
                null,
                List.of(new MenuItem(
                        "RestrictedChild", "child", "/child",
                        new MenuItem.Meta("t", null, null, null, null, null, null, null),
                        Permission.DASHBOARD_READ, null)));

        List<MenuItem> menus = MenuService.filter(List.of(directory), Set.of());

        assertThat(menus)
                .as("空目录点开什么都没有，用户分不清是没权限还是功能坏了")
                .isEmpty();
    }

    @Test
    @DisplayName("按钮权限点按授予情况逐个过滤")
    void filtersButtonLevelAuthPoints() {
        MenuItem page = new MenuItem(
                "Page", "/page", "/page",
                new MenuItem.Meta("t", null, null, null, null, null, null,
                        List.of(new MenuItem.AuthPoint("查看概要", Permission.DASHBOARD_READ))),
                null, null);

        List<MenuItem> granted = MenuService.filter(
                List.of(page), Set.of(Permission.DASHBOARD_READ));
        List<MenuItem> notGranted = MenuService.filter(List.of(page), Set.of());

        assertThat(granted.getFirst().meta().authList()).hasSize(1);
        assertThat(notGranted.getFirst().meta().authList())
                .as("一个都没剩时置 null，序列化后字段整个消失")
                .isNull();
    }

    /**
     * 未登记的角色必须落到空权限（fail-closed）。这条防的是「新增角色忘了在
     * {@code RolePermissions} 里登记」——那时的正确表现是什么都看不到，
     * 而不是什么都能做。
     */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    @DisplayName("每个内置角色都已登记权限，且至少能看概要")
    void everyBuiltInRoleIsRegistered(ProjectRole role) {
        assertThat(RolePermissions.of(role))
                .as("角色 %s 未在 RolePermissions 中登记，其用户将看不到任何菜单", role)
                .isNotEmpty();
        assertThat(menuService.permissionCodesFor(role)).contains("dashboard:read");
        assertThat(menuService.permissionCodesFor(role)).contains("quota:read");
        assertThat(namesOf(menuService.menusFor(role))).contains("Overview", "ProjectSettings");
    }

    /**
     * 还没选项目时没有任何项目角色，因此也没有任何项目级权限 ——
     * 菜单会退到「只剩不需要权限的那些」。这是刻意的 fail-closed。
     */
    @Test
    @DisplayName("未选择项目（角色为 null）时没有任何权限")
    void noPermissionsWithoutProject() {
        assertThat(RolePermissions.of(null)).isEmpty();
        assertThat(menuService.permissionCodesFor(null)).isEmpty();
        assertThat(namesOf(menuService.menusFor(null)))
                .as("未选项目时应当只剩不需要权限的菜单，用户先去选项目")
                .doesNotContain("Overview")
                .contains("Project", "PlanCatalog", "SystemStatus");
    }

    /**
     * S14-6e：套餐与权益目录是**平台全局只读页**，与系统状态同一口径——不设权限点、无需选项目。
     *
     * <p>服务端接口仍要求有效令牌；这条只钉住「菜单语义是平台级而不是项目级」，避免有人把它挪到
     * 某个项目角色下（那会让「未选项目」与「选了一个没有该权限的项目」两种状态无法区分）。
     */
    @Test
    @DisplayName("套餐目录与系统状态一样，不依赖项目角色")
    void planCatalogIsPlatformLevelWithoutProjectRole() {
        assertThat(namesOf(menuService.menusFor(null))).contains("PlanCatalog");
        for (ProjectRole role : ProjectRole.values()) {
            assertThat(namesOf(menuService.menusFor(role)))
                    .as("角色 %s 也应当能看到平台级目录", role)
                    .contains("PlanCatalog");
        }
    }

    /**
     * <b>四个角色第一次真正不同</b>（S1 切片 5d）。
     *
     * <p>在此之前把四个角色的权限集合改成完全一样，所有测试仍然全绿 ——
     * 因为它们本来就一样。这条是第一个会因为分级被抹平而变红的断言。
     */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    @DisplayName("成员管理权限只给 OWNER 与 ADMIN，OPERATOR 与 VIEWER 只能看")
    void memberManagementSeparatesRoles(ProjectRole role) {
        Set<Permission> permissions = RolePermissions.of(role);

        assertThat(permissions)
                .as("看得见「这个项目里有谁」是协作的前提，四个角色都该有")
                .contains(Permission.MEMBER_READ);

        if (role == ProjectRole.OWNER || role == ProjectRole.ADMIN) {
            assertThat(permissions).contains(
                    Permission.MEMBER_INVITE,
                    Permission.MEMBER_UPDATE_ROLE,
                    Permission.MEMBER_REMOVE);
        } else {
            assertThat(permissions)
                    .as("%s 不该能改成员。这里放宽的话，前端会显示按钮 ——"
                            + "服务端仍会拒绝，但用户会以为是系统坏了", role)
                    .doesNotContain(
                            Permission.MEMBER_INVITE,
                            Permission.MEMBER_UPDATE_ROLE,
                            Permission.MEMBER_REMOVE);
        }
    }

    /**
     * 授予表与真正的服务端判定必须一致。
     *
     * <p>{@code ProjectMemberService} 读的是 {@link ProjectRole#canManageMembers()}，
     * 授予表也读它 —— 这条钉住的是「两边确实读同一个方法」。各写一份
     * {@code role == OWNER || role == ADMIN} 的话，改漏一处的后果是
     * <b>按钮藏起来了而接口照样放行</b>，而且没有任何症状：没人会去点一个看不见的按钮。
     */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    @DisplayName("权限点的下发范围与服务端的角色判定一致")
    void grantedPermissionsMatchServerSideRule(ProjectRole role) {
        assertThat(RolePermissions.of(role).contains(Permission.MEMBER_INVITE))
                .as("角色 %s：下发的权限点与 canManageMembers() 不一致", role)
                .isEqualTo(role.canManageMembers());
    }

    /**
     * 成员菜单是第一个<b>按角色下发不同按钮</b>的真实节点。
     * 在它之前，{@code authList} 有机制、有测试，但没有任何真实菜单用到。
     */
    @Test
    @DisplayName("成员页对 OPERATOR 不下发任何操作按钮")
    void memberPageHidesWriteButtonsFromOperator() {
        assertThat(namesOf(menuService.menusFor(ProjectRole.OPERATOR)))
                .as("OPERATOR 有 member:read，页面本身要能进")
                .contains("ProjectMembers");

        assertThat(findMemberPage(ProjectRole.OPERATOR).meta().authList())
                .as("三个按钮一个都不剩时置 null，序列化后字段整个消失")
                .isNull();
        assertThat(findMemberPage(ProjectRole.OWNER).meta().authList())
                .hasSize(3);
    }

    /** 设备组读取向四种项目角色开放，但配置按钮只下发给 OWNER 与 ADMIN。 */
    @Test
    @DisplayName("设备组页对只读角色隐藏配置按钮")
    void deviceGroupPageHidesWriteButtonsFromViewer() {
        MenuItem viewerPage = findPage(ProjectRole.VIEWER, "DeviceGroups");
        MenuItem ownerPage = findPage(ProjectRole.OWNER, "DeviceGroups");

        assertThat(viewerPage.meta().authList()).isNull();
        assertThat(ownerPage.meta().authList()).hasSize(3);
    }

    /**
     * 设备列表页必须向 OWNER/ADMIN/OPERATOR 下发 {@code device:control} 按钮权限，
     * 否则前端命令下发面板会误显示「当前角色没有设备控制权限」。VIEWER 保持纯只读。
     */
    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN", "OPERATOR"})
    @DisplayName("设备列表页向 OWNER/ADMIN/OPERATOR 下发命令按钮权限")
    void deviceListPageGrantsControlToOperationalRoles(ProjectRole role) {
        assertThat(findPage(role, "Devices").meta().authList())
                .as("%s 应能下发命令", role)
                .extracting(MenuItem.AuthPoint::permission)
                .contains(Permission.DEVICE_CONTROL);
    }

    /** 与运行角色对称：VIEWER 只能读，命令按钮不能下发。 */
    @Test
    @DisplayName("设备列表页对 VIEWER 隐藏命令按钮权限")
    void deviceListPageHidesControlFromViewer() {
        assertThat(findPage(ProjectRole.VIEWER, "Devices").meta().authList())
                .as("VIEWER 只获得关联读取入口，不获得控制或规则管理权限")
                .extracting(MenuItem.AuthPoint::permission)
                .containsExactlyInAnyOrder(Permission.ENDUSER_READ, Permission.TASK_READ, Permission.RULE_READ);
    }

    /** 设备详情复用其他域的读取能力，菜单元数据必须随项目角色过滤而非缺省隐藏。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    void deviceDetailsExposeAssociationReadsAndRestrictRuleManagement(ProjectRole role) {
        var permissions = findPage(role, "Devices").meta().authList().stream()
                .map(MenuItem.AuthPoint::permission).toList();
        assertThat(permissions).contains(Permission.ENDUSER_READ, Permission.TASK_READ, Permission.RULE_READ);
        assertThat(permissions.contains(Permission.RULE_MANAGE))
                .as("仅OWNER/ADMIN可以读取关联配置，其他角色只读执行历史")
                .isEqualTo(role == ProjectRole.OWNER || role == ProjectRole.ADMIN);
    }

    /** 拓扑页读取向四种角色开放，绑定/解绑按钮只下发给 OWNER 与 ADMIN（复用 device:update）。 */
    @Test
    @DisplayName("拓扑页对只读角色隐藏绑定按钮")
    void topologyPageHidesWriteButtonsFromViewer() {
        MenuItem viewerPage = findPage(ProjectRole.VIEWER, "DeviceTopology");
        MenuItem ownerPage = findPage(ProjectRole.OWNER, "DeviceTopology");

        assertThat(viewerPage.meta().authList()).isNull();
        assertThat(ownerPage.meta().authList()).hasSize(1);
    }

    /** 消息日志是只读运维信息，所有具备设备读取权限的项目角色都可见。 */
    @Test
    @DisplayName("消息日志页不向任意角色下发写操作")
    void deviceMessagesPageHasNoWriteButtons() {
        MenuItem viewerPage = findPage(ProjectRole.VIEWER, "DeviceMessages");
        MenuItem ownerPage = findPage(ProjectRole.OWNER, "DeviceMessages");

        assertThat(viewerPage.meta().authList()).isNull();
        assertThat(ownerPage.meta().authList()).isNull();
    }

    /** 告警的只读、配置和处置权限必须按角色分离，避免 OPERATOR 获得规则配置能力。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    @DisplayName("告警读取向全角色开放，配置与处置按职责分离")
    void alarmPermissionsSeparateConfigurationAndMaintenance(ProjectRole role) {
        Set<Permission> permissions = RolePermissions.of(role);

        assertThat(permissions).contains(Permission.ALARM_READ);
        assertThat(permissions.contains(Permission.ALARM_MANAGE))
                .isEqualTo(role == ProjectRole.OWNER || role == ProjectRole.ADMIN);
        assertThat(permissions.contains(Permission.ALARM_MAINTAIN))
                .isEqualTo(role != ProjectRole.VIEWER);
    }

    /** 告警菜单的按钮清单必须与后端角色权限一致。 */
    @Test
    @DisplayName("告警规则、通知配置与历史页按角色过滤操作按钮")
    void alarmPagesFilterWriteButtonsByRole() {
        assertThat(findPage(ProjectRole.VIEWER, "AlarmRules").meta().authList()).isNull();
        assertThat(findPage(ProjectRole.OPERATOR, "AlarmRules").meta().authList()).isNull();
        assertThat(findPage(ProjectRole.OWNER, "AlarmRules").meta().authList()).hasSize(3);

        assertThat(findPage(ProjectRole.VIEWER, "AlarmHistory").meta().authList()).isNull();
        assertThat(findPage(ProjectRole.OPERATOR, "AlarmHistory").meta().authList()).hasSize(2);
        assertThat(findPage(ProjectRole.ADMIN, "AlarmHistory").meta().authList()).hasSize(2);

        for (String pageName : List.of("AlarmNotificationGroups", "AlarmNotificationTemplates")) {
            assertThat(findPage(ProjectRole.VIEWER, pageName).meta().authList()).isNull();
            assertThat(findPage(ProjectRole.OPERATOR, pageName).meta().authList()).isNull();
            assertThat(findPage(ProjectRole.OWNER, pageName).meta().authList()).hasSize(3);
            assertThat(findPage(ProjectRole.ADMIN, pageName).meta().authList()).hasSize(3);
        }
    }

    /** 任务配置与手动运行是两种职责，OPERATOR 可运行但不能改计划，VIEWER 保持只读。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    @DisplayName("任务读取向全角色开放，配置与手动运行按职责分离")
    void taskPermissionsSeparateManagementAndRun(ProjectRole role) {
        Set<Permission> permissions = RolePermissions.of(role);

        assertThat(permissions).contains(Permission.TASK_READ);
        assertThat(permissions.contains(Permission.TASK_MANAGE))
                .isEqualTo(role == ProjectRole.OWNER || role == ProjectRole.ADMIN);
        assertThat(permissions.contains(Permission.TASK_RUN))
                .isEqualTo(role != ProjectRole.VIEWER);
    }

    /** 任务页按钮必须随角色过滤，执行日志留在同页 Drawer，不注册多余路由。 */
    @Test
    @DisplayName("任务页按角色过滤配置和立即执行按钮")
    void taskPageFiltersButtonsByRole() {
        assertThat(findPage(ProjectRole.VIEWER, "TaskJobs").meta().authList()).isNull();
        assertThat(findPage(ProjectRole.OPERATOR, "TaskJobs").meta().authList())
                .extracting(MenuItem.AuthPoint::permission)
                .containsExactly(Permission.TASK_RUN);
        assertThat(findPage(ProjectRole.OWNER, "TaskJobs").meta().authList()).hasSize(5);
        assertThat(namesOf(menuService.menusFor(ProjectRole.OWNER)))
                .doesNotContain("TaskExecutions");
    }

    /**
     * 在成员菜单树里找到成员页节点。
     *
     * @param role 角色
     * @return 成员页节点
     */
    private MenuItem findMemberPage(ProjectRole role) {
        return menuService.menusFor(role).stream()
                .filter(item -> "Project".equals(item.name()))
                .flatMap(item -> item.children().stream())
                .filter(child -> "ProjectMembers".equals(child.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("角色 " + role + " 看不到成员页"));
    }

    /**
     * 在完整菜单树中递归查找页面。
     *
     * @param role 当前项目角色
     * @param pageName 路由名
     * @return 匹配页面
     */
    private MenuItem findPage(ProjectRole role, String pageName) {
        return findPage(menuService.menusFor(role), pageName);
    }

    /** 递归查找菜单叶子，避免测试依赖菜单顺序。 */
    private MenuItem findPage(List<MenuItem> items, String pageName) {
        for (MenuItem item : items) {
            if (pageName.equals(item.name())) {
                return item;
            }
            if (item.children() != null) {
                try {
                    return findPage(item.children(), pageName);
                } catch (AssertionError ignored) {
                    // 当前分支未命中时继续遍历兄弟节点，最终仍以明确断言失败。
                }
            }
        }
        throw new AssertionError("菜单中不存在页面 " + pageName);
    }

    /**
     * 授予表必须是不可变的。可变的话，一次 {@code add} 会永久污染全进程的权限配置，
     * 症状是「某个用户莫名多了权限」，几乎无法追查。
     */
    @Test
    @DisplayName("角色权限集合不可被调用方修改")
    void grantsAreImmutable() {
        Set<Permission> permissions = RolePermissions.of(ProjectRole.VIEWER);

        assertThat(permissions).isUnmodifiable();
    }

    /** 应用目录与草稿是项目协作只读事实，四角色均可读；未选择项目仍必须失败关闭。 */
    @ParameterizedTest(name = "{0}获得应用读取权限")
    @EnumSource(ProjectRole.class)
    @DisplayName("四种项目角色均获得应用读取权限")
    void applicationReadPermissionMatchesServerReadBoundary(ProjectRole role) {
        assertThat(RolePermissions.of(role)).contains(Permission.APPLICATION_READ);
        assertThat(menuService.permissionCodesFor(role)).contains("application:read");
        assertThat(Permission.byCode("application:read")).contains(Permission.APPLICATION_READ);
    }

    /** 无项目身份没有任何项目级权限，新增应用能力不能扩大该安全默认值。 */
    @Test
    @DisplayName("未选择项目时不下发应用读取权限")
    void projectlessIdentityHasNoApplicationReadPermission() {
        assertThat(RolePermissions.of(null)).doesNotContain(Permission.APPLICATION_READ);
        assertThat(menuService.permissionCodesFor(null)).doesNotContain("application:read");
    }

    /** 应用写入口只允许配置管理角色，权限下发必须与服务端OWNER/ADMIN判定一致。 */
    @ParameterizedTest(name = "{0}的应用管理权限与成员管理能力一致")
    @EnumSource(ProjectRole.class)
    @DisplayName("应用管理权限只授予OWNER和ADMIN")
    void applicationManagePermissionMatchesServerWriteBoundary(ProjectRole role) {
        boolean expected = role == ProjectRole.OWNER || role == ProjectRole.ADMIN;

        assertThat(RolePermissions.of(role).contains(Permission.APPLICATION_MANAGE)).isEqualTo(expected);
        assertThat(menuService.permissionCodesFor(role).contains("application:manage")).isEqualTo(expected);
        assertThat(Permission.byCode("application:manage")).contains(Permission.APPLICATION_MANAGE);
    }

    /** 未选择项目时不能预发应用写权限，否则前端会在没有授权范围时展示编辑入口。 */
    @Test
    @DisplayName("未选择项目时不下发应用管理权限")
    void projectlessIdentityHasNoApplicationManagePermission() {
        assertThat(namesOf(menuService.menusFor(null))).doesNotContain("ApplicationManager");
        assertThat(RolePermissions.of(null)).doesNotContain(Permission.APPLICATION_MANAGE);
        assertThat(menuService.permissionCodesFor(null)).doesNotContain("application:manage");
    }

    /** S12-4m应用目录对项目四角色开放，菜单不得把读取权限扩为写权限。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    void applicationManagerMenuPreservesReadAndWriteBoundary(ProjectRole role) {
        assertThat(namesOf(menuService.menusFor(role))).contains("ApplicationManager");
        assertThat(menuService.permissionCodesFor(role)).contains("application:read");
        assertThat(menuService.permissionCodesFor(role).contains("application:manage"))
                .isEqualTo(role == ProjectRole.OWNER || role == ProjectRole.ADMIN);
    }

    /** 看板定义是项目协作只读事实，四角色均可读；概要权限不得替代该独立资源权限。 */
    @ParameterizedTest(name = "{0}获得看板定义读取权限")
    @EnumSource(ProjectRole.class)
    @DisplayName("四种项目角色均获得看板定义读取权限")
    void dashboardDefinitionReadPermissionMatchesServerReadBoundary(ProjectRole role) {
        assertThat(namesOf(menuService.menusFor(role))).contains("DashboardDesigner");
        assertThat(RolePermissions.of(role)).contains(Permission.DASHBOARD_DEFINITION_READ);
        assertThat(menuService.permissionCodesFor(role)).contains("dashboard_definition:read");
        assertThat(Permission.byCode("dashboard_definition:read"))
                .contains(Permission.DASHBOARD_DEFINITION_READ);
    }

    /** 未选择项目时没有看板定义的授权范围，权限下发必须保持失败关闭。 */
    @Test
    @DisplayName("未选择项目时不下发看板定义读取权限")
    void projectlessIdentityHasNoDashboardDefinitionReadPermission() {
        assertThat(namesOf(menuService.menusFor(null))).doesNotContain("DashboardDesigner");
        assertThat(RolePermissions.of(null)).doesNotContain(Permission.DASHBOARD_DEFINITION_READ);
        assertThat(menuService.permissionCodesFor(null)).doesNotContain("dashboard_definition:read");
    }

    /** 看板定义写权限只属于配置管理角色，不能因四角色都有读取权限而被一并放宽。 */
    @ParameterizedTest(name = "{0}的看板定义管理权限与配置管理边界一致")
    @EnumSource(ProjectRole.class)
    @DisplayName("看板定义管理权限只授予OWNER和ADMIN")
    void dashboardDefinitionManagePermissionSeparatesReadFromWrite(ProjectRole role) {
        boolean expected = role == ProjectRole.OWNER || role == ProjectRole.ADMIN;

        assertThat(namesOf(menuService.menusFor(role))).contains("DashboardDesigner");
        assertThat(RolePermissions.of(role)).contains(Permission.DASHBOARD_DEFINITION_READ);
        assertThat(menuService.permissionCodesFor(role)).contains("dashboard_definition:read");
        assertThat(RolePermissions.of(role).contains(Permission.DASHBOARD_DEFINITION_MANAGE)).isEqualTo(expected);
        assertThat(menuService.permissionCodesFor(role).contains("dashboard_definition:manage")).isEqualTo(expected);
        assertThat(Permission.byCode("dashboard_definition:manage"))
                .contains(Permission.DASHBOARD_DEFINITION_MANAGE);
    }

    /** 未选择项目时不能预发看板定义写权限，否则前端会在没有授权范围时展示编辑入口。 */
    @Test
    @DisplayName("未选择项目时不下发看板定义管理权限")
    void projectlessIdentityHasNoDashboardDefinitionManagePermission() {
        assertThat(RolePermissions.of(null)).doesNotContain(Permission.DASHBOARD_DEFINITION_MANAGE);
        assertThat(menuService.permissionCodesFor(null)).doesNotContain("dashboard_definition:manage");
    }

    /**
     * OTA读取与部署都只授予配置管理角色。
     *
     * <p>原因是服务端边界：已交付的OTA读取端点里只有固件列表/详情、上传会话、发布尝试与
     * 信任域摘要接受任意项目成员，生命周期、发布物、类型基线、设备资格、活动与回退预检
     * 都要求OWNER/ADMIN。把{@code ota:read}发给四种角色会让只读与运行角色看到固件页却在
     * 生命周期面板上拿到403。
     */
    @ParameterizedTest(name = "{0}的OTA读取权限与成员管理能力一致")
    @EnumSource(ProjectRole.class)
    @DisplayName("OTA读取权限只授予OWNER和ADMIN")
    void otaReadPermissionMatchesServerReadBoundary(ProjectRole role) {
        assertThat(RolePermissions.of(role).contains(Permission.OTA_READ))
                .isEqualTo(role.canManageMembers());
        assertThat(menuService.permissionCodesFor(role).contains("ota:read"))
                .isEqualTo(role.canManageMembers());
        assertThat(Permission.byCode("ota:read")).contains(Permission.OTA_READ);
    }

    /** 未选择项目时没有OTA授权范围，权限下发必须保持失败关闭。 */
    @Test
    @DisplayName("未选择项目时不下发OTA读取权限")
    void projectlessIdentityHasNoOtaReadPermission() {
        assertThat(namesOf(menuService.menusFor(null))).doesNotContain("Ota", "OtaFirmwares");
        assertThat(RolePermissions.of(null)).doesNotContain(Permission.OTA_READ);
        assertThat(menuService.permissionCodesFor(null)).doesNotContain("ota:read");
    }

    /**
     * OTA部署权限只属于配置管理角色。
     *
     * <p>授予范围必须与{@code OtaAuthorization.mayDeploy}读的
     * {@link ProjectRole#canManageMembers()}逐角色一致：两边分叉时会出现
     * 「按钮藏起来了但接口照样放行」，没有任何人会去点一个看不见的按钮来发现它。
     */
    @ParameterizedTest(name = "{0}的OTA部署权限与成员管理能力一致")
    @EnumSource(ProjectRole.class)
    @DisplayName("OTA部署权限只授予OWNER和ADMIN")
    void otaDeployPermissionSeparatesReadFromWrite(ProjectRole role) {
        assertThat(RolePermissions.of(role).contains(Permission.OTA_DEPLOY))
                .isEqualTo(role.canManageMembers());
        assertThat(menuService.permissionCodesFor(role).contains("ota:deploy"))
                .isEqualTo(role.canManageMembers());
        assertThat(Permission.byCode("ota:deploy")).contains(Permission.OTA_DEPLOY);
    }

    /** 固件页对只读与运行角色完全不可见，读端点开放不代表控制台提供入口。 */
    @Test
    @DisplayName("固件页只对OWNER和ADMIN下发")
    void otaFirmwarePageIsVisibleToManagersOnly() {
        assertThat(namesOf(menuService.menusFor(ProjectRole.OWNER))).contains("Ota", "OtaFirmwares");
        assertThat(namesOf(menuService.menusFor(ProjectRole.ADMIN))).contains("Ota", "OtaFirmwares");
        assertThat(namesOf(menuService.menusFor(ProjectRole.OPERATOR))).doesNotContain("Ota", "OtaFirmwares");
        assertThat(namesOf(menuService.menusFor(ProjectRole.VIEWER))).doesNotContain("Ota", "OtaFirmwares");
        assertThat(findPage(ProjectRole.OWNER, "OtaFirmwares").meta().authList()).hasSize(4);
        assertThat(findPage(ProjectRole.OWNER, "OtaFirmwares").meta().authList())
                .allSatisfy(point -> assertThat(point.permission()).isEqualTo(Permission.OTA_DEPLOY));
        // S13-4b-2：活动子页与固件页同一授予边界，按钮级权限点也全部指向ota:deploy。
        assertThat(namesOf(menuService.menusFor(ProjectRole.OWNER))).contains("OtaCampaigns");
        assertThat(namesOf(menuService.menusFor(ProjectRole.OPERATOR))).doesNotContain("OtaCampaigns");
        assertThat(findPage(ProjectRole.OWNER, "OtaCampaigns").meta().authList()).hasSize(5);
        assertThat(findPage(ProjectRole.OWNER, "OtaCampaigns").meta().authList())
                .allSatisfy(point -> assertThat(point.permission()).isEqualTo(Permission.OTA_DEPLOY));
        // 作业页与审计页都是只读事实，不承载按钮级权限点，但仍与OTA同一授予边界。
        assertThat(namesOf(menuService.menusFor(ProjectRole.OWNER))).contains("OtaJobs", "OtaAudits");
        assertThat(namesOf(menuService.menusFor(ProjectRole.VIEWER))).doesNotContain("OtaJobs", "OtaAudits");
        assertThat(findPage(ProjectRole.OWNER, "OtaJobs").meta().authList()).isNull();
        assertThat(findPage(ProjectRole.OWNER, "OtaAudits").meta().authList()).isNull();
    }

    @Test
    @DisplayName("权限点标识唯一且符合 resource:action 命名")
    void permissionCodesAreWellFormed() {
        List<String> codes = java.util.Arrays.stream(Permission.values())
                .map(Permission::code)
                .toList();

        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).allMatch(code -> code.matches("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*"),
                "权限点必须是 resource:action（架构文档 7.2）");
        // byCode 是将来自定义角色配置接口解析外部输入的入口，必须与 code() 对得上
        assertThat(Permission.byCode("dashboard:read")).contains(Permission.DASHBOARD_READ);
        assertThat(Permission.byCode("no:such")).isEmpty();
    }

}
