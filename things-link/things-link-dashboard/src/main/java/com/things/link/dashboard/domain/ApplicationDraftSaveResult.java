package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 应用草稿CAS保存的原子分类结果。
 *
 * <p>分类与保存后的草稿来自同一条数据库语句，调用方不能再用第二次查询猜测零行更新原因。
 * 只有{@link Status#SAVED}携带本次{@code UPDATE RETURNING}产生的草稿，其余状态不携带事实。</p>
 *
 * @param status 原子保存状态
 * @param savedDraft 本次成功保存的草稿；非成功状态为空
 */
public record ApplicationDraftSaveResult(Status status, Optional<ApplicationDraft> savedDraft) {

    /** 强制成功状态与草稿载荷一一对应，避免调用方观察到含糊组合。 */
    public ApplicationDraftSaveResult {
        status = Objects.requireNonNull(status, "status");
        savedDraft = Objects.requireNonNull(savedDraft, "savedDraft");
        if ((status == Status.SAVED) != savedDraft.isPresent()) {
            throw new IllegalArgumentException("只有SAVED状态可以携带保存后的草稿");
        }
    }

    /**
     * 创建携带同次数据库返回事实的成功结果。
     *
     * @param draft 本次保存后的草稿
     * @return 成功结果
     */
    public static ApplicationDraftSaveResult saved(ApplicationDraft draft) {
        return new ApplicationDraftSaveResult(Status.SAVED, Optional.of(Objects.requireNonNull(draft, "draft")));
    }

    /** @return 活目录不存在或已经软删除的结果 */
    public static ApplicationDraftSaveResult notFound() {
        return rejected(Status.NOT_FOUND);
    }

    /** @return 当前草稿revision与调用方预期不一致的结果 */
    public static ApplicationDraftSaveResult revisionConflict() {
        return rejected(Status.REVISION_CONFLICT);
    }

    /** @return 当前草稿revision已经达到Long上限的结果 */
    public static ApplicationDraftSaveResult revisionExhausted() {
        return rejected(Status.REVISION_EXHAUSTED);
    }

    /** 创建不携带草稿事实的拒绝结果。 */
    private static ApplicationDraftSaveResult rejected(Status status) {
        return new ApplicationDraftSaveResult(status, Optional.empty());
    }

    /** 草稿CAS保存的完整且封闭状态集合。 */
    public enum Status {
        /** 草稿以预期revision成功保存并递增。 */
        SAVED,
        /** 应用不存在、跨项目或已经软删除。 */
        NOT_FOUND,
        /** 应用存在，但草稿revision已经被其他写入推进。 */
        REVISION_CONFLICT,
        /** 草稿revision已经达到Long上限，不能继续递增。 */
        REVISION_EXHAUSTED
    }
}
