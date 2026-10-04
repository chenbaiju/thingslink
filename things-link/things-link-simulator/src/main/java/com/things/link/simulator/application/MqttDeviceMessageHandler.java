package com.things.link.simulator.application;

/**
 * 已认证设备收到 MQTT 下行消息时的应用层回调。
 *
 * <p>这个端口隔离 Paho 的消息类型，令模拟器可在无 Broker 的单元测试中验证 Topic、QoS 和
 * 回复语义；payload 保持原始字节，由协议处理方决定是否按 UTF-8 JSON 解析。</p>
 */
@FunctionalInterface
public interface MqttDeviceMessageHandler {

    /**
     * 处理一条匹配订阅的下行 MQTT 消息。
     *
     * @param topic Broker 投递的完整 Topic
     * @param payload 原始 MQTT payload
     */
    void onMessage(String topic, byte[] payload);
}
