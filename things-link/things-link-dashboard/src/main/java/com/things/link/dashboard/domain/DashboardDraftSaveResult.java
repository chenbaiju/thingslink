package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 看板草稿内容与模型关系整组CAS保存的结构化结果。
 *
 * <p>状态由持久层在目录行锁内分类。只有成功状态携带已经写入内容和整组关系的草稿；调用方不得
 * 在零行更新后另查一次当前状态并猜测失败原因。</p>
 *
 * @param status 原子保存状态
 * @param savedDraft 本次成功保存的草稿；非成功状态为空
 */
public record DashboardDraftSaveResult(Status status, Optional<DashboardDraft> savedDraft) {

    /** 强制成功状态与草稿载荷一一对应。 */
    public DashboardDraftSaveResult {
        status = Objects.requireNonNull(status, "status");
        savedDraft = Objects.requireNonNull(savedDraft, "savedDraft");
        if ((status == Status.SAVED) != savedDraft.isPresent()) {
            throw new IllegalArgumentException("只有SAVED状态可以携带保存后的草稿");
        }
    }

    /**
     * 创建带保存后完整事实的成功结果。
     *
     * @param draft 保存后的草稿及关系
     * @return 成功结果
     */
    public static DashboardDraftSaveResult saved(DashboardDraft draft) {
        return new DashboardDraftSaveResult(Status.SAVED,
                Optional.of(Objects.requireNonNull(draft, "draft")));
    }

    /** @return 活目录不存在、跨项目或已经软删除的结果 */
    public static DashboardDraftSaveResult notFound() {
        return rejected(Status.NOT_FOUND);
    }

    /** @return 当前草稿revision与预期不一致的结果 */
    public static DashboardDraftSaveResult revisionConflict() {
        return rejected(Status.REVISION_CONFLICT);
    }

    /** @return 当前草稿revision已经达到Long上限的结果 */
    public static DashboardDraftSaveResult revisionExhausted() {
        return rejected(Status.REVISION_EXHAUSTED);
    }

    /** 创建不携带持久事实的拒绝结果。 */
    private static DashboardDraftSaveResult rejected(Status status) {
        return new DashboardDraftSaveResult(status, Optional.empty());
    }

    /** 草稿CAS保存的完整且封闭状态集合。 */
    public enum Status {
        /** 草稿和关系以预期revision成功保存并递增。 */
        SAVED,
        /** 看板不存在、跨项目或已经软删除。 */
        NOT_FOUND,
        /** 看板存在，但草稿revision已经被其他写入推进。 */
        REVISION_CONFLICT,
        /** 草稿revision已经达到Long上限，不能继续递增。 */
        REVISION_EXHAUSTED
    }
}
