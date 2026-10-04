package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 终端用户在项目中的角色赋值投影（S11-1b 列表读模型）。
 *
 * <p>这是一条把 {@code app_user}（租户级登录身份）与 {@code app_user_role}（项目角色
 * 赋值）反规范化到一行的只读投影：控制台「项目用户列表」只展示<b>当前项目赋值</b>，
 * 不含未分配本项目角色的其他租户用户（见 {@link AppUserRoleRepository#findAssignmentsByProject}）。
 *
 * <p>两个 {@code status} 语义不同，刻意并列表述：
 * {@code userStatus} 是租户级登录状态（{@link AppUser.Status}，项目管理员不得修改），
 * {@code roleStatus} 是项目级停用/恢复状态（{@link AppUserRole.Status}，正是项目管理员能动的那一列）。
 *
 * @param appUserId   终端用户 ID。控制台对它做 suspend/restore/改角色定位
 * @param username    租户内用户名
 * @param displayName 显示名称，可空
 * @param userStatus  租户级登录状态
 * @param role        项目角色
 * @param roleStatus  项目级角色状态
 * @param assignedAt  角色分配时刻
 */
public record AppUserAssignment(
        UUID appUserId,
        String username,
        String displayName,
        AppUser.Status userStatus,
        EndUserRole role,
        AppUserRole.Status roleStatus,
        Instant assignedAt) {
}
