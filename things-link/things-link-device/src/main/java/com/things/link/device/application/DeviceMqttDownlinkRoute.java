package com.things.link.device.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 同一次业务许可冻结的实际MQTT接收设备及配置空间，提交后不得升级成新路由。 */
public record DeviceMqttDownlinkRoute(UUID tenantId, UUID projectId, UUID deviceId, long configVersion,
        String projectKey, String deviceKey) {
    /** 服务器身份与规范路径字段缺失时拒绝，不构造裸Topic替身。 */
    public DeviceMqttDownlinkRoute {
        if (tenantId == null || projectId == null || deviceId == null || configVersion < 0
                || !DeviceAccessIdentifiers.isValid(projectKey) || !DeviceAccessIdentifiers.isValid(deviceKey)) {
            throw new IllegalArgumentException("MQTT下行路由不完整");
        }
    }

    /** 只能包装此接收者的原下行Topic，防止身份与实际Topic目标错配。 */
    public String internalTopic(String originalTopic) {
        String prefix = "tc/v1/" + projectKey + "/" + deviceKey + "/down/";
        if (originalTopic == null || !originalTopic.startsWith(prefix) || originalTopic.length() == prefix.length()
                || originalTopic.indexOf('#') >= 0 || originalTopic.indexOf('+') >= 0 || originalTopic.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(originalTopic)) {
            throw new IllegalArgumentException("MQTT下行Topic与许可路由不匹配");
        }
        String routed = DeviceMqttSessionIdentity.mountpoint(deviceId, configVersion) + originalTopic;
        if (routed.getBytes(StandardCharsets.UTF_8).length > 65535) throw new IllegalArgumentException("MQTT下行Topic过长");
        return routed;
    }
}
