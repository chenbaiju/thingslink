package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.SubscriptionPendingChange;
import com.things.link.project.domain.SubscriptionPendingChangeStatus;
import com.things.link.project.domain.TenantSubscriptionChangeRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读写预约降级事实（S14-3b）。
 *
 * <p>写入端没有 DELETE、也没有「改目标」：撤销是 {@code PENDING → CANCELLED} 的状态推进，
 * 客户改主意必须显式撤销再预约，两步各留一行审计。{@code WHERE status = 'PENDING'} 让
 * 重复撤销更新不到行，本语句自身即幂等仲裁；「每租户至多一条 PENDING」由部分唯一索引
 * {@code sys_tenant_subscription_pending_change_pending_uk} 兜底。
 *
 * <p>与 {@code sys_tenant_subscription}/{@code sys_tenant_order} 一样不套租户 RLS：
 * 服务以显式 {@code tenantId} 为参数，授权留在应用入口。
 */
@Repository
public class JdbcSubscriptionPendingChangeRepository implements TenantSubscriptionChangeRepository {

    /** 预约行投影。 */
    private static final RowMapper<SubscriptionPendingChange> CHANGE_ROW_MAPPER =
            (resultSet, rowNumber) -> new SubscriptionPendingChange(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("subscription_id", UUID.class),
                    resultSet.getObject("from_plan_revision_id", UUID.class),
                    resultSet.getObject("target_plan_revision_id", UUID.class),
                    resultSet.getTimestamp("effective_at").toInstant(),
                    SubscriptionPendingChangeStatus.valueOf(resultSet.getString("status")),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant(),
                    resultSet.getLong("revision"));

    /** 预约事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcSubscriptionPendingChangeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean lockTenant(UUID tenantId) {
        return !jdbcTemplate.queryForList(
                "SELECT id FROM sys_tenant WHERE id = ? FOR UPDATE", UUID.class, tenantId).isEmpty();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<SubscriptionPendingChange> findById(UUID changeId) {
        return selectChange("WHERE id = ?", changeId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<SubscriptionPendingChange> findPending(UUID tenantId) {
        return selectChange("WHERE tenant_id = ? AND status = 'PENDING'", tenantId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID insertPending(UUID tenantId, UUID subscriptionId, UUID fromPlanRevisionId,
                              UUID targetPlanRevisionId, Instant effectiveAt) {
        UUID changeId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription_pending_change (
                    id, tenant_id, subscription_id, from_plan_revision_id, target_plan_revision_id,
                    effective_at, status, cancelled_at, applied_at,
                    created_at, updated_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', NULL, NULL, now(), now(), 1)
                """, changeId, tenantId, subscriptionId, fromPlanRevisionId, targetPlanRevisionId,
                Timestamp.from(effectiveAt));
        return changeId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean cancelPending(UUID changeId) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_subscription_pending_change
                   SET status = 'CANCELLED',
                       cancelled_at = now(),
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ? AND status = 'PENDING'
                """, changeId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public java.util.List<SubscriptionPendingChange> findDuePending(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, subscription_id, from_plan_revision_id,
                       target_plan_revision_id, effective_at, status,
                       created_at, updated_at, revision
                  FROM sys_tenant_subscription_pending_change
                 WHERE status = 'PENDING' AND effective_at <= ?
                 ORDER BY effective_at, id
                 LIMIT ?
                """, CHANGE_ROW_MAPPER, Timestamp.from(now), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean markApplied(UUID changeId, Instant appliedAt) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_subscription_pending_change
                   SET status = 'APPLIED',
                       applied_at = ?,
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(appliedAt), changeId) == 1;
    }

    /**
     * 按给定谓词读取一条预约。
     *
     * @param predicate SQL 谓词片段（仅本类内部常量，不接受外部输入）
     * @param argument 谓词参数
     * @return 预约事实；不存在时为空
     */
    private Optional<SubscriptionPendingChange> selectChange(String predicate, UUID argument) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, subscription_id, from_plan_revision_id,
                               target_plan_revision_id, effective_at, status,
                               created_at, updated_at, revision
                          FROM sys_tenant_subscription_pending_change
                        """ + predicate, CHANGE_ROW_MAPPER, argument)
                .stream().findFirst();
    }
}
