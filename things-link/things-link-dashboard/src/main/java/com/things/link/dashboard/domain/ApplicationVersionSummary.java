package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 应用不可变版本列表使用的轻量元数据。
 *
 * <p>S12-1e2b禁止版本列表批量装载最大64KiB的ApplicationSnapshot；本投影只保留定位版本、
 * 展示发布顺序及核对权威摘要所需的固定大小字段。租户与项目身份留在领域层复核RLS结果，
 * 不进入HTTP响应。</p>
 *
 * @param id 应用版本ID
 * @param tenantId 版本所属租户ID
 * @param projectId 版本所属项目ID
 * @param applicationId 版本所属应用ID
 * @param versionNumber 应用内严格递增的展示版本号
 * @param sourceDraftRevision 封存版本的草稿revision
 * @param snapshotDigestAlgorithm PostgreSQL规范文本摘要算法
 * @param snapshotDigest 应用快照摘要小写十六进制值
 * @param publishedAt 版本封存时刻
 */
public record ApplicationVersionSummary(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID applicationId,
        long versionNumber,
        long sourceDraftRevision,
        String snapshotDigestAlgorithm,
        String snapshotDigest,
        Instant publishedAt) {

    /** SHA-256小写十六进制表示的精确语法。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** 拒绝把损坏的持久元数据投影成可公开历史。 */
    public ApplicationVersionSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(snapshotDigestAlgorithm, "snapshotDigestAlgorithm");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber必须为正数");
        }
        if (sourceDraftRevision < 0) {
            throw new IllegalArgumentException("sourceDraftRevision不得为负数");
        }
        if (!ApplicationVersion.SNAPSHOT_DIGEST_ALGORITHM.equals(snapshotDigestAlgorithm)) {
            throw new IllegalArgumentException("snapshotDigestAlgorithm未登记");
        }
        if (!SHA256.matcher(snapshotDigest).matches()) {
            throw new IllegalArgumentException("snapshotDigest必须是64位小写十六进制");
        }
    }
}
