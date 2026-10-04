package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.FreeSubscriptionSnapshot;
import com.things.link.project.domain.TenantSubscriptionRepository;
import com.things.link.project.domain.plan.ProductRevision1;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读取 FREE 冻结快照并幂等建租户免费订阅（S14-2a）。
 *
 * <p>写入端只有一条 {@code INSERT ... WHERE NOT EXISTS ... ON CONFLICT DO NOTHING}：它同时让
 * 「租户已有 ACTIVE 订阅时不重复插入」与部分唯一索引 {@code sys_tenant_subscription_active_tenant_uk}
 * 在并发下一致收敛 —— 先到的插入成功，后到的既可能被 {@code NOT EXISTS} 拦下，也可能撞唯一索引后
 * 被 {@code ON CONFLICT} 静默丢弃，两条路径都返回 0 行，调用方据此判断是否还需要推进运行时指针。
 *
 * <p>订阅表是租户历史事实，注册时尚无租户上下文，因此本表与 {@code sys_tenant} 一样不套
 * {@code enable_tenant_rls()}：策略写作 {@code tenant_id = app_current_tenant()}，而注册时该函数为
 * NULL（fail-closed），加了策略会让新租户的订阅永远插不进去。访问控制由应用层保证，读取面在
 * S14-2c 按当前租户过滤。
 */
@Repository
public class JdbcTenantSubscriptionRepository implements TenantSubscriptionRepository {

    /** JDBC 数据库访问模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcTenantSubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<FreeSubscriptionSnapshot> findFreeSubscriptionSnapshot() {
        return jdbcTemplate.query("""
                        SELECT r.id AS plan_revision_id, q.id AS quota_policy_id,
                               r.billing_period, r.price_cents, r.currency
                          FROM sys_plan_revision r
                          JOIN sys_plan p ON p.id = r.plan_id
                          JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                         WHERE p.code = ?
                           AND r.revision_code = ?
                           AND q.code = ?
                           AND q.plan_template
                        """, (resultSet, rowNumber) -> new FreeSubscriptionSnapshot(
                                resultSet.getObject("plan_revision_id", UUID.class),
                                resultSet.getObject("quota_policy_id", UUID.class),
                                resultSet.getString("billing_period"),
                                resultSet.getLong("price_cents"),
                                resultSet.getString("currency")),
                        ProductRevision1.FREE_PLAN_CODE, ProductRevision1.CODE,
                        ProductRevision1.quotaPolicyCode(ProductRevision1.FREE_PLAN_CODE))
                .stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public boolean createFreeSubscriptionIfAbsent(UUID tenantId, FreeSubscriptionSnapshot snapshot) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription (
                    id, tenant_id, plan_revision_id, status, starts_at, ends_at,
                    billing_period, renewal_mode, price_cents, currency, source_order_id, revision)
                SELECT ?, ?, ?, 'ACTIVE', now(), NULL, ?, 'NONE', ?, ?, NULL, 1
                 WHERE NOT EXISTS (
                     SELECT 1 FROM sys_tenant_subscription
                      WHERE tenant_id = ? AND status = 'ACTIVE')
                ON CONFLICT DO NOTHING
                """, Uuid7.generate(), tenantId, snapshot.planRevisionId(), snapshot.billingPeriod(),
                snapshot.priceCents(), snapshot.currency(), tenantId);
        return inserted == 1;
    }

    /** {@inheritDoc} */
    @Override
    public java.util.OptionalLong findFreeProjectsMax() {
        return jdbcTemplate.query("""
                        SELECT q.projects_max
                          FROM sys_quota_policy q
                         WHERE q.code = ? AND q.plan_template
                        """, (resultSet, rowNumber) -> resultSet.getObject("projects_max", Long.class),
                        ProductRevision1.quotaPolicyCode(ProductRevision1.FREE_PLAN_CODE))
                .stream().filter(java.util.Objects::nonNull).findFirst()
                .map(java.util.OptionalLong::of).orElseGet(java.util.OptionalLong::empty);
    }
}
