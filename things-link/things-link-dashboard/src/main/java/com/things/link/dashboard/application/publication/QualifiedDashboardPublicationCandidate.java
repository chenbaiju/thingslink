package com.things.link.dashboard.application.publication;

import java.util.Objects;

/**
 * 已逐项通过全部外部资格核验、仍未写入发布事实的看板候选。
 *
 * <p>构造器保持包内可见，只有同包资格编排器能产生该证明；该类型不表示版本、指针或审计已经写入。</p>
 */
public final class QualifiedDashboardPublicationCandidate {

    /** 已完成内部校验、PG摘要和全部外部资格核验的不可变候选。 */
    private final DashboardPublicationCandidate candidate;

    /** @param candidate 已逐项核验的候选 */
    QualifiedDashboardPublicationCandidate(DashboardPublicationCandidate candidate) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
    }

    /** @return 防御边界完整的底层候选 */
    public DashboardPublicationCandidate candidate() {
        return candidate;
    }
}
