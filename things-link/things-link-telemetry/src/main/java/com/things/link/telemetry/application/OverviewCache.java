package com.things.link.telemetry.application;

import java.util.Optional;
import java.util.UUID;

/** 项目概要短 TTL Redis 派生层端口；PostgreSQL 始终是唯一事实源。 */
public interface OverviewCache {
    /** @param projectId 项目 ID @return 可丢失快照 */
    Optional<OverviewSnapshot> find(UUID projectId);

    /** @param projectId 项目 ID @param snapshot PostgreSQL 事实聚合结果 */
    void put(UUID projectId, OverviewSnapshot snapshot);
}
