package com.things.link.iam.application;

import com.things.link.iam.domain.Permission;

import java.util.List;

/**
 * 平台菜单全集。
 *
 * <p>这里是<b>未经过滤</b>的完整菜单树，过滤由 {@link MenuService} 按当前用户的权限
 * 集合完成。两者分开是为了让「有哪些菜单」与「谁能看到哪些」各自可读、可测。
 *
 * <h2>为什么菜单树在代码里而不是数据库表里</h2>
 * 菜单节点携带 {@code component}（前端源码路径）与 {@code name}（前端路由名），
 * 它们是<b>前端代码的坐标</b>。放进数据库意味着前端改一个目录名就得配一次数据迁移，
 * 而漏改的症状是运行时白屏 —— 不是编译错误，不是接口报错，是用户点进去什么都没有。
 * 放在代码里，两边至少能在同一次提交里改完，并被同一次端到端验证覆盖。
 *
 * <p>真正需要入库的是「租户自定义了哪些菜单」这类运营数据，本项目目前没有这个需求。
 *
 * <h2>与前端兜底路由的关系</h2>
 * 前端 {@code src/router/modules/} 下还留着一份等价的静态路由，供
 * {@code VITE_ACCESS_MODE=frontend} 时使用。这是刻意保留的冗余：后端不可用时前端
 * 仍可独立开发调试。
 *
 * <p>G3-LOCAL-5通过MenuCatalogContractTests校验生产目录快照，Console的
 * menuCatalogContract测试以实际MenuProcessor比对全部节点及身份集合；更新快照必须双端通过。
 */
final class MenuCatalog {

    private MenuCatalog() {
    }

    /**
     * 构造完整菜单树。
     *
     * <p>每次调用新建对象而非共享静态常量：树是不可变记录，共享本无风险，但过滤
     * 结果会被逐节点重建，共享带来的收益接近零，而「全局可变状态」的风险是真实的。
     *
     * @return 未过滤的菜单树
     */
    static List<MenuItem> all() {
        return List.of(dashboard(), device(), alarm(), task(), ota(), rule(), project(), planCatalog(), commercialOperations(), selfHostedEnrollment(), selfHostedReview(),
                systemStatus(), exception(), userCenter());
    }

    /**
     * OTA菜单。固件与升级活动是本项目的协作事实，四种项目角色都能读取；
     * 草稿创建、对象上传、提交发布与退役撤销只下发给 OWNER 与 ADMIN。
     *
     * <p>菜单过滤只负责呈现：{@code things-link-ota} 的控制器读同一个
     * {@link com.things.link.shared.authz.ProjectRole#canManageMembers()} 判定，
     * 隐藏按钮不构成授权，无signer时的fail-closed也不因本节点存在而被解除。</p>
     *
     * <p>S13-4a只落固件页；活动/批次与设备作业页面在各自子片落地时追加子节点，
     * 不预造指向未交付路由的菜单项。</p>
     *
     * @return OTA一级菜单
     */
    private static MenuItem ota() {
        return new MenuItem(
                "Ota", "/ota", "/index/index",
                new MenuItem.Meta("menus.ota.title", "ri:upload-cloud-2-line", null, null,
                        null, null, null, null),
                Permission.OTA_READ,
                List.of(new MenuItem(
                        "OtaFirmwares", "firmwares", "/ota/firmwares",
                        new MenuItem.Meta("menus.ota.firmwares", "ri:archive-2-line", false, null,
                                null, null, null,
                                List.of(new MenuItem.AuthPoint("创建固件草稿", Permission.OTA_DEPLOY),
                                        new MenuItem.AuthPoint("上传固件对象", Permission.OTA_DEPLOY),
                                        new MenuItem.AuthPoint("提交固件发布", Permission.OTA_DEPLOY),
                                        new MenuItem.AuthPoint("退役或撤销固件", Permission.OTA_DEPLOY))),
                        null, null),
                new MenuItem(
                        "OtaCampaigns", "campaigns", "/ota/campaigns",
                        new MenuItem.Meta("menus.ota.campaigns", "ri:rocket-2-line", false, null,
                                null, null, null,
                                List.of(new MenuItem.AuthPoint("创建活动", Permission.OTA_DEPLOY),
                                        new MenuItem.AuthPoint("排程与启动", Permission.OTA_DEPLOY),
                                        new MenuItem.AuthPoint("暂停或恢复活动", Permission.OTA_DEPLOY),
                                        new MenuItem.AuthPoint("取消活动", Permission.OTA_DEPLOY),
                                        new MenuItem.AuthPoint("放行下一批", Permission.OTA_DEPLOY))),
                        null, null),
                new MenuItem(
                        "OtaJobs", "jobs", "/ota/jobs",
                        new MenuItem.Meta("menus.ota.jobs", "ri:device-line", false, null,
                                null, null, null, null),
                        null, null),
                new MenuItem(
                        "OtaAudits", "audits", "/ota/audits",
                        new MenuItem.Meta("menus.ota.audits", "ri:history-line", false, null,
                                null, null, null, null),
                        null, null)));
    }

