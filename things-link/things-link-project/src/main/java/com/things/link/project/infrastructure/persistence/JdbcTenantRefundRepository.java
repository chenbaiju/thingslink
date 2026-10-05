package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.RefundStatus;
import com.things.link.project.domain.TenantRefund;
import com.things.link.project.domain.TenantRefundRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读写租户退款事实（S14-5a）。
 *
 * <p>写入端没有 UPDATE/DELETE：退款是资金历史，改写已发生的事实会让对账失去意义。幂等由两层叠加：
 * ① 退款事务先持订单行锁并 CAS 占住金额（{@code sys_tenant_order.refunded_cents}）；
 * ② {@code sys_tenant_refund_provider_refund_uk} 唯一索引拒绝同渠道同流水号的第二条退款。
 *
 * <p>读取端额外提供「按订单累计成功退款」，供对账不变量与真库用例核对
 * {@code sys_tenant_order.refunded_cents} 与逐笔退款之和是否一致。
 *
 * <p>退款表与订单/订阅/包一样不套租户 RLS：服务以显式 {@code tenantId} 为参数，授权留在应用入口。
 */
@Repository
public class JdbcTenantRefundRepository implements TenantRefundRepository {

    /** 退款行投影。 */
    private static final RowMapper<TenantRefund> REFUND_ROW_MAPPER = (resultSet, rowNumber) ->
            new TenantRefund(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("order_id", UUID.class),
                    resultSet.getLong("amount_cents"),
                    resultSet.getString("currency"),
                    PaymentProvider.valueOf(resultSet.getString("provider")),
                    resultSet.getString("provider_refund_id"),
                    RefundStatus.valueOf(resultSet.getString("status")),
                    resultSet.getString("reason"),
                    resultSet.getTimestamp("created_at").toInstant());

    /** 退款行全列；两处 SELECT 共用，避免漏列导致重建快照失败。 */
    private static final String REFUND_COLUMNS = """
            id, tenant_id, order_id, amount_cents, currency, provider, provider_refund_id,
            status, reason, created_at
            """;

    /** 退款事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcTenantRefundRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID insert(UUID tenantId, UUID orderId, long amountCents, String currency,
                       PaymentProvider provider, String providerRefundId, RefundStatus status,
                       String reason) {
        UUID refundId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_refund (
                    id, tenant_id, order_id, amount_cents, currency, provider, provider_refund_id,
                    status, reason, created_at, updated_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 1)
                """, refundId, tenantId, orderId, amountCents, currency, provider.name(),
                providerRefundId, status.name(), reason);
        return refundId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantRefund> findByProviderRefundId(PaymentProvider provider,
                                                        String providerRefundId) {
        return jdbcTemplate.query("SELECT " + REFUND_COLUMNS + " FROM sys_tenant_refund "
                        + "WHERE provider = ? AND provider_refund_id = ?",
                REFUND_ROW_MAPPER, provider.name(), providerRefundId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long sumSucceededByOrder(UUID orderId) {
        Long total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount_cents), 0) FROM sys_tenant_refund
                 WHERE order_id = ? AND status = 'SUCCEEDED'
                """, Long.class, orderId);
        return total == null ? 0L : total;
    }
}
