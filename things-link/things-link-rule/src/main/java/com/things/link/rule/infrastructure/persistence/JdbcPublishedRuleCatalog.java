package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.application.queue.PublishedRuleAction;
import com.things.link.rule.application.queue.PublishedRuleCatalog;
import com.things.link.rule.application.queue.PublishedRulePlan;
import com.things.link.rule.application.queue.PublishedRuleStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 通过最小 {@code SECURITY DEFINER} 投影读取生产规则版本。
 *
 * <p>数据库函数只返回执行所需源码、动作与身份列，并在数据库内核对 owner tenant、项目、规则和版本归属；
 * 后台线程不设置请求 RLS 范围，也不获得规则表的跨项目通用读取能力。</p>
 */
@Repository
public class JdbcPublishedRuleCatalog implements PublishedRuleCatalog {

    /** 规则域 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** 动作 jsonb 与生产计划动作之间的映射器。 */
    private final ObjectMapper objectMapper;

    /** @param jdbcTemplate 已配置应用角色的数据源访问器 @param objectMapper JSON 映射器 */
    public JdbcPublishedRuleCatalog(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public PublishedRulePlan resolve(UUID tenantId, UUID projectId, UUID messageId) {
        return new PublishedRulePlan(jdbcTemplate.query(
                "SELECT * FROM rule_execution_plan_resolve(?, ?, ?)",
                this::mapStep, tenantId, projectId, messageId));
    }

    /** @param resultSet 受限函数返回行 @param row 行号 @return 有序计划中的不可变脚本与动作步骤 */
    private PublishedRuleStep mapStep(ResultSet resultSet, int row) throws SQLException {
        return new PublishedRuleStep(
                resultSet.getObject("rule_id", UUID.class),
                resultSet.getObject("version_id", UUID.class),
                resultSet.getLong("version_number"),
                resultSet.getString("source"),
                createdBy(resultSet),
                readActions(resultSet.getString("actions")));
    }

    /** 旧单元夹具没有 created_by 列值时以 ruleId 兼容；生产 V0290 始终返回真实账号。 */
    private static UUID createdBy(ResultSet resultSet) throws SQLException {
        UUID value = resultSet.getObject("created_by", UUID.class);
        return value == null ? resultSet.getObject("rule_id", UUID.class) : value;
    }

    /** @param json actions 列原始 JSON @return 冻结的动作步骤集合 */
    private List<PublishedRuleAction> readActions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode array = objectMapper.readTree(json);
        if (!array.isArray()) {
            throw new IllegalArgumentException("规则版本 actions 必须是 JSON 数组");
        }
        List<PublishedRuleAction> actions = new ArrayList<>();
        for (JsonNode element : array) {
            String nodeType = element.path("nodeType").asText(null);
            JsonNode config = element.get("config");
            if (nodeType == null || nodeType.isBlank() || config == null) {
                throw new IllegalArgumentException("规则版本 actions 元素缺少 nodeType 或 config");
            }
            actions.add(new PublishedRuleAction(nodeType, config));
        }
        return actions;
    }
}
