package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.SubscriptionProvenanceVerificationService;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.project.application.TenantSubscriptionChangeService;
import com.things.link.project.application.SubscriptionLifecycleService;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.project.domain.SubscriptionProvenanceRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogService;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** ADR0166 新旧来源交叉核验及原子补录，所有夹具用唯一租户并随容器回收。 */
class SubscriptionProvenanceVerificationIntegrationTests extends AbstractIntegrationTest {

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

    /** 真实核验服务。 */
    @Autowired private SubscriptionProvenanceVerificationService verifier;
    /** 当前订单服务。 */
    @Autowired private TenantOrderService orders;
    /** 新租户真实FREE开通。 */
    @Autowired private TenantProvisioning provisioning;
    /** 真实订单仓储。 */
    @Autowired private TenantOrderRepository orderRepository;
    /** 原生命周期仓储。 */
    @Autowired private TenantSubscriptionLifecycleRepository lifecycle;
    /** 原策略CAS。 */
    @Autowired private QuotaPolicyAssignmentService assignment;
    /** 原降级预约服务。 */
    @Autowired private TenantSubscriptionChangeService changes;
    /** 原生命周期推进。 */
    @Autowired private SubscriptionLifecycleService lifecycleService;
    /** 真实审计。 */
    @Autowired private AuditLogService audit;
    /** 原事务边界。 */
    @Autowired private TransactionTemplate tx;
    /** 实际数据库断言。 */
    @Autowired private JdbcTemplate jdbc;

    /** 新支付账本可核验，不重复写入或改变绑定版本。 */
    @Test
    void verifiesNewPurchaseRenewalAndUpgradeWithoutNewFacts() {
        UUID tenant=tenant();
        purchase(orders,tenant,"STANDARD");
        purchase(orders,tenant,"STANDARD");
        var upgrade=orders.createSimulatedUpgradeOrder(tenant,revision("ENTERPRISE"));
        var current=orders.applySimulatedPaymentSucceeded(upgrade.id(),UUID.randomUUID().toString());
        assertThat(verifier.verifyAndBackfill(tenant,current.subscriptionId())).hasSize(3);
        assertThat(verifier.verifyAndBackfill(tenant,current.subscriptionId())).hasSize(3);
        assertThat(count(tenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_subscription_provenance WHERE tenant_id=? AND evidence_kind='VERIFIED_AUDIT'",
                Integer.class,tenant)).isZero();
    }

    /** 模拟0310以前的真实订单与审计，补录后可重复读取且保留全部来源。 */
    @Test
    void atomicallyBackfillsLegacyPaidChain() {
        UUID tenant=tenant();
        var legacy=legacy(audit);
        purchase(legacy,tenant,"STANDARD");
        purchase(legacy,tenant,"STANDARD");
        UUID current=tx.execute(status -> {
            var upgrade=legacy.createSimulatedUpgradeOrder(tenant,revision("ENTERPRISE"));
            return legacy.applySimulatedPaymentSucceeded(upgrade.id(),UUID.randomUUID().toString()).subscriptionId();
        });
        assertThat(count(tenant)).isZero();
        assertThat(verifier.verifyAndBackfill(tenant,current)).hasSize(3);
        assertThat(verifier.verifyAndBackfill(tenant,current)).hasSize(3);
        assertThat(count(tenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT bool_and(evidence_kind='VERIFIED_AUDIT' AND evidence_audit_id IS NOT NULL) FROM sys_subscription_provenance WHERE tenant_id=?",
                Boolean.class,tenant)).isTrue();
    }

    /** 最新节点有效但祖先缺审计，不能留下已验证前缀的补录。 */
    @Test
    void missingAncestorEvidenceLeavesNoPartialBackfill() {
        UUID tenant=tenant();
        purchase(legacy(mock(AuditLogService.class)),tenant,"STANDARD");
        UUID current=purchase(legacy(audit),tenant,"STANDARD");
        refused(tenant,current);
        assertThat(count(tenant)).isZero();
    }

    /** 重复激活审计是冲突，不以最新一条覆盖旧证据。 */
    @Test
    void duplicateAuditPreventsBackfill() {
        UUID tenant=tenant();
        UUID current=purchase(legacy(audit),tenant,"STANDARD");
        jdbc.update("""
                INSERT INTO sys_audit_log(id,tenant_id,project_id,actor_account_id,target_type,target_id,action,details)
                SELECT ?,tenant_id,project_id,actor_account_id,target_type,target_id,action,details
                  FROM sys_audit_log WHERE tenant_id=? AND target_id=? AND action='commercial.subscription.activated'
                """,UUID.randomUUID(),tenant,current);
        refused(tenant,current);
        assertThat(count(tenant)).isZero();
    }

