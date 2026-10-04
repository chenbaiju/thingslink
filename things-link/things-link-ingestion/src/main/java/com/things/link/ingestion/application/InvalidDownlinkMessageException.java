package com.things.link.ingestion.application;

/**
 * 下行 Kafka 信封或协议编码输入违反冻结契约。
 *
 * <p>这类错误重放不会恢复，应由 Kafka 公共错误处理器直接送入 DLQ，避免持续阻塞实际连接设备所在分区。</p>
 */
public class InvalidDownlinkMessageException extends RuntimeException {

    /**
     * @param message 不含业务载荷和凭据的稳定诊断
     */
    public InvalidDownlinkMessageException(String message) {
        super(message);
    }

    /**
     * @param message 不含业务载荷和凭据的稳定诊断
     * @param cause 原始解析异常
     */
    public InvalidDownlinkMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
