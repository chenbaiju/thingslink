package com.things.link.device.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 自定义数据流仓储端口。 */
public interface DeviceDataStreamRepository {
    /** @param stream 新数据流 */ void create(DeviceDataStream stream);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 未删除数据流 */
    List<DeviceDataStream> findByDeviceType(UUID projectId, UUID deviceTypeId);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 数据流 ID @return 数据流 */
    Optional<DeviceDataStream> findById(UUID projectId, UUID deviceTypeId, UUID id);
    /** @param stream 更新状态 @return 是否命中 */ boolean update(DeviceDataStream stream);
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 数据流 ID @return 是否命中 */
    boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id);
}
