package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 看板不可变版本列表使用的轻量元数据。
 *
 * <p>S12-1e1a禁止版本列表批量装载最高500KiB的Schema、派生清单和模型关系；本投影只保留
 * 定位版本、展示发布顺序及核对权威摘要所需的固定大小字段。租户与项目身份留在领域层用于
 * 复核RLS结果，不进入HTTP响应。</p>
 *
 * @param id 看板版本ID
 * @param tenantId 版本所属租户ID
 * @param projectId 版本所属项目ID
 * @param dashboardId 版本所属看板ID
 * @param versionNumber 看板内严格递增的展示版本号
 * @param sourceDraftRevision 封存版本的草稿revision
 * @param schemaVersion 看板Schema合同版本
 * @param schemaDigestAlgorithm PostgreSQL规范文本摘要算法
 * @param schemaDigest Schema摘要小写十六进制值
 * @param publishedAt 版本封存时刻
 */
public record DashboardVersionSummary(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID dashboardId,
        long versionNumber,
        long sourceDraftRevision,
        String schemaVersion,
        String schemaDigestAlgorithm,
        String schemaDigest,
        Instant publishedAt) {

    /** SHA-256小写十六进制表示的精确语法。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** 拒绝把损坏的持久元数据投影成可公开历史。 */
    public DashboardVersionSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
        Objects.requireNonNull(schemaDigest, "schemaDigest");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber必须为正数");
        }
        if (sourceDraftRevision < 0) {
            throw new IllegalArgumentException("sourceDraftRevision不得为负数");
        }
        if (!DashboardDraft.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion未登记");
        }
        if (!DashboardVersion.SCHEMA_DIGEST_ALGORITHM.equals(schemaDigestAlgorithm)) {
            throw new IllegalArgumentException("schemaDigestAlgorithm未登记");
        }
        if (!SHA256.matcher(schemaDigest).matches()) {
            throw new IllegalArgumentException("schemaDigest必须是64位小写十六进制");
        }
    }
}
