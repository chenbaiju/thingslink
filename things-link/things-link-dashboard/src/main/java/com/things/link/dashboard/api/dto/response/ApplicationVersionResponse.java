package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.ApplicationVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console应用精确不可变版本详情响应。
 *
 * <p>路径已经表达应用身份，因此响应只返回版本定位、完整冻结快照及摘要，不暴露租户、项目、
 * 应用归属、发布账号或关系表内部投影。</p>
 *
 * @param id 应用版本ID
 * @param versionNumber 应用内展示版本号的十进制字符串
 * @param sourceDraftRevision 来源草稿revision的十进制字符串
 * @param snapshot 完整规范tc.application/v1不可变快照
 * @param snapshotDigestAlgorithm PostgreSQL规范文本摘要算法
 * @param snapshotDigest 快照摘要小写十六进制值
 * @param publishedAt 版本封存时刻
 */
public record ApplicationVersionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String versionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceDraftRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ApplicationSnapshotResponse snapshot,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String snapshotDigestAlgorithm,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String snapshotDigest,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant publishedAt) {

    /** 冻结全部必填响应事实；快照已经转换为只含不可变值的公开投影。 */
    public ApplicationVersionResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(versionNumber, "versionNumber");
        Objects.requireNonNull(sourceDraftRevision, "sourceDraftRevision");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(snapshotDigestAlgorithm, "snapshotDigestAlgorithm");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    /**
     * 把精确不可变应用版本转换为不含内部归属与操作者的HTTP响应。
     *
     * @param version 已确认属于请求路径应用的版本
     * @return 防御复制的完整版本响应
     */
    public static ApplicationVersionResponse from(ApplicationVersion version) {
        Objects.requireNonNull(version, "version");
        return new ApplicationVersionResponse(
                version.id(),
                Long.toString(version.versionNumber()),
                Long.toString(version.sourceDraftRevision()),
                ApplicationSnapshotResponse.from(version.snapshot()),
                version.snapshotDigestAlgorithm(),
                version.snapshotDigest(),
                version.publishedAt());
    }
}
