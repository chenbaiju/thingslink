package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 受控数据库软删入口在看板行锁内得出的封闭结果。
 *
 * @param status 已软删、不可见、发布revision冲突或计数耗尽
 * @param previousVersionId 成功时软删前的当前版本ID；看板已撤回时为空
 * @param publicationRevision 成功时的新发布revision；冲突时为当前发布revision
 * @param deletedAt 成功时数据库写入的软删除时刻
 */
public record DashboardSoftDeleteResult(
        Status status,
        UUID previousVersionId,
        Long publicationRevision,
        Instant deletedAt) {

    /** 核对状态与可选事实组合，避免仓储返回无法审计的半成功结果。 */
    public DashboardSoftDeleteResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.DELETED) {
            if (publicationRevision == null || publicationRevision <= 0) {
                throw new IllegalArgumentException("软删成功必须携带正发布revision");
            }
            Objects.requireNonNull(deletedAt, "软删成功必须携带删除时刻");
        } else if (previousVersionId != null || deletedAt != null) {
            throw new IllegalArgumentException("软删失败不得携带原版本或删除时刻");
        } else if (publicationRevision != null && publicationRevision < 0) {
            throw new IllegalArgumentException("可观察发布revision不得为负数");
        }
    }

    /** @return 成功时软删前的当前版本ID；软删已撤回看板时为空 */
    public Optional<UUID> deletedPreviousVersionId() {
        return Optional.ofNullable(previousVersionId);
    }

    /** @return 成功或发布轴冲突时可观察的发布revision */
    public Optional<Long> observedPublicationRevision() {
        return Optional.ofNullable(publicationRevision);
    }

    /** @return 成功时数据库实际写入的软删除时刻 */
    public Optional<Instant> observedDeletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    /** 受控函数允许返回的稳定分类。 */
    public enum Status {
        /** 看板已软删、当前指针已清空且发布revision推进一次。 */
        DELETED,
        /** 看板不存在、跨项目、已软删或项目上下文不匹配。 */
        DASHBOARD_NOT_FOUND,
        /** 当前发布revision与命令预期不一致。 */
        PUBLICATION_CONFLICT,
        /** 发布revision已经达到Long上限。 */
        REVISION_EXHAUSTED
    }
}
