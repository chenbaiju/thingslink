package com.things.link.simulator.application;

/** 创建一机一密 MQTT 连接的工厂端口。 */
public interface MqttDeviceClientFactory {

    /**
     * 建立单台模拟设备连接。
     *
     * @param brokerUri Broker TCP/SSL 地址
     * @param projectKey 项目标识
     * @param deviceKey 设备标识
     * @param accessToken 设备 Access Token
     * @return 已认证连接
     */
    MqttDeviceClient connect(String brokerUri, String projectKey, String deviceKey, String accessToken);
}
