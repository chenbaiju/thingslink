package com.things.link.project.application;

import java.util.UUID;

/** 自部署设备用量端口；由拥有设备事实的模块实现。 */
public interface SelfHostedDeviceUsageReader {
    long activeDevices(UUID billingTenantId);
}
