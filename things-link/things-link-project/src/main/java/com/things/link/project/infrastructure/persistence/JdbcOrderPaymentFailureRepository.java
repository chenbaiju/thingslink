package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.OrderPaymentFailure;
import com.things.link.project.domain.OrderPaymentFailureRepository;
import com.things.link.project.domain.PaymentProvider;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读写支付失败事实（S14-5b）。
 *
 * <p>写入端没有 UPDATE/DELETE，也没有任何状态推进：失败只被记下来。幂等由两层叠加：
 * ① 服务层先按事件 ID 回读并比较完整候选；②
 * {@code sys_tenant_order_payment_failure_event_uk} 唯一索引拒绝同渠道同事件的第二行。
 *
 * <p>失败表与订单/退款/包一样不套租户 RLS：服务以显式 {@code tenantId} 为参数，授权留在应用入口。
 */
@Repository
public class JdbcOrderPaymentFailureRepository implements OrderPaymentFailureRepository {

    /** 失败行投影。 */
    private static final RowMapper<OrderPaymentFailure> FAILURE_ROW_MAPPER = (resultSet, rowNumber) ->
            new OrderPaymentFailure(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("order_id", UUID.class),
                    PaymentProvider.valueOf(resultSet.getString("provider")),
                    resultSet.getString("provider_event_id"),
                    resultSet.getString("failure_code"),
                    resultSet.getLong("amount_cents"),
                    resultSet.getString("currency"),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    resultSet.getTimestamp("created_at").toInstant());

    /** 失败行全列；两处 SELECT 共用，避免漏列导致重建快照失败。 */
    private static final String FAILURE_COLUMNS = """
            id, tenant_id, order_id, provider, provider_event_id, failure_code, amount_cents,
            currency, occurred_at, created_at
            """;

    /** 失败事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcOrderPaymentFailureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID insert(UUID tenantId, UUID orderId, PaymentProvider provider, String providerEventId,
                       String failureCode, long amountCents, String currency, Instant occurredAt) {
        UUID failureId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_order_payment_failure (
                    id, tenant_id, order_id, provider, provider_event_id, failure_code, amount_cents,
                    currency, occurred_at, created_at, updated_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 1)
                """, failureId, tenantId, orderId, provider.name(), providerEventId, failureCode,
                amountCents, currency, Timestamp.from(occurredAt));
        return failureId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<OrderPaymentFailure> findByProviderEventId(PaymentProvider provider,
                                                              String providerEventId) {
        return jdbcTemplate.query("SELECT " + FAILURE_COLUMNS
                        + " FROM sys_tenant_order_payment_failure"
                        + " WHERE provider = ? AND provider_event_id = ?",
                FAILURE_ROW_MAPPER, provider.name(), providerEventId)
                .stream().findFirst();
    }
}
