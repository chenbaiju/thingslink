package com.things.link.dashboard.application.draft;

/**
 * 看板草稿保存信封与完整Schema的公开校验门面。
 *
 * <p>门面接收未经建树的UTF-8字节，以保留重复键和非法Unicode证据；返回值包含注入唯一默认值后的
 * 规范Schema及从其models数组逐项派生的外部模型核验需求。</p>
 */
public interface DashboardDraftContractValidator {

    /**
     * 校验expectedRevision和完整看板Schema。
     *
     * @param expectedRevision 调用方读取的规范十进制草稿revision
     * @param source 未经建树的tc.dashboard/v1 UTF-8原文
     * @return 与调用方隔离的规范草稿及模型需求
     * @throws DashboardDraftContractViolation revision、原文或内部Schema语义不合法
     */
    ValidatedDashboardDraft validate(String expectedRevision, byte[] source);
}
