package com.things.link.iam.domain;

import com.things.link.shared.authz.ProjectRole;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>项目</b>角色到权限点的授予关系（架构文档 7.2 + ADR 0012 校准）。
 *
 * <h2>为什么是项目角色而不是租户角色</h2>
 * 角色是 {@code (账号, 项目)} 的属性 —— 同一个账号可以是项目 A 的 OWNER、
 * 项目 B 的 VIEWER。原先键在租户角色上，隐含前提是「一个人在一个租户里只有一个
 * 身份」，那个前提在校准后不成立。
 *
 * <h2>为什么是代码而不是数据表</h2>
 * 内置角色的语义是产品定义的一部分，不是运营数据：把 OWNER 的权限改小不是一次配置
 * 变更，而是一次产品变更，应当走代码评审与版本发布，而不是某个人在生产库上 UPDATE
 * 一行。<b>自定义角色</b>是另一回事，它天然是运营数据，将来会落到
 * {@code role} / {@code role_permission} 两张表，届时本类是内置角色的默认值来源。
 *
 * <h2>这里只是「角色带来的权限」，不是最终判定</h2>
 * 架构文档 7.2 的判定式是：
 * <pre>Decision = 角色权限 ∩ 项目成员关系 ∩ 数据范围 ∩ 套餐能力 ∩ 资源状态</pre>
 * 本类只提供第一项。把它当成完整授权来用，会在多项目场景下越权 —— 同一账号在项目 A
 * 是 OWNER、在项目 B 是 VIEWER 是完全正常的情况。项目维度的收窄在 project 模块落地。
 */
public final class RolePermissions {

    /**
     * 角色 → 权限点集合。
     *
     * <p>S1 切片 5d 起<b>四个角色终于不再相同</b>：成员管理的三个权限点只给
     * OWNER 与 ADMIN。在此之前只有「查看概要」一个权限点、四个角色都有，
     * 分级机制接通了但看不出效果。
     *
     * <p>此处刻意逐个角色写全，而不是「VIEWER 的权限 + 增量」式的继承写法。角色权限
     * 常常不是严格包含关系（OPERATOR 能控制设备但不能管成员，ADMIN 反之亦可能成立），
     * 继承写法在第一次遇到这种情况时就会被迫拆开，反而更乱。
     */
    private static final Map<ProjectRole, Set<Permission>> GRANTS = buildGrants();

    private RolePermissions() {
    }

    /**
     * 取某个角色被授予的权限点。
     *
     * @param role 项目内角色；{@code null} 表示尚未选择项目
     * @return 不可变的权限点集合；角色未登记或为 null 时返回空集合
     */
    public static Set<Permission> of(ProjectRole role) {
        if (role == null) {
            // 还没选项目 —— 此时没有任何项目角色，自然也没有任何项目级权限。
            // 返回空集合而不是「默认最低角色」：后者会让「未选项目」和「访客」
            // 变得无法区分，而它们该看到的东西完全不同（ADR 0012）
            return Set.of();
        }
        // 未登记的角色返回空集合而非抛异常，也不是「全部权限」：
        // 新增角色时忘了在这里登记，表现应当是「什么都看不到」而不是「什么都能做」。
        // 权限相关的默认值必须朝安全的一侧倒（fail-closed）。
        return GRANTS.getOrDefault(role, Set.of());
    }

    /**
     * 构造授予表。
     *
     * @return 角色到权限点集合的不可变映射
     */
    private static Map<ProjectRole, Set<Permission>> buildGrants() {
        Map<ProjectRole, Set<Permission>> grants = new EnumMap<>(ProjectRole.class);

        for (ProjectRole role : ProjectRole.values()) {
            // 告警历史是项目运行事实，VIEWER 也需要读取；是否能配置或处置由独立权限收窄。
            Set<Permission> permissions =
                    EnumSet.of(Permission.DASHBOARD_READ, Permission.DASHBOARD_DEFINITION_READ,
                            Permission.QUOTA_READ, Permission.MEMBER_READ,
                            Permission.DEVICE_READ, Permission.ALARM_READ, Permission.TASK_READ,
                            Permission.RULE_READ, Permission.APPLICATION_READ, Permission.ENDUSER_READ);

            /*
             * 成员管理的三个权限点跟着 ProjectRole.canManageMembers() 走，
             * **不在这里重写一遍 role == OWNER || role == ADMIN**。
             *
             * 真正的服务端判定在 project 的 ProjectMemberService 里，它读的是同一个
             * 方法。两边各写一份的话，改漏一处的后果分两种：按钮显示了但点下去 403
             * （烦人但安全），或者按钮藏起来了而接口照样放行 —— 后者是越权，
             * 而且不会有任何症状，没人会去点一个看不见的按钮来发现它。
             */
            if (role.canManageMembers()) {
                permissions.add(Permission.MEMBER_INVITE);
                permissions.add(Permission.MEMBER_UPDATE_ROLE);
                permissions.add(Permission.MEMBER_REMOVE);
                permissions.add(Permission.DEVICE_CREATE);
                permissions.add(Permission.DEVICE_UPDATE);
                permissions.add(Permission.DEVICE_DELETE);
                permissions.add(Permission.ALARM_MANAGE);
                permissions.add(Permission.TASK_MANAGE);
                permissions.add(Permission.RULE_MANAGE);
                permissions.add(Permission.INTEGRATION_MANAGE);
                permissions.add(Permission.APPLICATION_MANAGE);
                permissions.add(Permission.DASHBOARD_DEFINITION_MANAGE);
                permissions.add(Permission.ENDUSER_MANAGE);
                /*
                 * OTA是部署管理面而非协作只读事实：现有OTA读取端点里只有固件列表/详情、
                 * 上传会话、发布尝试与信任域摘要接受任意项目成员，生命周期、发布物、类型基线、
                 * 设备资格、活动与回退预检都要求OWNER/ADMIN。把ota:read发给四种角色会让
                 * OPERATOR/VIEWER看到固件页却在生命周期面板上拿到403——正是菜单过滤要避免的
                 * 「显示了注定失败的操作」。因此读取与部署一起收窄到管理角色；将来若要让
                 * VIEWER浏览OTA，必须先在同一次改动里放宽那些服务端读取，不能只改授予表。
                 */
                permissions.add(Permission.OTA_READ);
                permissions.add(Permission.OTA_DEPLOY);
            }

            // 控制设备是运行操作，不是配置管理：OPERATOR 必须可用，VIEWER 仍保持纯只读。
            if (role.canControlDevices()) {
                permissions.add(Permission.DEVICE_CONTROL);
                permissions.add(Permission.ALARM_MAINTAIN);
                permissions.add(Permission.TASK_RUN);
            }

            grants.put(role, permissions);
        }

        // 双层不可变：外层防止增删角色，内层 unmodifiableSet 防止调用方改动某个角色的
        // 权限集合。少了内层的话，一次 permissions.add(...) 会永久污染全进程的授予表，
        // 而且症状是「某个用户莫名多了权限」，几乎无法追查
        grants.replaceAll((role, permissions) -> Collections.unmodifiableSet(permissions));
        return Collections.unmodifiableMap(grants);
    }

}
