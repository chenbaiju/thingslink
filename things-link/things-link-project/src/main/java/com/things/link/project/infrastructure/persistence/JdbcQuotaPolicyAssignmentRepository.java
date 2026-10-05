package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.QuotaPolicyChanged;
import com.things.link.project.domain.QuotaPolicyAssignmentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL CAS 更新租户配额策略绑定。
 */
@Repository
public class JdbcQuotaPolicyAssignmentRepository implements QuotaPolicyAssignmentRepository {

    /** JDBC 数据库访问模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcQuotaPolicyAssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<QuotaPolicyChanged> assign(UUID tenantId, UUID policyId, long expectedAssignmentVersion) {
        if (expectedAssignmentVersion <= 0) {
            throw new IllegalArgumentException("期望的配额策略绑定版本必须为正数");
        }
        return jdbcTemplate.query("""
                        UPDATE sys_tenant t
                           SET quota_policy_id = p.id,
                               quota_policy_assignment_version = t.quota_policy_assignment_version + 1,
                               updated_at = now()
                          FROM sys_quota_policy p
                         WHERE t.id = ?
                           AND t.quota_policy_assignment_version = ?
                           AND t.status = 'ACTIVE'
                           AND p.id = ?
                        RETURNING t.id, t.quota_policy_assignment_version, p.version
                        """, (resultSet, rowNumber) -> new QuotaPolicyChanged(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("quota_policy_assignment_version"),
                        resultSet.getLong("version")), tenantId, expectedAssignmentVersion, policyId)
                .stream().findFirst();
    }
}
