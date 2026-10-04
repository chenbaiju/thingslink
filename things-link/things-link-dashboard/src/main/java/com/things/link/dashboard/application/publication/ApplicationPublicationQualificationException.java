package com.things.link.dashboard.application.publication;

import java.util.Objects;

/** 应用候选的草稿、精确看板、聚合清单或宿主资格未满足时的安全内部分类。 */
public class ApplicationPublicationQualificationException extends RuntimeException {

    /** 不携带外部ID、摘要或平台能力细节的失败原因。 */
    private final Reason reason;

    /**
     * 创建安全资格拒绝。
     *
     * @param reason 稳定内部原因
     */
    public ApplicationPublicationQualificationException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    /** @return 不泄漏被引用事实的稳定内部原因 */
    public Reason reason() {
        return reason;
    }

    /** 应用候选与宿主核验的封闭失败分类。 */
    public enum Reason {
        /** 持久草稿不再满足完整应用合同。 */
        DRAFT_INVALID,
        /** 应用没有可发布的看板引用或入口。 */
        EMPTY_APPLICATION,
        /** 看板或精确版本不存在、归属错误或历史不自洽。 */
        DASHBOARD_REFERENCE_INVALID,
        /** 看板已撤回、软删除或没有当前可运行指针。 */
        DASHBOARD_NOT_RUNNABLE,
        /** 多个看板的组件或资源需求互相冲突或超过应用上限。 */
        AGGREGATE_REQUIREMENT_INVALID,
        /** 应用快照PostgreSQL规范文本超过64KiB。 */
        SNAPSHOT_TOO_LARGE,
        /** 受管宿主事实缺失或与范围、Schema、组件、资源不兼容。 */
        HOST_UNAVAILABLE
    }
}
