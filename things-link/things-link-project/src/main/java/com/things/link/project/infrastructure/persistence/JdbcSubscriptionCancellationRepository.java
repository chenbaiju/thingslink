package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.SubscriptionCancellationReceipt;
import com.things.link.project.domain.SubscriptionCancellationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 持事务、显式租户的取消写入；不改原服务区间、金额或来源。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSubscriptionCancellationRepository implements SubscriptionCancellationRepository {
    /** 原事务数据库连接。 */
    private final JdbcTemplate jdbc;
    /** @param jdbc 原事务数据库连接 */
    public JdbcSubscriptionCancellationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** {@inheritDoc} */
    @Override
    public Optional<SubscriptionCancellationReceipt> find(UUID tenant, UUID operation) {
        return jdbc.query("""
                SELECT * FROM sys_subscription_cancellation WHERE tenant_id=? AND operation_id=?
                """,(rs,n) -> new SubscriptionCancellationReceipt(rs.getObject("operation_id",UUID.class),
                rs.getObject("tenant_id",UUID.class),rs.getObject("subscription_id",UUID.class),
                rs.getObject("free_subscription_id",UUID.class),rs.getTimestamp("cancelled_at").toInstant(),
                rs.getString("reason"),rs.getLong("assignment_version_before"),rs.getLong("assignment_version_after")),
                tenant,operation).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Instant currentTime() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class)).toInstant();
    }

    /** {@inheritDoc} */
    @Override
    public boolean cancelActive(UUID tenant, UUID subscription, Instant at) {
        return jdbc.update("""
                UPDATE sys_tenant_subscription SET status='CANCELLED',revision=revision+1,updated_at=?
                 WHERE tenant_id=? AND id=? AND status='ACTIVE' AND source_order_id IS NOT NULL AND ends_at>?
                """,Timestamp.from(at),tenant,subscription,Timestamp.from(at))==1;
    }

    /** {@inheritDoc} */
    @Override
    public void record(SubscriptionCancellationReceipt receipt) {
        int inserted=jdbc.update("""
                INSERT INTO sys_subscription_cancellation(operation_id,tenant_id,subscription_id,free_subscription_id,
                    cancelled_at,reason,assignment_version_before,assignment_version_after)
                SELECT ?,t.id,s.id,f.id,?,?,?,? FROM sys_tenant t
                  JOIN sys_tenant_subscription s ON s.tenant_id=t.id AND s.id=? AND s.status='CANCELLED'
                  JOIN sys_tenant_subscription f ON f.tenant_id=t.id AND f.id=? AND f.status='ACTIVE'
                  JOIN sys_plan_revision r ON r.id=f.plan_revision_id JOIN sys_plan p ON p.id=r.plan_id
                 WHERE t.id=? AND s.source_order_id IS NOT NULL AND f.source_order_id IS NULL
                   AND f.price_cents=0 AND f.ends_at IS NULL AND f.billing_period='NONE' AND p.code='FREE'
                   AND t.quota_policy_assignment_version=?
                """,receipt.operationId(),Timestamp.from(receipt.cancelledAt()),receipt.reason(),
                receipt.assignmentVersionBefore(),receipt.assignmentVersionAfter(),receipt.subscriptionId(),
                receipt.freeSubscriptionId(),receipt.tenantId(),receipt.assignmentVersionAfter());
        if (inserted!=1) throw new IllegalStateException("取消回执与当前终止/FREE事实不一致");
    }
}
