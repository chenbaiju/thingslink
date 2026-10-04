package com.things.link.shared.tenant;

import java.util.UUID;

/**
 * 数据库 RLS 所需的隔离范围：租户 + 项目。
 *
 * <p>与 {@link TenantScope} 的区别是<b>不含账号</b>。控制台请求里账号 ID 与租户/项目
 * 天然绑定，可以塞进同一个 {@link TenantScope}；但 App 请求的行为主体是 {@code app_user_id}，
 * 它是与控制台账号完全分离的第二类身份，绝不能冒充 {@link TenantScope#accountId()} 去
 * 写审计日志（ADR 0036）。于是 RLS 所需的这一部分单独拆出来，让两类过滤器都能喂它。
 *
 * <p>{@code projectId} 可以为 null：尚未选定项目的请求就是「没有项目上下文」，此时
 * 受项目 RLS 保护的表一行也读不到（fail-closed，ADR 0012）。
 *
 * @param tenantId  租户 ID
 * @param projectId 项目 ID；可空
 */
public record RlsScope(UUID tenantId, UUID projectId) {

    /**
     * 紧凑构造器：租户必须存在，否则这个范围没有意义。
     */
    public RlsScope {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
    }

}
