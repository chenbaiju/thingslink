package com.things.link.device.application;

import java.util.UUID;

/** 实际接收设备的MQTT当前能力，不构造客户端身份或替代业务授权。 */
public interface DeviceMqttCapabilityPort {
    /** 调用者已持项目写许可；在同事务锁设备并独立重读配置，数据库异常必须传播。 */
    boolean lockCurrent(UUID tenantId, UUID projectId, UUID deviceId);
}
