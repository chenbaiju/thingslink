package com.things.link.device.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 属性定义仓储端口，所有定位条件必须包含项目和设备类型。 */
public interface DevicePropertyDefinitionRepository {
    /** @param definition 新属性定义 */ void create(DevicePropertyDefinition definition);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 有效属性列表 */
    List<DevicePropertyDefinition> findByDeviceType(UUID projectId, UUID deviceTypeId);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 属性 ID @return 有效属性 */
    Optional<DevicePropertyDefinition> findById(UUID projectId, UUID deviceTypeId, UUID id);
    /**
     * 按消息键读取有效属性；数据面校验必须复用 device 域的定义，禁止 telemetry 直接查询设备表。
     * @param projectId 项目 ID
     * @param deviceTypeId 类型 ID
     * @param propertyKey 属性消息键
     * @return 有效属性
     */
    Optional<DevicePropertyDefinition> findByPropertyKey(UUID projectId, UUID deviceTypeId, String propertyKey);
    /** @param definition 更新状态 @return 是否命中 */ boolean update(DevicePropertyDefinition definition);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 属性 ID @return 是否命中 */
    boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id);
}