    /**
     * 套餐与权益目录菜单（S14-6e，关闭 D-167 遗留入口）。
     *
     * <p>目录是**平台全局售卖事实**：没有租户列、不走 RLS，四个档位的名称、冻结额度维度、能力权益与
     * 参考价在官网已经公开（见 D-034 的公开文案与商业架构 §2）。因此本节点与 {@link #systemStatus()}
     * 采用同一口径：<b>不设权限点</b>（{@code requiredPermission = null}），登录即可读、无需先选项目；
     * 服务端接口 {@code GET /api/v1/plans} 仍由安全链要求有效令牌，菜单可见性不替代接口认证。
     *
     * <p>为什么不新增一个"平台读权限"：目录无需运营特权；ADR0164商业调整权限不用于限制公开目录，
     * 否则会让普通租户看不到公开套餐。若将来要求收窄目录可见性，仍属独立架构裁决
     * （见 D-167 的"待复核项"）。
     *
     * @return 套餐与权益目录一级菜单
     */
    private static MenuItem planCatalog() {
        return new MenuItem(
                "PlanCatalog",
                "/plan-catalog",
                "/plan-catalog/index",
                new MenuItem.Meta(
                        "menus.planCatalog.title", "ri:price-tag-3-line",
                        false, null, null, null, null, null),
                null,
                null);
    }

    /** ADR0164：没有项目也可操作，但必须显式取得平台商业授权。 */
    private static MenuItem commercialOperations() {
        return new MenuItem("CommercialOperations", "/commercial-operations", "/commercial-operations/index",
                new MenuItem.Meta("menus.commercialOperations.title", "ri:shield-user-line",
                        false, null, null, null, null, null), Permission.COMMERCIAL_ADJUST, null);
    }

    /** 仅待审接收运营员可见；服务端仍在每次读写事务内复核数据库资格。 */
    private static MenuItem selfHostedEnrollment() {
        return new MenuItem("SelfHostedEnrollment", "/self-hosted-enrollment", "/self-hosted-enrollment/index",
                new MenuItem.Meta("menus.selfHostedEnrollment.title", "ri:file-list-3-line",
                        false, null, null, null, null, null), Permission.COMMERCIAL_ADJUST, null);
    }

    /** 独立审核者可见；不复用商业运营菜单权限。 */
    private static MenuItem selfHostedReview() {
        return new MenuItem("SelfHostedReview", "/self-hosted-review", "/self-hosted-review/index",
                new MenuItem.Meta("menus.selfHostedReview.title", "ri:shield-check-line",
                        false, null, null, null, null, null), Permission.SELF_HOSTED_REVIEW, null);
    }

    /**
     * 系统状态菜单。
     *
     * <p>状态页展示的是平台级运行依赖，不包含租户或项目数据，因此登录后无需先选择项目即可查看。
     * 服务端接口仍由安全链要求有效令牌，菜单可见性不替代接口认证。</p>
     *
     * @return 系统状态一级菜单
     */
    private static MenuItem systemStatus() {
        return new MenuItem(
                "SystemStatus",
                "/system-status",
                "/system-status/index",
                new MenuItem.Meta(
                        "menus.systemStatus.title", "ri:pulse-line",
                        false, null, null, null, null, null),
                null,
                null);
    }

