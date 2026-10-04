package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.application.DashboardShareSchema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * ADR0101匿名精确版本的九字段Schema包，保持完整所有页面而非裁剪摘要。
 * @param dashboardId 稳定看板身份
 * @param dashboardVersionId 分享绑定的精确版本
 * @param dashboardVersionNumber 正十进制版本号字符串
 * @param schemaVersion 看板Schema格式
 * @param schemaDigestAlgorithm PG规范文本摘要算法
 * @param schemaDigest 服务端复算且匹配的摘要
 * @param requiredComponents 精确组件需求
 * @param requiredResources 精确内置资源需求
 * @param schema 未裁剪的完整Schema
 */
public record DashboardShareSchemaResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardVersionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[1-9][0-9]{0,18}$") String dashboardVersionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "tc.dashboard/v1") String schemaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "PG_JSONB_TEXT_V1_SHA256") String schemaDigestAlgorithm,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$") String schemaDigest,
        @NotNull @ArraySchema(maxItems = 10, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<ComponentResponse> requiredComponents,
        @NotNull @ArraySchema(maxItems = 50, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<ResourceResponse> requiredResources,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "object") JsonNode schema) {
    /** 持久集合与Schema都不能在HTTP编码前被调用方修改。 */
    public DashboardShareSchemaResponse {
        requiredComponents = List.copyOf(requiredComponents);
        requiredResources = List.copyOf(requiredResources);
        schema = schema.deepCopy();
    }

    /** 按闭集逐字段映射，不透传内部租户/项目/签发者等事实。 */
    public static DashboardShareSchemaResponse from(DashboardShareSchema source) {
        return new DashboardShareSchemaResponse(source.dashboardId(), source.dashboardVersionId(),
                Long.toString(source.dashboardVersionNumber()), source.schemaVersion(), source.schemaDigestAlgorithm(),
                source.schemaDigest(), source.requiredComponents().stream().map(value ->
                        new ComponentResponse(value.kind(), value.componentVersion())).toList(),
                source.requiredResources().stream().map(value ->
                        new ResourceResponse(value.resourceId(), value.digest())).toList(), source.schema());
    }

    /** 返回防御副本，保持完整Schema包不可被共享引用改写。 */
    @Override public JsonNode schema() { return schema.deepCopy(); }

    /**
     * 版本实际使用的组件需求，不泄露宿主全部登记。
     * @param kind 组件机器名
     * @param componentVersion 精确组件版本
     */
    @Schema(name = "DashboardShareSchemaComponentResponse")
    public record ComponentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String componentVersion) { }

    /**
     * 版本实际使用的内置资源，不允许任意外链。
     * @param resourceId 内置资源标识
     * @param digest 精确内容摘要
     */
    @Schema(name = "DashboardShareSchemaResourceResponse")
    public record ResourceResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resourceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$") String digest) { }
}
