package com.things.link.iam.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 控制台账号（架构文档 7.2 第一类身份）。
 *
 * <p>这是**全局身份**，不含租户信息 —— 租户归属由 {@link TenantMembership} 表达。
 * 见架构文档第 9 节与 {@code V20260801_1110__tenant_member.sql} 的说明。
 *
 * @param id           账号 ID（UUIDv7）
 * @param email        邮箱，即登录名。全局唯一（不区分大小写）
 * @param passwordHash 带算法前缀的口令哈希，如 {@code {bcrypt}$2a$...}（ADR 0009）。
 *                     <b>任何地方都不得直接比较此字段</b>，一律走
 *                     {@code PasswordEncoder.matches()}
 * @param displayName  显示名
 * @param status       账号状态
 * @param lastLoginAt  上次登录时间，可能为 null（从未登录）
 * @param failedLoginAttempts 连续登录失败次数，登录成功清零
 * @param lockedUntil  临时锁定截止时刻，可能为 null。与 {@link Status#LOCKED} 是
 *                     两回事：那个是管理员手动锁定、需要人工解开，这个到点自动解除
 * @param emailVerifiedAt 邮箱被证实的时刻，null 表示未验证。同样与 {@code status} 分开 ——
 *                     那是管理员意图，这是客观事实（ADR 0013、迁移 V20260803_0300）
 */
public record Account(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        Status status,
        Instant lastLoginAt,
        int failedLoginAttempts,
        Instant lockedUntil,
        Instant emailVerifiedAt) {

    /** 账号状态。与迁移中的 CHECK 约束保持一致，改动要同时改迁移。 */
    public enum Status {
        /** 正常。 */
        ACTIVE,
        /** 被管理员停用。 */
        DISABLED,
        /** 因连续登录失败锁定。 */
        LOCKED
    }

    /**
     * 账号是否处于可登录状态。
     *
     * <p>放在领域对象上而不是散在服务里：这个判断会出现在登录、令牌刷新、
     * 后台校验等多处，写散了就会有某一处漏判。
     *
     * @return 可登录返回 true
     */
    public boolean canAuthenticate() {
        return status == Status.ACTIVE;
    }

    /**
     * 当前是否处于连续失败导致的临时锁定中。
     *
     * <p>不判断 {@link Status#LOCKED} —— 那是管理员手动锁定，由
     * {@link #canAuthenticate()} 覆盖。两者的解除方式完全不同，混在一个方法里
     * 会让调用方分不清「等一会儿就行」和「必须找管理员」。
     *
     * @param now 当前时刻
     * @return 仍在锁定期内返回 true
     */
    public boolean isTemporarilyLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * 邮箱是否已被证实。
     *
     * <p>它与 {@link #canAuthenticate()} 分开判断：账号状态是管理员意图，邮箱验证是
     * 用户是否真的持有该地址的事实。登录用例会在口令正确之后单独检查它，避免注册后
     * 未验证邮箱也能直接进入控制台。
     *
     * @return 已验证返回 true
     */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

}
