package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 终端用户在项目中的角色赋值（ADR 0035）。
 *
 * <p>角色是 {@code (项目, 终端用户)} 的属性，不是终端用户的属性：同一个用户可以是
 * 项目 A 的 APP_ADMIN、项目 B 的 OBSERVER。项目事实走 {@code enable_project_rls()}
 * （策略 {@code project_isolation}）。
 *
 * @param id         角色记录 ID（UUIDv7）
 * @param tenantId   归属租户，由复合外键锁死「必须等于项目与用户的归属租户」
 * @param projectId  所属项目
 * @param appUserId  被赋值的终端用户
 * @param role       终端用户项目角色
 * @param status     项目级状态
 * @param createdAt  赋值时刻
 */
public record AppUserRole(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID appUserId,
        EndUserRole role,
        Status status,
        Instant createdAt) {

    /** 项目级状态。与迁移中的 CHECK 约束保持一致，改动要同时改迁移。 */
    public enum Status {
        /** 有效。 */
        ACTIVE,
        /** 已停用。项目 OWNER/ADMIN 只能动这一列，不能改租户级 {@code app_user.status}。 */
        DISABLED
    }
}
