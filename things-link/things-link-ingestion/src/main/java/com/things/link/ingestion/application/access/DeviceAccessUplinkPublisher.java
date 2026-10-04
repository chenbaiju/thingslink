package com.things.link.ingestion.application.access;

import com.things.link.shared.message.StandardUplinkMessage;

/**
 * 标准上行进入消息总线的可靠接管端口。
 *
 * <p>接入面不直接依赖 Kafka，使 HTTP／TCP／CoAP 三个协议切片可以独立验证受理语义，也让 AX-5 的调试
 * 面复用同一受理边界。实现必须以 broker 确认返回为成功，未确认即抛
 * {@link DeviceAccessHandoffUnavailableException}，禁止出现「已回受理但消息未上车」。</p>
 */
public interface DeviceAccessUplinkPublisher {

    /**
     * 发布标准信封并等待总线确认。
     *
     * @param message 已完成协议标准化的上行信封
     * @throws DeviceAccessHandoffUnavailableException 总线未确认接管（含等待超时与线程中断）
     */
    void publish(StandardUplinkMessage message);
}
