package com.things.link.iam.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 刷新令牌的服务端记录。
 *
 * <p>注意本类型<b>不持有令牌明文</b>。明文只在签发的那一次响应里出现，此后服务端
 * 只保存它的 SHA-256（见 {@code V20260801_1120__refresh_token.sql}）。
 *
 * @param accountId  所属账号
 * @param tenantId   签发时所在的租户。刷新时沿用它，不重新推导 —— 一个账号可以属于
 *                   多个租户，「用哪个」是登录时的选择而非可事后推断的属性
 * @param projectId  该会话当前选中的项目；{@code null} 表示尚未选择。
 *                   <b>项目选择是会话状态</b>，落在刷新令牌上才能跨刷新存活 ——
 *                   否则访问令牌一过期，当前项目就会在某个不确定的时刻莫名丢失
 * @param projectLifecycleGeneration 该会话签发时项目的生命周期代次；未选项目时为0
 * @param familyId   轮换族。同一次登录派生出的所有令牌共享它
 * @param expiresAt  过期时刻
 * @param revokedAt  撤销时刻；{@code null} 表示未撤销
 * @param replacedBy 轮换后取代它的令牌 ID；{@code null} 表示尚未被轮换
 */
public record RefreshToken(
        UUID id,
        UUID accountId,
        UUID tenantId,
        UUID projectId,
        long projectLifecycleGeneration,
        UUID familyId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        UUID replacedBy) {

    /**
     * 兼容生命周期代次引入前的构造；旧记录与旧代码均从零代开始。
     * @param id 令牌记录ID
     * @param accountId 所属账号
     * @param tenantId 当前租户
     * @param projectId 当前项目，可为空
     * @param familyId 轮换族
     * @param issuedAt 签发时刻
     * @param expiresAt 过期时刻
     * @param revokedAt 撤销时刻
     * @param replacedBy 后继令牌ID
     */
    public RefreshToken(UUID id, UUID accountId, UUID tenantId, UUID projectId, UUID familyId,
                        Instant issuedAt, Instant expiresAt, Instant revokedAt, UUID replacedBy) {
        this(id, accountId, tenantId, projectId, 0L, familyId,
                issuedAt, expiresAt, revokedAt, replacedBy);
    }

    /** 持久会话代次必须非负，且无项目会话不能伪造项目代次。 */
    public RefreshToken {
        if (projectLifecycleGeneration < 0 || projectId == null && projectLifecycleGeneration != 0) {
            throw new IllegalArgumentException("刷新令牌的项目生命周期代次不合法");
        }
    }

    /**
     * 是否可用于换取新令牌。
     *
     * @param now 当前时刻
     * @return 未撤销、未被轮换且未过期时返回 true
     */
    public boolean isUsable(Instant now) {
        return revokedAt == null && replacedBy == null && now.isBefore(expiresAt);
    }

    /**
     * 是否已经被轮换过。
     *
     * <p>这是<b>复用检测</b>的判据：合法客户端换到新令牌后不会再用旧的，所以一个
     * 已被轮换的令牌再次出现，说明它被复制过。此时无法分辨来的是攻击者还是真用户，
     * 唯一安全的处置是把整族一起作废。
     *
     * @return 已被轮换返回 true
     */
    public boolean isRotated() {
        return replacedBy != null;
    }

}
