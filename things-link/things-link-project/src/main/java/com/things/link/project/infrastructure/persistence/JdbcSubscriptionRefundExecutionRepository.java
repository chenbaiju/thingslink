package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.SubscriptionRefundExecution;
import com.things.link.project.domain.SubscriptionRefundExecutionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 保存成功回执之前再次核对同租户报价和取消身份，全部失败同事务回滚。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSubscriptionRefundExecutionRepository implements SubscriptionRefundExecutionRepository {
    /** 原事务连接。 */
    private final JdbcTemplate jdbc;
    /** 原始整数结果编解码。 */
    private final ObjectMapper json;
    /** @param jdbc 数据库连接 @param json JSON编解码 */
    public JdbcSubscriptionRefundExecutionRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<SubscriptionRefundExecution> find(UUID tenant,UUID operation) {
        return jdbc.query("""
                SELECT * FROM sys_subscription_refund_execution WHERE tenant_id=? AND operation_id=?
                """,(rs,n) -> new SubscriptionRefundExecution(rs.getObject("operation_id",UUID.class),rs.getObject("tenant_id",UUID.class),
                rs.getObject("subscription_id",UUID.class),rs.getObject("free_subscription_id",UUID.class),
                rs.getTimestamp("executed_at").toInstant(),rs.getLong("total_cents"),
                List.of(json.readValue(rs.getString("settlements"),SubscriptionRefundExecution.Settlement[].class))),tenant,operation)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void insert(SubscriptionRefundExecution result) {
        int inserted=jdbc.update("""
                INSERT INTO sys_subscription_refund_execution(operation_id,tenant_id,subscription_id,free_subscription_id,
                    executed_at,total_cents,settlements)
                SELECT q.operation_id,q.tenant_id,c.subscription_id,c.free_subscription_id,c.cancelled_at,q.total_cents,?::jsonb
                  FROM sys_subscription_refund_quote q JOIN sys_subscription_cancellation c
                    ON c.operation_id=q.operation_id AND c.tenant_id=q.tenant_id AND c.subscription_id=q.current_subscription_id
                 WHERE q.operation_id=? AND q.tenant_id=? AND c.subscription_id=? AND c.free_subscription_id=?
                   AND c.cancelled_at=? AND q.total_cents=? AND c.cancelled_at>=q.quoted_at AND c.cancelled_at<q.expires_at
                """,json.writeValueAsString(result.settlements()),result.operationId(),result.tenantId(),result.subscriptionId(),
                result.freeSubscriptionId(),Timestamp.from(result.executedAt()),result.totalCents());
        if (inserted!=1) throw new IllegalStateException("模拟结算结果与原报价/取消不一致");
    }
}
