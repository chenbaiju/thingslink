package com.things.link.enduser.application;

import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行访问冻结§3.3：经App身份与READ grant过滤的当前应用投影，不是完整发布快照。
 * @param tenantId 仅供内部继续确权的可信租户，HTTP不暴露
 * @param projectId 已认证App项目
 * @param appUserId 已认证App用户
 * @param applicationId 当前稳定应用身份
 * @param appKey 当前应用公开键
 * @param displayName 当前版本展示名
 * @param publicationRevision 当前发布代次，A→B→A仍使用最新值
 * @param applicationVersionId 当前不可变应用版本
 * @param applicationVersionNumber 当前版本展示序号
 * @param applicationFormatVersion 应用格式合同版本
 * @param minimumHostVersionInclusive 最低宿主版本，包含边界
 * @param maximumHostVersionExclusive 最高宿主版本，不含边界
 * @param entryDashboardId 有权读取的原入口，无权则null而不自动选择其他入口
 * @param dashboards 保持应用原顺序的1至5个可见精确引用，不含隐藏引用
 */
public record CurrentWebAppApplication(UUID tenantId, UUID projectId, UUID appUserId,
        UUID applicationId, String appKey, String displayName, long publicationRevision,
        UUID applicationVersionId, long applicationVersionNumber, String applicationFormatVersion,
        String minimumHostVersionInclusive, String maximumHostVersionExclusive,
        UUID entryDashboardId, List<ApplicationPublishedDashboardReference> dashboards) {

    /** 冻结结果集合并拒绝内部漂移，不把空交集或重复引用序列化成成功响应。 */
    public CurrentWebAppApplication {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(appUserId, "appUserId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(applicationFormatVersion, "applicationFormatVersion");
        Objects.requireNonNull(minimumHostVersionInclusive, "minimumHostVersionInclusive");
        Objects.requireNonNull(maximumHostVersionExclusive, "maximumHostVersionExclusive");
        dashboards = List.copyOf(dashboards);
        List<UUID> ids = dashboards.stream().map(ApplicationPublishedDashboardReference::dashboardId).toList();
        if (publicationRevision < 1 || applicationVersionNumber < 1 || dashboards.isEmpty() || dashboards.size() > 5
                || new HashSet<>(ids).size() != ids.size()
                || (entryDashboardId != null && !ids.contains(entryDashboardId))) {
            throw new IllegalArgumentException("当前应用授权投影不符合冻结合同");
        }
    }
}
