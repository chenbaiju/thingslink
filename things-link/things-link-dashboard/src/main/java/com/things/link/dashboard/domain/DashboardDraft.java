package com.things.link.dashboard.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一看板一行的独立可变草稿及其精确模型关系快照。
 *
 * <p>content和modelReferences必须由同一次成功CAS产生，且models缺省为空数组或逐项匹配关系投影。
 * JSON树在构造和读取时均复制，调用方不能原位改写同一revision代表的内容；关系只核对位置、key和
 * versionId，不能据此声称摘要、Profile或外部存在性已经验证。</p>
 *
 * @param dashboardId 草稿所属看板ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 草稿所属项目ID
 * @param content 完整tc.dashboard/v1草稿内容
 * @param revision 草稿保存CAS修订号
 * @param updatedBy 最近保存草稿的Console账号ID
 * @param createdAt 草稿创建时刻
 * @param updatedAt 最近保存时刻
 * @param modelReferences 与本revision内容同步派生的精确模型关系
 */
public record DashboardDraft(
        UUID dashboardId,
        UUID tenantId,
        UUID projectId,
        JsonNode content,
        long revision,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        List<DashboardModelReference> modelReferences) {

    /** ADR0098冻结的看板Schema格式版本。 */
    public static final String SCHEMA_VERSION = "tc.dashboard/v1";

    /** 冻结输入内容和关系，并核对Schema models与关系表投影完全一致。 */
    public DashboardDraft {
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        content = requireDashboardDocument(content, "content");
        modelReferences = DashboardModelReference.requireExactSchemaProjection(content, modelReferences);
        if (revision < 0) {
            throw new IllegalArgumentException("草稿revision不得为负数");
        }
    }

    /**
     * 返回与内部事实隔离的草稿JSON树。
     *
     * @return 调用方可安全读取或修改的内容副本
     */
    @Override
    public JsonNode content() {
        return content.deepCopy();
    }

    /**
     * 校验并复制看板JSON文档的持久最小形状。
     *
     * <p>512000字节上限基于PostgreSQL {@code jsonb::text}，由数据库约束作最终裁决；完整原文、
     * 结构、默认和外部引用语义由独立校验用例负责。</p>
     *
     * @param document 待冻结文档
     * @param fieldName 错误中使用的字段名
     * @return 与调用方隔离的文档副本
     */
    static JsonNode requireDashboardDocument(JsonNode document, String fieldName) {
        Objects.requireNonNull(document, fieldName);
        if (!document.isObject()
                || !document.has("schemaVersion")
                || !document.path("schemaVersion").isString()
                || !SCHEMA_VERSION.equals(document.path("schemaVersion").asString())) {
            throw new IllegalArgumentException(fieldName + "必须是tc.dashboard/v1对象");
        }
        return document.deepCopy();
    }
}