    /** 与前端个人中心兜底路由保持一致；仅展示当前会话资料，登录即可访问。 */
    private static MenuItem userCenter() {
        return new MenuItem(
                "UserCenter", "/system/user-center", "/system/user-center",
                new MenuItem.Meta("menus.system.userCenter", "ri:user-3-line",
                        false, null, true, true, null, null),
                null, null);
    }

    /**
     * 任务菜单。执行记录由任务页内 Drawer 展示，不再建立一个只能靠人工保持同步的独立路由。
     *
     * <p>页面读取对全部项目角色开放；创建、编辑、暂停和删除按钮只给 OWNER/ADMIN，手动执行还向
     * OPERATOR 开放。菜单过滤只负责呈现，task 模块的 Controller 与应用服务仍须各自复验角色。</p>
     *
     * @return 任务一级菜单
     */
    private static MenuItem task() {
        return new MenuItem(
                "Task", "/task", "/index/index",
                new MenuItem.Meta("menus.task.title", "ri:calendar-schedule-line", null, null,
                        null, null, null, null),
                Permission.TASK_READ,
                List.of(new MenuItem(
                        "TaskJobs", "jobs", "/task/jobs",
                        new MenuItem.Meta("menus.task.jobs", "ri:task-line", false, null,
                                null, null, null,
                                List.of(new MenuItem.AuthPoint("创建任务", Permission.TASK_MANAGE),
                                        new MenuItem.AuthPoint("修改任务", Permission.TASK_MANAGE),
                                        new MenuItem.AuthPoint("暂停或恢复任务", Permission.TASK_MANAGE),
                                        new MenuItem.AuthPoint("删除任务", Permission.TASK_MANAGE),
                                        new MenuItem.AuthPoint("立即执行", Permission.TASK_RUN))),
                        null, null)));
    }

    /**
     * 规则中心：消息规则管理按 rule:manage 下发，执行记录按 rule:read 下发。
     * 执行记录保留场景与上行两个页签，管理菜单仅向 OWNER/ADMIN 开放。
     */
    private static MenuItem rule() {
        return new MenuItem(
                "Rule", "/rule", "/index/index",
                new MenuItem.Meta("menus.rule.title", "ri:flow-chart", null, null,
                        null, null, null, null),
                Permission.RULE_READ,
                List.of(new MenuItem("MessageRules", "messages", "/rule/messages",
                        new MenuItem.Meta("menus.rule.messages", "ri:code-box-line", false, null, null, null, null, null),
                        Permission.RULE_MANAGE, null), new MenuItem("RuleAutomations", "automations", "/rule/automations",
                        new MenuItem.Meta("menus.rule.automations", "ri:flashlight-line", false, null, null, null, null, null),
                        Permission.RULE_MANAGE, null), new MenuItem("RuleScenes", "scenes", "/rule/scenes",
                        new MenuItem.Meta("menus.rule.scenes", "ri:play-circle-line", false, null, null, null, null, null),
                        Permission.RULE_MANAGE, null), new MenuItem(
                        "RuleExecutions", "executions", "/rule/executions",
                        new MenuItem.Meta("menus.rule.executions", "ri:history-line", false, null,
                                null, null, null, null),
                        null, null)));
    }

