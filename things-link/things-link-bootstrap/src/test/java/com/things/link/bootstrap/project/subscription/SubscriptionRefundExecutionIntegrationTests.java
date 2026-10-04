package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.SubscriptionCancellationService;
import com.things.link.project.application.SubscriptionRefundExecutionService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R7g-2 原子模拟结算真库验收，绝不连接正式资金渠道。 */
class SubscriptionRefundExecutionIntegrationTests extends AbstractIntegrationTest {
    /** 为局部故障注入取真实协作者，不替换共享上下文Bean。 */
    @Autowired private org.springframework.context.ApplicationContext beans;
    /** 当前真实数据库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 被测执行器。 */
    @Autowired private SubscriptionRefundExecutionService execution;
    /** 正常持久报价入口。 */
    @Autowired private SubscriptionRefundQuoteService quotes;
    /** 明确历史报价/损坏报价测试夹具入口。 */
    @Autowired private SubscriptionRefundQuoteRepository quoteRepository;
    /** 正常模拟支付。 */
    @Autowired private TenantOrderService orders;
    /** 累计变化夹具。 */
    @Autowired private TenantOrderRepository orderRepository;
    /** 创建隔离租户。 */
    @Autowired private TenantProvisioning provisioning;
    /** 当前待生效降级。 */
    @Autowired private TenantSubscriptionChangeService changes;
    /** 独立状态步骤不能冒充结算。 */
    @Autowired private SubscriptionCancellationService cancellations;
    /** 原事务回滚边界。 */
    @Autowired private TransactionTemplate tx;
    /** 只清理本例业务身份。 */
    private final List<UUID> tenants=new ArrayList<>();

    /** 业务清理不删除独立不可变报价、取消、来源和执行证据，后者随容器回收。 */
    @AfterEach
    void cleanup() {
        for (UUID tenant:tenants) {
            jdbc.update("DELETE FROM sys_project WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",tenant);
        }
    }

