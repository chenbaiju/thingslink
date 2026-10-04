package com.things.link.dashboard.application.publication;

import java.util.Objects;

/**
 * 已通过当前宿主兼容检查、仍未写入任何发布事实的应用候选。
 *
 * <p>构造器保持包内可见，只有同包资格服务能产生该值；后续事务仍须在锁内重新准备和核验。</p>
 */
public final class QualifiedApplicationPublicationCandidate {

    /** 已完成草稿、精确看板和宿主资格核验的候选。 */
    private final ApplicationPublicationCandidate candidate;

    /** @param candidate 当前调用中已完成全部资格核验的候选 */
    QualifiedApplicationPublicationCandidate(ApplicationPublicationCandidate candidate) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
    }

    /** @return 防御边界完整的底层候选 */
    public ApplicationPublicationCandidate candidate() {
        return candidate;
    }
}
