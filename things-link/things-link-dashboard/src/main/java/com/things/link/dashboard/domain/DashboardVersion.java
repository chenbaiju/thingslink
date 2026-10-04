package com.things.link.dashboard.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 只追加的不可变看板发布版本事实。
 *
 * <p>schema、派生组件、内置资源和模型关系共同解释该版本；Schema models与关系的顺序、key和
 * versionId必须逐项一致。摘要只描述PostgreSQL规范JSONB文本，且外部模型摘要/Profile仍须另行验证；
 * 本类型既映射既有历史，也承载发布服务交给受控持久入口的完整候选；自身不提供数据库写能力。</p>
 *
 * @param id 看板版本ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 版本所属项目ID
 * @param dashboardId 版本所属看板ID
 * @param versionNumber 看板内严格递增的展示版本号
 * @param sourceDraftRevision 封存时读取的草稿revision
 * @param schema 完整规范tc.dashboard/v1 Schema
 * @param schemaVersion Schema合同版本
 * @param schemaDigestAlgorithm Schema摘要算法
 * @param schemaDigest Schema摘要小写十六进制值
 * @param requiredComponents 发布时派生的精确组件集合
 * @param requiredResources 发布时派生的精确内置资源集合
 * @param publishedByAccountId 发布本版本的Console账号ID
 * @param publishedAt 版本封存时刻
 * @param modelReferences 与版本Schema共同封存的精确模型关系
 */
public record DashboardVersion(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID dashboardId,
        long versionNumber,
        long sourceDraftRevision,
        JsonNode schema,
        String schemaVersion,
        String schemaDigestAlgorithm,
        String schemaDigest,
        JsonNode requiredComponents,
        JsonNode requiredResources,
        UUID publishedByAccountId,
        Instant publishedAt,
        List<DashboardModelReference> modelReferences) {

    /** ADR0098冻结的PostgreSQL JSONB规范文本摘要算法。 */
    public static final String SCHEMA_DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** SHA-256小写十六进制表示的精确语法。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    /** 当前组件白名单的最大种类数。 */
    private static final int MAXIMUM_REQUIRED_COMPONENT_COUNT = 10;
    /** 单版本允许封存的最大内置资源数。 */
    private static final int MAXIMUM_REQUIRED_RESOURCE_COUNT = 50;

    /** 冻结所有JSON与关系，并核对Schema models与关系表投影完全一致。 */
    public DashboardVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
        Objects.requireNonNull(schemaDigest, "schemaDigest");
        Objects.requireNonNull(publishedByAccountId, "publishedByAccountId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        schema = DashboardDraft.requireDashboardDocument(schema, "schema");
        requiredComponents = requireArray(requiredComponents, "requiredComponents",
                MAXIMUM_REQUIRED_COMPONENT_COUNT);
        requiredResources = requireArray(requiredResources, "requiredResources",
                MAXIMUM_REQUIRED_RESOURCE_COUNT);
        modelReferences = DashboardModelReference.requireExactSchemaProjection(schema, modelReferences);
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber必须为正数");
        }
        if (sourceDraftRevision < 0) {
            throw new IllegalArgumentException("sourceDraftRevision不得为负数");
        }
        if (!DashboardDraft.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion未登记");
        }
        if (!schemaVersion.equals(schema.path("schemaVersion").asString())) {
            throw new IllegalArgumentException("schemaVersion必须与schema根声明一致");
        }
        if (!SCHEMA_DIGEST_ALGORITHM.equals(schemaDigestAlgorithm)) {
            throw new IllegalArgumentException("schemaDigestAlgorithm未登记");
        }
        if (!SHA256.matcher(schemaDigest).matches()) {
            throw new IllegalArgumentException("schemaDigest必须是64位小写十六进制");
        }
    }

    /** @return 与内部事实隔离的Schema副本 */
    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }

    /** @return 与内部事实隔离的组件需求副本 */
    @Override
    public JsonNode requiredComponents() {
        return requiredComponents.deepCopy();
    }

    /** @return 与内部事实隔离的资源需求副本 */
    @Override
    public JsonNode requiredResources() {
        return requiredResources.deepCopy();
    }

    /** 校验并复制数据库以JSON数组保存的有界派生集合。 */
    private static JsonNode requireArray(JsonNode value, String fieldName, int maximumSize) {
        Objects.requireNonNull(value, fieldName);
        if (!value.isArray() || value.size() > maximumSize) {
            throw new IllegalArgumentException(fieldName + "必须是最多" + maximumSize + "项的数组");
        }
        return value.deepCopy();
    }
}
