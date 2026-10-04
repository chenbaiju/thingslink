package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;

import java.util.UUID;

/**
 * 终端用户的跨模块投影。刻意不含 {@code passwordHash} —— 口令哈希只在写入时出现，
 * 任何返回值都不该把它带出去。
 *
 * @param id          终端用户 ID
 * @param tenantId    归属租户
 * @param username    租户内用户名（已规范化）
 * @param displayName 显示名称
 * @param status      租户级登录状态
 */
public record AppUserReference(
        UUID id,
        UUID tenantId,
        String username,
        String displayName,
        AppUser.Status status) {

    /** 从领域对象投影，剥离敏感字段。 */
    public static AppUserReference of(AppUser user) {
        return new AppUserReference(
                user.id(), user.tenantId(), user.username(), user.displayName(), user.status());
    }
}
