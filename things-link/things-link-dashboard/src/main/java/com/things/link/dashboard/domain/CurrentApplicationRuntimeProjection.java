package com.things.link.dashboard.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 单次普通RLS查询恢复的当前应用运行投影。
 *
 * <p>该值只在dashboard域内传递已经完成摘要、快照与关系复核的事实；可运行看板集合允许为空，
 * 因为看板撤回或软删只影响运行资格，不改写应用不可变版本及其入口身份。</p>
 *
 * @param tenantId 应用所属租户ID
 * @param projectId 应用所属项目ID
 * @param applicationId 稳定应用ID
 * @param appKey 不可变公开定位符
 * @param displayName 当前版本公开展示名称
 * @param publicationRevision 当前发布代次
 * @param applicationVersionId 当前应用版本ID
 * @param applicationVersionNumber 当前应用版本号
 * @param applicationFormatVersion 应用快照格式
 * @param minimumHostVersionInclusive 宿主范围下界
 * @param maximumHostVersionExclusive 宿主范围上界
 * @param entryDashboardId 快照入口看板ID
 * @param dashboards 当前仍可运行的精确看板版本引用
 */
public record CurrentApplicationRuntimeProjection(
        UUID tenantId,
        UUID projectId,
        UUID applicationId,
        String appKey,
        String displayName,
        long publicationRevision,
        UUID applicationVersionId,
        long applicationVersionNumber,
        String applicationFormatVersion,
        String minimumHostVersionInclusive,
        String maximumHostVersionExclusive,
        UUID entryDashboardId,
        List<DashboardReference> dashboards) {

    /** 防御复制JDBC适配器恢复的有界集合。 */
    public CurrentApplicationRuntimeProjection {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(applicationFormatVersion, "applicationFormatVersion");
        Objects.requireNonNull(minimumHostVersionInclusive, "minimumHostVersionInclusive");
        Objects.requireNonNull(maximumHostVersionExclusive, "maximumHostVersionExclusive");
        Objects.requireNonNull(entryDashboardId, "entryDashboardId");
        dashboards = List.copyOf(dashboards);
    }

    /**
     * 已复核且当前目录仍可运行的精确看板版本投影。
     *
     * @param dashboardId 稳定看板ID
     * @param dashboardVersionId 应用引用的精确不可变版本ID
     * @param dashboardVersionNumber 精确版本号
     * @param title 应用导航标题
     * @param schemaVersion 看板Schema格式
     * @param schemaDigestAlgorithm Schema摘要算法
     * @param schemaDigest Schema摘要
     * @param pages 页面导航摘要
     */
    public record DashboardReference(
            UUID dashboardId,
            UUID dashboardVersionId,
            long dashboardVersionNumber,
            String title,
            String schemaVersion,
            String schemaDigestAlgorithm,
            String schemaDigest,
            List<Page> pages) {

        /** 冻结精确版本元数据与页面顺序。 */
        public DashboardReference {
            Objects.requireNonNull(dashboardId, "dashboardId");
            Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
            Objects.requireNonNull(schemaDigest, "schemaDigest");
            pages = List.copyOf(pages);
        }
    }

    /**
     * 看板Schema中的页面导航摘要。
     *
     * @param id 页面稳定键
     * @param title 页面展示标题
     */
    public record Page(String id, String title) {

        /** 页面正文已在快照解析时复核，本值继续拒绝null。 */
        public Page {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }
    }
}
