package com.things.link.device.application;

import java.util.Optional;
import java.util.UUID;

/** 当前原接收关系许可；必须加入原业务事务，锁忙或数据库故障不得变成空许可。 */
public interface DeviceCommandReceiverPort {
    /** 固定原目标与连接UUID，不追随改绑网关；项目/设备/类型保护持有至外层事务结束。 */
    Optional<DeviceCommandReceiverRoute> lockCurrent(UUID tenantId, UUID projectId,
            UUID targetDeviceId, UUID connectionDeviceId);
}
