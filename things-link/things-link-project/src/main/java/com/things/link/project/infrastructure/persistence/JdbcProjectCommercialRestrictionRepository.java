package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.ProjectCommercialRestriction;
import com.things.link.project.domain.ProjectCommercialRestrictionRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 以 PostgreSQL 读写商业受限项目台账（S14-3c）。
 *
 * <p>写入端只有一条 {@code INSERT ... ON CONFLICT DO NOTHING} 与一条
 * {@code UPDATE ... WHERE status = 'ACTIVE'}：前者让并发 worker/重跑不会为同一项目写第二条生效记录，
 * 后者让重复恢复更新不到行。本类不做「先查后插」，「恢复哪些」由一次性扫描给出。
 *
 * <p>与项目/订阅表一致不套租户 RLS：服务以显式 tenantId 为参数，访问控制留在应用入口。
 */
@Repository
public class JdbcProjectCommercialRestrictionRepository implements ProjectCommercialRestrictionRepository {

    /** 台账行投影；恢复时只需要身份、归属、项目与受限时刻。 */
    private static final RowMapper<ProjectCommercialRestriction> RESTRICTION_MAPPER =
            (resultSet, rowNumber) -> new ProjectCommercialRestriction(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("project_id", UUID.class),
                    resultSet.getObject("subscription_id", UUID.class),
                    resultSet.getTimestamp("restricted_at").toInstant());

    /** 台账事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcProjectCommercialRestrictionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean recordActive(UUID tenantId, UUID projectId, UUID subscriptionId, Instant restrictedAt) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO sys_project_commercial_restriction (
                    id, tenant_id, project_id, subscription_id, restricted_at, lifted_at,
                    status, created_at, updated_at, revision)
                VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', now(), now(), 1)
                ON CONFLICT (project_id) WHERE status = 'ACTIVE' DO NOTHING
                """, Uuid7.generate(), tenantId, projectId, subscriptionId, Timestamp.from(restrictedAt));
        return inserted == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<UUID> findPendingTenantIds() {
        return jdbcTemplate.query("""
                SELECT DISTINCT r.tenant_id FROM sys_project_commercial_restriction r
                  JOIN sys_project p ON p.id=r.project_id AND p.tenant_id=r.tenant_id
                 WHERE r.status='ACTIVE' AND p.status='ARCHIVED' AND p.deleted_at IS NULL
                 ORDER BY r.tenant_id
                """,(rs,row) -> rs.getObject("tenant_id",UUID.class));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<ProjectCommercialRestriction> findActiveByTenant(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT r.id,r.tenant_id,r.project_id,r.subscription_id,r.restricted_at
                  FROM sys_project_commercial_restriction r
                  JOIN sys_project p ON p.id=r.project_id AND p.tenant_id=r.tenant_id
                 WHERE r.tenant_id=? AND r.status='ACTIVE' AND p.status='ARCHIVED' AND p.deleted_at IS NULL
                 ORDER BY p.created_at,p.id
                """,RESTRICTION_MAPPER,tenantId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean markLifted(UUID restrictionId, Instant liftedAt) {
        return jdbcTemplate.update("""
                UPDATE sys_project_commercial_restriction
                   SET status = 'LIFTED',
                       lifted_at = ?,
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ? AND status = 'ACTIVE'
                """, Timestamp.from(liftedAt), restrictionId) == 1;
    }
}
