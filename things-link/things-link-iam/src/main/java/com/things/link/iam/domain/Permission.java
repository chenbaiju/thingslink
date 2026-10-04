package com.things.link.iam.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 权限点登记表。
 *
 * <p>命名遵循架构文档 7.2 的 {@code resource:action}，与 {@code docs/PERMISSIONS.md}
 * 逐条对应 —— <b>先在文档登记，再加枚举常量</b>，顺序反过来那份文档永远追不上代码
 * （与 {@code docs/ERROR_CODES.md} 同一套纪律）。
 *
 * <h2>为什么权限点定义在代码里而不是数据库表里</h2>
 * 权限点是<b>代码的能力清单</b>：每一个都必须有对应的服务端校验才有意义。放进数据库
 * 就会出现「表里有一行 device:delete，但没有任何代码检查它」这种状态，而且不会有任何
 * 症状 —— 直到某天发现越权可用。放在枚举里，删掉一个常量会让引用处编译失败。
 *
 * <p>将来支持自定义角色时，需要入库的是<b>角色到权限点的授予关系</b>，而不是权限点
 * 本身。本枚举那时会成为「可授予项的目录」，这是常见且正确的拆分方式。
 *
 * <h2>只登记已经有服务端校验的权限点</h2>
 * 一个被授予、被下发到前端、却没有任何 Controller 校验的权限点，其唯一效果是让前端
 * 显示一个按钮，而后端对谁都放行 —— 架构文档 7.2 明确写了「禁止仅靠前端隐藏菜单实现
 * 授权」。因此尚未实现的功能<b>不提前加常量</b>，只在 {@code docs/PERMISSIONS.md}
 * 的「规划中」一节留位置。
 */
public enum Permission {

    /**
     * 查看概要页。
     *
     * <p>目前平台只有这一个业务页面，因此权限点也只有这一个。它看起来微不足道，
     * 但它是整条链路（角色 → 权限集合 → 菜单过滤 → 前端渲染）唯一的真实数据；
     * 后续每个阶段按各自的资源往这里加。
     */
    DASHBOARD_READ("dashboard:read", "查看概要"),

    /**
     * 查看当前项目的看板定义、草稿与历史版本。
     *
     * <p>S12-1d3把可编辑看板定义与平台概要页拆成不同资源；四种项目角色均可读取协作事实，
     * 但不能借本权限获得后续看板写入、发布或分享能力（ADR0100访问合同第2节）。
     */
    DASHBOARD_DEFINITION_READ("dashboard_definition:read", "查看看板定义、草稿与版本"),

    /**
     * 修改当前项目的看板管理名称与草稿。
     *
     * <p>S12-1d4a把看板定义写入与四角色共享的读取能力分开，只向OWNER与ADMIN开放；
     * 未获得本权限不影响{@link #DASHBOARD_DEFINITION_READ}，也不能据此获得发布或分享能力
     * （ADR0100访问合同第2节）。
     */
    DASHBOARD_DEFINITION_MANAGE("dashboard_definition:manage", "管理看板定义与草稿"),

    /** 查看当前项目贡献与所属租户共享配额池；四种项目角色均可读取。 */
    QUOTA_READ("quota:read", "查看项目配额与用量"),

    /**
     * 查看项目成员列表。
     *
     * <p>四个角色都有。知道「这个项目里有谁」是协作的前提，藏起来只会让人去问
     * 管理员，而管理员会截图发群里 —— 保护效果为零，摩擦是真的。
     */
    MEMBER_READ("member:read", "查看项目成员"),

    /**
     * 邀请成员加入项目。
     *
     * <p>与下面两个一起，是<b>四个角色第一次真正产生差异</b>的地方：
     * 只有 OWNER 与 ADMIN 拥有它们。在此之前四个角色的权限集合完全相同，
     * 分级机制接通了但看不出效果。
     */
    MEMBER_INVITE("member:invite", "邀请项目成员"),

    /** 修改成员在项目中的角色。 */
    MEMBER_UPDATE_ROLE("member:update_role", "修改成员角色"),

    /** 把成员移出项目。只解除关联，不删除账号。 */
    MEMBER_REMOVE("member:remove", "移除项目成员"),

    /** 查看设备类型与设备。四种项目角色均可读取。 */
    DEVICE_READ("device:read", "查看设备与设备类型"),

    /** 创建设备类型或设备；配置能力只授予 OWNER 与 ADMIN。 */
    DEVICE_CREATE("device:create", "创建设备与设备类型"),

    /** 修改设备类型或设备配置；只授予 OWNER 与 ADMIN。 */
    DEVICE_UPDATE("device:update", "修改设备与设备类型"),

    /** 删除设备类型或设备；只授予 OWNER 与 ADMIN。 */
    DEVICE_DELETE("device:delete", "删除设备与设备类型"),

    /** 下发设备命令并查询执行结果；OWNER、ADMIN 与 OPERATOR 拥有，VIEWER 不拥有。 */
    DEVICE_CONTROL("device:control", "控制设备"),

