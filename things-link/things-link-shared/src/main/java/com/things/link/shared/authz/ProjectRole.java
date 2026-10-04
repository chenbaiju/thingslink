package com.things.link.shared.authz;

/**
 * 项目内角色（ADR 0012）。
 *
 * <h2>为什么在 shared 而不是 project</h2>
 * 它是两个模块<b>共用的词汇</b>：
 * <ul>
 *   <li>project 用它表达成员关系（{@code sys_project_member.role}）</li>
 *   <li>iam 用它决定权限点与菜单（{@code RolePermissions} / {@code MenuService}）</li>
 * </ul>
 *
 * <p>放在 project 的 {@code domain} 包里的话，iam 就够不着 —— 架构文档 10.4 规则 2/3：
 * 跨模块只能引用对方的 {@code application} 包。而把它挪进 project 的 application，
 * 又会让 project 自己的 domain 反过来依赖 application，方向是错的。
 *
 * <p>与 {@code shared.tenant.TenantScope} 同类：跨模块的共享词汇。
 *
 * <p>租户成员表不再有角色列：ADR 0012 校准后，授权只看本枚举对应的
 * {@code sys_project_member.role}。这条边界不能再放回 iam 或 tenant_member，
 * 否则同一账号在不同项目中的权限会被错误地合并。
 *
 * <p>顺序有意义：从上到下权限递减，便于做「至少需要 X 角色」的比较。
 */
public enum ProjectRole {

    /** 项目所有者。由创建项目产生，每个项目有且只有一个，不可被移除或降级。 */
    OWNER,

    /** 管理员：可管理成员与全部业务配置。 */
    ADMIN,

    /** 操作员：可操作设备与业务数据，不可管理成员。 */
    OPERATOR,

    /** 访客：只读。 */
    VIEWER;

    /**
     * 本角色是否可以管理项目成员（邀请、改角色、移出）。
     *
     * <h2>为什么这条规则放在枚举上，而不是各自实现</h2>
     * 它有<b>两个必须一致的读者</b>，分处两个模块：
     * <ul>
     *   <li>project 的 {@code ProjectMemberService} —— 真正的服务端判定</li>
     *   <li>iam 的 {@code RolePermissions} —— 决定下发哪些权限点与按钮</li>
     * </ul>
     *
     * <p>两边各写一份 {@code role == OWNER || role == ADMIN} 的话，
     * 改动其中一处的症状分两种，都很难发现：按钮显示了但点下去 403（烦人但安全），
     * 或者按钮藏起来了而接口照样放行（<b>越权，且没有任何症状</b>）。
     *
     * <p>放在共享枚举上，两个模块引用的是同一段代码，不存在「改漏一处」。
     *
     * <p>这不违反「授权判定在服务端」：本方法只是规则的<b>定义</b>，
     * 服务端仍然要在每次操作时调用它并拒绝不合格的请求 —— 前端拿到的权限点
     * 只影响界面显示（架构文档 7.2）。
     *
     * @return OWNER 与 ADMIN 返回 true
     */
    public boolean canManageMembers() {
        return this == OWNER || this == ADMIN;
    }

    /** 运行控制授予的共享规则，API Key与Console必须保持一致。 */
    public boolean canControlDevices() {return this != VIEWER;}

}
