package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 受控数据库入口在应用与全部精确看板锁内得出的封闭回滚结果。
 *
 * @param status 已回滚、不可见、目标无效、发布冲突、计数耗尽或看板资格变化
 * @param publicationRevision 成功时的新发布revision；冲突或资格变化时可携带当前值
 */
public record ApplicationPublicationRollbackResult(Status status, Long publicationRevision) {

    /** 核对状态与可选revision组合，防止仓储返回缺少提交轴的伪成功。 */
    public ApplicationPublicationRollbackResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.ROLLED_BACK) {
            if (publicationRevision == null || publicationRevision <= 0) {
                throw new IllegalArgumentException("应用回滚成功必须携带正发布revision");
            }
        } else if (publicationRevision != null && publicationRevision < 0) {
            throw new IllegalArgumentException("可观察发布revision不得为负数");
        }
    }

    /** @return 成功或数据库锁内拒绝时可观察的发布revision */
    public Optional<Long> observedPublicationRevision() {
        return Optional.ofNullable(publicationRevision);
    }

    /** 受控函数允许返回的稳定分类。 */
    public enum Status {
        /** 当前指针已切到既有版本且publicationRevision推进一次。 */
        ROLLED_BACK,
        /** 应用不存在、跨项目、已软删或项目上下文不匹配。 */
        NOT_FOUND,
        /** 目标版本不存在或不属于锁内应用。 */
        TARGET_NOT_FOUND,
        /** 目标已是当前版本或publicationRevision已经变化。 */
        PUBLICATION_CONFLICT,
        /** publicationRevision已经达到Long上限。 */
        REVISION_EXHAUSTED,
        /** 目标快照的精确看板版本、顺序或聚合投影不再匹配持久事实。 */
        DASHBOARD_REFERENCE_INVALID,
        /** 目标引用的看板已撤回、软删或没有当前可运行指针。 */
        DASHBOARD_NOT_RUNNABLE
    }
}
