package com.things.link.dashboard.application.draft;

/**
 * 应用草稿保存合同的原文字节校验端口。
 *
 * <p>expectedRevision由保存信封单独传入，source只包含content的原始UTF-8 JSON字节；这样64KiB上限
 * 精确约束草稿正文，不把HTTP信封序列化开销混入业务限制。</p>
 */
public interface ApplicationDraftContractValidator {

    /**
     * 校验草稿revision与完整内容，并返回与调用方隔离的结构化结果。
     *
     * @param expectedRevision 调用方读取的十进制字符串revision
     * @param source content字段未经建树的UTF-8 JSON原文
     * @return 已通过S12-0b3a合同的草稿保存事实
     * @throws ApplicationDraftContractViolation 原文或业务结构不符合冻结合同时抛出
     */
    ValidatedApplicationDraft validate(String expectedRevision, byte[] source);
}
