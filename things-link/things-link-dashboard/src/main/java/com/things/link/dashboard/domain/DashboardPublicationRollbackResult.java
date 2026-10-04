package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 受控数据库回滚入口在看板行锁内得出的封闭结果。
 *
 * @param status 已回滚、不可见、目标未变化、发布revision冲突或计数耗尽
 * @param publicationRevision 成功时的新发布revision；冲突时为当前发布revision
 */
public record DashboardPublicationRollbackResult(Status status, Long publicationRevision) {

    /** 核对状态与可选revision组合，避免仓储返回半成功事实。 */
    public DashboardPublicationRollbackResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.ROLLED_BACK) {
            if (publicationRevision == null || publicationRevision <= 0) {
                throw new IllegalArgumentException("回滚成功必须携带正发布revision");
            }
        } else if (publicationRevision != null && publicationRevision < 0) {
            throw new IllegalArgumentException("可观察发布revision不得为负数");
        }
    }

    /** @return 成功或发布轴冲突时可观察的发布revision */
    public Optional<Long> observedPublicationRevision() {
        return Optional.ofNullable(publicationRevision);
    }

    /** 受控函数允许返回的稳定分类。 */
    public enum Status {
        /** 指针已切回既有版本且发布revision推进一次。 */
        ROLLED_BACK,
        /** 看板不存在、跨项目、已软删或项目上下文不匹配。 */
        DASHBOARD_NOT_FOUND,
        /** 目标版本不存在或不属于锁内看板。 */
        TARGET_NOT_FOUND,
        /** 目标已经是当前版本，拒绝制造无意义的状态变化与审计。 */
        CURRENT_VERSION,
        /** 当前发布revision与命令预期不一致。 */
        PUBLICATION_CONFLICT,
        /** 发布revision已经达到Long上限。 */
        REVISION_EXHAUSTED
    }
}
