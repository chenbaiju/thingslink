package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.SubscriptionProvenanceRepository;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0166 新支付原事务来源快照；各用例唯一租户，账务快照随测试容器回收。 */
class SubscriptionProvenanceIntegrationTests extends AbstractIntegrationTest {

    /** 本例创建的租户；全局生命周期扫描不得看见其他用例遗留业务行。 */
    private final java.util.List<UUID> tenants=new java.util.ArrayList<>();

    /** 只清理本例业务行，独立不可变来源/审计保留到容器销毁。 */
    @AfterEach
    void cleanupBusinessFixtures() {
        for(UUID tenant:tenants) {
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",tenant);
        }
    }

    /** 真实账务查询。 */
    @Autowired private JdbcTemplate jdbc;
    /** 真实订阅订单入口。 */
    @Autowired private TenantOrderService orders;
    /** 原事务创建真实 FREE 租户。 */
    @Autowired private TenantProvisioning provisioning;
    /** 真实来源仓储。 */
    @Autowired private SubscriptionProvenanceRepository provenance;
    /** 故障注入时仍使用真实订单仓储。 */
    @Autowired private com.things.link.project.domain.TenantOrderRepository orderRepository;
    /** 故障注入时仍使用真实生命周期仓储。 */
    @Autowired private com.things.link.project.domain.TenantSubscriptionLifecycleRepository lifecycle;
    /** 故障注入时仍使用真实策略CAS。 */
    @Autowired private com.things.link.project.application.QuotaPolicyAssignmentService assignment;
    /** 故障注入时仍使用真实预约变更服务。 */
    @Autowired private com.things.link.project.application.TenantSubscriptionChangeService changes;
    /** 故障注入时仍使用真实审计。 */
    @Autowired private com.things.link.support.audit.AuditLogService audit;
    /** 明确控制失败回滚边界。 */
    @Autowired private TransactionTemplate tx;

    /** 首购、续费、升级可由明确前驱衔接，重放不产生第二份来源。 */
    @Test
    void preservesPaidChainAndReplay() {
        UUID tenant = tenant();
        UUID free = active(tenant);
        var purchase = orders.createSimulatedOrder(tenant, revision("STANDARD"));
        var first = orders.applySimulatedPaymentSucceeded(purchase.id(), UUID.randomUUID().toString());
        var renewal = orders.createSimulatedOrder(tenant, revision("STANDARD"));
        var second = orders.applySimulatedPaymentSucceeded(renewal.id(), UUID.randomUUID().toString());
        var upgrade = orders.createSimulatedUpgradeOrder(tenant, revision("ENTERPRISE"));
        String event = UUID.randomUUID().toString();
        var third = orders.applySimulatedPaymentSucceeded(upgrade.id(), event);
        assertThat(orders.applySimulatedPaymentSucceeded(upgrade.id(), event).subscriptionId()).isEqualTo(third.subscriptionId());
        assertThat(predecessor(first.subscriptionId())).isEqualTo(free);
        assertThat(predecessor(second.subscriptionId())).isEqualTo(first.subscriptionId());
        assertThat(predecessor(third.subscriptionId())).isEqualTo(second.subscriptionId());
        assertThat(count(tenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT (order_snapshot->>'amount_cents')::bigint FROM sys_subscription_provenance
                 WHERE subscription_id=?
                """, Long.class, third.subscriptionId())).isEqualTo(upgrade.amountCents());
        assertThat(jdbc.queryForObject("""
                SELECT (order_snapshot->>'paid_at') IS NOT NULL AND
                       order_snapshot->>'status'='PAID' AND
                       predecessor_snapshot->>'tenant_id'=tenant_id::text
                  FROM sys_subscription_provenance WHERE subscription_id=?
                """, Boolean.class, third.subscriptionId())).isTrue();
    }

    /** 上层故障撤销账务、来源、订阅及审计，不能留下假来源。 */
    @Test
    void outerFailureRollsBackWholeActivation() {
        UUID tenant = tenant();
        UUID free = active(tenant);
        var order = orders.createSimulatedOrder(tenant, revision("STANDARD"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            orders.applySimulatedPaymentSucceeded(order.id(), UUID.randomUUID().toString());
            assertThat(count(tenant)).isEqualTo(1);
            throw new IllegalStateException("forced rollback after provenance");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(tenant)).isZero();
        assertThat(active(tenant)).isEqualTo(free);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_order WHERE id=?",String.class,order.id()))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action='commercial.subscription.activated'
                """,Integer.class,tenant)).isZero();
    }

