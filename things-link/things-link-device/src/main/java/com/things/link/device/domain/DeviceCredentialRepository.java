package com.things.link.device.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 设备凭据仓储端口。 */
public interface DeviceCredentialRepository {
    /** @param credential 新凭据 */ void create(DeviceCredential credential);
    /** @param projectId 项目 ID @param deviceId 设备 ID @return 有效凭据列表（不含明文） */
    List<DeviceCredential> findByDevice(UUID projectId, UUID deviceId);
    /** @param projectId 项目 ID @param deviceId 设备 ID @param id 凭据 ID @return 有效凭据 */
    Optional<DeviceCredential> findById(UUID projectId, UUID deviceId, UUID id);
    /** 将设备同类旧凭据标记为 deleted，新凭据创建前调用。 @param projectId 项目 ID @param deviceId 设备 ID @param authType 类型 */
    void revokeByDeviceAndType(UUID projectId, UUID deviceId, DeviceCredential.AuthType authType);

    /**
     * 凭据事实改变后递增设备安全缓存版本。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 新的正数版本
     */
    long incrementCredentialVersion(UUID projectId, UUID deviceId);
    /** @param projectId 项目 ID @param deviceId 设备 ID @param id 凭据 ID @return 是否命中 */
    boolean softDelete(UUID projectId, UUID deviceId, UUID id);
}
