package com.things.link.dashboard.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 统一运行计划验证器消费的设备选择。
 *
 * @param deviceId 客户端明确选择的设备ID
 * @param expectedModelVersionId 选择时确认的当前模型版本ID
 * @param propertyKeys 本次计划读取的顶层属性键
 */
public record DashboardRuntimeDeviceRequest(
        UUID deviceId, UUID expectedModelVersionId, List<String> propertyKeys) {

    /** 防御复制属性键，避免验证后被调用方换成另一组读取计划。 */
    public DashboardRuntimeDeviceRequest {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(expectedModelVersionId, "expectedModelVersionId");
        propertyKeys = List.copyOf(propertyKeys);
    }
}
