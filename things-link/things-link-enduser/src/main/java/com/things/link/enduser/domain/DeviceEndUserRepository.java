package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 仅查询账号、项目赋值与设备关系均有效的当前授权。 */
public interface DeviceEndUserRepository {
    List<DeviceEndUserItem> find(UUID tenantId, UUID projectId, UUID deviceId,
            Instant beforeTime, UUID beforeId, int fetchLimit);
}
