package com.things.link.device.domain;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Collection;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

/** 设备影子仓储端口。 */
public interface DeviceShadowRepository {
    /** @param projectId 项目 ID @param deviceId 设备 ID @return 影子 */
    Optional<DeviceShadow> findByDevice(UUID projectId, UUID deviceId);
    /**
     * 批量读取当前值事实投影；项目条件必须保留在 SQL 中，不能只依赖 HTTP 上下文。
     * @param projectId 项目 ID
     * @param deviceIds 设备 ID 集合
     * @return 已存在影子快照
     */
    List<DeviceShadowSnapshot> findSnapshots(UUID projectId, Collection<UUID> deviceIds);
    /** 普通RLS批量读取属性接受序号，PG失败必须传播。 */
    List<DeviceReportedRevisionSnapshot> findReportedRevisions(UUID projectId, Collection<UUID> deviceIds);
    /** 插入初态影子（设备和项目均无影子时调用）。 @param shadow 新影子 */
    boolean createIfAbsent(DeviceShadow shadow);
    /**
     * 乐观锁更新 desired。仅当 {@code version} 匹配时生效。
     * @param projectId 项目 ID @param deviceId 设备 ID @param desired 新期望值 @param version 当前版本 @return 是否命中
     */
    boolean updateDesired(UUID projectId, UUID deviceId, String desired, int version);
    /**
     * 按属性采集时间执行 reported CAS；乱序点仍进入时序库，但不得让当前影子回退。
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 属性消息键
     * @param jsonValue 属性值 JSON
     * @param occurredAt 设备采集时间
     * @param thingModelVersionId 写入时不可变物模型版本
     * @return 本次接受实际序号，拒绝时为空；耗尽报错
     */
    OptionalLong updateReportedPropertyIfNewer(UUID projectId, UUID deviceId, String propertyKey,
                                          String jsonValue, Instant occurredAt, UUID thingModelVersionId);
}