    /** 现有区间与原激活证据矛盾不能被当前行覆盖。 */
    @Test
    void alteredServicePeriodFailsClosed() {
        UUID tenant=tenant();
        UUID current=purchase(legacy(audit),tenant,"STANDARD");
        jdbc.update("UPDATE sys_tenant_subscription SET ends_at=ends_at+interval '1 day' WHERE id=?",current);
        refused(tenant,current);
        assertThat(count(tenant)).isZero();
    }

    /** 别的租户或已经被取代的当前身份均拒绝。 */
    @Test
    void rejectsCrossTenantAndStaleCurrentPointer() {
        UUID tenant=tenant();
        UUID old=purchase(legacy(audit),tenant,"STANDARD");
        UUID current=purchase(legacy(audit),tenant,"STANDARD");
        refused(tenant,old);
        refused(tenant(),current);
        assertThat(count(tenant)).isZero();
    }

    /** 原生命周期真实执行降级后仍可核验原付款来源；核验不恢复ACTIVE或开放退款。 */
    @Test
    void appliedDowngradeExplainsOriginalRevisionDifference() {
        UUID tenant=tenant();
        UUID current=purchase(legacy(audit),tenant,"ENTERPRISE");
        var pending=changes.requestDowngrade(tenant,revision("STANDARD"));
        lifecycleService.advance(pending.effectiveAt());
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription WHERE id=?",String.class,current))
                .isEqualTo("GRACE");
        assertThat(verifier.verifyAndBackfill(tenant,current)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT subscription_snapshot->>'plan_revision_id' FROM sys_subscription_provenance WHERE subscription_id=?",
                String.class,current)).isEqualTo(revision("ENTERPRISE").toString());
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription WHERE id=?",String.class,current))
                .isEqualTo("GRACE");
    }

    /** 没有APPLIED事实与审计的原地换档不是合法降级。 */
    @Test
    void unexplainedRevisionChangeIsRejected() {
        UUID tenant=tenant();
        UUID current=purchase(legacy(audit),tenant,"ENTERPRISE");
        jdbc.update("UPDATE sys_tenant_subscription SET plan_revision_id=? WHERE id=?",revision("STANDARD"),current);
        refused(tenant,current);
        assertThat(count(tenant)).isZero();
    }

    /** 不可变账本能识别当前订单和订阅一起被非法改价，不能仅比对两张当前表。 */
    @Test
    void recordedSourceRejectsMatchingButAlteredCurrentAmounts() {
        UUID tenant=tenant();
        UUID current=purchase(orders,tenant,"STANDARD");
        jdbc.update("UPDATE sys_tenant_order SET amount_cents=amount_cents+1 WHERE id=(SELECT source_order_id FROM sys_tenant_subscription WHERE id=?)",current);
        jdbc.update("UPDATE sys_tenant_subscription SET price_cents=price_cents+1 WHERE id=?",current);
        refused(tenant,current);
        assertThat(count(tenant)).isEqualTo(1);
    }

    /** 外层失败会回滚本次全部历史补录。 */
    @Test
    void outerFailureRollsBackVerifiedBackfill() {
        UUID tenant=tenant();
        UUID current=purchase(legacy(audit),tenant,"STANDARD");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            verifier.verifyAndBackfill(tenant,current);
            assertThat(count(tenant)).isEqualTo(1);
            throw new IllegalStateException("rollback verified provenance");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(tenant)).isZero();
    }

    /** 使用旧支付的实际事务路径，仅省略0310之前不存在的来源写入。 */
    private TenantOrderService legacy(AuditLogService historicalAudit) {
        return new TenantOrderService(orderRepository,lifecycle,assignment,changes,mock(SubscriptionProvenanceRepository.class),historicalAudit);
    }

    /** 明确原事务包住未代理的历史服务，同时生产Bean可加入该事务。 */
    private UUID purchase(TenantOrderService service,UUID tenant,String code) {
        return tx.execute(status -> {
            var order=service.createSimulatedOrder(tenant,revision(code));
            return service.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString()).subscriptionId();
        });
    }

    /** 确定性证据拒绝，不将数据库故障当成通过。 */
    private void refused(UUID tenant,UUID current) {
        assertThatThrownBy(() -> verifier.verifyAndBackfill(tenant,current)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_PROVENANCE_INVALID));
    }

    /** 原事务开通隔离租户。 */
    private UUID tenant() { UUID id=tx.execute(status -> provisioning.createTenant("verify-source-"+UUID.randomUUID())); tenants.add(id); return id; }
    /** 来源条目计数。 */
    private int count(UUID tenant) { return jdbc.queryForObject("SELECT count(*) FROM sys_subscription_provenance WHERE tenant_id=?",Integer.class,tenant); }
    /** 冻结目录修订身份。 */
    private UUID revision(String code) {
        return jdbc.queryForObject("SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id=r.plan_id WHERE p.code=? AND r.revision_code='product-revision-1'",UUID.class,code);
    }
}
