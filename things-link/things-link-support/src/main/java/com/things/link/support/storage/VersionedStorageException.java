package com.things.link.support.storage;

/** 只暴露固定分类和可能写入事实，禁止附带供应商正文、凭据及URL。 */
public final class VersionedStorageException extends RuntimeException {
    /** 稳定序列化版本。 */
    private static final long serialVersionUID = 1L;
    /** 固定失败原因。 */
    private final Reason reason;
    /** 请求是否可能已经在服务端产生写入。 */
    private final boolean mayHaveWritten;
    /** 构造无底层异常链的安全错误。 */
    public VersionedStorageException(Reason reason, boolean mayHaveWritten) {
        super(reason.name());
        this.reason = reason;
        this.mayHaveWritten = mayHaveWritten;
    }
    /** 返回固定错误原因。 */
    public Reason reason() { return reason; }
    /** 返回写入结果是否需要上层按同一身份恢复。 */
    public boolean mayHaveWritten() { return mayHaveWritten; }
    /** 有界失败分类。 */
    public enum Reason {
        /** 配置、输入或桶资格不符。 */
        CONFIGURATION,
        /** 精确对象或版本不存在。 */
        NOT_FOUND,
        /** 请求身份不一致。 */
        CONFLICT,
        /** 长度、摘要或返回版本身份不符。 */
        INTEGRITY,
        /** 单调预算耗尽。 */
        TIMEOUT,
        /** 外部取消、线程中断或组件关闭。 */
        CANCELLED,
        /** 非写入依赖调用失败。 */
        UNAVAILABLE,
        /** 写入可能成功但未取得可信回执。 */
        RESULT_UNKNOWN
    }
}
