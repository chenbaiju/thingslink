package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 同事务读取与CAS持久软生命周期，不删除发布物或上传对象。 */
public interface OtaFirmwareLifecycleRepository {
    /** 精确项目范围读取，锁与元数据投影来自同一行。 */
    Optional<OtaFirmwareLifecycleState> find(UUID projectId,UUID firmwareId,boolean lock);
    /** READY退役一次；期望修订不匹配不更新。 */
    boolean deprecate(UUID tenantId,UUID projectId,UUID firmwareId,long expectedRevision,String reason,UUID actorId,Instant occurredAt);
    /** READY或DEPRECATED撤销一次；保留已有退役记录。 */
    boolean revoke(UUID tenantId,UUID projectId,UUID firmwareId,long expectedRevision,String reason,UUID actorId,Instant occurredAt);
}
