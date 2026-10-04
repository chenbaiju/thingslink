package com.things.link.dashboard.application.publication;

import tools.jackson.databind.JsonNode;

/** 将完整应用快照交给PostgreSQL生成权威规范文本及摘要。 */
public interface ApplicationSnapshotCanonicalizer {

    /**
     * 使用同一次数据库语句生成{@code jsonb::text}与其UTF-8 SHA-256。
     *
     * @param snapshot 已完成字段、引用和聚合清单校验的应用快照
     * @return 受64KiB独立边界保护的PostgreSQL规范结果
     */
    PostgreSqlApplicationSnapshotCanonicalForm canonicalize(JsonNode snapshot);
}
