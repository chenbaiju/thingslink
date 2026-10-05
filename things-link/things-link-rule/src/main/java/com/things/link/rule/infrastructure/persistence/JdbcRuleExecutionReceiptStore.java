package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionReceiptStore;
import com.things.link.rule.application.queue.RuleExecutionReceiptClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * 以规则域自有 PostgreSQL 回执吸收 Kafka 重投和人工重放。
 *
 * <p>抢占使用单条 upsert：只有新身份、等待重试的更高 attempt，或租约已经到期的崩溃工作能够取得执行权。
 * 完成事实永不回退；这保证相同 messageId 与不可变规则版本的重放不会再次执行副作用意图。</p>
 */
@Repository
public class JdbcRuleExecutionReceiptStore implements RuleExecutionReceiptStore {

    /** 规则域 JDBC 访问器，事务级 project RLS 由调用链设置。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 已装配的数据源访问器 */
    public JdbcRuleExecutionReceiptStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RuleExecutionReceiptClaim tryClaim(RuleExecutionEnvelope envelope) {
        String claim = jdbcTemplate.queryForObject("""
                SELECT rule_execution_receipt_claim_state(?, ?, ?, ?, ?, ?, ?, ?)
                """, String.class, envelope.tenantId(), envelope.key().projectId(), envelope.key().messageId(),
                envelope.key().ruleId(), envelope.key().ruleVersionId(),
                envelope.plan().steps().stream().map(step -> step.ruleVersionId()).toArray(UUID[]::new),
                envelope.attempt(),
                Timestamp.from(envelope.enqueuedAt()));
        return RuleExecutionReceiptClaim.valueOf(claim);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void complete(RuleExecutionEnvelope envelope) {
        finish(envelope, false, "完成");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void release(RuleExecutionEnvelope envelope) {
        finish(envelope, true, "释放");
    }

    /** @param envelope 当前抢占信封 @param retry 是否进入等待重试 @param operation 状态机动作 */
    private void finish(RuleExecutionEnvelope envelope, boolean retry, String operation) {
        Boolean changed = jdbcTemplate.queryForObject("""
                SELECT rule_execution_receipt_finish(?, ?, ?, ?, ?, ?)
                """, Boolean.class, envelope.key().projectId(), envelope.key().messageId(),
                envelope.key().ruleId(), envelope.key().ruleVersionId(), envelope.attempt(), retry);
        if (!Boolean.TRUE.equals(changed)) {
            throw new IllegalStateException("规则执行回执" + operation + "失败");
        }
    }
}
