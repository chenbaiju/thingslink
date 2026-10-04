package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.*;
import com.things.link.project.domain.SubscriptionCancellationReceipt;
import com.things.link.project.domain.SubscriptionCancellationRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R7f-2：真实PG原事务取消，所有资金仍为模拟；不连接正式渠道。 */
class SubscriptionCancellationIntegrationTests extends AbstractIntegrationTest {
    /** 实际数据库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 取消状态步骤。 */
    @Autowired private SubscriptionCancellationService cancellations;
    /** 回执读取。 */
    @Autowired private SubscriptionCancellationRepository receipts;
    /** 模拟购买。 */
    @Autowired private TenantOrderService orders;
    /** 商业限制恢复的真实协作者。 */
    @Autowired private CommercialProjectAccessService access;
    /** 原事务租户创建。 */
    @Autowired private TenantProvisioning provisioning;
    /** 预约降级。 */
    @Autowired private TenantSubscriptionChangeService changes;
    /** 独立资源包。 */
    @Autowired private TenantResourcePackageService packages;
    /** 原有效加数。 */
    @Autowired private TenantEntitlementAdjustmentService adjustments;
    /** 强制事务边界。 */
    @Autowired private TransactionTemplate tx;
    /** 本例拥有的租户。 */
    private final List<UUID> tenants=new ArrayList<>();

