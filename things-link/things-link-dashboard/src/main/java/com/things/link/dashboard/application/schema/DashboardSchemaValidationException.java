package com.things.link.dashboard.application.schema;

/** 看板Schema纯结构、内部引用或规范化拒绝异常。 */
public class DashboardSchemaValidationException extends RuntimeException {
    /** 不依赖Jackson文本的稳定拒绝原因。 */
    private final Reason reason;
    /** 不回显不可信字段名的稳定JSON路径。 */
    private final String path;

    /**
     * 创建结构校验拒绝。
     *
     * @param reason 稳定拒绝原因
     * @param message 带字段路径的中文说明
     */
    public DashboardSchemaValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
        this.path = "$";
    }

    /**
     * 创建带结构化安全路径的校验拒绝，并保持既有“路径 空格 详情”消息形状。
     *
     * @param reason 稳定拒绝原因
     * @param path 不回显不可信字段名的JSON路径
     * @param detail 面向开发者的中文说明
     */
    public DashboardSchemaValidationException(Reason reason, String path, String detail) {
        super(java.util.Objects.requireNonNull(path, "path") + " " + detail);
        this.reason = reason;
        this.path = path;
    }

    /**
     * 返回稳定拒绝原因。
     *
     * @return 稳定拒绝原因
     */
    public Reason reason() {
        return reason;
    }

    /**
     * 返回不包含不可信字段原文的稳定JSON路径。
     *
     * @return 安全JSON路径
     */
    public String path() {
        return path;
    }

    /** 看板Schema纯校验阶段的稳定拒绝原因。 */
    public enum Reason {
        /** 对象出现合同未声明字段。 */
        UNKNOWN_FIELD,
        /** 任意字段显式使用JSON null。 */
        NULL_NOT_ALLOWED,
        /** 必填字段缺失。 */
        REQUIRED_FIELD_MISSING,
        /** 字段JSON类型不匹配。 */
        TYPE_MISMATCH,
        /** 字符串语法、枚举、长度或控制字符不合法。 */
        INVALID_VALUE,
        /** 数字范围、整数词法或有效位不合法。 */
        INVALID_NUMBER,
        /** 集合数量或唯一性不符合合同。 */
        INVALID_COLLECTION,
        /** 画布结构、边界或同页矩形重叠。 */
        INVALID_LAYOUT,
        /** Schema内部引用不存在或引用类型不符合声明位置。 */
        INVALID_REFERENCE,
        /** 注入默认值后的规范Schema超过500 KiB。 */
        NORMALIZED_TOO_LARGE
    }
}
