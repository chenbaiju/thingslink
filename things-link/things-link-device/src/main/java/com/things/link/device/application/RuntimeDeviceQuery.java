package com.things.link.device.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一台设备的运行数据请求。
 *
 * @param deviceId 请求设备ID
 * @param expectedModelVersionId 看板声明并由调用方绑定的精确物模型版本
 * @param propertyKeys 按响应顺序请求的顶层属性键；仅检查设备身份时可为空
 */
public record RuntimeDeviceQuery(
        UUID deviceId,
        UUID expectedModelVersionId,
        List<String> propertyKeys) {

    /** 冻结跨模块请求，避免调用方在鉴权后改写设备或属性集合。 */
    public RuntimeDeviceQuery {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(expectedModelVersionId, "expectedModelVersionId");
        propertyKeys = List.copyOf(Objects.requireNonNull(propertyKeys, "propertyKeys"));
    }
}
