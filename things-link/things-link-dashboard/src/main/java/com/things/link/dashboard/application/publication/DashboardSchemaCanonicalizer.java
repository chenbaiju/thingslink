package com.things.link.dashboard.application.publication;

import tools.jackson.databind.JsonNode;

/**
 * 将已完成内部语义校验的看板Schema交给PostgreSQL生成权威规范表示。
 *
 * <p>摘要合同明确绑定{@code jsonb::text}，因此应用层不得用Jackson重新序列化结果冒充数据库表示。</p>
 */
public interface DashboardSchemaCanonicalizer {

    /**
     * 生成数据库权威文本及其同源SHA-256摘要。
     *
     * @param normalizedSchema 已注入合同默认值的完整Schema
     * @return 同一次数据库语句生成的规范表示
     */
    PostgreSqlDashboardSchemaCanonicalForm canonicalize(JsonNode normalizedSchema);
}
