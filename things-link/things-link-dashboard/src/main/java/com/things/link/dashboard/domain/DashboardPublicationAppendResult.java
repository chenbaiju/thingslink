package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 受控数据库发布入口在看板行锁内得出的封闭结果。
 *
 * @param status 发布、不可见、两条revision冲突或计数耗尽
 * @param versionNumber 成功时新分配的严格递增版本号
 * @param publicationRevision 成功时的新发布revision；冲突时为当前发布revision
 */
public record DashboardPublicationAppendResult(
        Status status,
        Long versionNumber,
        Long publicationRevision) {

    /** 核对状态与可选数值组合，避免仓储返回半成功事实。 */
    public DashboardPublicationAppendResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.PUBLISHED) {
            if (versionNumber == null || versionNumber <= 0
                    || publicationRevision == null || publicationRevision <= 0) {
                throw new IllegalArgumentException("发布成功必须携带正版本号和发布revision");
            }
        } else if (versionNumber != null) {
            throw new IllegalArgumentException("发布失败不得携带新版本号");
        } else if (publicationRevision != null && publicationRevision < 0) {
            throw new IllegalArgumentException("可观察发布revision不得为负数");
        }
    }

    /** @return 成功时的新版本号 */
    public Optional<Long> publishedVersionNumber() {
        return Optional.ofNullable(versionNumber);
    }

    /** @return 成功或发布轴冲突时可观察的发布revision */
    public Optional<Long> observedPublicationRevision() {
        return Optional.ofNullable(publicationRevision);
    }

    /** 受控函数允许返回的稳定分类。 */
    public enum Status {
        /** 版本、关系和指针已在当前事务写入。 */
        PUBLISHED,
        /** 看板不存在、跨项目、已软删或项目上下文不匹配。 */
        NOT_FOUND,
        /** 当前草稿revision与命令预期不一致。 */
        DRAFT_CONFLICT,
        /** 当前发布revision与命令预期不一致。 */
        PUBLICATION_CONFLICT,
        /** 发布revision或历史版本号已达到Long上限。 */
        REVISION_EXHAUSTED
    }
}
