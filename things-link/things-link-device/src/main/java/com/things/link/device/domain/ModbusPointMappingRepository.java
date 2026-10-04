package com.things.link.device.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Modbus 点位映射仓储端口。 */
public interface ModbusPointMappingRepository {
    /** @param point 新点位 */
    void create(ModbusPointMapping point);
    /** @param projectId 项目 ID @param deviceId 网关设备 ID @return 该网关的全部点位（含草稿与已发布） */
    List<ModbusPointMapping> findByDevice(UUID projectId, UUID deviceId);
    /**
     * @param projectId 项目 ID
     * @param deviceId 网关设备 ID
     * @return 最新已发布版本的点位（供配置下发）；无已发布点时为空
     */
    List<ModbusPointMapping> findPublished(UUID projectId, UUID deviceId);
    /** @param projectId 项目 ID @param id 点位 ID @return 点位 */
    Optional<ModbusPointMapping> findById(UUID projectId, UUID id);
    /** @param point 更新后的领域对象 @return 是否命中草稿 */
    boolean update(ModbusPointMapping point);
    /** @param projectId 项目 ID @param id 点位 ID @return 是否命中草稿 */
    boolean delete(UUID projectId, UUID id);
    /**
     * 把网关的全部草稿点位发布冻结；version 取该网关现有最大版本 + 1。
     *
     * @param projectId 项目 ID
     * @param deviceId 网关设备 ID
     * @return 是否命中至少一个草稿点位
     */
    boolean publish(UUID projectId, UUID deviceId);
}
