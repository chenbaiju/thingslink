package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.SubscriptionRefundQuote;
import com.things.link.project.domain.SubscriptionRefundQuoteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JSON保留整数与原版本；独立不可变报价不跟随业务删除。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSubscriptionRefundQuoteRepository implements SubscriptionRefundQuoteRepository {
    /** 原事务连接。 */
    private final JdbcTemplate jdbc;
    /** 结构化精确数值编解码器。 */
    private final ObjectMapper json;
    /** @param jdbc 原事务连接 @param json JSON编解码 */
    public JdbcSubscriptionRefundQuoteRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    /** {@inheritDoc} */
    @Override
    public Optional<SubscriptionRefundQuote> find(UUID tenant,UUID operation) {
        return jdbc.query("""
                SELECT * FROM sys_subscription_refund_quote WHERE tenant_id=? AND operation_id=?
                """,(rs,n) -> new SubscriptionRefundQuote(rs.getObject("operation_id",UUID.class),
                rs.getObject("tenant_id",UUID.class),rs.getObject("current_subscription_id",UUID.class),
                rs.getLong("assignment_version"),rs.getTimestamp("quoted_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),
                rs.getString("reason"),rs.getString("algorithm"),rs.getLong("total_cents"),rs.getString("state_snapshot"),
                List.of(json.readValue(rs.getString("lines"),SubscriptionRefundQuote.Line[].class))),tenant,operation)
                .stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public void insert(SubscriptionRefundQuote q) {
        jdbc.update("""
                INSERT INTO sys_subscription_refund_quote(operation_id,tenant_id,current_subscription_id,assignment_version,
                    quoted_at,expires_at,reason,algorithm,total_cents,state_snapshot,lines)
                VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """,q.operationId(),q.tenantId(),q.currentSubscriptionId(),q.assignmentVersion(),Timestamp.from(q.quotedAt()),
                Timestamp.from(q.expiresAt()),q.reason(),q.algorithm(),q.totalCents(),q.stateSnapshot(),json.writeValueAsString(q.lines()));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> tenantSnapshot(UUID tenant) {
        return jdbc.query("""
                SELECT jsonb_build_object('id',id,'status',status,'quotaPolicyId',quota_policy_id,
                    'assignmentVersion',quota_policy_assignment_version)::text FROM sys_tenant WHERE id=?
                """,(rs,n) -> rs.getString(1),tenant).stream().findFirst();
    }
}
