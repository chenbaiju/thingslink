package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardRequiredComponent;
import com.things.link.dashboard.application.publication.DashboardRequiredResource;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * 匿名分享完整精确版本Schema包，不跟随看板current切换。
 * @param dashboardId 看板 @param dashboardVersionId 精确版本 @param dashboardVersionNumber 展示版本号
 * @param schemaVersion Schema格式 @param schemaDigestAlgorithm PG规范摘要算法 @param schemaDigest 复核后的摘要
 * @param requiredComponents 派生组件清单 @param requiredResources 派生资源清单 @param schema 完整规范Schema
 */
public record DashboardShareSchema(UUID dashboardId, UUID dashboardVersionId, long dashboardVersionNumber,
        String schemaVersion, String schemaDigestAlgorithm, String schemaDigest,
        List<DashboardRequiredComponent> requiredComponents, List<DashboardRequiredResource> requiredResources,
        JsonNode schema) {
    /** 冻结已完整校验的输出，不允许调用方裁剪后继续声称原摘要。 */
    public DashboardShareSchema {
        requiredComponents = List.copyOf(requiredComponents);
        requiredResources = List.copyOf(requiredResources);
        schema = schema.deepCopy();
    }
    /** @return 完整Schema副本 */
    @Override public JsonNode schema() { return schema.deepCopy(); }
}
