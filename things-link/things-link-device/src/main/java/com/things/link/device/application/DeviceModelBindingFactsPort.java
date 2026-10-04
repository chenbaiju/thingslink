package com.things.link.device.application;

import java.util.Optional;
import java.util.UUID;

/** 提供默认设备当前模型绑定的跨领域只读资格事实。 */
public interface DeviceModelBindingFactsPort {

    /**
     * 在可信项目范围内查询未软删除设备；RLS不可见、未绑定及不存在统一为空。
     *
     * @param projectId 已确权项目ID
     * @param deviceId 精确设备ID
     * @return 当前模型绑定事实
     */
    Optional<DeviceModelBindingFacts> find(UUID projectId, UUID deviceId);
}
