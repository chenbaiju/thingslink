package com.things.link.device.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 调用方先授权并锁ACTIVE项目，目标锁仅冻结排程范围，不能代替设备派发资格。 */
public interface OtaDeviceTargetSnapshotPort {
    /** MANDATORY事务内按类型、规范UUID顺序持共享锁；缺失任一目标返回空，不部分成功。 */
    Optional<List<OtaDeviceTargetSnapshot>> lockExplicit(UUID tenantId, UUID projectId,
            UUID deviceTypeId, List<UUID> deviceIds);
}
