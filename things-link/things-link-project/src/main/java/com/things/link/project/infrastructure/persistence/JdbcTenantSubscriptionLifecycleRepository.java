package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.ActiveSubscription;
import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订阅生命周期写入口的 PostgreSQL 实现（S14-3a）。
 *
 * <p>「关旧 + 建新」由调用方在同一个事务里按 {@code lockTenant → lockActive → supersede → activate}
 * 的顺序执行：租户行锁串行化同租户的并发生效，先关后开保证部分唯一索引
 * {@code sys_tenant_subscription_active_tenant_uk} 在事务内的任意语句边界都不被违反。
 *
 * <p>{@code findFreeSubscriptionSnapshot} 与 {@code createFreeSubscriptionIfAbsent} 仍在
 * {@link JdbcTenantSubscriptionRepository}（S14-2a 的注册路径），本类不碰那段语义。
 */
@Repository
public class JdbcTenantSubscriptionLifecycleRepository implements TenantSubscriptionLifecycleRepository {

    /** S14-3c 生命周期事实的公共列清单；各扫描只追加自己的 WHERE/ORDER。 */
    private static final String SELECT_STATE = """
            SELECT id, tenant_id, plan_revision_id, status, starts_at, ends_at,
                   grace_ends_at, restricted_at
              FROM sys_tenant_subscription
            """;

    /** S14-3c 订阅生命周期事实投影。 */
    private static final RowMapper<SubscriptionLifecycleState> STATE_MAPPER =
            (resultSet, rowNumber) -> new SubscriptionLifecycleState(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("plan_revision_id", UUID.class),
                    SubscriptionStatus.valueOf(resultSet.getString("status")),
                    resultSet.getTimestamp("starts_at").toInstant(),
                    resultSet.getTimestamp("ends_at") == null
                            ? null : resultSet.getTimestamp("ends_at").toInstant(),
                    resultSet.getTimestamp("grace_ends_at") == null
                            ? null : resultSet.getTimestamp("grace_ends_at").toInstant(),
                    resultSet.getTimestamp("restricted_at") == null
                            ? null : resultSet.getTimestamp("restricted_at").toInstant());

