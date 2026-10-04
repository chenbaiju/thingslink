package com.things.link.dashboard.application.draft;

import java.util.Objects;

/** 应用草稿原文、封闭结构或内部引用违反冻结合同时的稳定拒绝。 */
public class ApplicationDraftContractViolation extends RuntimeException {
    /** 不依赖Jackson异常文本的拒绝原因。 */
    private final Reason reason;
    /** 不包含未知字段原文的安全JSON路径。 */
    private final String path;

    /**
     * 创建应用草稿合同拒绝。
     *
     * @param reason 稳定拒绝原因
     * @param path 安全JSON路径
     * @param message 面向开发者的中文说明
     */
    public ApplicationDraftContractViolation(Reason reason, String path, String message) {
        this(reason, path, message, null);
    }

    /**
     * 创建保留底层解析原因的应用草稿合同拒绝。
     *
     * @param reason 稳定拒绝原因
     * @param path 安全JSON路径
     * @param message 面向开发者的中文说明
     * @param cause 底层解析异常
     */
    public ApplicationDraftContractViolation(Reason reason, String path, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.path = Objects.requireNonNull(path, "path");
    }

    /** @return 稳定拒绝原因 */
    public Reason reason() {
        return reason;
    }

    /** @return 不回显未知字段名的安全JSON路径 */
    public String path() {
        return path;
    }

    /** 应用草稿合同的稳定拒绝分类。 */
    public enum Reason {
        /** content原文超过64KiB。 */
        RAW_TOO_LARGE,
        /** PostgreSQL jsonb规范文本超过64KiB。 */
        NORMALIZED_TOO_LARGE,
        /** 原文字节不是严格UTF-8。 */
        INVALID_UTF8,
        /** 原文携带UTF-8 BOM。 */
        BOM_NOT_ALLOWED,
        /** JSON词法或结构无效。 */
        INVALID_JSON,
        /** 根值不是对象。 */
        ROOT_MUST_BE_OBJECT,
        /** 根对象后仍有额外JSON值。 */
        TRAILING_VALUE,
        /** 对象或数组嵌套超过八层。 */
        DEPTH_EXCEEDED,
        /** 同一对象含解码后重复字段。 */
        DUPLICATE_KEY,
        /** 字符串或字段名含U+0000或孤立代理项。 */
        INVALID_UNICODE,
        /** 对象含合同未声明字段。 */
        UNKNOWN_FIELD,
        /** 必填字段缺失。 */
        REQUIRED_FIELD_MISSING,
        /** 不允许的位置出现null。 */
        NULL_NOT_ALLOWED,
        /** JSON类型不匹配。 */
        TYPE_MISMATCH,
        /** 标量语法或范围无效。 */
        INVALID_VALUE,
        /** 集合数量或唯一性无效。 */
        INVALID_COLLECTION,
        /** 草稿内部引用未命中。 */
        INVALID_REFERENCE
    }
}
