package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.SubscriptionCancellationService;
import com.things.link.project.application.SubscriptionRefundQuoteService;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantSubscriptionChangeService;
import com.things.link.project.domain.SubscriptionRefundQuote;
import com.things.link.project.domain.SubscriptionRefundQuoteRepository;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R7g-1 持久报价真库验收，报价本身不代表退钱或取消。 */
class SubscriptionRefundQuoteIntegrationTests extends AbstractIntegrationTest {
    /** 实际数据库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 报价用例。 */
    @Autowired private SubscriptionRefundQuoteService quotes;
    /** 执行前来源核验协作者。 */
    @Autowired private com.things.link.project.application.SubscriptionProvenanceVerificationService provenance;
    /** 持久回读。 */
    @Autowired private SubscriptionRefundQuoteRepository stored;
    /** 真实模拟支付。 */
    @Autowired private TenantOrderService orders;
    /** 历史已退累计夹具CAS。 */
    @Autowired private TenantOrderRepository orderRepository;
    /** 真实FREE创建。 */
    @Autowired private TenantProvisioning provisioning;
    /** 预约变更。 */
    @Autowired private TenantSubscriptionChangeService changes;
    /** 取消操作身份隔离。 */
    @Autowired private SubscriptionCancellationService cancellations;
    /** 原事务边界。 */
    @Autowired private TransactionTemplate tx;
    /** JSON精确比较。 */
    @Autowired private ObjectMapper json;
    /** 只清理本例身份。 */
    private final List<UUID> tenants=new ArrayList<>();

    /** 独立报价/来源/审计保留至容器销毁，其他业务行只清本例。 */
    @AfterEach
    void cleanup() {
        for (UUID tenant:tenants) {
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",tenant);
        }
    }

