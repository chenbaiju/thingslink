package com.things.link.device.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 命令定义聚合仓储端口。 */
public interface DeviceCommandDefinitionRepository {
    /** @param value 新命令定义 */ void create(DeviceCommandDefinition value);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 有效命令列表 */
    List<DeviceCommandDefinition> findByDeviceType(UUID projectId, UUID deviceTypeId);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 命令 ID @return 有效命令 */
    Optional<DeviceCommandDefinition> findById(UUID projectId, UUID deviceTypeId, UUID id);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param commandKey 命令标识 @return 有效命令 */
    Optional<DeviceCommandDefinition> findByCommandKey(UUID projectId, UUID deviceTypeId, String commandKey);
    /** @param value 更新后的完整聚合 @return 是否命中 */ boolean update(DeviceCommandDefinition value);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 命令 ID @return 是否命中 */
    boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id);
}
