package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.DashboardVersionSummary;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console看板历史版本列表的轻量响应。
 *
 * <p>版本号与来源草稿revision按十进制字符串输出，避免JavaScript数值精度破坏展示或后续选择；
 * 完整Schema、派生清单、模型关系、租户项目及发布账号均不进入列表响应。</p>
 *
 * @param id 看板版本ID
 * @param versionNumber 看板内展示版本号的十进制字符串
 * @param sourceDraftRevision 来源草稿revision的十进制字符串
 * @param schemaVersion 看板Schema合同版本
 * @param schemaDigestAlgorithm PostgreSQL规范文本摘要算法
 * @param schemaDigest Schema摘要小写十六进制值
 * @param publishedAt 版本封存时刻
 */
public record DashboardVersionSummaryResponse(
        UUID id,
        String versionNumber,
        String sourceDraftRevision,
        String schemaVersion,
        String schemaDigestAlgorithm,
        String schemaDigest,
        Instant publishedAt) {

    /**
     * 把项目范围内的轻量版本事实转换为HTTP响应。
     *
     * @param summary 已由仓储确认归属的版本摘要
     * @return 不含大字段和内部身份的响应
     */
    public static DashboardVersionSummaryResponse from(DashboardVersionSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new DashboardVersionSummaryResponse(
                summary.id(),
                Long.toString(summary.versionNumber()),
                Long.toString(summary.sourceDraftRevision()),
                summary.schemaVersion(),
                summary.schemaDigestAlgorithm(),
                summary.schemaDigest(),
                summary.publishedAt());
    }
}
