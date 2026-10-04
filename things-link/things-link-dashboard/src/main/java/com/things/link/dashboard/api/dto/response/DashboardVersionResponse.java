package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.DashboardVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console看板精确不可变版本详情响应。
 *
 * <p>响应保留权威Schema、摘要与派生组件资源清单，供管理端查看已封存内容；路径已经表达稳定
 * 看板身份，因此不重复返回dashboardId，也不暴露租户、项目、发布账号或模型关系持久投影。</p>
 *
 * @param id 看板版本ID
 * @param versionNumber 看板内展示版本号的十进制字符串
 * @param sourceDraftRevision 来源草稿revision的十进制字符串
 * @param schema 完整规范tc.dashboard/v1 Schema
 * @param schemaVersion 看板Schema合同版本
 * @param schemaDigestAlgorithm PostgreSQL规范文本摘要算法
 * @param schemaDigest Schema摘要小写十六进制值
 * @param requiredComponents 发布时派生的精确组件集合
 * @param requiredResources 发布时派生的精确内置资源集合
 * @param publishedAt 版本封存时刻
 */
public record DashboardVersionResponse(
        UUID id,
        String versionNumber,
        String sourceDraftRevision,
        @Schema(types = {"object"}, description = "完整tc.dashboard/v1不可变Schema")
        JsonNode schema,
        String schemaVersion,
        String schemaDigestAlgorithm,
        String schemaDigest,
        @Schema(types = {"array"}, description = "发布时派生的精确组件需求")
        JsonNode requiredComponents,
        @Schema(types = {"array"}, description = "发布时派生的精确内置资源需求")
        JsonNode requiredResources,
        Instant publishedAt) {

    /** 冻结三个JSON值，避免序列化前被调用方原位篡改不可变版本响应。 */
    public DashboardVersionResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(versionNumber, "versionNumber");
        Objects.requireNonNull(sourceDraftRevision, "sourceDraftRevision");
        schema = Objects.requireNonNull(schema, "schema").deepCopy();
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
        Objects.requireNonNull(schemaDigest, "schemaDigest");
        requiredComponents = Objects.requireNonNull(requiredComponents, "requiredComponents").deepCopy();
        requiredResources = Objects.requireNonNull(requiredResources, "requiredResources").deepCopy();
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    /** @return 与内部响应事实隔离的Schema副本 */
    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }

    /** @return 与内部响应事实隔离的组件需求副本 */
    @Override
    public JsonNode requiredComponents() {
        return requiredComponents.deepCopy();
    }

    /** @return 与内部响应事实隔离的资源需求副本 */
    @Override
    public JsonNode requiredResources() {
        return requiredResources.deepCopy();
    }

    /**
     * 把精确不可变版本转换为不含内部归属与操作者的HTTP响应。
     *
     * @param version 已确认属于请求路径看板的版本
     * @return 防御复制的完整版本响应
     */
    public static DashboardVersionResponse from(DashboardVersion version) {
        Objects.requireNonNull(version, "version");
        return new DashboardVersionResponse(
                version.id(),
                Long.toString(version.versionNumber()),
                Long.toString(version.sourceDraftRevision()),
                version.schema(),
                version.schemaVersion(),
                version.schemaDigestAlgorithm(),
                version.schemaDigest(),
                version.requiredComponents(),
                version.requiredResources(),
                version.publishedAt());
    }
}