    /** 订阅生命周期事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcTenantSubscriptionLifecycleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long lockTenantAndReadAssignmentVersion(UUID tenantId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ? FOR UPDATE",
                Long.class, tenantId);
        if (version == null) {
            // 订单的 tenant_id 外键已保证租户存在；真走到这里说明租户行被并发物理删除，必须失败而不是猜一个版本。
            throw new IllegalStateException("订阅生效时租户不存在: " + tenantId);
        }
        return version;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID readPolicyId(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quota_policy_id FROM sys_tenant WHERE id = ?", UUID.class, tenantId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ActiveSubscription> lockActiveSubscription(UUID tenantId) {
        return selectActive("WHERE tenant_id = ? AND status = 'ACTIVE' FOR UPDATE", tenantId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ActiveSubscription> findBySourceOrder(UUID orderId) {
        return selectActive("WHERE source_order_id = ?", orderId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void supersede(UUID subscriptionId) {
        int updated = jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET status = 'SUPERSEDED',
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ? AND status = 'ACTIVE'
                """, subscriptionId);
        if (updated != 1) {
            // 调用方刚在租户行锁下读到这条 ACTIVE 订阅，更新不到说明状态被越权改动，必须整体回滚。
            throw new IllegalStateException("关闭当前生效订阅失败，订阅已不在 ACTIVE: " + subscriptionId);
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID activate(UUID tenantId, UUID planRevisionId, Instant startsAt, Instant endsAt,
                         String billingPeriod, long priceCents, String currency, UUID sourceOrderId) {
        UUID subscriptionId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription (
                    id, tenant_id, plan_revision_id, status, starts_at, ends_at,
                    billing_period, renewal_mode, price_cents, currency, source_order_id, revision)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, 'MANUAL', ?, ?, ?, 1)
                """, subscriptionId, tenantId, planRevisionId, Timestamp.from(startsAt),
                Timestamp.from(endsAt), billingPeriod, priceCents, currency, sourceOrderId);
        return subscriptionId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<SubscriptionLifecycleState> findState(UUID subscriptionId) {
        return jdbcTemplate.query(SELECT_STATE + " WHERE id = ?", STATE_MAPPER, subscriptionId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<SubscriptionLifecycleState> findCurrentState(UUID tenantId) {
        return jdbcTemplate.query(SELECT_STATE + """
                         WHERE tenant_id = ? AND status IN ('ACTIVE', 'GRACE', 'RESTRICTED_FREE')
                         ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'GRACE' THEN 1 ELSE 2 END,
                                  starts_at DESC, created_at DESC
                         LIMIT 1
                        """, STATE_MAPPER, tenantId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<SubscriptionLifecycleState> findDueForGrace(Instant now, int limit) {
        return jdbcTemplate.query(SELECT_STATE + """
                         WHERE status = 'ACTIVE' AND ends_at IS NOT NULL AND ends_at <= ?
                         ORDER BY ends_at, id
                         LIMIT ?
                        """, STATE_MAPPER, Timestamp.from(now), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<SubscriptionLifecycleState> findDueForRestriction(Instant now, int limit) {
        // 「租户当前没有 ACTIVE 订阅」是续费保护：宽限期内续费新建 ACTIVE 后，旧 GRACE 行必须失去推进资格。
        return jdbcTemplate.query(SELECT_STATE + """
                         WHERE status = 'GRACE' AND grace_ends_at IS NOT NULL AND grace_ends_at <= ?
                           AND NOT EXISTS (
                               SELECT 1 FROM sys_tenant_subscription active
                                WHERE active.tenant_id = sys_tenant_subscription.tenant_id
                                  AND active.status = 'ACTIVE')
                         ORDER BY grace_ends_at, id
                         LIMIT ?
                        """, STATE_MAPPER, Timestamp.from(now), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<SubscriptionLifecycleState> findCurrentWithPeriodEnd(int limit) {
        return jdbcTemplate.query(SELECT_STATE + """
                         WHERE status IN ('ACTIVE', 'GRACE', 'RESTRICTED_FREE') AND ends_at IS NOT NULL
                         ORDER BY ends_at, id
                         LIMIT ?
                        """, STATE_MAPPER, limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean enterGrace(UUID subscriptionId, Instant graceEndsAt, Instant now) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET status = 'GRACE',
                       grace_ends_at = ?,
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ? AND status = 'ACTIVE' AND ends_at IS NOT NULL AND ends_at <= ?
                """, Timestamp.from(graceEndsAt), subscriptionId, Timestamp.from(now)) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean enterRestrictedFree(UUID subscriptionId, Instant restrictedAt, Instant now) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET status = 'RESTRICTED_FREE',
                       restricted_at = ?,
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ? AND status = 'GRACE' AND grace_ends_at IS NOT NULL AND grace_ends_at <= ?
                """, Timestamp.from(restrictedAt), subscriptionId, Timestamp.from(now)) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean changePlanRevision(UUID subscriptionId, UUID planRevisionId) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET plan_revision_id = ?, updated_at = now(), revision = revision + 1
                 WHERE id = ? AND status = 'ACTIVE'
                """, planRevisionId, subscriptionId) == 1;
    }

    /**
     * 按给定谓词读取一条订阅。
     *
     * @param predicate SQL 谓词片段（仅本类内部常量，不接受外部输入）
     * @param argument 谓词参数
     * @return 订阅事实；不存在时为空
     */
    private Optional<ActiveSubscription> selectActive(String predicate, UUID argument) {
        return jdbcTemplate.query("""
                        SELECT id, plan_revision_id, starts_at, ends_at
                          FROM sys_tenant_subscription
                        """ + predicate, (resultSet, rowNumber) -> new ActiveSubscription(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("plan_revision_id", UUID.class),
                        resultSet.getTimestamp("starts_at").toInstant(),
                        resultSet.getTimestamp("ends_at") == null
                                ? null : resultSet.getTimestamp("ends_at").toInstant()), argument)
                .stream().findFirst();
    }
}
