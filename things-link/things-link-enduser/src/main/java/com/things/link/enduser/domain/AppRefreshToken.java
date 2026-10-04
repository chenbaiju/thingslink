package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 终端用户（App）刷新令牌的服务端记录（ADR 0036）。
 *
 * <p>注意本类型<b>不持有令牌明文</b>。明文只在签发的那一次响应里出现，此后服务端只保存
 * 它的 SHA-256（见 {@code V20260817_0200__app_refresh_token.sql}）。与控制台
 * {@code sys_refresh_token} 分开一张表：行为主体是 {@code app_user_id}，不是
 * {@code account_id}，两者会话的租户恢复、状态复核、撤销矩阵全都不一样。
 *
 * @param appUserId  所属终端用户。第二类身份，与控制台 account_id 分离
 * @param tenantId   签发时所在的租户。刷新时沿用它，不重新推导 —— 登录时由 projectKey
 *                   唯一确定，是「用哪个」的选择而非可事后推断的属性
 * @param projectId  会话绑定的项目。App access token 是单项目令牌，因此本项目列
 *                   <b>NOT NULL</b>，与控制台（可未选项目）不同
 * @param projectGeneration 签发时项目生命周期代次；轮换前必须与项目当前代次一致
 * @param familyId   轮换族。同一次登录派生出的所有令牌共享它
 * @param expiresAt  过期时刻
 * @param revokedAt  撤销时刻；{@code null} 表示未撤销
 * @param replacedBy 轮换后取代它的令牌 ID；{@code null} 表示尚未被轮换
 */
public record AppRefreshToken(
        UUID id,
        UUID appUserId,
        UUID tenantId,
        UUID projectId,
        long projectGeneration,
        UUID familyId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        UUID replacedBy) {

    /** 持久会话代次必须非负；负值会破坏项目删除的单调撤销边界。 */
    public AppRefreshToken {
        if (projectGeneration < 0) {
            throw new IllegalArgumentException("App刷新令牌的项目生命周期代次不能为负数");
        }
    }

    /**
     * 兼容代次列上线前的构造调用；旧事实按ADR0073解释为项目代次0。
     *
     * @param id 令牌记录ID
     * @param appUserId 所属终端用户
     * @param tenantId 签发租户
     * @param projectId 会话项目
     * @param familyId 轮换族
     * @param issuedAt 签发时刻
     * @param expiresAt 过期时刻
     * @param revokedAt 撤销时刻
     * @param replacedBy 后继令牌ID
     */
    public AppRefreshToken(
            UUID id,
            UUID appUserId,
            UUID tenantId,
            UUID projectId,
            UUID familyId,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt,
            UUID replacedBy) {
        this(id, appUserId, tenantId, projectId, 0L, familyId,
                issuedAt, expiresAt, revokedAt, replacedBy);
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
     * <p>这是<b>复用检测</b>的判据：合法客户端换到新令牌后不会再用旧的，所以一个已被
     * 轮换的令牌再次出现，说明它被复制过。此时无法分辨来的是攻击者还是真用户，唯一
     * 安全的处置是把整族一起作废。
     *
     * @return 已被轮换返回 true
     */
    public boolean isRotated() {
        return replacedBy != null;
    }

}
