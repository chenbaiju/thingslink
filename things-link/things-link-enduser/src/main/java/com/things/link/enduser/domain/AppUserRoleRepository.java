package com.things.link.enduser.domain;

import com.things.link.shared.page.CursorPage;

import java.util.Optional;
import java.util.UUID;

/**
 * 终端用户项目角色赋值的仓储契约。
 *
 * <p><b>app_user_role 受项目 RLS 保护</b>（{@code enable_project_rls()}，策略
 * {@code project_isolation}）。读写前必须建立正确的项目上下文（{@code app.project_id}）
 * 与租户上下文（{@code app.tenant_id}）。
 */
public interface AppUserRoleRepository {

    /**
     * 建立项目角色赋值。
     *
     * @param role 角色赋值
     */
    void assign(AppUserRole role);

    /**
     * 查某终端用户在某项目中的角色。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @return 角色赋值；不存在时为空
     */
    Optional<AppUserRole> findByProjectAndUser(UUID projectId, UUID appUserId);

    /**
     * 修改某终端用户在某项目中的角色状态（停用/恢复）。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param status    目标状态
     * @return 实际更新的行数；0 表示该用户在本项目没有角色
     */
    int updateStatus(UUID projectId, UUID appUserId, AppUserRole.Status status);

    /**
     * 修改某终端用户在某项目中的角色（改角色，S11-1b）。
     *
     * <p>与 {@link #assign} 分离：assign 建行（重复分配由唯一索引仲裁），本方法改已存在
     * 行的 {@code role} 列。目标用户在本项目没有角色时返回 0，调用方映射为 60005。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param role      目标角色
     * @return 实际更新的行数；0 表示该用户在本项目没有角色
     */
    int updateRole(UUID projectId, UUID appUserId, EndUserRole role);

    /**
     * 列项目内已分配角色的终端用户（反规范化投影，S11-1b）。
     *
     * <p>以 {@code app_user_role} 为驱动表（项目轴 RLS），内联 {@code app_user}（租户轴
     * RLS）取回用户名/显示名/租户级状态。游标键集为
     * {@code (assigned_at, app_user_id)} 降序 —— 同一项目内 {@code app_user_id} 唯一，
     * 与分配时刻组合成稳定、无歧义的排序键。
     *
     * @param projectId 项目 ID
     * @param cursor    上一页游标；null 表示首页
     * @param limit     单页数量
     * @return 一页终端用户角色赋值投影
     */
    CursorPage<AppUserAssignment> findAssignmentsByProject(UUID projectId, String cursor, int limit);
}
