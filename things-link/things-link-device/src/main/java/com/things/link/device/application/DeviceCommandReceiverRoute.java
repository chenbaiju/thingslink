package com.things.link.device.application;

import java.util.UUID;

/** ADR0196：原目标及原接收者关系的当前持锁快照，不携带新物模型或改投身份。 */
public record DeviceCommandReceiverRoute(UUID tenantId, UUID projectId, String projectKey,
        UUID targetDeviceId, String targetDeviceKey, UUID connectionDeviceId, String connectionDeviceKey) {
    /** 只接受完整服务器身份，禁止后续用空值回退成新路由。 */
    public DeviceCommandReceiverRoute {
        if (tenantId == null || projectId == null || targetDeviceId == null || connectionDeviceId == null
                || !DeviceAccessIdentifiers.isValid(projectKey) || !DeviceAccessIdentifiers.isValid(targetDeviceKey)
                || !DeviceAccessIdentifiers.isValid(connectionDeviceKey)) {
            throw new IllegalArgumentException("原命令接收关系不完整");
        }
    }
}
