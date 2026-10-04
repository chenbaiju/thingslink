package com.things.link.device.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 接入层受理事实仓储端口。
 *
 * <p>调用方必须已经在事务内建立完整的项目 RLS 范围（{@code dev_access_request} 按项目隔离）；
 * 仓储不做身份解析，也不提供绕过范围的入口。</p>
 */
public interface DeviceAccessRequestRepository {

    /**
     * 按幂等键读取已受理事实。
     *
     * @param projectId 归属项目，用于 RLS 范围校验
     * @param deviceId 设备 ID
     * @param messageId 设备消息标识
     * @return 已受理事实；未受理过为空
     */
    Optional<DeviceAccessRequest> find(UUID projectId, UUID deviceId, UUID messageId);

    /**
     * 写入受理事实，同键已存在时不覆盖、不报错。
     *
     * @param request 待写入事实，{@code acceptedAt} 由数据库时钟决定
     * @return 本次写入成功时返回带数据库写入时刻的事实；同键已存在时为空，由调用方读取既有事实
     */
    Optional<DeviceAccessRequest> insertIfAbsent(DeviceAccessRequest request);

    /**
     * 限量删除保留期外的受理事实，供平台级定时清理调用。
     *
     * @param before 删除早于该时刻写入的事实
     * @param limit 单次删除上限
     * @return 实际删除行数
     */
    int deleteAcceptedBefore(Instant before, int limit);
}
