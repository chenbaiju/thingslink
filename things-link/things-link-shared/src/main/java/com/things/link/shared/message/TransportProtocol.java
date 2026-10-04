package com.things.link.shared.message;

/**
 * 消息进入或离开平台时使用的传输协议。
 *
 * <p>该枚举属于跨模块消息契约，统一供标准信封、消息日志和查询 API 使用。它不描述设备报文的业务语义；
 * 例如 Modbus RTU 报文可经 TCP 网关进入平台，此时设备类型的报文协议与本传输协议分别为
 * {@code MODBUS_RTU_PASSTHROUGH} 和 {@code TCP}。</p>
 */
public enum TransportProtocol {
    /** MQTT 3.1.1 或 5.0。 */
    MQTT,
    /** HTTP 设备接入。 */
    HTTP,
    /** CoAP 设备接入。 */
    COAP,
    /** 自定义 TCP 长连接接入。 */
    TCP
}
