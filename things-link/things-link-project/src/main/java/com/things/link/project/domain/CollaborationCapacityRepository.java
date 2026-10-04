package com.things.link.project.domain;

import java.util.UUID;

/** 已确权项目的外部协作聚合事实；不返回其他项目/账号明细。 */
public interface CollaborationCapacityRepository {
    /** 原RC写事务内先锁目标账号，再锁项目所属租户的协作席位池。 */
    void lockCapacity(UUID tenantId, UUID accountId);

    /** 同一SQL快照读取保留成员关系；内部归属只认真实租户成员事实。 */
    Usage readUsage(UUID tenantId, UUID projectId, UUID accountId);

    /** 已有本项目关系、租户内部身份、已占租户席位、租户去重席位及账号外部项目数。 */
    record Usage(boolean alreadyMember, boolean internal, boolean occupiesSeat, long seats, long externalProjects) { }
}
