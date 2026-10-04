package com.things.link.alarm.application;

import java.util.UUID;

/**
 * 告警读取绑定的单设备当前模型预期，不读取该模型的其他历史存在性。
 * @param deviceId 指定设备
 * @param expectedModelVersionId 预期当前模型精确版本
 */
public record AlarmDeviceExpectation(UUID deviceId, UUID expectedModelVersionId) {
}
