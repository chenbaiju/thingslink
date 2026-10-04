package com.things.link.dashboard.application.draft;

import java.util.Objects;

/** 看板草稿revision、原文或内部Schema语义违反冻结合同时的安全拒绝。 */
public class DashboardDraftContractViolation extends RuntimeException {

    /** 不依赖Jackson异常文本的稳定原因编码。 */
    private final String reasonCode;
    /** 不回显未知字段原文的安全JSON路径。 */
    private final String path;

    /**
     * 创建不携带底层解析文本的草稿合同拒绝。
     *
     * @param reasonCode 稳定原因编码
     * @param path 安全JSON路径
     * @param message 面向开发者的固定中文说明
     */
    public DashboardDraftContractViolation(String reasonCode, String path, String message) {
        this(reasonCode, path, message, null);
    }

    /**
     * 创建保留异常链但不要求调用方解析异常消息的草稿合同拒绝。
     *
     * @param reasonCode 稳定原因编码
     * @param path 安全JSON路径
     * @param message 面向开发者的固定中文说明
     * @param cause 原始结构化校验异常
     */
    public DashboardDraftContractViolation(
            String reasonCode, String path, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.path = Objects.requireNonNull(path, "path");
    }

    /** @return 稳定原因编码 */
    public String reasonCode() {
        return reasonCode;
    }

    /** @return 不包含未知字段原文的安全JSON路径 */
    public String path() {
        return path;
    }
}
