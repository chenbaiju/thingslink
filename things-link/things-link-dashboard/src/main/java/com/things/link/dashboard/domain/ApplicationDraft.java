package com.things.link.dashboard.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一应用一行的独立可变草稿事实。
 *
 * <p>草稿revision与发布状态revision相互独立。JSON树在构造和读取时均复制，调用方不能在CAS读取后
 * 原位修改共享树，从而让同一个revision代表两份内容。</p>
 *
 * @param applicationId 草稿所属应用ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 草稿所属项目ID
 * @param content 完整tc.application/v1草稿内容
 * @param revision 草稿保存CAS修订号
 * @param updatedBy 最近保存草稿的Console账号ID
 * @param createdAt 草稿创建时刻
 * @param updatedAt 最近保存时刻
 */
public record ApplicationDraft(
        UUID applicationId,
        UUID tenantId,
        UUID projectId,
        JsonNode content,
        long revision,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    /** ADR0100冻结的应用声明格式版本。 */
    public static final String FORMAT_VERSION = "tc.application/v1";
    /**
     * 冻结输入JSON并校验持久层能够表达的最小合同；完整字段语义由后续发布校验器承担。
     */
    public ApplicationDraft {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        content = requireApplicationDocument(content, "content");
        if (revision < 0) {
            throw new IllegalArgumentException("草稿revision不得为负数");
        }
    }

    /**
     * 返回隔离的草稿JSON树。
     *
     * @return 调用方可安全读取或修改的内容副本
     */
    @Override
    public JsonNode content() {
        return content.deepCopy();
    }

    /**
     * 校验并复制应用JSON文档的持久最小形状。
     *
     * <p>64KiB约束基于PostgreSQL {@code jsonb::text}，Jackson序列化不能等价模拟；因此精确字节上限由
     * 数据库约束裁决。完整字段、深度和引用语义由后续应用合同校验器承担。</p>
     *
     * @param document 待冻结文档
     * @param fieldName 错误中使用的字段名
     * @return 与调用方隔离的文档副本
     */
    static JsonNode requireApplicationDocument(JsonNode document, String fieldName) {
        Objects.requireNonNull(document, fieldName);
        if (!document.isObject()
                || !document.has("formatVersion")
                || !document.path("formatVersion").isString()
                || !FORMAT_VERSION.equals(document.path("formatVersion").asString())) {
            throw new IllegalArgumentException(fieldName + "必须是tc.application/v1对象");
        }
        return document.deepCopy();
    }
}
