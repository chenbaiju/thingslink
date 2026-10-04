package com.things.link.project.domain;

import java.util.UUID;

/** 自动化额度的持久原子预留端口，不能用缓存或先读后写替代。 */
public interface AutomationQuotaRepository {
    /** 发布时锁定当前策略开关，不预留次数。 */
    boolean enabled(UUID tenantId, UUID projectId);
    /** @return 数据库封闭结果，预留加入原执行事务 */
    String reserve(UUID tenantId, UUID projectId, UUID executionId);
}
