package com.things.link.iam.application;

import java.util.UUID;

/**
 * 当前登录用户的信息。
 *
 * <p>本类型在 {@code application} 包，是 iam 模块的对外契约面。
 *
 * @param accountId   账号 ID
 * @param email       邮箱
 * @param displayName 显示名
 * @param tenantId    当前租户 ID
 */
public record CurrentUser(
        UUID accountId,
        String email,
        String displayName,
        UUID tenantId) {
}
