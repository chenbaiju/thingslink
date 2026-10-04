package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 受控数据库入口在应用及引用看板行锁内得出的封闭发布结果。
 *
 * @param status 发布、不可见、双revision冲突、计数耗尽或看板资格变化
 * @param versionNumber 成功时新分配的严格递增应用版本号
 * @param publicationRevision 成功时的新发布revision；冲突时为当前发布revision
 */
public record ApplicationPublicationAppendResult(
        Status status, Long versionNumber, Long publicationRevision) {

    /** 核对状态与可选数值组合，防止仓储返回半成功或伪冲突事实。 */
    public ApplicationPublicationAppendResult {
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

    /** @return 成功时数据库分配的应用版本号 */
    public Optional<Long> publishedVersionNumber() {
        return Optional.ofNullable(versionNumber);
    }

    /** @return 成功或发布轴冲突时数据库观察的发布revision */
    public Optional<Long> observedPublicationRevision() {
        return Optional.ofNullable(publicationRevision);
    }

    /** 受控函数允许返回的稳定分类。 */
    public enum Status {
        /** 版本、关系和指针已在当前事务写入。 */
        PUBLISHED,
        /** 应用不存在、跨项目、已软删或连接项目上下文不匹配。 */
        NOT_FOUND,
        /** 当前草稿revision与命令预期不一致。 */
        DRAFT_CONFLICT,
        /** 当前发布revision与命令预期不一致。 */
        PUBLICATION_CONFLICT,
        /** 发布revision或历史版本号达到Long上限。 */
        REVISION_EXHAUSTED,
        /** 精确看板版本、顺序或快照投影不再匹配锁内事实。 */
        DASHBOARD_REFERENCE_INVALID,
        /** 某个引用看板已撤回、软删或没有当前可运行指针。 */
        DASHBOARD_NOT_RUNNABLE
    }
}
