package com.things.link.ingestion.application;

import com.things.link.shared.message.DeviceCommandDispatchFailure;

/**
 * 平台下行派发失败，携带稳定分类（D-037）。
 *
 * <p>EMQX 不可达、凭据缺失、超时或 HTTP 拒绝都属「未能把报文交给 Broker」的可重试失败；发布器把它们
 * 归一为本异常，下行消费者据此通过 telemetry application 入口报告命令状态机，且不再把该可重试异常抛回
 * Kafka，避免 Kafka 重试与命令业务重试叠加。</p>
 */
public class CommandDispatchException extends RuntimeException {

    /** 稳定派发失败分类。 */
    private final DeviceCommandDispatchFailure failure;

    /**
     * @param failure 稳定失败分类
     * @param message 不泄露密钥/报文的诊断摘要
     */
    public CommandDispatchException(DeviceCommandDispatchFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    /**
     * @param failure 稳定失败分类
     * @param message 不泄露密钥/报文的诊断摘要
     * @param cause 底层 HTTP/I/O 异常
     */
    public CommandDispatchException(DeviceCommandDispatchFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    /** @return 稳定失败分类 */
    public DeviceCommandDispatchFailure failure() {
        return failure;
    }
}
