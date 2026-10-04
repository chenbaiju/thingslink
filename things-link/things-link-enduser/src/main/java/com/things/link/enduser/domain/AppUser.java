package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 终端用户登录身份（ADR 0035）。
 *
 * <p>与控制台 {@code sys_account}（全局唯一邮箱）完全分离的第二类身份：用户名只在
 * {@code (tenant_id, username)} 上唯一，规范化规则冻结为 {@code trim + 小写}。租户级
 * 登录身份走 {@code enable_tenant_rls()}（策略 {@code tenant_isolation}）。
 *
 * @param id                终端用户 ID（UUIDv7）
 * @param tenantId          归属租户。用户名唯一性的范围，也是登录定位的租户
 * @param username          租户内用户名（已规范化：trim + 小写）
 * @param passwordHash      口令哈希，DelegatingPasswordEncoder 格式（默认 bcrypt，ADR 0009）
 * @param displayName       显示名称，可空
 * @param status            租户级登录状态。**项目 OWNER/ADMIN 不得修改**，项目侧停用落在
 *                          {@code app_user_role.status=DISABLED}
 * @param passwordChangedAt 最后改密时刻，App 会话撤销矩阵的事实依据（ADR 0036）
 * @param createdAt         创建时刻
 */
public record AppUser(
        UUID id,
        UUID tenantId,
        String username,
        String passwordHash,
        String displayName,
        Status status,
        Instant passwordChangedAt,
        Instant createdAt) {

    /** 租户级登录状态。与迁移中的 CHECK 约束保持一致，改动要同时改迁移。 */
    public enum Status {
        /** 正常。 */
        ACTIVE,
        /** 已锁定，无法登录。 */
        LOCKED
    }
}
