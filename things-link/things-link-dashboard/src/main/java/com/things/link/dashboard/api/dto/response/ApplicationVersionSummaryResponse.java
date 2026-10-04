package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.ApplicationVersionSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console应用历史版本列表的轻量响应。
 *
 * <p>版本号与来源草稿revision使用十进制字符串，避免JavaScript数值精度改变历史定位；完整快照、
 * 应用归属、发布账号和精确关系持久投影只留在详情或服务内部。</p>
 *
 * @param id 应用版本ID
 * @param versionNumber 应用内展示版本号的十进制字符串
 * @param sourceDraftRevision 来源草稿revision的十进制字符串
 * @param snapshotDigestAlgorithm PostgreSQL规范文本摘要算法
 * @param snapshotDigest 快照摘要小写十六进制值
 * @param publishedAt 版本封存时刻
 */
public record ApplicationVersionSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String versionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceDraftRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String snapshotDigestAlgorithm,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String snapshotDigest,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant publishedAt) {

    /**
     * 把项目范围内的轻量应用版本事实转换为HTTP响应。
     *
     * @param summary 已由仓储确认归属的版本摘要
     * @return 不含快照大字段和内部身份的响应
     */
    public static ApplicationVersionSummaryResponse from(ApplicationVersionSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new ApplicationVersionSummaryResponse(
                summary.id(),
                Long.toString(summary.versionNumber()),
                Long.toString(summary.sourceDraftRevision()),
                summary.snapshotDigestAlgorithm(),
                summary.snapshotDigest(),
                summary.publishedAt());
    }
}
