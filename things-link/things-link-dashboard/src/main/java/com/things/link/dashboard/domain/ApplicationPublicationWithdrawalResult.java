package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 受控数据库入口在应用行锁内得出的封闭撤回结果。
 *
 * @param status 已撤回、不可见、尚未发布、发布revision冲突或计数耗尽
 * @param previousVersionId 成功时撤回前的当前应用版本ID
 * @param publicationRevision 成功时的新发布revision；冲突时可携带当前值
 */
public record ApplicationPublicationWithdrawalResult(
        Status status, UUID previousVersionId, Long publicationRevision) {

    /** 核对状态与可选事实组合，防止仓储返回缺少原版本或提交轴的伪成功。 */
    public ApplicationPublicationWithdrawalResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.WITHDRAWN) {
            Objects.requireNonNull(previousVersionId, "应用撤回成功必须携带原版本ID");
            if (publicationRevision == null || publicationRevision <= 0) {
                throw new IllegalArgumentException("应用撤回成功必须携带正发布revision");
            }
        } else if (previousVersionId != null) {
            throw new IllegalArgumentException("应用撤回失败不得携带原版本ID");
        } else if (publicationRevision != null && publicationRevision < 0) {
            throw new IllegalArgumentException("可观察发布revision不得为负数");
        }
    }

    /** @return 成功时撤回前的当前应用版本ID */
    public Optional<UUID> withdrawnVersionId() {
        return Optional.ofNullable(previousVersionId);
    }

    /** @return 成功或发布轴冲突时可观察的发布revision */
    public Optional<Long> observedPublicationRevision() {
        return Optional.ofNullable(publicationRevision);
    }

    /** 受控函数允许返回的稳定分类。 */
    public enum Status {
        /** 当前发布指针已清空且publicationRevision推进一次。 */
        WITHDRAWN,
        /** 应用不存在、跨项目、已软删或项目上下文不匹配。 */
        NOT_FOUND,
        /** 应用当前没有发布版本，拒绝制造无意义状态变化与审计。 */
        NOT_PUBLISHED,
        /** 当前publicationRevision与命令预期不一致。 */
        PUBLICATION_CONFLICT,
        /** publicationRevision已经达到Long上限。 */
        REVISION_EXHAUSTED
    }
}
