package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.RuleDebugEvent;
import com.things.link.rule.domain.RuleDebugEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/** JDBC 调试事件仓储；只保存有界摘要，清理只能调用迁移冻结的受限函数。 */
@Repository
public class JdbcRuleDebugEventRepository implements RuleDebugEventRepository {

    /** 显式 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcRuleDebugEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public void save(RuleDebugEvent event) {
        jdbcTemplate.update("""
                INSERT INTO rule_debug_event (
                    id, tenant_id, project_id, rule_id, version_id, status, result_code,
                    duration_millis, input_bytes, output_bytes, input_summary, output_summary,
                    created_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.tenantId(), event.projectId(), event.ruleId(), event.versionId(),
                event.status(), event.resultCode(), event.durationMillis(), event.inputBytes(),
                event.outputBytes(), event.inputSummary(), event.outputSummary(), event.createdBy(),
                Timestamp.from(event.createdAt()), Timestamp.from(event.expiresAt()));
    }

    /** {@inheritDoc} */
    @Override
    public int deleteExpired(int batchSize) {
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT rule_debug_event_delete_expired(?)", Integer.class, batchSize);
        return deleted == null ? 0 : deleted;
    }
}
