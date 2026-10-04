package com.things.link.dashboard.application.publication;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 应用发布快照中一个精确且不可变的看板导航引用。
 *
 * <p>S12-0b3a要求应用固定看板版本身份、展示摘要和页面导航，但不内联完整Schema。
 * 看板管理名称和当前发布指针均不属于本值，后续看板独立发布不会改写既有应用候选。</p>
 *
 * @param dashboardId 稳定看板ID
 * @param dashboardVersionId 精确不可变看板版本ID
 * @param dashboardVersionNumber 看板内展示版本号
 * @param title 应用内导航标题
 * @param schemaVersion 看板Schema合同版本
 * @param schemaDigestAlgorithm 看板Schema摘要算法
 * @param schemaDigest 看板Schema权威摘要
 * @param pages 看板页面的有序导航摘要
 */
public record ApplicationPublishedDashboardReference(
        UUID dashboardId,
        UUID dashboardVersionId,
        long dashboardVersionNumber,
        String title,
        String schemaVersion,
        String schemaDigestAlgorithm,
        String schemaDigest,
        List<Page> pages) {

    /** SHA-256小写十六进制表示的精确语法。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** 冻结导航快照并拒绝数据库无法表达的版本或页面边界。 */
    public ApplicationPublishedDashboardReference {
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
        Objects.requireNonNull(schemaDigest, "schemaDigest");
        pages = List.copyOf(pages);
        if (dashboardVersionNumber <= 0) {
            throw new IllegalArgumentException("dashboardVersionNumber必须为正数");
        }
        if (!"tc.dashboard/v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion未登记");
        }
        if (!PostgreSqlDashboardSchemaCanonicalForm.DIGEST_ALGORITHM.equals(schemaDigestAlgorithm)
                || !SHA256.matcher(schemaDigest).matches()) {
            throw new IllegalArgumentException("看板Schema摘要不符合冻结合同");
        }
        if (pages.isEmpty() || pages.size() > 5) {
            throw new IllegalArgumentException("应用引用的看板必须包含1至5个页面");
        }
    }

    /**
     * 应用快照中不携带组件、变量或设备事实的页面导航摘要。
     *
     * @param id 看板Schema内的稳定页面键
     * @param title 页面展示标题
     */
    public record Page(String id, String title) {

        /** 页面字段已由完整看板Schema证明语法，本值只冻结非空事实。 */
        public Page {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }
    }
}
