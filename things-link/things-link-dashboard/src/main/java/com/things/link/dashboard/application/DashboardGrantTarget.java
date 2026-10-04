package com.things.link.dashboard.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 已在调用方事务内锁定的看板授权目标身份。
 *
 * <p>S12-2a3a2 只证明完整租户、项目与看板目录身份仍存在且未软删；该值不表示看板已发布、
 * 当前可运行或调用者拥有授权管理权限。调用方必须先完成项目 ACTIVE 许可并取得用户锁。</p>
 *
 * @param tenantId 看板所属租户ID
 * @param projectId 看板所属项目ID
 * @param dashboardId 稳定看板目录ID
 */
public record DashboardGrantTarget(UUID tenantId, UUID projectId, UUID dashboardId) {

    /** 授权锁不能接受残缺身份，否则会把空轴降格成目标不存在。 */
    public DashboardGrantTarget {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
    }
}
