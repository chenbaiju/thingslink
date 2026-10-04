package com.things.link.dashboard.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 只追加的不可变应用发布版本事实。
 *
 * <p>版本正文保存完整ApplicationSnapshot；版本号、发布账号和发布时间是快照之外的持久元数据。
 * snapshotDigest只描述PostgreSQL JSONB文本，不能与Dashboard Schema摘要或普通JSON序列化摘要互换。</p>
 *
 * @param id 应用版本ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 版本所属项目ID
 * @param applicationId 版本所属应用ID
 * @param versionNumber 应用内严格递增的展示版本号
 * @param sourceDraftRevision 封存时读取的草稿revision
 * @param snapshot 完整不可变ApplicationSnapshot
 * @param snapshotDigestAlgorithm 快照摘要算法
 * @param snapshotDigest 快照摘要小写十六进制值
 * @param publishedByAccountId 发布本版本的Console账号ID
 * @param publishedAt 版本封存时刻
 */
public record ApplicationVersion(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID applicationId,
        long versionNumber,
        long sourceDraftRevision,
        JsonNode snapshot,
        String snapshotDigestAlgorithm,
        String snapshotDigest,
        UUID publishedByAccountId,
        Instant publishedAt) {

    /** ADR0100冻结的应用快照摘要算法。 */
    public static final String SNAPSHOT_DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** SHA-256小写十六进制表示的精确语法。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * 冻结快照并校验版本事实的持久形状；版本号分配和摘要复算仍由后续原事务发布服务负责。
     */
    public ApplicationVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(snapshotDigestAlgorithm, "snapshotDigestAlgorithm");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(publishedByAccountId, "publishedByAccountId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        snapshot = ApplicationDraft.requireApplicationDocument(snapshot, "snapshot");
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber必须为正数");
        }
        if (sourceDraftRevision < 0) {
            throw new IllegalArgumentException("sourceDraftRevision不得为负数");
        }
        if (!SNAPSHOT_DIGEST_ALGORITHM.equals(snapshotDigestAlgorithm)) {
            throw new IllegalArgumentException("snapshotDigestAlgorithm未登记");
        }
        if (!SHA256.matcher(snapshotDigest).matches()) {
            throw new IllegalArgumentException("snapshotDigest必须是64位小写十六进制");
        }
    }

    /**
     * 返回隔离的不可变版本快照。
     *
     * @return 调用方可安全读取或修改的快照副本
     */
    @Override
    public JsonNode snapshot() {
        return snapshot.deepCopy();
    }
}
