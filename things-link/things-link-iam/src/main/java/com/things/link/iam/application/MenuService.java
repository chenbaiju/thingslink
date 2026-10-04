package com.things.link.iam.application;

import com.things.link.iam.domain.Permission;
import com.things.link.iam.domain.RolePermissions;
import com.things.link.shared.authz.ProjectRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 按当前用户的权限下发菜单树与权限点集合。
 *
 * <p>下发的是<b>已过滤</b>的结果，不是「全集 + 一个权限清单让前端自己筛」。后者会把
 * 完整的功能地图交给任何登录用户 —— 包括他无权使用的部分 —— 等于免费提供了一份
 * 侦察情报。
 *
 * <p>再次强调架构文档 7.2 的要求：<b>菜单过滤不是授权</b>。它只影响界面上出现什么，
 * 拦不住直接调接口的人。真正的授权必须在每个 Controller 与领域服务里各自校验。
 *
 * <h2>还没选项目时会看到什么</h2>
 * 角色为 {@code null}，权限集合为空，于是<b>只剩不需要任何权限的菜单</b> ——
 * 普通账号只获得公开菜单；ADR0164平台运营另按独立授权获得商业调整入口。这不是退化，而是刻意的：用户登录后应当先去项目列表
 * 选一个，选完才谈得上项目内的功能。与 RLS 的 fail-closed 是同一个思路（ADR 0012）。
 */
@Service
public class MenuService {

    /**
     * 取某角色可见的菜单树。
     *
     * @param role 当前用户在<b>当前项目</b>中的角色；{@code null} 表示尚未选择项目
     * @return 过滤后的菜单树，不会为 {@code null}
     */
    public List<MenuItem> menusFor(ProjectRole role) {
        return menusFor(role, false);
    }

    /**
     * 取某角色拥有的权限点标识。
     *
     * @param role 当前用户在<b>当前项目</b>中的角色；{@code null} 表示尚未选择项目
     * @return 形如 {@code dashboard:read} 的标识集合，按字典序排列
     */
    public List<String> permissionCodesFor(ProjectRole role) {
        // 排序是为了让响应稳定：无序集合会让同一份数据在不同 JVM 运行间产生不同的
        // 响应体，从而破坏缓存命中，也让契约快照类的测试无端变红
        return permissionCodesFor(role, false);
    }

    /** 平台权限独立于已选项目；调用方必须先按认证账号实时查询授权。 */
    public List<MenuItem> menusFor(ProjectRole role, boolean commercialOperator) {
        return menusFor(role, commercialOperator, false);
    }

    /** 独立审核资格只影响显示；审核 API 仍在发行方写事务内复核。 */
    public List<MenuItem> menusFor(ProjectRole role, boolean commercialOperator, boolean reviewer) {
        return filter(MenuCatalog.all(), effectivePermissions(role, commercialOperator, reviewer));
    }

    /** /me反馈包含独立平台权限，但不把它写入项目角色或JWT。 */
    public List<String> permissionCodesFor(ProjectRole role, boolean commercialOperator) {
        return permissionCodesFor(role, commercialOperator, false);
    }

    /** /me 单独反馈发行方审核资格，不写入项目角色或 JWT。 */
    public List<String> permissionCodesFor(ProjectRole role, boolean commercialOperator, boolean reviewer) {
        return effectivePermissions(role, commercialOperator, reviewer).stream().map(Permission::code).sorted().toList();
    }

    private Set<Permission> effectivePermissions(ProjectRole role, boolean commercialOperator, boolean reviewer) {
        Set<Permission> permissions = new java.util.HashSet<>(RolePermissions.of(role));
        if (commercialOperator) permissions.add(Permission.COMMERCIAL_ADJUST);
        if (reviewer) permissions.add(Permission.SELF_HOSTED_REVIEW);
        return Set.copyOf(permissions);
    }

    /**
     * 按权限集合递归过滤菜单树。
     *
     * <p>包级可见，供测试直接构造权限集合验证过滤行为 —— 通过角色间接测试的话，
     * 只要四个内置角色的权限恰好相同（当前就是如此），过滤逻辑就等于没被测到。
     *
     * @param items       待过滤的节点
     * @param permissions 当前用户的权限集合
     * @return 过滤后的节点
     */
    static List<MenuItem> filter(List<MenuItem> items, Set<Permission> permissions) {
        List<MenuItem> kept = new ArrayList<>();

        for (MenuItem item : items) {
            if (item.requiredPermission() != null
                    && !permissions.contains(item.requiredPermission())) {
                continue;
            }

            List<MenuItem> children = item.children() == null
                    ? null
                    : filter(item.children(), permissions);

            // 子节点被过滤光的目录不再下发。留着的话前端会渲染出一个点开是空的目录，
            // 用户看不出这是「没权限」还是「功能坏了」
            if (children != null && children.isEmpty()) {
                continue;
            }

            kept.add(new MenuItem(
                    item.name(), item.path(), item.component(),
                    filterMeta(item.meta(), permissions),
                    item.requiredPermission(), children));
        }

        return List.copyOf(kept);
    }

    /**
     * 过滤元信息里的按钮权限点。
     *
     * <p>只保留当前用户被授予的那些。全量下发会让前端渲染出用户点了必然报错的按钮，
     * 同时也暴露了他无权使用的操作有哪些。
     *
     * @param meta        原始元信息
     * @param permissions 当前用户的权限集合
     * @return 过滤后的元信息
     */
    private static MenuItem.Meta filterMeta(MenuItem.Meta meta, Set<Permission> permissions) {
        if (meta.authList() == null || meta.authList().isEmpty()) {
            return meta;
        }

        List<MenuItem.AuthPoint> granted = meta.authList().stream()
                .filter(auth -> permissions.contains(auth.permission()))
                .toList();

        return new MenuItem.Meta(
                meta.title(), meta.icon(), meta.keepAlive(), meta.fixedTab(),
                meta.isHide(), meta.isHideTab(), meta.isFullPage(),
                // 一个都没剩时置 null 而非空数组，序列化时字段整个消失，
                // 与「本页面本来就没有按钮权限点」表现一致
                granted.isEmpty() ? null : granted);
    }

}
