package com.things.link.ingestion.application.access;

/**
 * 表示标准上行无法由消息总线可靠接管。
 *
 * <p>接入合同 §4.3 冻结受理的唯一判据是持久事实或消息总线已经接管：总线不可用时 HTTP 必须返回
 * 503、CoAP 返回 5.03、TCP 返回 {@code ERROR}，绝不能先回受理再尝试落库。本异常是这条判据在
 * 接入面与协议层之间的稳定信号，协议层据此映射 {@code HANDOFF_UNAVAILABLE}。</p>
 */
public class DeviceAccessHandoffUnavailableException extends RuntimeException {

    /**
     * 创建带根因的交接不可用异常。
     *
     * @param message 面向日志的稳定错误说明
     * @param cause 总线或线程中断等原始故障
     */
    public DeviceAccessHandoffUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建不需要包装根因的交接不可用异常。
     *
     * @param message 面向日志的稳定错误说明
     */
    public DeviceAccessHandoffUnavailableException(String message) {
        super(message);
    }
}
