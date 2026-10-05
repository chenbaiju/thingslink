package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.SubscriptionProvenanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

/** 只从同租户已支付事实插入来源快照；失败必须回滚整个生效事务。 */
@Repository
public class JdbcSubscriptionProvenanceRepository implements SubscriptionProvenanceRepository {

    /** 当前支付事务使用的数据源。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 当前事务的数据访问器 */
    public JdbcSubscriptionProvenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordActivation(UUID tenantId, UUID subscriptionId, UUID predecessorId) {
        Objects.requireNonNull(tenantId, "来源租户不得为空");
        Objects.requireNonNull(subscriptionId, "来源订阅不得为空");
        int inserted = jdbc.update("""
                INSERT INTO sys_subscription_provenance (
                    subscription_id,tenant_id,order_id,predecessor_id,evidence_kind,
                    order_snapshot,subscription_snapshot,predecessor_snapshot)
                SELECT s.id,s.tenant_id,o.id,p.id,'LIVE_ACTIVATION',to_jsonb(o),to_jsonb(s),
                       CASE WHEN p.id IS NULL THEN NULL ELSE to_jsonb(p) END
                  FROM sys_tenant_subscription s
                  JOIN sys_tenant_order o ON o.id=s.source_order_id AND o.tenant_id=s.tenant_id
                  LEFT JOIN sys_tenant_subscription p ON p.id=? AND p.tenant_id=s.tenant_id
                 WHERE s.tenant_id=? AND s.id=? AND s.status='ACTIVE'
                   AND o.status='PAID' AND o.paid_at IS NOT NULL
                   AND o.order_kind IN ('PURCHASE','UPGRADE')
                   AND s.plan_revision_id=o.plan_revision_id AND s.price_cents=o.amount_cents
                   AND s.currency=o.currency AND s.ends_at IS NOT NULL
                   AND (?::uuid IS NULL OR (p.id IS NOT NULL AND p.status='SUPERSEDED'))
                """, predecessorId, tenantId, subscriptionId, predecessorId);
        if (inserted != 1) {
            throw new IllegalStateException("订阅来源不完整或租户/金额不一致，拒绝提交生效事实");
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<Facts> current(UUID tenantId, UUID subscriptionId) {
        return jdbc.query("""
                SELECT to_jsonb(s)::text AS subscription_json,to_jsonb(o)::text AS order_json,p.code
                  FROM sys_tenant_subscription s
                  JOIN sys_plan_revision r ON r.id=s.plan_revision_id JOIN sys_plan p ON p.id=r.plan_id
                  LEFT JOIN sys_tenant_order o ON o.id=s.source_order_id AND o.tenant_id=s.tenant_id
                 WHERE s.tenant_id=? AND s.id=?
                """,(rs,row) -> new Facts(rs.getString("subscription_json"),rs.getString("order_json"),rs.getString("code")),
                tenantId,subscriptionId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<Stored> find(UUID tenantId, UUID subscriptionId) {
        return jdbc.query("""
                SELECT order_id,predecessor_id,order_snapshot::text,subscription_snapshot::text,
                       predecessor_snapshot::text,evidence_kind,evidence_audit_id
                  FROM sys_subscription_provenance WHERE tenant_id=? AND subscription_id=?
                """,(rs,row) -> new Stored(rs.getObject("order_id",UUID.class),rs.getObject("predecessor_id",UUID.class),
                rs.getString("order_snapshot"),rs.getString("subscription_snapshot"),rs.getString("predecessor_snapshot"),
                rs.getString("evidence_kind"),rs.getObject("evidence_audit_id",UUID.class)),tenantId,subscriptionId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<String> appliedChanges(UUID tenantId, UUID subscriptionId) {
        return jdbc.query("""
                SELECT to_jsonb(c)::text AS fact FROM sys_tenant_subscription_pending_change c
                 WHERE tenant_id=? AND subscription_id=? AND status='APPLIED'
                """,(rs,row) -> rs.getString("fact"),tenantId,subscriptionId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordVerified(UUID tenantId, UUID subscriptionId, Stored source) {
        jdbc.update("""
                INSERT INTO sys_subscription_provenance(subscription_id,tenant_id,order_id,predecessor_id,
                    evidence_kind,evidence_audit_id,order_snapshot,subscription_snapshot,predecessor_snapshot)
                VALUES(?,?,?,?,'VERIFIED_AUDIT',?,?::jsonb,?::jsonb,?::jsonb)
                """,subscriptionId,tenantId,source.orderId(),source.predecessorId(),source.evidenceAuditId(),
                source.order(),source.subscription(),source.predecessor());
    }
}
