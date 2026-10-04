package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardRequiredComponent;
import com.things.link.dashboard.application.publication.DashboardRequiredResource;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * App运行入口读取的单个精确Dashboard Schema描述。
 *
 * <p>S12-2a5a只携带当前应用上下文和一个已引用看板版本的Schema包；该值不包含其他看板、
 * 应用全局快照摘要、发布人或D-145宿主资格。Schema与集合均防御复制，调用方不能改写本次观察。</p>
 *
 * @param tenantId 应用所属租户ID
 * @param projectId 应用所属项目ID
 * @param applicationId 稳定应用ID
 * @param appKey 稳定应用公开键
 * @param applicationVersionId 当前不可变应用版本ID
 * @param publicationRevision 当前应用发布代次
 * @param dashboardId 稳定看板ID
 * @param dashboardVersionId 应用引用的精确看板版本ID
 * @param dashboardVersionNumber 精确看板版本号
 * @param schemaVersion 看板Schema格式
 * @param schemaDigestAlgorithm Schema摘要算法
 * @param schemaDigest PostgreSQL规范Schema摘要
 * @param requiredComponents 仅此Schema派生的组件清单
 * @param requiredResources 仅此Schema派生的资源清单
 * @param schema 完整规范Dashboard Schema
 * @param schemaUtf8Bytes PostgreSQL规范Schema文本的UTF-8字节数
 */
public record RuntimeDashboardSchema(
        UUID tenantId,
        UUID projectId,
        UUID applicationId,
        String appKey,
        UUID applicationVersionId,
        long publicationRevision,
        UUID dashboardId,
        UUID dashboardVersionId,
        long dashboardVersionNumber,
        String schemaVersion,
        String schemaDigestAlgorithm,
        String schemaDigest,
        List<DashboardRequiredComponent> requiredComponents,
        List<DashboardRequiredResource> requiredResources,
        JsonNode schema,
        int schemaUtf8Bytes) {

    /** 冻结Schema包并拒绝越过持久合同的数量或字节边界。 */
    public RuntimeDashboardSchema {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
        Objects.requireNonNull(schemaDigest, "schemaDigest");
        requiredComponents = List.copyOf(requiredComponents);
        requiredResources = List.copyOf(requiredResources);
        schema = Objects.requireNonNull(schema, "schema").deepCopy();
        if (publicationRevision <= 0 || dashboardVersionNumber <= 0) {
            throw new IllegalArgumentException("Schema运行上下文的发布代次和版本号必须为正数");
        }
        if (requiredComponents.size() > 10 || requiredResources.size() > 50) {
            throw new IllegalArgumentException("Schema派生需求超过冻结上限");
        }
        if (schemaUtf8Bytes < 1 || schemaUtf8Bytes > 500 * 1024) {
            throw new IllegalArgumentException("Dashboard Schema超过500KiB冻结边界");
        }
    }

    /** @return 与内部Schema隔离的深副本 */
    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }
}
