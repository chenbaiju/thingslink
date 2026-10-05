package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.PlanPurchase;
import com.things.link.project.domain.ResourcePackagePurchase;
import com.things.link.project.domain.SubscriptionUpgradeProration;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读写租户订单事实（S14-3a；S14-3b 增加升级折算快照列）。
 *
 * <p>S14-5a 追加：{@link #reserveFullRefund(UUID, long)} 以 CAS 占住全额退款金额，让「一笔订单
 * 最多全额退一次」由数据库仲裁（{@code refunded_cents} 上的行级 CHECK 同时保证累计金额不越界）。
 *
 * <p>写入端没有 DELETE：取消与支付都是状态推进，订单行始终保留。幂等由三层叠加：
 * ① {@link #lockOrder(UUID)} 的行锁串行化同一订单的并发支付；②
 * {@code UPDATE ... WHERE status = 'CREATED'} 让重复支付更新不到行；③
 * {@code sys_tenant_order_provider_event_uk} 部分唯一索引拒绝同渠道同事件的第二条订单。
 *
 * <p>读取端额外提供购买投影：一次读齐修订版的档位展示顺序、参考价、币种、计费周期与配额
 * 模板 ID，供订单服务在同一事务里完成「快照金额 + 生效后绑策略」。订单表与
 * {@code sys_tenant_subscription} 一样不套租户 RLS（下单时尚无 HTTP 租户上下文，
 * 授权留在应用入口），因此这里按显式参数过滤，不读 {@code app_current_tenant()}。
 *
 * <p>升级订单的折算列只在 {@code order_kind = 'UPGRADE'} 时非空，数据库 CHECK 保证
 * 「要么全有、要么全无」；映射时按同一开关重建 {@link SubscriptionUpgradeProration}，
 * 让被篡改的半截快照在重建对象时失败。
 */
@Repository
public class JdbcTenantOrderRepository implements TenantOrderRepository {

    /** 订单行投影；{@code provider_event_id}/{@code paid_at} 在未支付时为空。 */
    private static final RowMapper<TenantOrder> ORDER_ROW_MAPPER = (resultSet, rowNumber) -> {
        TenantOrderKind kind = TenantOrderKind.valueOf(resultSet.getString("order_kind"));
        return new TenantOrder(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("plan_revision_id", UUID.class),
                kind,
                resultSet.getObject("source_plan_revision_id", UUID.class),
                proration(resultSet, kind),
                packagePurchase(resultSet, kind),
                PaymentProvider.valueOf(resultSet.getString("provider")),
                TenantOrderStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("provider_event_id"),
                resultSet.getLong("amount_cents"),
                resultSet.getString("currency"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("paid_at") == null
                        ? null : resultSet.getTimestamp("paid_at").toInstant(),
                resultSet.getLong("refunded_cents"),
                resultSet.getLong("revision"));
    };

    /** 订单事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcTenantOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean tenantExists(UUID tenantId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sys_tenant WHERE id = ?)", Boolean.class, tenantId);
        return Boolean.TRUE.equals(exists);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<PlanPurchase> findPlanPurchase(UUID planRevisionId) {
        return jdbcTemplate.query("""
                        SELECT r.id AS plan_revision_id, p.code AS plan_code, p.display_order,
                               r.sale_status, r.billing_period, r.currency, r.reference_price_cents,
                               r.quota_policy_id
                          FROM sys_plan_revision r
                          JOIN sys_plan p ON p.id = r.plan_id
                         WHERE r.id = ?
                        """, (resultSet, rowNumber) -> new PlanPurchase(
                        resultSet.getObject("plan_revision_id", UUID.class),
                        resultSet.getString("plan_code"),
                        resultSet.getInt("display_order"),
                        resultSet.getString("sale_status"),
                        resultSet.getString("billing_period"),
                        resultSet.getString("currency"),
                        resultSet.getObject("reference_price_cents", Long.class),
                        resultSet.getObject("quota_policy_id", UUID.class)), planRevisionId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantOrder> findOrder(UUID orderId) {
        return selectOrder("WHERE id = ?", orderId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantOrder> lockOrder(UUID orderId) {
        return selectOrder("WHERE id = ? FOR UPDATE", orderId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantOrder> findByProviderEventId(PaymentProvider provider, String providerEventId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, plan_revision_id, order_kind, source_plan_revision_id,
                               proration_effective_at, proration_period_ends_at,
                               proration_remaining_days, proration_old_daily_price_cents,
                               proration_new_daily_price_cents, proration_credit_cents,
                               proration_charge_cents,
                               package_dimension_code, package_amount, package_unit,
                               package_window_kind, package_period_months, package_starts_at,
                               provider, status, provider_event_id,
                               amount_cents, currency, created_at, paid_at, refunded_cents, revision
                          FROM sys_tenant_order
                         WHERE provider = ? AND provider_event_id = ?
                        """, ORDER_ROW_MAPPER, provider.name(), providerEventId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID insertOrder(UUID tenantId, UUID planRevisionId, PaymentProvider provider,
                            long amountCents, String currency) {
        UUID orderId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_order (
                    id, tenant_id, plan_revision_id, order_kind, source_plan_revision_id,
                    proration_effective_at, proration_period_ends_at, proration_remaining_days,
                    proration_old_daily_price_cents, proration_new_daily_price_cents,
                    proration_credit_cents, proration_charge_cents,
                    provider, status, provider_event_id,
                    amount_cents, currency, created_at, paid_at, updated_at, revision)
                VALUES (?, ?, ?, 'PURCHASE', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                        ?, 'CREATED', NULL, ?, ?, now(), NULL, now(), 1)
                """, orderId, tenantId, planRevisionId, provider.name(), amountCents, currency);
        return orderId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID insertUpgradeOrder(UUID tenantId, UUID planRevisionId, UUID sourcePlanRevisionId,
                                   SubscriptionUpgradeProration proration, PaymentProvider provider,
                                   String currency) {
        UUID orderId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_order (
                    id, tenant_id, plan_revision_id, order_kind, source_plan_revision_id,
                    proration_effective_at, proration_period_ends_at, proration_remaining_days,
                    proration_old_daily_price_cents, proration_new_daily_price_cents,
                    proration_credit_cents, proration_charge_cents,
                    provider, status, provider_event_id,
                    amount_cents, currency, created_at, paid_at, updated_at, revision)
                VALUES (?, ?, ?, 'UPGRADE', ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, 'CREATED', NULL, ?, ?, now(), NULL, now(), 1)
                """, orderId, tenantId, planRevisionId, sourcePlanRevisionId,
                Timestamp.from(proration.effectiveAt()),
                Timestamp.from(proration.periodEndsAt()),
                proration.remainingDays(), proration.oldDailyPriceCents(),
                proration.newDailyPriceCents(), proration.creditCents(),
                proration.chargeCents(), provider.name(),
                proration.differenceCents(), currency);
        return orderId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID insertPackageOrder(UUID tenantId, ResourcePackagePurchase purchase, PaymentProvider provider,
                                   long amountCents, String currency) {
        UUID orderId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_order (
                    id, tenant_id, plan_revision_id, order_kind, source_plan_revision_id,
                    proration_effective_at, proration_period_ends_at, proration_remaining_days,
                    proration_old_daily_price_cents, proration_new_daily_price_cents,
                    proration_credit_cents, proration_charge_cents,
                    package_dimension_code, package_amount, package_unit, package_window_kind,
                    package_period_months, package_starts_at,
                    provider, status, provider_event_id,
                    amount_cents, currency, created_at, paid_at, updated_at, revision)
                VALUES (?, ?, NULL, 'PACKAGE', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                        ?, ?, ?, ?, ?, ?,
                        ?, 'CREATED', NULL, ?, ?, now(), NULL, now(), 1)
                """, orderId, tenantId, purchase.dimensionCode(), purchase.amount(), purchase.unit(),
                purchase.window(), purchase.periodMonths(),
                purchase.startsAt() == null ? null : Timestamp.from(purchase.startsAt()),
                provider.name(), amountCents, currency);
        return orderId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<Instant> markPaid(UUID orderId, PaymentProvider provider, String providerEventId) {
        return jdbcTemplate.query("""
                        UPDATE sys_tenant_order
                           SET status = 'PAID',
                               provider_event_id = ?,
                               paid_at = now(),
                               updated_at = now(),
                               revision = revision + 1
                         WHERE id = ?
                           AND provider = ?
                           AND status = 'CREATED'
                        RETURNING paid_at
                        """, (resultSet, rowNumber) -> resultSet.getTimestamp("paid_at").toInstant(),
                providerEventId, orderId, provider.name())
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean cancelOrder(UUID orderId) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_order
                   SET status = 'CANCELLED',
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ? AND status = 'CREATED'
                """, orderId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean reserveFullRefund(UUID orderId, long amountCents) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_order
                   SET refunded_cents = amount_cents,
                       updated_at = now(),
                       revision = revision + 1
                 WHERE id = ?
                   AND status = 'PAID'
                   AND refunded_cents = 0
                   AND amount_cents = ?
                """, orderId, amountCents) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean reserveRefund(UUID orderId, long amountCents) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_order
                   SET refunded_cents = refunded_cents + ?, updated_at = now(), revision = revision + 1
                 WHERE id = ? AND status = 'PAID'
                   AND ? > 0 AND ? <= amount_cents - refunded_cents
                """, amountCents, orderId, amountCents, amountCents) == 1;
    }

    /**
     * 按给定谓词读取一条订单。
     *
     * @param predicate SQL 谓词片段（仅本类内部常量，不接受外部输入）
     * @param orderId 订单 ID
     * @return 订单事实；不存在时为空
     */
    private Optional<TenantOrder> selectOrder(String predicate, UUID orderId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, plan_revision_id, order_kind, source_plan_revision_id,
                               proration_effective_at, proration_period_ends_at,
                               proration_remaining_days, proration_old_daily_price_cents,
                               proration_new_daily_price_cents, proration_credit_cents,
                               proration_charge_cents,
                               package_dimension_code, package_amount, package_unit,
                               package_window_kind, package_period_months, package_starts_at,
                               provider, status, provider_event_id,
                               amount_cents, currency, created_at, paid_at, refunded_cents, revision
                          FROM sys_tenant_order
                        """ + predicate, ORDER_ROW_MAPPER, orderId)
                .stream().findFirst();
    }

    /**
     * 按订单种类重建折算快照；非升级订单必须三个折算列全空。
     *
     * @param resultSet 当前行
     * @param kind 订单种类
     * @return 升级订单的折算快照；其他订单为 {@code null}
     * @throws SQLException 列读取失败
     */
    private static SubscriptionUpgradeProration proration(ResultSet resultSet, TenantOrderKind kind)
            throws SQLException {
        if (kind != TenantOrderKind.UPGRADE) {
            return null;
        }
        return new SubscriptionUpgradeProration(
                resultSet.getTimestamp("proration_effective_at").toInstant(),
                resultSet.getTimestamp("proration_period_ends_at").toInstant(),
                resultSet.getInt("proration_remaining_days"),
                resultSet.getLong("proration_old_daily_price_cents"),
                resultSet.getLong("proration_new_daily_price_cents"),
                resultSet.getLong("proration_credit_cents"),
                resultSet.getLong("proration_charge_cents"),
                Math.max(0L, resultSet.getLong("proration_charge_cents")
                        - resultSet.getLong("proration_credit_cents")));
    }

    /**
     * 按订单种类重建资源包购买快照；非 PACKAGE 订单必须包列全空。
     *
     * @param resultSet 当前行
     * @param kind 订单种类
     * @return 资源包订单的购买快照；其他订单为 {@code null}
     * @throws SQLException 列读取失败
     */
    private static ResourcePackagePurchase packagePurchase(ResultSet resultSet, TenantOrderKind kind)
            throws SQLException {
        if (kind != TenantOrderKind.PACKAGE) {
            return null;
        }
        Timestamp startsAt = resultSet.getTimestamp("package_starts_at");
        return new ResourcePackagePurchase(
                resultSet.getString("package_dimension_code"),
                resultSet.getLong("package_amount"),
                resultSet.getString("package_unit"),
                resultSet.getString("package_window_kind"),
                resultSet.getInt("package_period_months"),
                startsAt == null ? null : startsAt.toInstant());
    }
}
