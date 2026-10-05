package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.DailyUsageReconciliationRepository;
import com.things.link.project.domain.DailyUsageScope;
import com.things.link.project.domain.DailyUsageValue;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 以受限领取函数和绝对值 UPSERT 保存 UTC 日用量。
 */
@Repository
public class JdbcDailyUsageReconciliationRepository implements DailyUsageReconciliationRepository {

    /** 事务感知 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate 事务感知 JDBC 访问器
     */
    public JdbcDailyUsageReconciliationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DailyUsageScope> claimDueScopes(int maximumRows) {
        return jdbcTemplate.query("SELECT tenant_id, project_id FROM claim_due_daily_usage_scopes(?)",
                (resultSet, rowNumber) -> new DailyUsageScope(
                        resultSet.getObject("tenant_id", java.util.UUID.class),
                        resultSet.getObject("project_id", java.util.UUID.class)),
                maximumRows);
    }

    /** 同项目归并串行化；先FOR UPDATE避免后续complete升级SHARE锁时相互等待。 */
    @Override
    public void lockScope(DailyUsageScope scope) {
        var rows = jdbcTemplate.queryForList("SELECT id FROM sys_project WHERE tenant_id=? AND id=? FOR UPDATE",
                java.util.UUID.class, scope.tenantId(), scope.projectId());
        if (rows.size() != 1) throw new IllegalStateException("日用量归并项目范围已失效");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void mergeAbsolute(DailyUsageScope scope, LocalDate usageDate, DailyUsageValue value) {
        jdbcTemplate.update("""
                INSERT INTO sys_usage_counter_daily
                    (id, tenant_id, project_id, usage_date, metric, used_value)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, usage_date, metric) DO UPDATE
                   SET used_value = GREATEST(sys_usage_counter_daily.used_value, EXCLUDED.used_value),
                       updated_at = now()
                """, Uuid7.generate(), scope.tenantId(), scope.projectId(), usageDate,
                value.metric().name(), value.usedValue());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void complete(DailyUsageScope scope) {
        int updated = jdbcTemplate.update("""
                UPDATE sys_project
                   SET usage_reconcile_after = now() + interval '1 minute',
                       usage_reconcile_lease_until = NULL,
                       updated_at = now()
                 WHERE id = ? AND tenant_id = ?
                """, scope.projectId(), scope.tenantId());
        if (updated != 1) {
            throw new IllegalStateException("日用量归并项目范围已失效");
        }
    }
}
