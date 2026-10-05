package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.DailyQuotaDecisionRepository;
import com.things.link.project.domain.DailyUsageScope;
import com.things.link.project.domain.QuotaMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 通过受限 SECURITY DEFINER 函数读取单指标日额度决策事实。
 */
@Repository
public class JdbcDailyQuotaDecisionRepository implements DailyQuotaDecisionRepository {

    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 访问器
     */
    public JdbcDailyQuotaDecisionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DailyQuotaDecisionFact> find(DailyUsageScope scope, LocalDate usageDate, QuotaMetric metric) {
        return jdbcTemplate.query("""
                        SELECT limit_value, tenant_used_value,
                               daily_soft_limit_basis_points, daily_degrade_basis_points
                          FROM trusted_project_daily_quota_decision(?, ?, ?, ?)
                        """, (resultSet, rowNumber) -> new DailyQuotaDecisionFact(
                        resultSet.getObject("limit_value", Long.class),
                        resultSet.getLong("tenant_used_value"),
                        resultSet.getInt("daily_soft_limit_basis_points"),
                        resultSet.getInt("daily_degrade_basis_points")),
                scope.tenantId(), scope.projectId(), usageDate, metric.name()).stream().findFirst();
    }
}
