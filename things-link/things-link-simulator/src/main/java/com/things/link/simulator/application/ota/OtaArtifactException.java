package com.things.link.simulator.application.ota;

/**
 * artifact 取字节过程中的可分类失败。
 *
 * <p>分类而不是只用一个 {@code IOException}，是因为状态机对不同类别的裁决完全不同：
 * 续传被拒必须保持暂停（不能从零重来），超限/长度不符是判定性失败（绝不刷写），
 * 而普通 HTTP/传输失败只是可重试抖动（有界预算内重试）。把这些语义留在异常类型里，
 * 可以避免状态机靠匹配异常消息猜原因。</p>
 */
public final class OtaArtifactException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败类别。 */
    public enum Kind {
        /** 服务器/传输明确拒绝从请求偏移续传（例如回 200 或 Content-Range 起点不符）。 */
        RESUME_REJECTED,
        /** artifact 超过允许的最大尺寸。 */
        ARTIFACT_OVERSIZED,
        /** 服务器声明的总长度与预期不一致，或无法确定总长度。 */
        ARTIFACT_SIZE_MISMATCH,
        /** 非 2xx/206 的 HTTP 状态。 */
        HTTP_STATUS,
        /** 普通传输失败（连接、读中断等），可能有界重试。 */
        TRANSPORT,
        /** 本次请求预算或整体下载预算已经耗尽。 */
        DEADLINE
    }

    /** 失败类别。 */
    private final Kind kind;

    /**
     * 创建可分类失败。
     *
     * @param kind 失败类别
     * @param message 供操作者定位的中文说明，不得包含下载地址或秘密
     */
    public OtaArtifactException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * 创建带原因的可分类失败。
     *
     * @param kind 失败类别
     * @param message 供操作者定位的中文说明，不得包含下载地址或秘密
     * @param cause 底层异常
     */
    public OtaArtifactException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * @return 失败类别
     */
    public Kind kind() {
        return kind;
    }
}
