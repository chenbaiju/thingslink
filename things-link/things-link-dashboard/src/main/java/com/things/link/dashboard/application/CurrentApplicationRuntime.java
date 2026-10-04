package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * App运行读取所需的当前应用封闭描述。
 *
 * <p>S12-2a4a只公开当前不可变应用版本的导航事实；完整快照、摘要、组件与资源需求留在
 * dashboard域内。看板列表已经按同次数据库观察过滤软删或无当前发布指针的目录，因此允许为空；
 * {@code entryDashboardId}仍保留应用快照原值，由enduser在授权交集后决定是否可作为入口。</p>
 *
 * @param tenantId 应用所属租户ID
 * @param projectId 应用所属项目ID
 * @param applicationId 稳定应用ID
 * @param appKey 创建后不可变的公开定位符
 * @param displayName 当前不可变应用版本中的公开展示名称
 * @param publicationRevision 当前应用发布代次
 * @param applicationVersionId 当前不可变应用版本ID
 * @param applicationVersionNumber 当前应用版本号
 * @param applicationFormatVersion 应用快照合同版本
 * @param minimumHostVersionInclusive 宿主兼容范围下界
 * @param maximumHostVersionExclusive 宿主兼容范围上界
 * @param entryDashboardId 应用快照中的入口看板ID
 * @param dashboards 同次观察中仍可运行的精确看板版本引用
 */
public record CurrentApplicationRuntime(
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
        List<ApplicationPublishedDashboardReference> dashboards) {

    /** ADR0096冻结的公开定位符语法。 */
    private static final Pattern APP_KEY = Pattern.compile("^app_[0-9a-f]{32}$");

    /** 冻结身份和有界引用，并拒绝持久适配器制造无法表达的运行描述。 */
    public CurrentApplicationRuntime {
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
        if (!APP_KEY.matcher(appKey).matches()) {
            throw new IllegalArgumentException("持久应用appKey不符合冻结语法");
        }
        if (publicationRevision <= 0 || applicationVersionNumber <= 0) {
            throw new IllegalArgumentException("当前应用发布代次和版本号必须为正数");
        }
        if (!"tc.application/v1".equals(applicationFormatVersion)) {
            throw new IllegalArgumentException("当前应用格式未登记");
        }
        if (dashboards.size() > 5) {
            throw new IllegalArgumentException("当前应用可运行看板超过冻结上限");
        }
        Set<UUID> dashboardIds = new HashSet<>();
        for (ApplicationPublishedDashboardReference dashboard : dashboards) {
            if (!dashboardIds.add(dashboard.dashboardId())) {
                throw new IllegalArgumentException("当前应用可运行看板ID重复");
            }
        }
    }
}