    /** 首购、预付和升级按原成交逐单退，整组取消/FREE/预约/审计同提交。 */
    @Test
    void settlesWholePurchaseRenewalUpgradeChainAtomically() {
        UUID tenant=tenant();
        buy(tenant); buy(tenant);
        var upgrade=orders.createSimulatedUpgradeOrder(tenant,revision("ENTERPRISE"));
        UUID sub=orders.applySimulatedPaymentSucceeded(upgrade.id(),UUID.randomUUID().toString()).subscriptionId();
        var pending=changes.requestDowngrade(tenant,revision("STANDARD"));
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"整链模拟退款");
        String original=periods(tenant);
        var result=execution.execute(tenant,quote.operationId());
        assertThat(result.totalCents()).isEqualTo(quote.totalCents());
        assertThat(result.settlements()).hasSize(3).allSatisfy(line -> assertThat(line.refundId()).isNotNull());
        assertThat(periods(tenant)).isEqualTo(original);
        assertThat(refunded(tenant)).isEqualTo(quote.totalCents());
        assertThat(refundCount(tenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription WHERE id=?",String.class,sub)).isEqualTo("CANCELLED");
        assertThat(current(tenant)).isEqualTo(result.freeSubscriptionId());
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription_pending_change WHERE id=?",String.class,pending.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_refund WHERE tenant_id=? AND provider='SIMULATED' AND status='SUCCEEDED'",Integer.class,tenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action='commercial.subscription.refund.executed'",Integer.class,tenant)).isEqualTo(1);
    }

    /** 原成功结果优先恢复，之后重新购买不造成新退款或再次回落。 */
    @Test
    void successfulReplayDoesNotChangeRepurchase() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"退款");
        var result=execution.execute(tenant,quote.operationId());
        UUID repurchased=buy(tenant);
        assertThat(execution.execute(tenant,quote.operationId())).isEqualTo(result);
        assertThat(current(tenant)).isEqualTo(repurchased);
        assertThat(refundCount(tenant)).isEqualTo(1);
        assertThat(executionCount(tenant)).isEqualTo(1);
    }

    /** 当前订阅、现行预约、累计、绑定任一变化都使原报价失效。 */
    @Test
    void staleSnapshotsRefuseBeforeAnyRefund() {
        for (String mutation:List.of("renewal","pending","refund-total","binding")) {
            UUID tenant=tenant(), sub=buy(tenant);
            var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"陈旧报价");
            switch(mutation) {
                case "renewal" -> buy(tenant);
                case "pending" -> changes.requestDowngrade(tenant,revision("FREE"));
                case "refund-total" -> tx.executeWithoutResult(status -> orderRepository.reserveRefund(quote.lines().getFirst().orderId(),1));
                case "binding" -> jdbc.update("UPDATE sys_tenant SET quota_policy_assignment_version=quota_policy_assignment_version+1 WHERE id=?",tenant);
                default -> throw new AssertionError(mutation);
            }
            rejects(tenant,quote.operationId());
            assertThat(refundCount(tenant)).isZero();
            assertThat(executionCount(tenant)).isZero();
        }
    }

    /** 持久历史报价和未来报价夹具验证截止与回退，不等待五分钟或依赖机器时钟。 */
    @Test
    void expiredAndFutureQuotesRefuseWithoutMoneyChanges() {
        for (boolean expired:List.of(true,false)) {
            UUID tenant=tenant(), sub=buy(tenant);
            var original=quotes.quote(tenant,sub,UUID.randomUUID(),"时间边界");
            Instant time=expired?original.quotedAt().minusSeconds(600):original.quotedAt().plusSeconds(600);
            var fixture=new SubscriptionRefundQuote(UUID.randomUUID(),tenant,sub,original.assignmentVersion(),time,time.plusSeconds(300),
                    original.reason(),original.algorithm(),original.totalCents(),original.stateSnapshot(),original.lines());
            tx.executeWithoutResult(status -> quoteRepository.insert(fixture));
            rejects(tenant,fixture.operationId());
            assertThat(refundCount(tenant)).isZero();
            assertThat(current(tenant)).isEqualTo(sub);
        }
    }

    /** 取消事实不是资金事实，不能把独立状态步骤当成已完成退款。 */
    @Test
    void cancellationOnlyCannotBeRecoveredAsSuccessfulSettlement() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"只有取消");
        tx.executeWithoutResult(status -> cancellations.cancel(tenant,sub,quote.operationId(),quote.reason()));
        rejects(tenant,quote.operationId());
        assertThat(refundCount(tenant)).isZero();
        assertThat(executionCount(tenant)).isZero();
    }

    /** 外层在执行结果写入后失败，累计、资金、取消/FREE和结果全部撤销。 */
    @Test
    void outerFailureRollsBackEntireSettlementAndCanRetryOriginalQuote() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"整组回滚");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            execution.execute(tenant,quote.operationId());
            assertThat(refundCount(tenant)).isEqualTo(1);
            assertThat(executionCount(tenant)).isEqualTo(1);
            throw new IllegalStateException("failure after final settlement audit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(refunded(tenant)).isZero();
        assertThat(refundCount(tenant)).isZero();
        assertThat(executionCount(tenant)).isZero();
        assertThat(current(tenant)).isEqualTo(sub);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_subscription_cancellation WHERE tenant_id=?",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action IN ('commercial.subscription.refund.executed','commercial.refund.succeeded','commercial.subscription.cancelled')",Integer.class,tenant)).isZero();
        assertThat(execution.execute(tenant,quote.operationId()).totalCents()).isEqualTo(quote.totalCents());
    }

    /** 历史已退祖先仅保留零结算行，不能制造零价退款或重复返还。 */
    @Test
    void zeroAmountAncestorHasNoRefundRow() {
        UUID tenant=tenant(), first=buy(tenant);
        UUID order=jdbc.queryForObject("SELECT source_order_id FROM sys_tenant_subscription WHERE id=?",UUID.class,first);
        var fact=orderRepository.findOrder(order).orElseThrow();
        tx.executeWithoutResult(status -> orderRepository.reserveRefund(order,fact.amountCents()));
        UUID sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"零行保留");
        var result=execution.execute(tenant,quote.operationId());
        assertThat(result.settlements()).hasSize(2);
        assertThat(result.settlements().get(1).amountCents()).isZero();
        assertThat(result.settlements().get(1).refundId()).isNull();
        assertThat(refundCount(tenant)).isEqualTo(1);
    }

    /** 即使持久夹具的快照正确，逐单金额也必须能按冻结算法重算。 */
    @Test
    void inconsistentStoredLineIsRejected() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"损坏报价夹具");
        var line=quote.lines().getFirst();
        var bad=new SubscriptionRefundQuote.Line(line.subscriptionId(),line.orderId(),line.kind(),line.startsAt(),line.endsAt(),
                line.subscriptionRevision(),line.orderRevision(),line.amountCents(),line.refundedCents(),line.totalDays(),line.unusedDays(),line.refundableCents()-1);
        var fixture=new SubscriptionRefundQuote(UUID.randomUUID(),tenant,sub,quote.assignmentVersion(),quote.quotedAt(),quote.expiresAt(),
                quote.reason(),quote.algorithm(),bad.refundableCents(),quote.stateSnapshot(),List.of(bad));
        tx.executeWithoutResult(status -> quoteRepository.insert(fixture));
        rejects(tenant,fixture.operationId());
        assertThat(refundCount(tenant)).isZero();
        assertThat(current(tenant)).isEqualTo(sub);
    }

    /** 错误租户与不存在操作均不能恢复其他人的资金结果。 */
    @Test
    void executionIdentityIsTenantScoped() {
        UUID tenant=tenant(), sub=buy(tenant), other=tenant();
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"隔离");
        rejects(other,quote.operationId());
        rejects(tenant,UUID.randomUUID());
        execution.execute(tenant,quote.operationId());
        rejects(other,quote.operationId());
        assertThat(refundCount(other)).isZero();
    }

    /** 成功回执不可变且独立保留，业务行清理后仍只重放原结果。 */
    @Test
    void immutableResultSurvivesBusinessCleanup() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"结果保留");
        var result=execution.execute(tenant,quote.operationId());
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_subscription_refund_execution SET total_cents=0 WHERE operation_id=?",quote.operationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sys_subscription_refund_execution WHERE operation_id=?",quote.operationId())).isInstanceOf(DataAccessException.class);
        jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
        assertThat(execution.execute(tenant,quote.operationId())).isEqualTo(result);
    }

    /** 同报价第二执行真实等待订单锁，提交后恢复原结果，不能重复退。 */
    @Test
    void concurrentSameQuoteRecoversOriginalResultAfterOrderLock() throws Exception {
        UUID tenant=tenant(); buy(tenant); UUID sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"并发同报价");
        String marker="r7g-same-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<com.things.link.project.domain.SubscriptionRefundExecution>>();
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=tx.execute(status -> {
                var result=execution.execute(tenant,quote.operationId());
                future.set(worker.submit(() -> tx.execute(inner -> { mark(marker); return execution.execute(tenant,quote.operationId()); })));
                awaitLock(observer,marker);
                return result;
            });
            assertThat(future.get().get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(refundCount(tenant)).isEqualTo(2);
        assertThat(refunded(tenant)).isEqualTo(quote.totalCents());
        assertThat(executionCount(tenant)).isEqualTo(1);
    }

    /** 不同报价竞争同链，只能一组成功；后等待者不能在新FREE上重退。 */
    @Test
    void competingQuotesCannotBothSettleSameChain() throws Exception {
        UUID tenant=tenant(); buy(tenant); UUID sub=buy(tenant);
        var first=quotes.quote(tenant,sub,UUID.randomUUID(),"报价一");
        var second=quotes.quote(tenant,sub,UUID.randomUUID(),"报价二");
        String marker="r7g-compete-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                execution.execute(tenant,first.operationId());
                future.set(worker.submit(() -> tx.execute(inner -> { mark(marker); return execution.execute(tenant,second.operationId()); })));
                awaitLock(observer,marker);
            });
            assertConflict(future.get());
        }
        assertThat(refunded(tenant)).isEqualTo(first.totalCents());
        assertThat(refundCount(tenant)).isEqualTo(2);
        assertThat(executionCount(tenant)).isEqualTo(1);
    }

    /** 预约不递增策略版本，执行等待租户锁后仍须重读预约，不能只比较版本。 */
    @Test
    void waitingExecutionRejectsNewPendingChangeAfterTenantLock() throws Exception {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"等待预约");
        String marker="r7g-pending-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                changes.requestDowngrade(tenant,revision("FREE"));
                future.set(worker.submit(() -> tx.execute(inner -> { mark(marker); return execution.execute(tenant,quote.operationId()); })));
                awaitLock(observer,marker);
            });
            assertConflict(future.get());
        }
        assertThat(refunded(tenant)).isZero();
        assertThat(current(tenant)).isEqualTo(sub);
        assertThat(refundCount(tenant)).isZero();
    }

    /** 续费已持租户锁但未提交时，等待执行必须看到新当前身份并拒绝原报价。 */
    @Test
    void waitingExecutionCannotCancelConcurrentRenewal() throws Exception {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"等待续费");
        var renewal=orders.createSimulatedOrder(tenant,revision("STANDARD"));
        String marker="r7g-renewal-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        UUID newSub;
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            newSub=tx.execute(status -> {
                var activated=orders.applySimulatedPaymentSucceeded(renewal.id(),UUID.randomUUID().toString());
                future.set(worker.submit(() -> tx.execute(inner -> { mark(marker); return execution.execute(tenant,quote.operationId()); })));
                awaitLock(observer,marker);
                return activated.subscriptionId();
            });
            assertConflict(future.get());
        }
        assertThat(current(tenant)).isEqualTo(newSub);
        assertThat(refundCount(tenant)).isZero();
        assertThat(refunded(tenant)).isZero();
    }

    /** 第二笔退款存储失败，第一笔已插入资金和累计也必须撤销。 */
    @Test
    void secondRefundStorageFailureRollsBackFirstRefund() {
        UUID tenant=tenant(); buy(tenant); UUID sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"第二笔失败");
        var real=beans.getBean(com.things.link.project.domain.TenantRefundRepository.class);
        var failing=org.mockito.Mockito.mock(com.things.link.project.domain.TenantRefundRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(real));
        var writes=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (writes.incrementAndGet()==2) {
                assertThat(refundCount(tenant)).isEqualTo(1);
                throw new IllegalStateException("second refund storage failed");
            }
            return real.insert(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3),
                    invocation.getArgument(4),invocation.getArgument(5),invocation.getArgument(6),invocation.getArgument(7));
        }).when(failing).insert(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        var service=custom(failing,beans.getBean(com.things.link.project.domain.SubscriptionRefundExecutionRepository.class),
                quoteRepository,beans.getBean(com.things.link.project.domain.SubscriptionCancellationRepository.class));
        assertThatThrownBy(() -> tx.execute(status -> service.execute(tenant,quote.operationId())))
                .isInstanceOf(IllegalStateException.class).hasMessage("second refund storage failed");
        assertRolledBack(tenant,sub);
        assertThat(execution.execute(tenant,quote.operationId()).totalCents()).isEqualTo(quote.totalCents());
    }

    /** 最终结果写入后故障会回滚此前所有资金与FREE事实，不留下仅取消成功。 */
    @Test
    void finalResultStorageFailureRollsBackMoneyAndFree() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"最终结果失败");
        var real=beans.getBean(com.things.link.project.domain.SubscriptionRefundExecutionRepository.class);
        var failing=org.mockito.Mockito.mock(com.things.link.project.domain.SubscriptionRefundExecutionRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(real));
        org.mockito.Mockito.doAnswer(invocation -> {
            real.insert(invocation.getArgument(0));
            assertThat(refundCount(tenant)).isEqualTo(1);
            assertThat(executionCount(tenant)).isEqualTo(1);
            assertThat(current(tenant)).isNotEqualTo(sub);
            throw new IllegalStateException("final result storage failed");
        }).when(failing).insert(org.mockito.ArgumentMatchers.any());
        var service=custom(beans.getBean(com.things.link.project.domain.TenantRefundRepository.class),failing,
                quoteRepository,beans.getBean(com.things.link.project.domain.SubscriptionCancellationRepository.class));
        assertThatThrownBy(() -> tx.execute(status -> service.execute(tenant,quote.operationId())))
                .isInstanceOf(IllegalStateException.class).hasMessage("final result storage failed");
        assertRolledBack(tenant,sub);
        assertThat(execution.execute(tenant,quote.operationId()).totalCents()).isEqualTo(quote.totalCents());
    }

    /** 原成功回执恢复不依赖报价或时钟再读取，原报价过期不能触发再次结算。 */
    @Test
    void successfulReplayDoesNotDependOnClockOrQuoteAvailability() {
        UUID tenant=tenant(), sub=buy(tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"恢复独立性");
        var result=execution.execute(tenant,quote.operationId());
        var unavailableQuote=org.mockito.Mockito.mock(SubscriptionRefundQuoteRepository.class);
        var unavailableClock=org.mockito.Mockito.mock(com.things.link.project.domain.SubscriptionCancellationRepository.class);
        var service=custom(beans.getBean(com.things.link.project.domain.TenantRefundRepository.class),
                beans.getBean(com.things.link.project.domain.SubscriptionRefundExecutionRepository.class),unavailableQuote,unavailableClock);
        var replay=tx.execute(status -> service.execute(tenant,quote.operationId()));
        assertThat(replay).isEqualTo(result);
        org.mockito.Mockito.verifyNoInteractions(unavailableQuote,unavailableClock);
    }

    /** 0310前的真实激活审计链可先完整补录，再由同一报价执行器结算。 */
    @Test
    void legacyAuditedChainBackfillsAndSettlesWithoutInventingOrders() {
        UUID tenant=tenant();
        var withoutLedger=org.mockito.Mockito.mock(com.things.link.project.domain.SubscriptionProvenanceRepository.class);
        var legacy=new TenantOrderService(orderRepository,
                beans.getBean(com.things.link.project.domain.TenantSubscriptionLifecycleRepository.class),
                beans.getBean(com.things.link.project.application.QuotaPolicyAssignmentService.class),changes,withoutLedger,
                beans.getBean(com.things.link.support.audit.AuditLogService.class));
        UUID sub=null;
        for(int i=0;i<2;i++) {
            var order=orders.createSimulatedOrder(tenant,revision("STANDARD"));
            sub=tx.execute(status -> legacy.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString()).subscriptionId());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_subscription_provenance WHERE tenant_id=?",Integer.class,tenant)).isZero();
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"旧证据整链结算");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_subscription_provenance WHERE tenant_id=? AND evidence_kind='VERIFIED_AUDIT'",Integer.class,tenant)).isEqualTo(2);
        assertThat(execution.execute(tenant,quote.operationId()).settlements()).hasSize(2);
        assertThat(refunded(tenant)).isEqualTo(quote.totalCents());
    }

    /** 多次预付与多次升级只退实际原成交，各历史区间及来源订单不回写。 */
    @Test
    void repeatedPrepaymentsAndUpgradesRetainEveryOriginalInterval() {
        UUID tenant=tenant(); buy(tenant); buy(tenant); buy(tenant);
        UUID sub=null;
        for(String tier:List.of("ENTERPRISE","PROFESSIONAL")) {
            var upgrade=orders.createSimulatedUpgradeOrder(tenant,revision(tier));
            sub=orders.applySimulatedPaymentSucceeded(upgrade.id(),UUID.randomUUID().toString()).subscriptionId();
        }
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"多周期多升级");
        String before=periods(tenant);
        assertThat(quote.lines()).hasSize(5);
        var result=execution.execute(tenant,quote.operationId());
        assertThat(result.settlements()).hasSize(5);
        assertThat(refundCount(tenant)).isEqualTo(5);
        assertThat(periods(tenant)).isEqualTo(before);
        assertThat(refunded(tenant)).isEqualTo(result.totalCents());
        assertThat(jdbc.queryForObject("SELECT bool_and(refunded_cents<=amount_cents) FROM sys_tenant_order WHERE tenant_id=?",Boolean.class,tenant)).isTrue();
    }

    /** 已执行降级可核验原来源，但已进入GRACE时仍不具有退款报价资格。 */
    @Test
    void appliedDowngradeHistoryDoesNotBypassGraceEligibility() {
        UUID tenant=tenant();
        var order=orders.createSimulatedOrder(tenant,revision("ENTERPRISE"));
        UUID sub=orders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString()).subscriptionId();
        var pending=changes.requestDowngrade(tenant,revision("STANDARD"));
        beans.getBean(com.things.link.project.application.SubscriptionLifecycleService.class).advance(pending.effectiveAt());
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription WHERE id=?",String.class,sub)).isEqualTo("GRACE");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_tenant_subscription_pending_change WHERE id=?",String.class,pending.id())).isEqualTo("APPLIED");
        assertThat(beans.getBean(com.things.link.project.application.SubscriptionProvenanceVerificationService.class).verifyAndBackfill(tenant,sub)).hasSize(1);
        assertThatThrownBy(() -> quotes.quote(tenant,sub,UUID.randomUUID(),"宽限拒绝"))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode().code()).isEqualTo(50055));
        assertThat(refundCount(tenant)).isZero();
        assertThat(executionCount(tenant)).isZero();
    }

    /** 订阅整链结算保留独立包/调整，之后包部分退款与原订阅重放彼此独立。 */
    @Test
    void subscriptionSettlementAndLaterPackageRefundKeepIndependentEntitlements() {
        UUID tenant=tenant(), sub=buy(tenant);
        var packageService=beans.getBean(com.things.link.project.application.TenantResourcePackageService.class);
        var packageOrder=packageService.createSimulatedPackageOrder(tenant,"DEVICES_MAX",2,null);
        packageService.applySimulatedPackagePaymentSucceeded(packageOrder.id(),UUID.randomUUID().toString());
        beans.getBean(com.things.link.project.application.TenantEntitlementAdjustmentService.class).createAdjustment(tenant,
                new com.things.link.project.application.EntitlementAdjustmentRequest("PROJECTS_MAX",1,
                        Instant.now().minusSeconds(5),Instant.now().plusSeconds(86400),"独立项目容量",UUID.randomUUID(),UUID.randomUUID().toString()));
        var projects=new ArrayList<UUID>();
        for(int i=0;i<4;i++) {
            UUID id=UUID.randomUUID(); projects.add(id);
            jdbc.update("""
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key,status,created_at,updated_at,lifecycle_generation)
                    VALUES(?,?,'refund-combination','sh-1',?,'ACTIVE',?,now(),0)
                    """,id,tenant,"r7h"+id.toString().replace("-",""),java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i)));
        }
        jdbc.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",projects.get(1));
        String addons=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::text FROM sys_tenant_resource_package p WHERE tenant_id=?",String.class,tenant);
        var quote=quotes.quote(tenant,sub,UUID.randomUUID(),"独立权益组合");
        var result=execution.execute(tenant,quote.operationId());
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::text FROM sys_tenant_resource_package p WHERE tenant_id=?",String.class,tenant)).isEqualTo(addons);
        var quota=tx.execute(status -> beans.getBean(com.things.link.project.domain.EffectiveQuotaPolicyRepository.class).findForCommercialLifecycle(tenant).orElseThrow());
        assertThat(quota.devicesMax()).isEqualTo(5);
        assertThat(quota.projectsMax()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_commercial_restriction WHERE tenant_id=? AND status='ACTIVE'",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_commercial_restriction WHERE project_id=?",Integer.class,projects.get(1))).isZero();
        beans.getBean(com.things.link.project.application.TenantRefundService.class).refundPackageOrder(tenant,packageOrder.id(),100,"包部分结算",UUID.randomUUID().toString());
        var after=tx.execute(status -> beans.getBean(com.things.link.project.domain.EffectiveQuotaPolicyRepository.class).findForCommercialLifecycle(tenant).orElseThrow());
        assertThat(after.devicesMax()).isEqualTo(3);
        assertThat(after.projectsMax()).isEqualTo(2);
        assertThat(execution.execute(tenant,quote.operationId())).isEqualTo(result);
        assertThat(current(tenant)).isEqualTo(result.freeSubscriptionId());
        assertThat(refunded(tenant)).isEqualTo(quote.totalCents()+100);
    }

    /** 保留原真实事务组件，只替换待验证故障的最小端口。 */
    private SubscriptionRefundExecutionService custom(com.things.link.project.domain.TenantRefundRepository refunds,
            com.things.link.project.domain.SubscriptionRefundExecutionRepository results,SubscriptionRefundQuoteRepository stored,
            com.things.link.project.domain.SubscriptionCancellationRepository clock) {
        return new SubscriptionRefundExecutionService(stored,results,orderRepository,refunds,
                beans.getBean(com.things.link.project.domain.TenantSubscriptionLifecycleRepository.class),
                beans.getBean(com.things.link.project.application.SubscriptionProvenanceVerificationService.class),quotes,clock,cancellations,
                beans.getBean(com.things.link.support.audit.AuditLogService.class),beans.getBean(tools.jackson.databind.ObjectMapper.class));
    }
    /** 原数据面和控制面事实同时检查。 */
    private void assertRolledBack(UUID tenant,UUID sub) {
        assertThat(refundCount(tenant)).isZero();
        assertThat(executionCount(tenant)).isZero();
        assertThat(refunded(tenant)).isZero();
        assertThat(current(tenant)).isEqualTo(sub);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_subscription_cancellation WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }
    /** 线程结果必须是原报价冲突而非死锁/超时。 */
    private void assertConflict(java.util.concurrent.Future<?> future) {
        assertThatThrownBy(() -> future.get(10,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException)failure.getCause()).errorCode().code()).isEqualTo(50055));
    }
    /** 独立观察连接不占小业务池。 */
    private java.sql.Connection owner() throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }
    /** 标识仅作用本事务。 */
    private void mark(String marker) { jdbc.queryForObject("SELECT set_config('application_name',?,true)",String.class,marker); }
    /** 确认真实订单/租户锁竞争；观察不到就失败，不靠sleep猜测顺序。 */
    private void awaitLock(java.sql.Connection observer,String marker) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        try (var query=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND lower(query) LIKE '%sys_tenant%'")) {
            query.setString(1,marker);
            while(System.nanoTime()<deadline) {
                try(var rows=query.executeQuery()) { rows.next(); if(rows.getInt(1)>0) return; }
            }
        } catch(java.sql.SQLException failure) { throw new IllegalStateException("锁观察失败",failure); }
        throw new AssertionError("未观察到订单或租户锁等待："+marker);
    }

    /** 原报价不可用统一409。 */
    private void rejects(UUID tenant,UUID operation) {
        assertThatThrownBy(() -> execution.execute(tenant,operation)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(50055));
    }
    /** 原事务创建隔离租户。 */
    private UUID tenant() { UUID id=tx.execute(status -> provisioning.createTenant("execute-"+UUID.randomUUID())); tenants.add(id); return id; }
    /** 真实模拟支付。 */
    private UUID buy(UUID tenant) { var order=orders.createSimulatedOrder(tenant,revision("STANDARD")); return orders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString()).subscriptionId(); }
    /** 当前生效身份。 */
    private UUID current(UUID tenant) { return jdbc.queryForObject("SELECT id FROM sys_tenant_subscription WHERE tenant_id=? AND status='ACTIVE'",UUID.class,tenant); }
    /** 冻结目录。 */
    private UUID revision(String code) { return jdbc.queryForObject("SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id=r.plan_id WHERE p.code=? AND r.revision_code='product-revision-1'",UUID.class,code); }
    /** 成功结果数。 */
    private int executionCount(UUID tenant) { return jdbc.queryForObject("SELECT count(*) FROM sys_subscription_refund_execution WHERE tenant_id=?",Integer.class,tenant); }
    /** 原退款资金行数。 */
    private int refundCount(UUID tenant) { return jdbc.queryForObject("SELECT count(*) FROM sys_tenant_refund WHERE tenant_id=?",Integer.class,tenant); }
    /** 原订单累计之和。 */
    private long refunded(UUID tenant) { return jdbc.queryForObject("SELECT coalesce(sum(refunded_cents),0) FROM sys_tenant_order WHERE tenant_id=?",Long.class,tenant); }
    /** 付费原服务期序列化，新FREE不计入。 */
    private String periods(UUID tenant) { return jdbc.queryForObject("SELECT jsonb_agg(jsonb_build_array(id,starts_at,ends_at,source_order_id) ORDER BY id)::text FROM sys_tenant_subscription WHERE tenant_id=? AND source_order_id IS NOT NULL",String.class,tenant); }
}