    /** 查看告警规则与告警历史；四种项目角色均可读取。 */
    ALARM_READ("alarm:read", "查看告警规则与告警历史"),

    /** 创建、修改或删除告警规则；规则配置只授予 OWNER 与 ADMIN。 */
    ALARM_MANAGE("alarm:manage", "管理告警规则"),

    /** 确认或人工清除告警；运行维护能力授予 OWNER、ADMIN 与 OPERATOR。 */
    ALARM_MAINTAIN("alarm:maintain", "确认与清除告警"),

    /** 查看任务定义与执行日志；四种项目角色都能读取项目运行事实。 */
    TASK_READ("task:read", "查看任务与执行日志"),

    /** 创建、修改、暂停或删除任务；只授予 OWNER 与 ADMIN。 */
    TASK_MANAGE("task:manage", "管理任务"),

    /** 手动触发任务；运行操作向 OWNER、ADMIN 与 OPERATOR 开放。 */
    TASK_RUN("task:run", "运行任务"),

    /** 查看规则执行记录（场景执行 + 上行规则执行）；四种项目角色都能读取运行事实。 */
    RULE_READ("rule:read", "查看规则执行记录"),
    /** 管理规则源码、版本、调试及场景执行。 */
    RULE_MANAGE("rule:manage", "管理规则与场景"),

    /** 仅OWNER/ADMIN可管理项目API Key，不能用公开凭据取得本权限。 */
    INTEGRATION_MANAGE("integration:manage", "管理公开集成"),

    /**
     * 查看当前项目的应用目录、应用详情和草稿。
     *
     * <p>S12-1d1只开放管理侧只读HTTP，因此四种项目角色都拥有本权限；应用写入由独立的
     * {@link #APPLICATION_MANAGE}收窄，不能借本权限放宽（ADR0100访问合同第2节）。
     */
    APPLICATION_READ("application:read", "查看应用目录、草稿与版本"),

    /**
     * 修改当前项目的应用管理名称与草稿。
     *
     * <p>S12-1d2a只向OWNER与ADMIN开放改名和草稿保存；应用创建因域幂等恢复身份独立留到
     * S12-1d2b，发布生命周期也不借本权限登记提前开放（ADR0100访问合同第2节）。
     */
    APPLICATION_MANAGE("application:manage", "管理应用"),

    /** 查看项目终端用户与设备授权；四种项目角色均可读取。 */
    ENDUSER_READ("enduser:read", "查看终端用户"),

    /** 预置账号、分配或停用终端用户项目角色；只授予 OWNER 与 ADMIN。 */
    ENDUSER_MANAGE("enduser:manage", "管理终端用户"),

    /**
     * 查看当前项目的固件、上传会话、发布尝试、生命周期与信任域摘要。
     *
     * <p>S13-4a把OTA控制台落地时才登记本权限点：OTA是部署管理面，现有读取端点里
     * 生命周期、发布物、类型基线、设备资格、活动与回退预检都要求OWNER/ADMIN，
     * 因此本权限与{@link #OTA_DEPLOY}一起只授予OWNER与ADMIN；固件列表这一类
     * 已对全部成员开放的读取端点<b>不因本权限收窄</b>，菜单可见性也不替代服务端授权。
     */
    OTA_READ("ota:read", "查看OTA固件、活动与设备作业"),

    /**
     * 管理当前项目的OTA固件发布与升级活动。
     *
     * <p>只授予OWNER与ADMIN，与{@link com.things.link.shared.authz.ProjectRole#canManageMembers()}
     * 走同一判定；OTA控制器读同一方法，不另写一份{@code role == OWNER || role == ADMIN}。
     * 本权限不包含设备侧刷写能力，也不因存在而解除无signer时的fail-closed。
     */
    OTA_DEPLOY("ota:deploy", "管理OTA固件发布与升级活动"),

    /** ADR0164：仅独立平台授权持有者可得，不加入项目RolePermissions。 */
    COMMERCIAL_ADJUST("commercial:adjust", "审批租户商业权益调整"),

    /** ADR0223：仅发行方独立数据库审核资格持有者可得，不继承项目或商业运营权限。 */
    SELF_HOSTED_REVIEW("self_hosted:review", "核验自部署申请");

    /** 对外的权限点标识，即 {@code resource:action}。前端与文档都用这个字符串。 */
    private final String code;

    /** 中文说明，用于文档与将来的角色配置界面。 */
    private final String description;

    Permission(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 权限点标识。
     *
     * @return 形如 {@code dashboard:read} 的字符串
     */
    public String code() {
        return code;
    }

    /**
     * 权限点说明。
     *
     * @return 中文描述
     */
    public String description() {
        return description;
    }

    /**
     * 按标识查找权限点。
     *
     * <p>返回 {@link Optional} 而不是抛异常：调用方通常是在解析外部输入
     * （将来的角色配置接口），未知标识是「请求不合法」而不是「服务端故障」。
     *
     * @param code 权限点标识
     * @return 匹配的权限点，无匹配时为空
     */
    public static Optional<Permission> byCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }

}