    /** 原订单、整日结果、版本与预约同时冻结，报价不改变任何商业状态。 */
    @Test
    void freezesPurchaseAndPendingChangeWithoutRefundingOrCancelling() {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        var pending=changes.requestDowngrade(tenant,revision("FREE"));
        String before=businessSnapshot(tenant);
        var quote=quotes.quote(tenant,sub,operation,"  按剩余期退订  ");
        assertThat(quote.reason()).isEqualTo("按剩余期退订");
        assertThat(quote.algorithm()).isEqualTo(SubscriptionRefundQuote.ALGORITHM);
        assertThat(quote.expiresAt()).isEqualTo(quote.quotedAt().plusSeconds(300));
        assertThat(quote.lines()).hasSize(1);
        assertThat(quote.lines().getFirst().subscriptionId()).isEqualTo(sub);
        assertThat(quote.totalCents()).isEqualTo(quote.lines().getFirst().amountCents());
        assertThat(quote.lines().getFirst().unusedDays()).isEqualTo(quote.lines().getFirst().totalDays());
        assertThat(json.readTree(quote.stateSnapshot()).path("pending").path("id").asText()).isEqualTo(pending.id().toString());
        assertThat(businessSnapshot(tenant)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_refund WHERE tenant_id=?",Integer.class,tenant)).isZero();
        assertThat(quotes.quote(tenant,sub,operation,"按剩余期退订")).isEqualTo(quote);
        assertThat(count(tenant)).isEqualTo(1);
    }

    /** 续费/升级每笔只按实际成交金额定价，不能把抵扣重复加回。 */
    @Test
    void prepaidRenewalAndUpgradeHaveDistinctActualPaidLines() {
        UUID tenant=tenant(), first=buy(tenant), renewal=buy(tenant);
        var upgrade=orders.createSimulatedUpgradeOrder(tenant,revision("ENTERPRISE"));
        UUID current=orders.applySimulatedPaymentSucceeded(upgrade.id(),UUID.randomUUID().toString()).subscriptionId();
        var quote=quotes.quote(tenant,current,UUID.randomUUID(),"整链退订");
        assertThat(quote.lines()).hasSize(3);
        assertThat(quote.lines().stream().map(SubscriptionRefundQuote.Line::subscriptionId)).containsExactly(current,renewal,first);
        assertThat(quote.lines().getFirst().amountCents()).isEqualTo(upgrade.amountCents());
        assertThat(quote.lines().getFirst().refundableCents()).isEqualTo(upgrade.amountCents());
        long paid=jdbc.queryForObject("SELECT sum(amount_cents) FROM sys_tenant_order WHERE tenant_id=? AND status='PAID'",Long.class,tenant);
        assertThat(quote.totalCents()).isEqualTo(paid);
    }

    /** 旧结算累计作为明确历史夹具，已全退祖先保留零行，不超剩余余额。 */
    @Test
    void existingRefundTotalsCapLinesAndPreserveZeroResult() {
        UUID tenant=tenant(), first=buy(tenant);
        UUID order=jdbc.queryForObject("SELECT source_order_id FROM sys_tenant_subscription WHERE id=?",UUID.class,first);
        var fact=orderRepository.findOrder(order).orElseThrow();
        tx.executeWithoutResult(status -> assertThat(orderRepository.reserveRefund(order,fact.amountCents())).isTrue());
        UUID current=buy(tenant);
        var quote=quotes.quote(tenant,current,UUID.randomUUID(),"余额上界");
        assertThat(quote.lines()).hasSize(2);
        assertThat(quote.lines().get(1).refundableCents()).isZero();
        assertThat(quote.lines().get(1).refundedCents()).isEqualTo(fact.amountCents());
        assertThat(quote.totalCents()).isEqualTo(quote.lines().getFirst().refundableCents());
    }

    /** 原身份只恢复原报价，不随续费或预约变化偷偷重新定价。 */
    @Test
    void replayRetainsOriginalSnapshotAfterBusinessChanges() {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        var quote=quotes.quote(tenant,sub,operation,"原报价");
        changes.requestDowngrade(tenant,revision("FREE"));
        UUID renewed=buy(tenant);
        assertThat(quotes.quote(tenant,sub,operation,"原报价")).isEqualTo(quote);
        rejects(tenant,renewed,operation,"原报价",50055);
        rejects(tenant,sub,operation,"换原因",50055);
        assertThat(quotes.quote(tenant,renewed,UUID.randomUUID(),"新报价").currentSubscriptionId()).isEqualTo(renewed);
        assertThat(count(tenant)).isEqualTo(2);
    }

    /** FREE/其他租户/已占取消身份拒绝，原始来源异常仍保持50053。 */
    @Test
    void rejectsInvalidScopeStateAndCancellationIdentity() {
        UUID tenant=tenant(), other=tenant();
        rejects(tenant,current(tenant),UUID.randomUUID(),"免费",50055);
        UUID sub=buy(tenant), operation=UUID.randomUUID();
        rejects(other,sub,UUID.randomUUID(),"跨租户",50055);
        rejects(tenant,sub,operation," ",50055);
        tx.executeWithoutResult(status -> cancellations.cancel(tenant,sub,operation,"取消"));
        UUID renewed=buy(tenant);
        rejects(tenant,renewed,operation,"新报价",50055);
        jdbc.update("UPDATE sys_tenant_subscription SET starts_at=starts_at+interval '1 second' WHERE id=?",renewed);
        rejects(tenant,renewed,UUID.randomUUID(),"来源异常",50053);
        assertThat(count(tenant)).isZero();
    }

    /** 全局ID碰撞不能把别人的报价当作本租户结果。 */
    @Test
    void operationCollisionIsScopedAndRollsBack() {
        UUID tenant=tenant(), sub=buy(tenant), other=tenant(), otherSub=buy(other), operation=UUID.randomUUID();
        var first=quotes.quote(tenant,sub,operation,"原报价");
        rejects(other,otherSub,operation,"原报价",50055);
        assertThat(count(other)).isZero();
        assertThat(quotes.quote(tenant,sub,operation,"原报价")).isEqualTo(first);
    }

    /** 报价/审计同事务，失败不留下貌似可执行的报价。 */
    @Test
    void outerFailureRollsBackQuoteAndAudit() {
        UUID tenant=tenant(), sub=buy(tenant);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            quotes.quote(tenant,sub,UUID.randomUUID(),"外层回滚");
            assertThat(count(tenant)).isEqualTo(1);
            throw new IllegalStateException("after quote insertion");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action='commercial.subscription.refund.quoted'",Integer.class,tenant)).isZero();
    }

    /** 数据库拒绝报价改写/删除，业务清理不能抹除报价证据。 */
    @Test
    void quoteIsImmutableAndSurvivesBusinessCleanup() {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        var quote=quotes.quote(tenant,sub,operation,"报价保留");
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_subscription_refund_quote SET total_cents=0 WHERE operation_id=?",operation)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sys_subscription_refund_quote WHERE operation_id=?",operation)).isInstanceOf(DataAccessException.class);
        jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
        SubscriptionRefundQuote recovered=tx.execute(status -> stored.find(tenant,operation).orElseThrow());
        assertThat(recovered).isEqualTo(quote);
    }

    /** 同一组事实在不同时区连接读取仍相等，跨实例不能因文本偏移误判陈旧。 */
    @Test
    void snapshotComparisonNormalizesDatabaseSessionTimeZones() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"跨时区恢复");
        String snapshot=tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('TimeZone','Asia/Shanghai',true)",String.class);
            var chain=provenance.verifyAndBackfill(tenant,sub);
            return quotes.captureSnapshot(tenant,chain);
        });
        assertThat(json.readTree(snapshot)).isEqualTo(json.readTree(quote.stateSnapshot()));
    }

    /** 确定性错误码。 */
    private void rejects(UUID tenant,UUID sub,UUID operation,String reason,int code) {
        assertThatThrownBy(() -> quotes.quote(tenant,sub,operation,reason)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }
    /** 明确租户隔离。 */
    private UUID tenant() { UUID id=tx.execute(status -> provisioning.createTenant("quote-"+UUID.randomUUID())); tenants.add(id); return id; }
    /** 原模拟支付。 */
    private UUID buy(UUID tenant) { var order=orders.createSimulatedOrder(tenant,revision("STANDARD")); return orders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString()).subscriptionId(); }
    /** 当前生效身份。 */
    private UUID current(UUID tenant) { return jdbc.queryForObject("SELECT id FROM sys_tenant_subscription WHERE tenant_id=? AND status='ACTIVE'",UUID.class,tenant); }
    /** 冻结目录。 */
    private UUID revision(String code) { return jdbc.queryForObject("SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id=r.plan_id WHERE p.code=? AND r.revision_code='product-revision-1'",UUID.class,code); }
    /** 报价计数。 */
    private int count(UUID tenant) { return jdbc.queryForObject("SELECT count(*) FROM sys_subscription_refund_quote WHERE tenant_id=?",Integer.class,tenant); }
    /** 当前核心事实序列化，证明报价没有提前修改权益或资金。 */
    private String businessSnapshot(UUID tenant) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object('tenant',to_jsonb(t),'subscriptions',
                    (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM sys_tenant_subscription s WHERE s.tenant_id=t.id),
                    'orders',(SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM sys_tenant_order o WHERE o.tenant_id=t.id))::text
                  FROM sys_tenant t WHERE t.id=?
                """,String.class,tenant);
    }
}