    /**
     * 告警菜单。规则/通知配置和运行处置是两类能力，不能复用一个“写权限”。
     *
     * <p>规则、通知组和模板页对所有项目成员可读，但只有 OWNER 与 ADMIN 收到配置按钮；历史页的确认、
     * 人工清除按钮还向 OPERATOR 开放。服务端仍会在告警应用层独立授权，菜单只负责避免呈现不可用操作。
     *
     * @return 告警一级菜单
     */
    private static MenuItem alarm() {
        return new MenuItem(
                "Alarm", "/alarm", "/index/index",
                new MenuItem.Meta("menus.alarm.title", "ri:alarm-warning-line", null, null,
                        null, null, null, null),
                Permission.ALARM_READ,
                List.of(
                        new MenuItem("AlarmRules", "rules", "/alarm/rules",
                                new MenuItem.Meta("menus.alarm.rules", "ri:list-settings-line", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("创建告警规则", Permission.ALARM_MANAGE),
                                                new MenuItem.AuthPoint("修改告警规则", Permission.ALARM_MANAGE),
                                                new MenuItem.AuthPoint("删除告警规则", Permission.ALARM_MANAGE))),
                                null, null),
                        new MenuItem("AlarmHistory", "history", "/alarm/history",
                                new MenuItem.Meta("menus.alarm.history", "ri:history-line", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("确认告警", Permission.ALARM_MAINTAIN),
                                                new MenuItem.AuthPoint("人工清除告警", Permission.ALARM_MAINTAIN))),
                                null, null),
                        new MenuItem("AlarmNotificationGroups", "notification-groups",
                                "/alarm/notification-groups",
                                new MenuItem.Meta("menus.alarm.notificationGroups", "ri:group-line", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("创建通知组", Permission.ALARM_MANAGE),
                                                new MenuItem.AuthPoint("修改通知组", Permission.ALARM_MANAGE),
                                                new MenuItem.AuthPoint("删除通知组", Permission.ALARM_MANAGE))),
                                null, null),
                        new MenuItem("AlarmNotificationTemplates", "notification-templates",
                                "/alarm/notification-templates",
                                new MenuItem.Meta("menus.alarm.notificationTemplates", "ri:mail-settings-line", false,
                                        null, null, null, null,
                                        List.of(new MenuItem.AuthPoint("创建通知模板", Permission.ALARM_MANAGE),
                                                new MenuItem.AuthPoint("修改通知模板", Permission.ALARM_MANAGE),
                                                new MenuItem.AuthPoint("删除通知模板", Permission.ALARM_MANAGE))),
                                null, null)));
    }

    /**
     * 设备菜单。S2-1 先落设备类型，S3-1 追加设备列表；设备组与查询按 S5 依赖顺序追加。
     * @return 设备一级菜单
     */
    private static MenuItem device() {
        return new MenuItem(
                "Device", "/device", "/index/index",
                new MenuItem.Meta("menus.device.title", "ri:cpu-line", null, null, null, null, null, null),
                Permission.DEVICE_READ,
                List.of(
                        new MenuItem("Devices", "list", "/device/index",
                                new MenuItem.Meta("menus.device.list", "ri:device-line", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("创建设备", Permission.DEVICE_CREATE),
                                                new MenuItem.AuthPoint("修改设备", Permission.DEVICE_UPDATE),
                                                new MenuItem.AuthPoint("删除设备", Permission.DEVICE_DELETE),
                                                new MenuItem.AuthPoint("下发命令", Permission.DEVICE_CONTROL),
                                                new MenuItem.AuthPoint("查看设备终端用户", Permission.ENDUSER_READ),
                                                new MenuItem.AuthPoint("查看设备任务", Permission.TASK_READ),
                                                new MenuItem.AuthPoint("查看设备规则历史", Permission.RULE_READ),
                                                new MenuItem.AuthPoint("查看设备规则配置", Permission.RULE_MANAGE))),
                                null, null),
                        new MenuItem("DeviceGroups", "groups", "/device/groups",
                                new MenuItem.Meta("menus.device.groups", "ri:folder-settings-line", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("创建设备组", Permission.DEVICE_CREATE),
                                                new MenuItem.AuthPoint("修改设备组", Permission.DEVICE_UPDATE),
                                                new MenuItem.AuthPoint("删除设备组", Permission.DEVICE_DELETE))),
                                null, null),
                        new MenuItem("DeviceMessages", "messages", "/device/messages",
                                new MenuItem.Meta("menus.device.messages", "ri:file-list-3-line", false, null,
                                        null, null, null, null),
                                null, null),
                        new MenuItem(
                                "DeviceTypes", "types", "/device/types",
                                new MenuItem.Meta("menus.device.types", "ri:list-settings-line", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("创建设备类型", Permission.DEVICE_CREATE),
                                                new MenuItem.AuthPoint("修改设备类型", Permission.DEVICE_UPDATE),
                                                new MenuItem.AuthPoint("删除设备类型", Permission.DEVICE_DELETE))),
                                null, null),
                        new MenuItem(
                                "DeviceTopology", "topology", "/device/topology",
                                new MenuItem.Meta("menus.device.topology", "ri:node-tree", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("绑定或解绑子设备", Permission.DEVICE_UPDATE))),
                                null, null),
                        new MenuItem(
                                "DeviceModbusPoints", "modbus-points", "/device/modbus-points",
                                new MenuItem.Meta("menus.device.modbusPoints", "ri:plug-line", false, null,
                                        null, null, null,
                                        List.of(new MenuItem.AuthPoint("配置 Modbus 点位", Permission.DEVICE_UPDATE))),
                                null, null)));
    }

    /**
     * 项目。
     *
     * <p><b>不设权限点</b>：所有登录账号都能创建项目、都能看到自己参与的项目
     * （ADR 0012）。而且这个入口发生在<b>选定项目之前</b> —— 那时还没有项目角色
     * 可言，用项目权限点去守它是循环的。
     *
     * @return 项目菜单节点
     */
    private static MenuItem project() {
        return new MenuItem(
                "Project",
                "/project",
                "/index/index",
                new MenuItem.Meta(
                        "menus.project.title", "ri:folder-3-line",
                        null, null, null, null, null, null),
                null,
                List.of(
                        new MenuItem(
                                "ProjectList",
                                "list",
                                "/project/list",
                                new MenuItem.Meta(
                                        "menus.project.list", "ri:list-check",
                                        false, null, null, null, null, null),
                                null,
                                null),
                        projectMembers(),
                        projectSettings(),
                        projectApiKeys(), projectWebhooks()));
    }

    /**
     * 项目成员。
     *
     * <p>这是<b>第一个按角色产生差异的菜单节点</b>，两个层面都有：
     * <ul>
     *   <li><b>节点本身</b>要 {@code member:read}，而它是项目级权限 ——
     *       未选项目时角色为 null、权限集合为空，于是整个节点消失。
     *       用户会先在项目列表里选一个，与 RLS 的 fail-closed 同一个思路</li>
     *   <li><b>三个按钮</b>只对 OWNER / ADMIN 下发。这是
     *       {@code MenuItem.Meta.authList} 第一次承载真实数据 ——
     *       在此之前它有机制、有测试，但没有任何一个真实菜单用到它</li>
     * </ul>
     *
     * <p>按钮权限点<b>不等于授权</b>：藏起来的按钮拦不住直接调接口的人，
     * 服务端在 {@code ProjectMemberService} 里独立判定（架构文档 7.2）。
     *
     * @return 项目成员菜单节点
     */
    private static MenuItem projectMembers() {
        return new MenuItem(
                "ProjectMembers",
                "members",
                "/project/members",
                new MenuItem.Meta(
                        "menus.project.members", "ri:team-line",
                        false, null, null, null, null,
                        List.of(
                                new MenuItem.AuthPoint("邀请成员", Permission.MEMBER_INVITE),
                                new MenuItem.AuthPoint("修改角色", Permission.MEMBER_UPDATE_ROLE),
                                new MenuItem.AuthPoint("移除成员", Permission.MEMBER_REMOVE))),
                Permission.MEMBER_READ,
                null);
    }

    /**
     * 项目设置。
     *
     * <p>S7-2 首屏只读展示当前项目贡献和租户共享池，不提供套餐修改入口。四种项目角色都可读，
     * 但服务端仍会实时复验当前项目成员关系，并通过受限数据库投影阻止读取其他项目明细。</p>
     *
     * @return 项目设置菜单节点
     */
    private static MenuItem projectApiKeys() {
        return new MenuItem("ProjectApiKeys", "api-keys", "/project/api-keys",
                new MenuItem.Meta("menus.project.apiKeys", "ri:key-2-line", false, null, null, null, null,
                        List.of(new MenuItem.AuthPoint("管理API Key", Permission.INTEGRATION_MANAGE))),
                Permission.INTEGRATION_MANAGE, null);
    }

    private static MenuItem projectWebhooks() {
        return new MenuItem("ProjectWebhooks", "webhooks", "/project/webhooks",
                new MenuItem.Meta("menus.project.webhooks", "ri:link", false, null, null, null, null,
                        List.of(new MenuItem.AuthPoint("管理Webhook", Permission.INTEGRATION_MANAGE))),
                Permission.INTEGRATION_MANAGE, null);
    }

    private static MenuItem projectSettings() {
        return new MenuItem(
                "ProjectSettings",
                "settings",
                "/project/settings",
                new MenuItem.Meta(
                        "menus.project.settings", "ri:settings-3-line",
                        false, null, null, null, null, null),
                Permission.QUOTA_READ,
                null);
    }

    /**
     * 概要菜单。对应架构文档第 2 节后台菜单的第一项。
     *
     * @return 概要菜单节点
     */
    private static MenuItem dashboard() {
        return new MenuItem(
                "Dashboard",
                "/dashboard",
                // 一级目录用布局组件承载，真正的页面在子节点
                "/index/index",
                new MenuItem.Meta(
                        "menus.dashboard.title", "ri:pie-chart-line",
                        null, null, null, null, null, null),
                Permission.DASHBOARD_READ,
                List.of(new MenuItem(
                        "Overview",
                        // 子级路径不带前导斜杠，前端会拼成 /dashboard/overview
                        "overview",
                        "/dashboard/overview",
                        new MenuItem.Meta(
                                "menus.dashboard.overview", "ri:home-smile-2-line",
                                false,
                                // 登录后的落点，标签页固定不可关闭
                                true,
                                null, null, null, null),
                        // 子节点不再重复声明权限：父节点已经要求 dashboard:read，
                        // 父节点被过滤掉时子节点根本不会出现
                        null,
                        null), new MenuItem(
                        "DashboardDesigner", "designer", "/dashboard/designer",
                        new MenuItem.Meta("menus.dashboard.designer", "ri:layout-4-line",
                                false, null, null, null, null, null),
                        // 定义读取与概要分开授权；创建和保存仍由manage权限及服务端双重仲裁。
                        Permission.DASHBOARD_DEFINITION_READ,
                        null), new MenuItem(
                        "ApplicationManager", "applications", "/dashboard/applications",
                        new MenuItem.Meta("menus.dashboard.applications", "ri:apps-line",
                                false, null, null, null, null, null),
                        // S12-4m仅打开应用组合编辑，写权限仍由应用服务复核。
                        Permission.APPLICATION_READ,
                        null)));
    }

    /**
     * 异常页。
     *
     * <p><b>不设权限</b>：403/404/500 是任何登录用户都可能被跳转到的页面，要求权限
     * 会导致「没权限访问某页 → 跳 403 → 403 也没权限」的死循环。
     *
     * @return 异常页菜单节点
     */
    private static MenuItem exception() {
        return new MenuItem(
                "Exception",
                "/exception",
                "/index/index",
                new MenuItem.Meta(
                        "menus.exception.title", "ri:error-warning-line",
                        null, null, null, null, null, null),
                null,
                List.of(
                        exceptionPage("Exception403", "403", "menus.exception.forbidden"),
                        exceptionPage("Exception404", "404", "menus.exception.notFound"),
                        exceptionPage("Exception500", "500", "menus.exception.serverError")));
    }

    /**
     * 构造一个异常页节点。三个页面除标题外配置完全相同，抽出来避免复制粘贴时改漏。
     *
     * @param name  路由名
     * @param path  子路径
     * @param title 标题的 i18n key
     * @return 异常页节点
     */
    private static MenuItem exceptionPage(String name, String path, String title) {
        return new MenuItem(
                name,
                path,
                "/exception/" + path,
                new MenuItem.Meta(
                        title, null,
                        true,
                        null,
                        null,
                        // 异常页不生成标签页，也不套布局
                        true,
                        true,
                        null),
                null,
                null);
    }

}
