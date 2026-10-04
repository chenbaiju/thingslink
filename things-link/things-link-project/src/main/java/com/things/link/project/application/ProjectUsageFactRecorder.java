package com.things.link.project.application;

import java.time.Instant;
import java.util.UUID;

/** 为没有既有业务表的入口保存受控、幂等日用量事实。 */
public interface ProjectUsageFactRecorder {
    /**
     * 记录当前已授权项目的一次真实用量；重复 eventKey 不重复计数。
     *
     * @param ownerTenantId 由有效策略解析的项目所有者租户
     * @param projectId 当前已选项目
     * @param metric 允许的日用量指标
     * @param eventKey 调用方稳定幂等键
     * @param occurredAt 平台接受事实的 UTC 时刻
     * @return 是否通过项目/租户二元组校验
     */
    boolean record(UUID ownerTenantId, UUID projectId, QuotaMetric metric,
                   String eventKey, Instant occurredAt);
}
