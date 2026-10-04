package com.things.link.dashboard.application.schema;

/**
 * 看板Schema原文拒绝异常。
 *
 * <p>拒绝原因保持为稳定枚举，后续保存和发布入口才能把相同输入映射为一致的业务错误，
 * 同时避免依赖Jackson异常文本。</p>
 */
public class DashboardSchemaParseException extends RuntimeException {
    /** 看板Schema被拒绝的稳定原因。 */
    private final Reason reason;
    /** 不回显不可信字段名的稳定JSON路径。 */
    private final String path;

    /**
     * 创建不携带底层异常的拒绝结果。
     *
     * @param reason 稳定拒绝原因
     * @param message 面向开发者的中文说明
     */
    public DashboardSchemaParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
        this.path = "$";
    }

    /**
     * 创建保留底层解析原因的拒绝结果。
     *
     * @param reason 稳定拒绝原因
     * @param message 面向开发者的中文说明
     * @param cause 底层解析异常
     */
    public DashboardSchemaParseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.path = "$";
    }

    /**
     * 创建携带结构化安全路径并保留底层原因的拒绝结果。
     *
     * @param reason 稳定拒绝原因
     * @param path 不回显不可信字段名的JSON路径
     * @param message 面向开发者的中文说明
     * @param cause 底层解析异常
     */
    public DashboardSchemaParseException(Reason reason, String path, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.path = java.util.Objects.requireNonNull(path, "path");
    }

    /**
     * 创建不携带底层原因的结构化拒绝，避免与旧三参构造器的null调用产生重载二义性。
     *
     * @param reason 稳定拒绝原因
     * @param path 不回显不可信字段名的JSON路径
     * @param message 面向开发者的中文说明
     * @return 结构化拒绝异常
     */
    public static DashboardSchemaParseException atPath(Reason reason, String path, String message) {
        return new DashboardSchemaParseException(reason, path, message, null);
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

    /** 看板Schema原文解析阶段的稳定拒绝原因。 */
    public enum Reason {
        /** 原文字节超过合同规定的500 KiB。 */
        RAW_TOO_LARGE,
        /** 原文字节不是严格合法的UTF-8。 */
        INVALID_UTF8,
        /** 原文以UTF-8 BOM开头。 */
        BOM_NOT_ALLOWED,
        /** JSON词法或结构不合法。 */
        INVALID_JSON,
        /** 同一对象包含解码后相同的属性名。 */
        DUPLICATE_KEY,
        /** JSON数字原始词法超过64个ASCII字节。 */
        NUMBER_TOO_LONG,
        /** 科学计数法指数不符合有界词法。 */
        INVALID_EXPONENT,
        /** 字符串或属性名包含合同禁止的Unicode值。 */
        INVALID_UNICODE,
        /** 对象和数组嵌套深度超过16。 */
        DEPTH_EXCEEDED,
        /** 根值结束后仍存在另一个JSON值。 */
        TRAILING_VALUE,
        /** Schema根值不是JSON对象。 */
        ROOT_MUST_BE_OBJECT,
        /** 根对象缺少字符串类型的schemaVersion。 */
        VERSION_REQUIRED,
        /** schemaVersion不是当前登记的合同版本。 */
        VERSION_UNSUPPORTED
    }
}
