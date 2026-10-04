package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantRefundService;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.RefundStatus;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantRefund;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S14-5a 资源包订单退款的真实 PostgreSQL 验收（模拟渠道，全额退款）。
 *
 * <p>本类钉住六件事：① 全额退款后来源包立刻停止贡献（有效设备上限回落）而**订阅完全不被触碰**；
 * ② 渠道退款流水号幂等：相同重放返回既有事实、参数不同显式冲突；③ 并发双退恰好一笔成功；
 * ④ 拒绝面（未支付/已取消/订阅订单/超剩余金额/跨租户/未知订单/空白原因与流水号）；
 * ⑤ 对账不变量：{@code refunded_cents} 与逐笔成功退款之和逐值一致、REFUNDED 包必有对应成功退款；
 * ⑥ 数据库层拒绝越界退款金额（行级 CHECK 是最终仲裁者）。
 *
 * <p>不使用方法级 {@code @Transactional}：并发用例必须看到其他连接已提交的事实；清理显式按外键
 * 依赖顺序执行（审计表禁止 DELETE 且有租户唯一的新 UUID 作用域，不做清理）。
 */
@DisplayName("S14-5a 资源包订单退款")
class TenantRefundIntegrationTests extends AbstractIntegrationTest {

    /** 夹具事实写入与核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅与 PLAN_R1_FREE 绑定。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 被测生产服务：资源包下单与生效（退款用例的前置事实）。 */
    @Autowired
    private TenantResourcePackageService tenantResourcePackageService;
    /** 被测生产服务：退款。 */
    @Autowired
    private TenantRefundService tenantRefundService;
    /** 套餐订单服务：用于构造「订阅订单不可退款」的前置事实。 */
    @Autowired
    private TenantOrderService tenantOrderService;
    /** 有效权益读取入口（合成后的 devicesMax）。 */
    @Autowired
    private EffectiveQuotaPolicyProvider effectiveQuotaPolicyProvider;
    /** 为 {@code MANDATORY} 的租户创建提供外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户，清理时按依赖顺序回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();

    /** 按外键依赖顺序回收共享容器夹具并清线程范围。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant_refund WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            TenantContext.clear();
            tenantIds.clear();
        }
    }

    /** 全额退款收回包权益、上限回落，且订阅行与服务期逐字不变。 */
    @Test
    void fullRefundRevokesPackageAndDropsEffectiveLimitWithoutTouchingSubscription() {
        UUID tenantId = createFreeTenant("S14-5a 退款租户");
        PurchasedPackage purchased = buyDevicePackage(tenantId, 2, "sim-pkg-refund-1");
        assertThat(effectiveDevicesMax(tenantId)).as("买包后有效设备上限 3 + 2").isEqualTo(5);
        Timestamp subscriptionEndsAt = activeSubscriptionEndsAt(tenantId);
        int subscriptionRows = subscriptionCount(tenantId);

        TenantRefund refund = tenantRefundService.refundPackageOrder(tenantId, purchased.order().id(),
                purchased.order().amountCents(), "客户取消购买", "sim-refund-1");

        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.amountCents()).isEqualTo(purchased.order().amountCents());
        assertThat(refund.currency()).isEqualTo(purchased.order().currency());
        assertThat(packageStatus(purchased.packageId())).isEqualTo("REFUNDED");
        assertThat(effectiveDevicesMax(tenantId)).as("退款即收回权益，上限落回基础档 3").isEqualTo(3);
        assertThat(activeSubscriptionEndsAt(tenantId))
                .as("退款不得改动订阅服务期")
                .isEqualTo(subscriptionEndsAt);
        assertThat(subscriptionCount(tenantId))
                .as("退款不得新建或替换订阅行")
                .isEqualTo(subscriptionRows);
        assertThat(orderRefundedCents(purchased.order().id()))
                .as("订单已退金额必须精确等于订单金额（全额口径）")
                .isEqualTo(purchased.order().amountCents());
        assertThat(auditCount(tenantId, "commercial.refund.succeeded")).isEqualTo(1);

