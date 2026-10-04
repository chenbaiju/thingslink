package com.things.link.support.scheduling;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** 跨实例租户工作槽端口；数据库连接只在短领取/释放函数内持有。 */
public interface TenantWorkSlotRepository {

    /** @return 成功竞争固定工作槽时的随机租约，否则为空 */
    Optional<Lease> tryAcquire(WorkType workType, UUID tenantId, Duration duration);

    /** @return token 匹配时是否释放成功 */
    boolean release(Lease lease);

    /** 原事务末尾锁定并复验槽，旧持有者不得提交副作用。 */
    boolean fence(Lease lease);

    /** 当前允许参与集群互斥的固定工作类型。 */
    enum WorkType {
        /** 告警与规则共享的外部通知投递。 */
        NOTIFICATION,
        /** 自动化数据库求值与动作受理。 */
        AUTOMATION
    }

    /** @param workType 固定工作类型 @param tenantId 租户 @param token 持有者令牌 */
    record Lease(WorkType workType, UUID tenantId, UUID token) {
    }
}