    /** 只清理本例业务行，不删除不可变回执/审计，后者随测试容器回收。 */
    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID tenant:tenants) {
            jdbc.update("DELETE FROM sys_project WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",tenant);
        }
    }

    /** 原始区间及来源不变，取消预约/策略/回执同提交，重复操作零变化。 */
    @Test
    void cancelsPaidSubscriptionWithRealFreeAndPendingChange() {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        String before=original(sub);
        long version=version(tenant);
        var pending=changes.requestDowngrade(tenant,revision("FREE"));
        var receipt=cancel(tenant,sub,operation,"  主动取消  ");
        assertThat(original(sub)).isEqualTo(before);
        assertThat(state(sub)).isEqualTo("CANCELLED");
        assertThat(receipt.reason()).isEqualTo("主动取消");
        assertThat(receipt.assignmentVersionBefore()).isEqualTo(version);
        assertThat(version(tenant)).isEqualTo(version+1);
        assertThat(current(tenant)).isEqualTo(receipt.freeSubscriptionId());
        assertThat(jdbc.queryForObject("""
                SELECT price_cents=0 AND source_order_id IS NULL AND ends_at IS NULL AND billing_period='NONE'
                  FROM sys_tenant_subscription WHERE id=?
                """,Boolean.class,receipt.freeSubscriptionId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription_pending_change WHERE id=?",String.class,pending.id()))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT details->>'reason' FROM sys_audit_log WHERE target_id=?
                   AND action='commercial.subscription.downgrade.cancelled'
                """,String.class,pending.id())).isEqualTo("SUBSCRIPTION_CANCELLED");
        assertThat(cancel(tenant,sub,operation,"主动取消")).isEqualTo(receipt);
        assertThat(count(tenant)).isEqualTo(1);
        assertThat(version(tenant)).isEqualTo(version+1);
    }

    /** 新购买不能被旧操作回放撤销；旧回执明确保留原FREE身份。 */
    @Test
    void replayAfterRepurchaseDoesNotChangeCurrentEntitlement() {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        var receipt=cancel(tenant,sub,operation,"取消");
        UUID repurchased=buy(tenant);
        long version=version(tenant);
        assertThat(cancel(tenant,sub,operation,"取消")).isEqualTo(receipt);
        assertThat(current(tenant)).isEqualTo(repurchased);
        assertThat(state(receipt.freeSubscriptionId())).isEqualTo("SUPERSEDED");
        assertThat(version(tenant)).isEqualTo(version);
        rejects(tenant,sub,operation,"不同内容",50054);
        rejects(tenant,repurchased,operation,"取消",50054);
        rejects(tenant,sub,UUID.randomUUID(),"取消",50054);
    }

    /** 预付续费的未来起点合法，前驱原行不重激活也不回写。 */
    @Test
    void prepaidRenewalCanBeCancelledWithoutRewritingAncestor() {
        UUID tenant=tenant(), first=buy(tenant), renewal=buy(tenant);
        assertThat(jdbc.queryForObject("SELECT starts_at>now() FROM sys_tenant_subscription WHERE id=?",Boolean.class,renewal)).isTrue();
        String original=original(first);
        cancel(tenant,renewal,UUID.randomUUID(),"预付取消");
        assertThat(state(first)).isEqualTo("SUPERSEDED");
        assertThat(original(first)).isEqualTo(original);
        assertThat(state(renewal)).isEqualTo("CANCELLED");
    }

    /** FREE、其他租户、陈旧、已到期与GRACE均不能当作现行付费取消。 */
    @Test
    void rejectsIneligibleOrMismatchedStateWithoutReceipt() {
        UUID tenant=tenant(), other=tenant();
        rejects(tenant,current(tenant),UUID.randomUUID(),"FREE",50054);
        UUID sub=buy(tenant);
        rejects(other,sub,UUID.randomUUID(),"跨租户",50054);
        rejects(tenant,UUID.randomUUID(),UUID.randomUUID(),"陈旧",50054);
        jdbc.update("UPDATE sys_tenant_subscription SET starts_at=now()-interval '40 days',ends_at=now()-interval '1 day' WHERE id=?",sub);
        rejects(tenant,sub,UUID.randomUUID(),"已过期",50054);
        jdbc.update("UPDATE sys_tenant_subscription SET status='GRACE',grace_ends_at=ends_at+interval '14 days' WHERE id=?",sub);
        rejects(tenant,sub,UUID.randomUUID(),"宽限",50054);
        assertThat(count(tenant)).isZero();
        assertThat(count(other)).isZero();
    }

    /** 来源可核验也不能掩盖链上非SUPERSEDED的异常历史状态。 */
    @Test
    void rejectsGraceAncestorWithoutHidingItBehindNewFree() {
        UUID tenant=tenant(), first=buy(tenant), renewed=buy(tenant);
        jdbc.update("UPDATE sys_tenant_subscription SET status='GRACE',grace_ends_at=ends_at+interval '14 days' WHERE id=?",first);
        rejects(tenant,renewed,UUID.randomUUID(),"祖先异常",50054);
        assertThat(state(first)).isEqualTo("GRACE");
        assertThat(current(tenant)).isEqualTo(renewed);
        assertThat(count(tenant)).isZero();
    }

    /** 来源不一致在任何权益改变前拒绝，不能靠当前行看似付费来绕过来源。 */
    @Test
    void invalidProvenanceRejectsWithNoMutation() {
        UUID tenant=tenant(), sub=buy(tenant);
        jdbc.update("UPDATE sys_tenant_subscription SET starts_at=starts_at+interval '1 second' WHERE id=?",sub);
        long version=version(tenant);
        rejects(tenant,sub,UUID.randomUUID(),"证据冲突",50053);
        assertThat(state(sub)).isEqualTo("ACTIVE");
        assertThat(version(tenant)).isEqualTo(version);
        assertThat(count(tenant)).isZero();
    }

    /** 独立包/调整保留，FREE项目配额有界，用户归档不伪造商业限制。 */
    @Test
    void preservesIndependentAddonsAndUserArchive() {
        UUID tenant=tenant(), sub=buy(tenant);
        var order=packages.createSimulatedPackageOrder(tenant,"DEVICES_MAX",2,null);
        packages.applySimulatedPackagePaymentSucceeded(order.id(),UUID.randomUUID().toString());
        String packageBefore=jdbc.queryForObject("SELECT row_to_json(p)::text FROM sys_tenant_resource_package p WHERE source_order_id=?",String.class,order.id());
        adjustments.createAdjustment(tenant,new EntitlementAdjustmentRequest("PROJECTS_MAX",1,
                Instant.now().minusSeconds(5),Instant.now().plusSeconds(86400),"独立容量",UUID.randomUUID(),UUID.randomUUID().toString()));
        var projects=projects(tenant,4);
        jdbc.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",projects.get(1));
        cancel(tenant,sub,UUID.randomUUID(),"取消基础订阅");
        assertThat(jdbc.queryForObject("SELECT row_to_json(p)::text FROM sys_tenant_resource_package p WHERE source_order_id=?",String.class,order.id()))
                .isEqualTo(packageBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_project WHERE id=?",String.class,projects.getFirst())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_commercial_restriction WHERE tenant_id=? AND status='ACTIVE'",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_commercial_restriction WHERE project_id=?",Integer.class,projects.get(1))).isZero();
    }

    /** 后续故障回滚当前状态、FREE、预约、策略、项目、回执与取消审计。 */
    @Test
    void outerFailureRollsBackEveryStateStep() {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        var pending=changes.requestDowngrade(tenant,revision("FREE"));
        var projects=projects(tenant,3);
        long version=version(tenant);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            cancellations.cancel(tenant,sub,operation,"故障回滚");
            assertThat(count(tenant)).isEqualTo(1);
            throw new IllegalStateException("rollback after all cancellation steps");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(sub)).isEqualTo("ACTIVE");
        assertThat(current(tenant)).isEqualTo(sub);
        assertThat(version(tenant)).isEqualTo(version);
        assertThat(count(tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription_pending_change WHERE id=?",String.class,pending.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project WHERE tenant_id=? AND status='ACTIVE'",Integer.class,tenant)).isEqualTo(projects.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_commercial_restriction WHERE tenant_id=?",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action='commercial.subscription.cancelled'",Integer.class,tenant)).isZero();
    }

    /** 不可变回执独立保留；写端口不可在事务外调用。 */
    @Test
    void immutableReceiptSurvivesCleanupAndRequiresTransaction() {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        assertThatThrownBy(() -> cancellations.cancel(tenant,sub,operation,"取消"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var receipt=cancel(tenant,sub,operation,"取消");
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_subscription_cancellation SET reason='changed' WHERE operation_id=?",operation))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sys_subscription_cancellation WHERE operation_id=?",operation))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
        assertThat(cancel(tenant,sub,operation,"取消")).isEqualTo(receipt);
    }

    /** 原因校验与跨租户操作ID冲突不得留下第二租户取消事实。 */
    @Test
    void globalOperationCollisionRollsBackOtherTenant() {
        UUID tenant=tenant(), sub=buy(tenant), other=tenant(), otherSub=buy(other), operation=UUID.randomUUID();
        rejects(tenant,sub,operation,"  ",50054);
        rejects(tenant,sub,operation,"x".repeat(501),50054);
        cancel(tenant,sub,operation,"取消");
        long version=version(other);
        rejects(other,otherSub,operation,"取消",50054);
        assertThat(state(otherSub)).isEqualTo("ACTIVE");
        assertThat(current(other)).isEqualTo(otherSub);
        assertThat(version(other)).isEqualTo(version);
        assertThat(count(other)).isZero();
    }

    /** 第二取消在数据库确实等待第一取消，提交后只读取原回执。 */
    @Test
    void concurrentSameOperationReturnsOneReceiptAfterTenantLock() throws Exception {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        String marker="r7f-duplicate-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<SubscriptionCancellationReceipt>>();
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=tx.execute(status -> {
                var result=cancellations.cancel(tenant,sub,operation,"并发取消");
                future.set(worker.submit(() -> tx.execute(inner -> {
                    mark(marker);
                    return cancellations.cancel(tenant,sub,operation,"并发取消");
                })));
                awaitTenantLock(observer,marker);
                return result;
            });
            assertThat(future.get().get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(count(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action='commercial.subscription.cancelled'",Integer.class,tenant)).isEqualTo(1);
    }

    /** 取消先提交，已下单的购买在租户锁放行后取代新FREE，旧取消重放不撤销购买。 */
    @Test
    void waitingPaymentActivatesAfterCancellationWithoutResurrectingOldSubscription() throws Exception {
        UUID tenant=tenant(), sub=buy(tenant), operation=UUID.randomUUID();
        var renewal=orders.createSimulatedOrder(tenant,revision("STANDARD"));
        String marker="r7f-pay-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<UUID>>();
        SubscriptionCancellationReceipt receipt;
        UUID purchased;
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            receipt=tx.execute(status -> {
                var result=cancellations.cancel(tenant,sub,operation,"取消先到");
                future.set(worker.submit(() -> tx.execute(inner -> {
                    mark(marker);
                    return orders.applySimulatedPaymentSucceeded(renewal.id(),UUID.randomUUID().toString()).subscriptionId();
                })));
                awaitTenantLock(observer,marker);
                return result;
            });
            purchased=future.get().get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(current(tenant)).isEqualTo(purchased);
        assertThat(state(sub)).isEqualTo("CANCELLED");
        assertThat(state(receipt.freeSubscriptionId())).isEqualTo("SUPERSEDED");
        assertThat(cancel(tenant,sub,operation,"取消先到")).isEqualTo(receipt);
        assertThat(current(tenant)).isEqualTo(purchased);
        assertThat(jdbc.queryForObject("SELECT predecessor_id FROM sys_subscription_provenance WHERE subscription_id=?",UUID.class,purchased))
                .isEqualTo(receipt.freeSubscriptionId());
    }

    /** 续费先提交，取消旧订阅等待后必须冲突，不能取消新周期。 */
    @Test
    void waitingCancellationRejectsOldSubscriptionAfterRenewalCommits() throws Exception {
        UUID tenant=tenant(), sub=buy(tenant);
        var renewal=orders.createSimulatedOrder(tenant,revision("STANDARD"));
        String marker="r7f-stale-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<SubscriptionCancellationReceipt>>();
        UUID renewed;
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            renewed=tx.execute(status -> {
                UUID result=orders.applySimulatedPaymentSucceeded(renewal.id(),UUID.randomUUID().toString()).subscriptionId();
                future.set(worker.submit(() -> tx.execute(inner -> {
                    mark(marker);
                    return cancellations.cancel(tenant,sub,UUID.randomUUID(),"陈旧取消");
                })));
                awaitTenantLock(observer,marker);
                return result;
            });
            assertThatThrownBy(() -> future.get().get(10,java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class)
                    .satisfies(failure -> assertThat(((BusinessException)failure.getCause()).errorCode().code()).isEqualTo(50054));
        }
        assertThat(current(tenant)).isEqualTo(renewed);
        assertThat(state(sub)).isEqualTo("SUPERSEDED");
        assertThat(count(tenant)).isZero();
    }

    /** 恢复器等待取消事务后必须按已提交的FREE额度处理，不能恢复刚限制的超额项目。 */
    @Test
    void waitingRestorerCannotUndoCancellationRestriction() throws Exception {
        UUID tenant=tenant(), sub=buy(tenant);
        var projects=projects(tenant,3);
        String marker="r7f-restorer-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                cancellations.cancel(tenant,sub,UUID.randomUUID(),"取消限制");
                future.set(worker.submit(() -> tx.execute(inner -> {
                    mark(marker);
                    return access.restoreEligible(tenant,Instant.now());
                })));
                awaitTenantLock(observer,marker);
            });
            assertThat(future.get().get(10,java.util.concurrent.TimeUnit.SECONDS)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM sys_project WHERE id=?",String.class,projects.getFirst())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project WHERE tenant_id=? AND status='ARCHIVED'",Integer.class,tenant)).isEqualTo(2);
    }

    /** 独立观察连接不占业务池。 */
    private java.sql.Connection owner() throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }
    /** 仅标识本次事务，连接归还后自动清除。 */
    private void mark(String marker) { jdbc.queryForObject("SELECT set_config('application_name',?,true)",String.class,marker); }
    /** 有界观察数据库锁等待，禁止固定延时假定并发时序。 */
    private void awaitTenantLock(java.sql.Connection observer,String marker) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        try (var query=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND lower(query) LIKE '%sys_tenant%'")) {
            query.setString(1,marker);
            while (System.nanoTime()<deadline) {
                try (var rows=query.executeQuery()) { rows.next(); if (rows.getInt(1)>0) return; }
            }
        } catch(java.sql.SQLException failure) { throw new IllegalStateException("锁观察失败",failure); }
        throw new AssertionError("未观察到预期租户锁等待："+marker);
    }

    /** 明确原事务调用。 */
    private SubscriptionCancellationReceipt cancel(UUID tenant,UUID sub,UUID operation,String reason) {
        return tx.execute(status -> cancellations.cancel(tenant,sub,operation,reason));
    }
    /** 确定性业务拒绝码。 */
    private void rejects(UUID tenant,UUID sub,UUID operation,String reason,int code) {
        assertThatThrownBy(() -> cancel(tenant,sub,operation,reason)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }
    /** 真实隔离租户。 */
    private UUID tenant() { UUID id=tx.execute(s -> provisioning.createTenant("cancel-"+UUID.randomUUID())); tenants.add(id); return id; }
    /** 真实模拟支付。 */
    private UUID buy(UUID tenant) { var order=orders.createSimulatedOrder(tenant,revision("STANDARD")); return orders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString()).subscriptionId(); }
    /** 当前生效身份。 */
    private UUID current(UUID tenant) { return jdbc.queryForObject("SELECT id FROM sys_tenant_subscription WHERE tenant_id=? AND status='ACTIVE'",UUID.class,tenant); }
    /** 冻结目录。 */
    private UUID revision(String code) { return jdbc.queryForObject("SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id=r.plan_id WHERE p.code=? AND r.revision_code='product-revision-1'",UUID.class,code); }
    /** 原始服务期、金额与来源的精确序列化。 */
    private String original(UUID sub) { return jdbc.queryForObject("SELECT jsonb_build_array(starts_at,ends_at,price_cents,source_order_id)::text FROM sys_tenant_subscription WHERE id=?",String.class,sub); }
    /** 当前状态。 */
    private String state(UUID sub) { return jdbc.queryForObject("SELECT status FROM sys_tenant_subscription WHERE id=?",String.class,sub); }
    /** 当前绑定版本。 */
    private long version(UUID tenant) { return jdbc.queryForObject("SELECT quota_policy_assignment_version FROM sys_tenant WHERE id=?",Long.class,tenant); }
    /** 回执计数。 */
    private int count(UUID tenant) { return jdbc.queryForObject("SELECT count(*) FROM sys_subscription_cancellation WHERE tenant_id=?",Integer.class,tenant); }
    /** 有序超额历史夹具，不作为创建准入证据。 */
    private List<UUID> projects(UUID tenant,int count) {
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<count;i++) {
            UUID id=UUID.randomUUID(); ids.add(id);
            jdbc.update("""
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key,status,created_at,updated_at,lifecycle_generation)
                    VALUES(?,?,'cancellation-history','sh-1',?,'ACTIVE',?,now(),0)
                    """,id,tenant,"r7fc"+id.toString().replace("-",""),Timestamp.from(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i)));
        }
        return ids;
    }
}
