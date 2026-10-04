package com.things.link.device.domain;

import java.util.Optional;
import java.util.UUID;

/** 当前坐标持久化端口；沿设备项目RLS和软删除边界。 */
public interface DeviceLocationRepository {
    Optional<DeviceLocationPoint> find(UUID projectId, UUID deviceId);
    boolean update(UUID projectId, UUID deviceId, Double longitude, Double latitude, long version);
}
