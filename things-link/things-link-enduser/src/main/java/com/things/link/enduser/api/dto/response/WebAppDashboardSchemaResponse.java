package com.things.link.enduser.api.dto.response;

import com.things.link.dashboard.application.RuntimeDashboardSchema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 发布元数据合同§5.3的单看板Schema包闭集；内部可信租户、项目和字节计数不进入HTTP。
 * @param applicationVersionId 已复核当前应用版本
 * @param publicationRevision 当前应用正发布代次字符串
 * @param dashboardId 单目标看板目录身份
 * @param dashboardVersionId 精确看板版本身份
 * @param dashboardVersionNumber 精确看板正版本号字符串
 * @param schemaVersion 看板Schema格式
 * @param schemaDigestAlgorithm 服务端权威摘要算法
 * @param schemaDigest 服务端权威摘要
 * @param requiredComponents 此Schema实际使用的组件清单
 * @param requiredResources 此Schema实际使用的资源清单
 * @param schema 完整Schema对象，不附原规范文本副本
 */
@Schema(name = "WebAppDashboardSchemaResponse", description = "已授权单看板精确Schema包")
public record WebAppDashboardSchemaResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID applicationVersionId,
        @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$", requiredMode = Schema.RequiredMode.REQUIRED)
        String publicationRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardVersionId,
        @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$", requiredMode = Schema.RequiredMode.REQUIRED)
        String dashboardVersionNumber,
        @Schema(allowableValues = "tc.dashboard/v1", requiredMode = Schema.RequiredMode.REQUIRED) String schemaVersion,
        @Schema(allowableValues = "PG_JSONB_TEXT_V1_SHA256", requiredMode = Schema.RequiredMode.REQUIRED)
        String schemaDigestAlgorithm,
        @Schema(pattern = "^[0-9a-f]{64}$", requiredMode = Schema.RequiredMode.REQUIRED) String schemaDigest,
        @NotNull @ArraySchema(minItems = 0, maxItems = 10,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<RequiredComponentResponse> requiredComponents,
        @NotNull @ArraySchema(minItems = 0, maxItems = 50,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<RequiredResourceResponse> requiredResources,
        @Schema(type = "object", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode schema) {

    /** 固定HTTP投影，不让调用方的可变Schema或集合改变已授权包。 */
    public WebAppDashboardSchemaResponse {
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(publicationRevision, "publicationRevision");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        Objects.requireNonNull(dashboardVersionNumber, "dashboardVersionNumber");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
        Objects.requireNonNull(schemaDigest, "schemaDigest");
        requiredComponents = List.copyOf(requiredComponents);
        requiredResources = List.copyOf(requiredResources);
        schema = Objects.requireNonNull(schema, "schema").deepCopy();
        if (!schema.isObject() || requiredComponents.size() > 10 || requiredResources.size() > 50) {
            throw new IllegalStateException("Schema响应投影超过冻结结构边界");
        }
    }

    /** 从公开应用值逐字段投影，所有持久Long只经过精确十进制转换。 */
    public static WebAppDashboardSchemaResponse from(RuntimeDashboardSchema source) {
        return new WebAppDashboardSchemaResponse(source.applicationVersionId(), Long.toString(source.publicationRevision()),
                source.dashboardId(), source.dashboardVersionId(), Long.toString(source.dashboardVersionNumber()),
                source.schemaVersion(), source.schemaDigestAlgorithm(), source.schemaDigest(),
                source.requiredComponents().stream().map(item ->
                        new RequiredComponentResponse(item.kind(), item.componentVersion())).toList(),
                source.requiredResources().stream().map(item ->
                        new RequiredResourceResponse(item.resourceId(), item.digest())).toList(), source.schema());
    }

    /** @return 与内部对象隔离的Schema副本 */
    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }

    /**
     * 精确组件需求，不暴露组件宿主注册信息。
     * @param kind 组件机器值
     * @param componentVersion 精确组件版本
     */
    @Schema(name = "WebAppSchemaRequiredComponentResponse")
    public record RequiredComponentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String componentVersion) {
        /** 需求由已验Schema派生，空字段是内部合同失败。 */
        public RequiredComponentResponse {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(componentVersion, "componentVersion");
        }
    }

    /**
     * 精确资源需求，不内联资源正文或位置。
     * @param resourceId 内置资源身份
     * @param digest 资源字节摘要
     */
    @Schema(name = "WebAppSchemaRequiredResourceResponse")
    public record RequiredResourceResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resourceId,
            @Schema(pattern = "^[0-9a-f]{64}$", requiredMode = Schema.RequiredMode.REQUIRED) String digest) {
        /** 需求由已验Schema派生，空字段是内部合同失败。 */
        public RequiredResourceResponse {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(digest, "digest");
        }
    }
}
