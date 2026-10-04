package com.things.link.ota.domain;

import com.things.link.shared.page.CursorPage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 固件草稿持久端口，写入参与调用方现有事务。 */
public interface OtaFirmwareRepository {
    /** 串行化同一真实租户、项目、账号和键摘要的创建。 */
    void lockCreation(UUID tenantId, UUID projectId, UUID accountId, String keyDigest);
    /** 查询既有创建身份，不借公共HTTP响应缓存恢复。 */
    Optional<Creation> findCreation(UUID tenantId, UUID projectId, UUID accountId, String keyDigest);
    /** 原子追加固件与恢复映射。 */
    void create(OtaFirmware firmware, String keyDigest, String requestDigest);
    /** 按显式项目查找，取消事务可锁定同一行。 */
    Optional<OtaFirmware> find(UUID projectId, UUID firmwareId, boolean lock);
    /** 按创建时刻及UUID倒序的有界游标分页。 */
    CursorPage<OtaFirmware> page(UUID projectId, String cursor, int limit);
    /** 锁内执行草稿取消，更新一行且修订只加一次。 */
    void cancel(UUID projectId, UUID firmwareId, long revision, Instant cancelledAt);

    /**
     * 创建恢复摘要与目标身份。
     * @param firmwareId 初次生成的固件ID
     * @param requestDigest 精确请求摘要
     */
    record Creation(UUID firmwareId, String requestDigest) { }
}