        // 退款后包不再贡献：再次推进时间也不会把它变回 ACTIVE。
        tenantResourcePackageService.advance(Instant.now().plusSeconds(60));
        assertThat(packageStatus(purchased.packageId())).isEqualTo("REFUNDED");
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(3);
    }

    /** 渠道退款流水号幂等：相同重放返回既有事实且只写一行一次审计；参数不同显式冲突。 */
    @Test
    void replayedRefundEventIsIdempotentAndConflictingParametersAreRefused() {
        UUID tenantId = createFreeTenant("S14-5a 退款幂等租户");
        PurchasedPackage first = buyDevicePackage(tenantId, 2, "sim-pkg-refund-2");
        PurchasedPackage second = buyDevicePackage(tenantId, 1, "sim-pkg-refund-3");

        TenantRefund original = tenantRefundService.refundPackageOrder(tenantId, first.order().id(),
                first.order().amountCents(), "工单退款", "sim-refund-idem-1");
        TenantRefund replay = tenantRefundService.refundPackageOrder(tenantId, first.order().id(),
                first.order().amountCents(), "工单退款", "sim-refund-idem-1");

        assertThat(replay.id()).as("重放返回既有退款事实").isEqualTo(original.id());
        assertThat(refundCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.refund.succeeded")).isEqualTo(1);

        BusinessException conflict = catchThrowableOfType(
                () -> tenantRefundService.refundPackageOrder(tenantId, second.order().id(),
                        second.order().amountCents(), "换一笔订单复用同一流水号", "sim-refund-idem-1"),
                BusinessException.class);
        assertThat(conflict).isNotNull();
        assertThat(conflict.errorCode()).isEqualTo(ProjectErrorCode.REFUND_EVENT_CONFLICT);
        assertThat(conflict.errorCode().code()).isEqualTo(50045);
        assertThat(refundCount(tenantId)).as("冲突不得留下第二行").isEqualTo(1);
        assertThat(packageStatus(second.packageId())).as("冲突不得收回另一笔包权益").isEqualTo("ACTIVE");
    }

    /** 并发双退（不同流水号）恰好一笔成功，另一笔被订单金额 CAS 拒绝。 */
    @Test
    void concurrentRefundsOnSameOrderProduceExactlyOneRefund() throws Exception {
        UUID tenantId = createFreeTenant("S14-5a 并发退款租户");
        PurchasedPackage purchased = buyDevicePackage(tenantId, 2, "sim-pkg-refund-4");

        List<Object> outcomes = runConcurrently(List.of(
                () -> tenantRefundService.refundPackageOrder(tenantId, purchased.order().id(),
                        purchased.order().amountCents(), "并发退款甲", "sim-refund-race-1"),
                () -> tenantRefundService.refundPackageOrder(tenantId, purchased.order().id(),
                        purchased.order().amountCents(), "并发退款乙", "sim-refund-race-2")));

        long succeeded = outcomes.stream().filter(TenantRefund.class::isInstance)
                .map(TenantRefund.class::cast).count();
        assertThat(succeeded).as("并发双退恰好一笔成功").isEqualTo(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .map(BusinessException::errorCode))
                .as("失败方只能是「已退款/不可退」或并发流水号冲突")
                .allMatch(code -> code == ProjectErrorCode.ORDER_NOT_REFUNDABLE
                        || code == ProjectErrorCode.REFUND_EVENT_CONFLICT);
        assertThat(refundCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.refund.succeeded")).isEqualTo(1);
        assertThat(orderRefundedCents(purchased.order().id())).isEqualTo(purchased.order().amountCents());
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(3);
    }

    /** 拒绝面：未支付、已取消、订阅订单、超剩余金额、跨租户、未知订单、空白原因与流水号。 */
    @Test
    void refusesUnpaidCancelledSubscriptionOrdersAndExcessAmounts() {
        UUID tenantId = createFreeTenant("S14-5a 退款拒绝租户");
        UUID otherTenant = createFreeTenant("S14-5a 退款拒绝租户乙");

        // 未支付订单不可退款。
        TenantOrder created = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        assertThat(refundError(tenantId, created.id(), created.amountCents(), "原因", "sim-refund-reject-1"))
                .isEqualTo(ProjectErrorCode.ORDER_NOT_REFUNDABLE);

        // 已取消订单不可退款。
        TenantOrder cancelled = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        tenantOrderService.cancelSimulatedOrder(cancelled.id());
        assertThat(orderRow(cancelled.id()).get("status")).isEqualTo("CANCELLED");
        assertThat(refundError(tenantId, cancelled.id(), cancelled.amountCents(), "原因",
                "sim-refund-reject-2")).isEqualTo(ProjectErrorCode.ORDER_NOT_REFUNDABLE);

        // 已支付订单：超剩余金额与已退款都被拒。
        PurchasedPackage purchased = buyDevicePackage(tenantId, 2, "sim-pkg-refund-5");
        assertThat(refundError(tenantId, purchased.order().id(),
                purchased.order().amountCents() + 1, "超额退款", "sim-refund-reject-3"))
                .isEqualTo(ProjectErrorCode.REFUND_AMOUNT_INVALID);
        assertThat(refundError(tenantId, purchased.order().id(), 0, "零金额", "sim-refund-reject-4"))
                .isEqualTo(ProjectErrorCode.REFUND_AMOUNT_INVALID);
        tenantRefundService.refundPackageOrder(tenantId, purchased.order().id(),
                purchased.order().amountCents(), "首次全额退款", "sim-refund-reject-5");
        assertThat(refundError(tenantId, purchased.order().id(), purchased.order().amountCents(),
                "再次退款", "sim-refund-reject-6")).isEqualTo(ProjectErrorCode.ORDER_NOT_REFUNDABLE);

        // 订阅订单（首购/续费/升级补差）不支持退款。
        TenantOrder planOrder = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(planOrder.id(), "sim-pay-plan-1");
        assertThat(refundError(tenantId, planOrder.id(), planOrder.amountCents(), "订阅退款",
                "sim-refund-reject-7"))
                .isEqualTo(ProjectErrorCode.SUBSCRIPTION_ORDER_REFUND_NOT_SUPPORTED);

        // 跨租户与未知订单同码，不做存在性探测。
        PurchasedPackage otherOwned = buyDevicePackage(otherTenant, 2, "sim-pkg-refund-6");
        assertThat(refundError(tenantId, otherOwned.order().id(), otherOwned.order().amountCents(),
                "跨租户", "sim-refund-reject-8")).isEqualTo(ProjectErrorCode.ORDER_NOT_FOUND);
        assertThat(refundError(tenantId, UUID.randomUUID(), 1_000L, "未知订单", "sim-refund-reject-9"))
                .isEqualTo(ProjectErrorCode.ORDER_NOT_FOUND);

        // 空白原因与空白流水号是参数错误。
        assertThat(refundError(tenantId, otherOwned.order().id(), otherOwned.order().amountCents(),
                "   ", "sim-refund-reject-10")).isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        assertThat(refundError(tenantId, otherOwned.order().id(), otherOwned.order().amountCents(),
                "原因", "  ")).isEqualTo(CommonErrorCode.INVALID_PARAMETER);

        assertThat(refundCount(tenantId))
                .as("只有首次全额退款成功，其余被拒请求都不得留下退款事实")
                .isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.refund.succeeded")).isEqualTo(1);
        assertThat(orderRefundedCents(purchased.order().id()))
                .as("失败的退款请求不得改动订单已退金额")
                .isEqualTo(purchased.order().amountCents());
    }

    /** 对账不变量：订单已退金额与逐笔成功退款之和一致；越界金额被数据库行级 CHECK 拒绝。 */
    @Test
    void refundConsistencyInvariantsHoldAndDatabaseRejectsOverRefund() {
        UUID tenantId = createFreeTenant("S14-5a 对账租户");
        PurchasedPackage purchased = buyDevicePackage(tenantId, 2, "sim-pkg-refund-7");
        tenantRefundService.refundPackageOrder(tenantId, purchased.order().id(),
                purchased.order().amountCents(), "对账用例", "sim-refund-consistency-1");

        // 不变量一：订单 refunded_cents 等于该订单全部 SUCCEEDED 退款之和。
        Map<String, Object> order = orderRow(purchased.order().id());
        long refundedCents = ((Number) order.get("refunded_cents")).longValue();
        long amountCents = ((Number) order.get("amount_cents")).longValue();
        Long summed = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount_cents), 0) FROM sys_tenant_refund
                 WHERE order_id = ? AND status = 'SUCCEEDED'
                """, Long.class, purchased.order().id());
        assertThat(refundedCents).as("订单已退金额必须等于逐笔退款之和").isEqualTo(summed);
        assertThat(refundedCents).isEqualTo(amountCents);

        // 不变量二：所有 REFUNDED 的购买包，其来源订单都存在成功退款；且没有包订单仍是 ACTIVE。
        Long orphanRefundedPackages = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_package p
                 WHERE p.tenant_id = ?
                   AND p.status = 'REFUNDED'
                   AND NOT EXISTS (
                        SELECT 1 FROM sys_tenant_refund r
                         WHERE r.order_id = p.source_order_id AND r.status = 'SUCCEEDED')
                """, Long.class, tenantId);
        assertThat(orphanRefundedPackages).as("REFUNDED 包必须有对应的成功退款").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_package
                 WHERE tenant_id = ? AND source = 'PURCHASE' AND status = 'ACTIVE'
                """, Long.class, tenantId)).as("退款后不得再有生效的购买包").isZero();

        // 不变量三：数据库行级 CHECK 拒绝把已退金额推过订单金额（应用层绕过也要失败）。
        DataIntegrityViolationException overRefund = catchThrowableOfType(
                () -> jdbcTemplate.update("""
                        UPDATE sys_tenant_order SET refunded_cents = amount_cents + 1 WHERE id = ?
                        """, purchased.order().id()),
                DataIntegrityViolationException.class);
        assertThat(overRefund).isNotNull();
        assertThat(overRefund.getMessage()).contains("sys_tenant_order_refunded_ck");
    }

    /** ADR0165：首次一分钱即收回整包；补退只结算，不重复失效或改变服务期。 */
    @Test
    void partialSettlementRevokesWholePackageAndLaterRefundOnlyUpdatesMoney() {
        UUID tenant=createFreeTenant("分次结算");
        PurchasedPackage purchased=buyDevicePackage(tenant,2,"partial-package");
        long before=assignmentVersion(tenant);
        tenantRefundService.refundPackageOrder(tenant,purchased.order().id(),1,"终止包并分次结算","partial-one");
        assertThat(packageStatus(purchased.packageId())).isEqualTo("REFUNDED");
        assertThat(effectiveDevicesMax(tenant)).isEqualTo(3);
        assertThat(assignmentVersion(tenant)).isEqualTo(before+1);
        assertThat(refundError(tenant,purchased.order().id(),purchased.order().amountCents(),"超剩余","partial-excess"))
                .isEqualTo(ProjectErrorCode.REFUND_AMOUNT_INVALID);
        tenantRefundService.refundPackageOrder(tenant,purchased.order().id(),purchased.order().amountCents()-1,"补退余额","partial-rest");
        assertThat(assignmentVersion(tenant)).isEqualTo(before+1);
        assertThat(refundCount(tenant)).isEqualTo(2);
        assertThat(auditCount(tenant,"commercial.refund.succeeded")).isEqualTo(2);
        assertReconciled(purchased.order().id(),purchased.order().amountCents());
        assertThat(jdbcTemplate.queryForObject("SELECT details->>'remainingRefundableCents' FROM sys_audit_log WHERE tenant_id=? AND details->>'providerRefundId'='partial-rest'",String.class,tenant)).isEqualTo("0");
    }

    /** 同流水在订单锁后恢复，两个并发调用返回同一已提交事实。 */
    @Test
    void sameRefundEventConvergesAfterWaitingForOrderLock() throws Exception {
        UUID tenant=createFreeTenant("相同退款事件");
        PurchasedPackage p=buyDevicePackage(tenant,2,"same-refund-package");
        List<Object> results;
        try (var blocker=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);
             var observer=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var workers=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM sys_tenant_order WHERE id=? FOR UPDATE")) {
                lock.setObject(1,p.order().id());lock.executeQuery().close();
            }
            Future<TenantRefund> first=workers.submit(() -> tenantRefundService.refundPackageOrder(tenant,p.order().id(),1,"原因","same-refund-event"));
            Future<TenantRefund> second=workers.submit(() -> tenantRefundService.refundPackageOrder(tenant,p.order().id(),1,"原因","same-refund-event"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                int blocked=0;
                while(System.nanoTime()<deadline) {
                    try(var query=observer.createStatement();var rows=query.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%sys_tenant_order%' AND pid<>pg_backend_pid()")) {
                        rows.next();blocked=rows.getInt(1);
                    }
                    if(blocked>=2) break;
                    Thread.yield();
                }
                assertThat(blocked).as("两个事务都在初次未命中流水后等待同一订单锁").isGreaterThanOrEqualTo(2);
            } finally { blocker.commit(); }
            results=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertThat(results).allMatch(TenantRefund.class::isInstance);
        assertThat(results.stream().map(TenantRefund.class::cast).map(TenantRefund::id).distinct()).hasSize(1);
        assertThat(refundCount(tenant)).isEqualTo(1);
        assertReconciled(p.order().id(),1);
    }

    /** 不同流水总额超剩余时只有一笔能占款；剩余仍能合法补退。 */
    @Test
    void concurrentPartialSettlementsCannotOverRefund() throws Exception {
        UUID tenant=createFreeTenant("并发分次结算");
        PurchasedPackage p=buyDevicePackage(tenant,2,"concurrent-partial-package");
        long total=p.order().amountCents();
        List<Object> results=runConcurrently(List.of(
                () -> tenantRefundService.refundPackageOrder(tenant,p.order().id(),total-1,"原因","partial-race-a"),
                () -> tenantRefundService.refundPackageOrder(tenant,p.order().id(),2,"原因","partial-race-b")));
        assertThat(results.stream().filter(TenantRefund.class::isInstance)).hasSize(1);
        long settled=results.stream().filter(TenantRefund.class::isInstance).map(TenantRefund.class::cast).findFirst().orElseThrow().amountCents();
        assertReconciled(p.order().id(),settled);
        tenantRefundService.refundPackageOrder(tenant,p.order().id(),total-settled,"剩余结算","partial-race-rest");
        assertReconciled(p.order().id(),total);
    }

    /** 审计之后任一故障仍回滚金额、包状态、审计及缓存版本事实。 */
    @Test
    void failedPartialSettlementRollsBackAllPersistentConsequences() {
        UUID tenant=createFreeTenant("分次退款回滚");
        PurchasedPackage p=buyDevicePackage(tenant,2,"rollback-partial-package");
        long before=assignmentVersion(tenant);
        var failure=catchThrowableOfType(() -> transactionTemplate.execute(status -> {
            tenantRefundService.refundPackageOrder(tenant,p.order().id(),1,"原因","rollback-partial-event");
            throw new IllegalStateException("post-audit failure");
        }),IllegalStateException.class);
        assertThat(failure).isNotNull();
        assertReconciled(p.order().id(),0);
        assertThat(packageStatus(p.packageId())).isEqualTo("ACTIVE");
        assertThat(assignmentVersion(tenant)).isEqualTo(before);
        assertThat(auditCount(tenant,"commercial.refund.succeeded")).isZero();
        assertThat(effectiveDevicesMax(tenant)).isEqualTo(5);
    }

    /** 过期没有活跃权益：只记资金，不篡改已过期服务窗口或推进策略版本。 */
    @Test
    void expiredPackageSettlementPreservesHistory() {
        UUID tenant=createFreeTenant("过期退款");
        PurchasedPackage p=buyDevicePackage(tenant,2,"expired-partial-package");
        jdbcTemplate.update("UPDATE sys_tenant_resource_package SET status='EXPIRED' WHERE id=?",p.packageId());
        Map<String,Object> original=jdbcTemplate.queryForMap("SELECT starts_at,ends_at FROM sys_tenant_resource_package WHERE id=?",p.packageId());
        long before=assignmentVersion(tenant);
        tenantRefundService.refundPackageOrder(tenant,p.order().id(),1,"原因","expired-partial-event");
        assertThat(packageStatus(p.packageId())).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForMap("SELECT starts_at,ends_at FROM sys_tenant_resource_package WHERE id=?",p.packageId())).isEqualTo(original);
        assertThat(assignmentVersion(tenant)).isEqualTo(before);
        assertReconciled(p.order().id(),1);
    }

    /** 特制合法long金额快照验证累加与求和不回绕，不代表该参考价实际可售。 */
    @Test
    void longBoundarySettlementsStayExact() {
        UUID tenant=createFreeTenant("大额数值边界");
        PurchasedPackage p=buyDevicePackage(tenant,2,"long-partial-package");
        jdbcTemplate.update("UPDATE sys_tenant_order SET amount_cents=? WHERE id=?",Long.MAX_VALUE,p.order().id());
        tenantRefundService.refundPackageOrder(tenant,p.order().id(),Long.MAX_VALUE-2,"数值验证","long-partial-first");
        tenantRefundService.refundPackageOrder(tenant,p.order().id(),2,"数值验证","long-partial-rest");
        assertReconciled(p.order().id(),Long.MAX_VALUE);
        assertThat(refundError(tenant,p.order().id(),1,"超额","long-partial-over")).isEqualTo(ProjectErrorCode.ORDER_NOT_REFUNDABLE);
    }

    private long assignmentVersion(UUID tenant) {
        return jdbcTemplate.queryForObject("SELECT quota_policy_assignment_version FROM sys_tenant WHERE id=?",Long.class,tenant);
    }
    private void assertReconciled(UUID order,long expected) {
        assertThat(((Number)orderRow(order).get("refunded_cents")).longValue()).isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT coalesce(sum(amount_cents),0) FROM sys_tenant_refund WHERE order_id=? AND status='SUCCEEDED'",Long.class,order)).isEqualTo(expected);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助
    // ------------------------------------------------------------------

    /** 一次成功购买得到的订单与包。 */
    private record PurchasedPackage(TenantOrder order, UUID packageId) {
    }

    /**
     * 走真实租户创建入口，拿到默认 FREE 订阅与 PLAN_R1_FREE 绑定。
     *
     * @param label 租户名
     * @return 新租户 ID
     */
    private UUID createFreeTenant(String label) {
        UUID tenantId = transactionTemplate.execute(status -> tenantProvisioning.createTenant(label));
        assertThat(tenantId).isNotNull();
        tenantIds.add(tenantId);
        return tenantId;
    }

    /**
     * 买一个设备资源包并断言已生效。
     *
     * @param tenantId 租户 ID
     * @param amount 包额度
     * @param eventId 模拟支付事件 ID
     * @return 订单与包 ID
     */
    private PurchasedPackage buyDevicePackage(UUID tenantId, long amount, String eventId) {
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", amount, null);
        UUID packageId = tenantResourcePackageService
                .applySimulatedPackagePaymentSucceeded(order.id(), eventId).packageId();
        return new PurchasedPackage(order, packageId);
    }

    /**
     * 执行一次退款请求并返回业务错误码。
     *
     * @param tenantId 租户 ID
     * @param orderId 订单 ID
     * @param amountCents 退款金额
     * @param reason 退款原因
     * @param providerRefundId 渠道退款流水号
     * @return 抛出的错误码
     */
    private com.things.link.shared.error.ErrorCode refundError(UUID tenantId, UUID orderId,
                                                               long amountCents, String reason,
                                                               String providerRefundId) {
        BusinessException exception = catchThrowableOfType(
                () -> tenantRefundService.refundPackageOrder(tenantId, orderId, amountCents, reason,
                        providerRefundId),
                BusinessException.class);
        assertThat(exception).as("该退款请求必须被拒绝").isNotNull();
        return exception.errorCode();
    }

    /**
     * 经既有投影读取合成后的设备上限（需要真实租户上下文才能命中带 RLS 的权威查询）。
     *
     * @param tenantId 租户 ID
     * @return 有效设备上限
     */
    private long effectiveDevicesMax(UUID tenantId) {
        TenantContext.set(new TenantScope(tenantId, null, UUID.randomUUID()));
        try {
            return effectiveQuotaPolicyProvider.resolveTrustedTenant(tenantId).planQuota().devicesMax();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 并发执行一组退款调用，返回结果或业务异常。
     *
     * @param calls 待并发执行的调用
     * @return 与调用顺序对应的结果列表
     * @throws Exception 线程池等待被中断时直接失败
     */
    private List<Object> runConcurrently(List<Callable<Object>> calls) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(calls.size());
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    try {
                        return call.call();
                    } catch (BusinessException exception) {
                        return exception;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅服务期终点 */
    private Timestamp activeSubscriptionEndsAt(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户全部订阅行数（含历史） */
    private int subscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户全部退款行数 */
    private int refundCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_refund WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param orderId 订单 ID @return 订单上的已退金额（人民币分） */
    private long orderRefundedCents(UUID orderId) {
        Long refunded = jdbcTemplate.queryForObject(
                "SELECT refunded_cents FROM sys_tenant_order WHERE id = ?", Long.class, orderId);
        return refunded == null ? 0L : refunded;
    }

    /** @param orderId 订单 ID @return 订单金额与已退金额 */
    private Map<String, Object> orderRow(UUID orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT amount_cents, refunded_cents, status FROM sys_tenant_order WHERE id = ?", orderId);
    }

    /** @param packageId 包 ID @return 包状态 */
    private String packageStatus(UUID packageId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_resource_package WHERE id = ?", String.class, packageId);
    }

    /** @param planCode 档位编码 @return {@code product-revision-1} 对应档位的修订版 ID */
    private UUID revisionId(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT r.id FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = 'product-revision-1'
                """, UUID.class, planCode);
    }

    /**
     * 统计某租户某动作的商业审计行数。
     *
     * @param tenantId 租户 ID
     * @param action 动作编码
     * @return 审计行数
     */
    private int auditCount(UUID tenantId, String action) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log WHERE tenant_id = ? AND action = ?
                """, Integer.class, tenantId, action);
    }
}
