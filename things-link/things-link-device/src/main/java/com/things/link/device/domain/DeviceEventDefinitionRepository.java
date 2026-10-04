package com.things.link.device.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 事件定义聚合仓储端口。 */
public interface DeviceEventDefinitionRepository {
    /** @param value 新事件及参数 */ void create(DeviceEventDefinition value);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 有效事件 */
    List<DeviceEventDefinition> findByDeviceType(UUID projectId, UUID deviceTypeId);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 事件 ID @return 有效事件 */
    Optional<DeviceEventDefinition> findById(UUID projectId, UUID deviceTypeId, UUID id);
    /** @param value 更新后的完整聚合 @return 是否命中 */ boolean update(DeviceEventDefinition value);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 事件 ID @return 是否命中 */
    boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id);
}
