package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.application.queue.RuleExecutionLogEntry;
import com.things.link.rule.application.queue.RuleExecutionLogStore;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/** 通过受限数据库函数追加生产规则执行日志，避免后台 Worker 关闭项目 RLS。 */
@Repository
public class JdbcRuleExecutionLogStore implements RuleExecutionLogStore {

    /** 规则域 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 已配置应用角色的数据源访问器 */
    public JdbcRuleExecutionLogStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean append(RuleExecutionLogEntry entry) {
        java.util.List<Object> args = new java.util.ArrayList<>(java.util.List.of(Uuid7.generate(), entry.tenantId(), entry.key().projectId(),
                entry.key().messageId(), entry.key().ruleId(), entry.key().ruleVersionId(), entry.attempt(),
                entry.status().name(), entry.resultCode(), entry.duration().toMillis(), entry.inputBytes(),
                entry.outputBytes(), Timestamp.from(entry.createdAt())));
        String sql = "SELECT rule_execution_log_append(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        if (entry.deviceId() != null) {
            args.add(entry.deviceId());
            sql = "SELECT rule_execution_log_append_device(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }
        Boolean inserted = jdbcTemplate.queryForObject(sql, Boolean.class, args.toArray());
        return Boolean.TRUE.equals(inserted);
    }
}
