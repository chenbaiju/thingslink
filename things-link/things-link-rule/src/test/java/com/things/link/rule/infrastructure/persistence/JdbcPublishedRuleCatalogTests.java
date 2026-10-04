package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.application.queue.PublishedRulePlan;
import com.things.link.rule.application.queue.PublishedRuleStep;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 生产规则目录只调用冻结的受限函数，不退回普通 RLS 表查询，并把动作 jsonb 还原为生产动作。 */
class JdbcPublishedRuleCatalogTests {

    /** 计划解析必须携带 messageId，使数据库可优先恢复首次持久计划。 */
    @Test
    @SuppressWarnings("unchecked")
    void activePlanUsesTrustedTupleProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        PublishedRuleStep version = version();
        when(jdbc.query(eq("SELECT * FROM rule_execution_plan_resolve(?, ?, ?)"),
                any(RowMapper.class), eq(tenantId), eq(projectId), eq(messageId))).thenReturn(List.of(version));

        PublishedRulePlan plan = new JdbcPublishedRuleCatalog(jdbc, new ObjectMapper())
                .resolve(tenantId, projectId, messageId);
        assertThat(plan.steps()).containsExactly(version);
        verify(jdbc).query(eq("SELECT * FROM rule_execution_plan_resolve(?, ?, ?)"),
                any(RowMapper.class), eq(tenantId), eq(projectId), eq(messageId));
    }

    /** actions jsonb 列必须在映射步骤时还原为冻结动作，未知 nodeType/config 缺失则立即失败。 */
    @Test
    @SuppressWarnings("unchecked")
    void parsesFrozenActionsFromJsonb() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID ruleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(jdbc.query(eq("SELECT * FROM rule_execution_plan_resolve(?, ?, ?)"),
                any(RowMapper.class), any(), any(), any())).thenAnswer(invocation -> {
                    RowMapper<PublishedRuleStep> rowMapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("rule_id", UUID.class)).thenReturn(ruleId);
                    when(rs.getObject("version_id", UUID.class)).thenReturn(versionId);
                    when(rs.getLong("version_number")).thenReturn(7L);
                    when(rs.getString("source")).thenReturn("input => input");
                    when(rs.getString("actions")).thenReturn(
                            "[{\"nodeType\":\"notification-action\",\"config\":{\"channel\":\"email\"}}]");
                    return List.of(rowMapper.mapRow(rs, 1));
                });

        PublishedRulePlan plan = new JdbcPublishedRuleCatalog(jdbc, new ObjectMapper())
                .resolve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(plan.steps()).hasSize(1);
        PublishedRuleStep step = plan.steps().getFirst();
        assertThat(step.ruleId()).isEqualTo(ruleId);
        assertThat(step.versionNumber()).isEqualTo(7L);
        assertThat(step.actions()).hasSize(1);
        assertThat(step.actions().getFirst().nodeType()).isEqualTo("notification-action");
        assertThat(step.actions().getFirst().config().get("channel").asText()).isEqualTo("email");
    }

    /** @return 完整可信投影夹具 */
    private static PublishedRuleStep version() {
        return new PublishedRuleStep(UUID.randomUUID(), UUID.randomUUID(), 1, "input => input");
    }
}
