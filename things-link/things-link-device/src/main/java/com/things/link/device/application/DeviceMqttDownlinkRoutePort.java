package com.things.link.device.application;

import java.util.Optional;
import java.util.UUID;

/** 跨模块下行许可端口，不替代调用者的动作授权；传入实际连接设备而非子设备目标。 */
public interface DeviceMqttDownlinkRoutePort {
    /** 加入原业务事务取得项目/设备锁后复验配置；拒绝为空，数据库故障传播且不得回退裸Topic。 */
    Optional<DeviceMqttDownlinkRoute> lockCurrent(UUID tenantId, UUID projectId, UUID deviceId);
}