    /** 来源写失败也必须使支付失败，不能先成功付款再悄悄丢掉来源。 */
    @Test
    void unavailableProvenanceStorageRollsBackPayment() {
        UUID tenant = tenant();
        var order = orders.createSimulatedOrder(tenant, revision("STANDARD"));
        SubscriptionProvenanceRepository failing = org.mockito.Mockito.mock(SubscriptionProvenanceRepository.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            UUID subscription=invocation.getArgument(1);
            assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_order WHERE id=?",String.class,order.id()))
                    .isEqualTo("PAID");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='commercial.subscription.activated'",
                    Integer.class,subscription)).isEqualTo(1);
            throw new IllegalStateException("provenance write failed after activation and audit");
        }).when(failing).recordActivation(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        TenantOrderService failingOrders = new TenantOrderService(orderRepository,lifecycle,assignment,changes,failing,audit);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                failingOrders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_order WHERE id=?",String.class,order.id()))
                .isEqualTo("CREATED");
    }

    /** 快照不跟随业务状态变化，也不随业务物理清理消失。 */
    @Test
    void immutableSnapshotSurvivesBusinessCleanup() {
        UUID tenant = tenant();
        var order = orders.createSimulatedOrder(tenant, revision("STANDARD"));
        var activation = orders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString());
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_subscription_provenance SET order_snapshot='{}' WHERE subscription_id=?",
                activation.subscriptionId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sys_subscription_provenance WHERE subscription_id=?",
                activation.subscriptionId())).isInstanceOf(DataAccessException.class);
        jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
        assertThat(count(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subscription_snapshot->>'status' FROM sys_subscription_provenance WHERE subscription_id=?",
                String.class,activation.subscriptionId())).isEqualTo("ACTIVE");
    }

    /** 不能用错误租户或跨租户前驱制造来源；事务外不能调用写端口。 */
    @Test
    void rejectsMismatchedScopeAndMissingTransaction() {
        UUID tenant = tenant();
        UUID other = tenant();
        var order = orders.createSimulatedOrder(tenant, revision("STANDARD"));
        var activation = orders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString());
        assertThatThrownBy(() -> provenance.recordActivation(tenant,activation.subscriptionId(),null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                provenance.recordActivation(other,activation.subscriptionId(),null)))
                .isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                provenance.recordActivation(tenant,activation.subscriptionId(),active(other))))
                .isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(count(tenant)).isEqualTo(1);
        assertThat(count(other)).isZero();
    }

    /** 为每例创建隔离身份，容器销毁后整体回收。 */
    private UUID tenant() {
        UUID id=tx.execute(status -> provisioning.createTenant("provenance-"+UUID.randomUUID()));
        tenants.add(id); return id;
    }

    /** 读取当前真实订阅。 */
    private UUID active(UUID tenant) {
        return jdbc.queryForObject("SELECT id FROM sys_tenant_subscription WHERE tenant_id=? AND status='ACTIVE'",UUID.class,tenant);
    }

    /** 读取不可变链节点的前驱。 */
    private UUID predecessor(UUID subscription) {
        return jdbc.queryForObject("SELECT predecessor_id FROM sys_subscription_provenance WHERE subscription_id=?",UUID.class,subscription);
    }

    /** 统计当前用例独占的来源记录。 */
    private int count(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_subscription_provenance WHERE tenant_id=?",Integer.class,tenant);
    }

    /** 使用冻结的目录修订，不造参考售价。 */
    private UUID revision(String code) {
        return jdbc.queryForObject("""
                SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id=r.plan_id
                 WHERE p.code=? AND r.revision_code='product-revision-1'
                """,UUID.class,code);
    }
}
