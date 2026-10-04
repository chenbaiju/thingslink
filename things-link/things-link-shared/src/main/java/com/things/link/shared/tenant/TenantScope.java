package com.things.link.shared.tenant;

import java.util.UUID;

/**
 * 当前请求的租户范围。
 *
 * <p>架构文档第 7 节的隔离模型是 {@code tenant -> project -> resource} 三级。
 * 本记录承载前两级，是所有数据访问的隐式过滤条件。
 *
 * <p>{@code projectId} 可以为 null：账号级接口（登录、列出我的项目、账号设置）
 * 尚未选定项目。<b>但业务数据接口必须要求它非空</b> —— 见
 * {@link TenantContext#requireProjectId()}。
 *
 * @param tenantId  租户 ID，任何业务数据访问都必须落在其范围内
 * @param projectId 项目 ID；账号级接口下为 null
 * @param accountId 当前控制台账号 ID，用于审计日志
 */
public record TenantScope(UUID tenantId, UUID projectId, UUID accountId) {

    /**
     * 紧凑构造器：租户与账号必须存在，否则这个上下文没有意义。
     */
    public TenantScope {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
    }

}
