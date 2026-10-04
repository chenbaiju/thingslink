package com.things.link.device.application;

import java.util.Optional;
import java.util.UUID;

/** OTA使用的device权威类型投影；调用者先完成自己的授权，端口不假设Console管理成员。 */
@FunctionalInterface
public interface OtaDeviceTypeIdentityPort {
    /** 调用者须持事务；精确三轴共享锁保持已发布类型资格到事务结束，缺失或RLS不可见为空。 */
    Optional<OtaDeviceTypeIdentity> find(UUID tenantId, UUID projectId, UUID deviceTypeId);
}
