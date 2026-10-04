package com.things.link.device.domain;

import java.util.List;
import java.util.UUID;

/** 设备连接仓储端口。 */
public interface DeviceConnectionRepository {
    /** @param conn 新连接记录 */ void create(DeviceConnection conn);
    /** @param projectId 项目 ID @param deviceId 设备 ID @return 连接记录，最新在前 */
    List<DeviceConnection> findByDevice(UUID projectId, UUID deviceId);
    /** @param projectId 项目 ID @param deviceId 设备 ID @return 去重后的活跃 MQTT clientId */
    List<String> findActiveMqttSessionIds(UUID projectId, UUID deviceId);
}
