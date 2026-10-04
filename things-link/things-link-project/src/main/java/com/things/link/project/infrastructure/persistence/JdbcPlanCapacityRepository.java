package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.PlanCapacityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.OptionalLong;
import java.util.Optional;
import com.things.link.project.domain.PlanHistoryWindow;
import java.util.UUID;

/** 项目域读取自己的归属/策略表；单SQL快照同时读取基础值、包及人工调整。 */
@Repository
public class JdbcPlanCapacityRepository implements PlanCapacityRepository {
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 同原业务事务的数据库连接 */
    public JdbcPlanCapacityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public OptionalLong findEndUsersLimit(UUID tenantId, UUID projectId) {
        return findLimit(tenantId, projectId, "end_users_max", "END_USERS_MAX");
    }

    /** {@inheritDoc} */
    @Override
    public OptionalLong findDashboardsLimit(UUID tenantId, UUID projectId) {
        return findLimit(tenantId, projectId, "dashboards_max", "DASHBOARDS_MAX");
    }

    /** {@inheritDoc} */
    @Override
    public OptionalLong findExternalSeatsLimit(UUID tenantId, UUID projectId) {
        return findLimit(tenantId, projectId, "external_collaborator_seats", "EXTERNAL_COLLABORATOR_SEATS");
    }

    /** 列名仅来自本类固定常量，不接受业务/HTTP提供的SQL标识符。 */
    private OptionalLong findLimit(UUID tenantId, UUID projectId, String column, String dimension) {
        return jdbcTemplate.query("""
                SELECT LEAST(q.%s::numeric + tenant_resource_package_addon(
                    t.id, ?, 'COUNT', 'NONE', statement_timestamp()), 9223372036854775807)::bigint
                  FROM sys_project p JOIN sys_tenant t ON t.id=p.tenant_id
                  JOIN sys_quota_policy q ON q.id=t.quota_policy_id
                 WHERE p.id=? AND p.tenant_id=? AND p.deleted_at IS NULL
                   AND p.status IN ('ACTIVE','ARCHIVED') AND t.status='ACTIVE' AND q.plan_template
                """.formatted(column), (rs, row) -> rs.getLong(1), dimension, projectId, tenantId)
                .stream().mapToLong(Long::longValue).findFirst();
    }
    /** 同一语句快照合成权益与数据库时刻；UTC日历减法由生产函数实现。 */
    @Override
    public Optional<PlanHistoryWindow> findHistoryWindow(UUID tenantId, UUID projectId) {
        return jdbcTemplate.query("""
                SELECT plan_history_window_lower_bound(statement_timestamp(), q.history_window_unit,
                    q.history_window_amount::numeric + tenant_resource_package_addon(
                        t.id, 'HISTORY_WINDOW', q.history_window_unit, 'ROLLING', statement_timestamp())) AS lower_bound,
                    statement_timestamp() AS upper_bound
                  FROM sys_project p JOIN sys_tenant t ON t.id=p.tenant_id
                  JOIN sys_quota_policy q ON q.id=t.quota_policy_id
                 WHERE p.id=? AND p.tenant_id=? AND p.deleted_at IS NULL
                   AND p.status IN ('ACTIVE','ARCHIVED') AND t.status='ACTIVE' AND q.plan_template
                """, (rs, row) -> rs.getTimestamp("lower_bound") == null ? null : new PlanHistoryWindow(
                    rs.getTimestamp("lower_bound").toInstant(), rs.getTimestamp("upper_bound").toInstant()),
                projectId, tenantId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

}
