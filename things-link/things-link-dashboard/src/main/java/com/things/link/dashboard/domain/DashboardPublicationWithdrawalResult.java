package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 受控数据库撤回入口在看板行锁内得出的封闭结果。
 *
 * @param status 已撤回、不可见、尚未发布、发布revision冲突或计数耗尽
 * @param previousVersionId 成功时撤回前的当前版本ID
 * @param publicationRevision 成功时的新发布revision；冲突时为当前发布revision
 */
public record DashboardPublicationWithdrawalResult(
        Status status, UUID previousVersionId, Long publicationRevision) {

    /** 核对状态与可选事实组合，避免仓储返回无法审计的半成功结果。 */
    public DashboardPublicationWithdrawalResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.WITHDRAWN) {
            Objects.requireNonNull(previousVersionId, "撤回成功必须携带原版本ID");
            if (publicationRevision == null || publicationRevision <= 0) {
                throw new IllegalArgumentException("撤回成功必须携带正发布revision");
            }
        } else if (previousVersionId != null) {
            throw new IllegalArgumentException("撤回失败不得携带原版本ID");
        } else if (publicationRevision != null && publicationRevision < 0) {
            throw new IllegalArgumentException("可观察发布revision不得为负数");
        }
    }

    /** @return 成功时撤回前的当前版本ID */
    public Optional<UUID> withdrawnVersionId() {
        return Optional.ofNullable(previousVersionId);
    }

    /** @return 成功或发布轴冲突时可观察的发布revision */
    public Optional<Long> observedPublicationRevision() {
        return Optional.ofNullable(publicationRevision);
    }

    /** 受控函数允许返回的稳定分类。 */
    public enum Status {
        /** 当前发布指针已清空且发布revision推进一次。 */
        WITHDRAWN,
        /** 看板不存在、跨项目、已软删或项目上下文不匹配。 */
        DASHBOARD_NOT_FOUND,
        /** 看板当前没有发布版本，拒绝制造无意义的状态变化与审计。 */
        NOT_PUBLISHED,
        /** 当前发布revision与命令预期不一致。 */
        PUBLICATION_CONFLICT,
        /** 发布revision已经达到Long上限。 */
        REVISION_EXHAUSTED
    }
}
