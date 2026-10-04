package com.things.link.ingestion.application;

/**
 * 表示重放也无法恢复的设备上行协议错误。
 *
 * <p>S3-11D 将报文格式、冻结契约和 Kafka 分区键错误归入此类型，使错误处理器可直接送入
 * DLQ；若把它们与 broker 短暂故障一起重试，只会阻塞同一分区且不会改变处理结果。</p>
 */
public class InvalidUplinkMessageException extends RuntimeException {

    /**
     * 创建带根因的不可重试协议异常。
     *
     * @param message 面向日志与 DLQ 异常头的稳定错误说明
     * @param cause 原始解析或契约校验异常
     */
    public InvalidUplinkMessageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建不需要包装根因的不可重试协议异常。
     *
     * @param message 面向日志与 DLQ 异常头的稳定错误说明
     */
    public InvalidUplinkMessageException(String message) {
        super(message);
    }
}
