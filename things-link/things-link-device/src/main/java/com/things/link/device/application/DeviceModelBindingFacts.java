package com.things.link.device.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 默认设备资格核验所需的当前模型绑定事实。
 *
 * @param deviceId 设备ID
 * @param projectId 所属项目ID
 * @param thingModelVersionId 当前绑定的不可变模型版本ID
 */
public record DeviceModelBindingFacts(UUID deviceId, UUID projectId, UUID thingModelVersionId) {
    /** 冻结非空且已排除软删除设备的投影。 */
    public DeviceModelBindingFacts {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(thingModelVersionId, "thingModelVersionId");
    }
}
