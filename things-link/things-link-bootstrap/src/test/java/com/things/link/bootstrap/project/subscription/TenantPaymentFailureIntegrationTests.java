package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantPaymentFailureService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.OrderPaymentFailure;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.TenantOrder;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * S14-5b 支付失败隔离的真实 PostgreSQL 验收（模拟渠道）。
 *
 * <p>本类钉住五件事：① 失败只留下事实——订单仍可重试、不建任何权益、订阅逐字不动、有效额度不变；
 * ② 失败之后成功支付仍走同一条生效路径，失败事实保留；③ 渠道失败事件 ID 幂等：相同重放返回既有
 * 事实、参数不同显式冲突；④ 与已结算/已取消订单矛盾的迟到失败 fail-closed 且不改动任何事实；
 * ⑤ 拒绝面与一致性不变量（已支付订单不得存在失败事实、失败不产生包或订阅）。
 *
 * <p>不使用方法级 {@code @Transactional}：并发用例必须看到其他连接已提交的事实；清理按外键依赖
 * 顺序执行（审计表禁止 DELETE，且租户 ID 每用例都是新生成的，断言始终按租户限定）。
 */
@DisplayName("S14-5b 支付失败隔离")
class TenantPaymentFailureIntegrationTests extends AbstractIntegrationTest {

    /** 夹具事实写入与核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅与 PLAN_R1_FREE 绑定。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 资源包下单与生效入口（构造待支付/已支付订单的前置事实）。 */
    @Autowired
    private TenantResourcePackageService tenantResourcePackageService;
    /** 被测生产服务：支付失败隔离。 */
    @Autowired
    private TenantPaymentFailureService tenantPaymentFailureService;
    /** 套餐订单服务：取消订单夹具。 */
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
                jdbcTemplate.update(
                        "DELETE FROM sys_tenant_order_payment_failure WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_refund WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
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

    /** 失败只留事实：订单可重试、无权益、订阅与有效额度逐字不变；失败后成功支付仍可生效。 */
    @Test
    void failureLeavesOrderRetryableCreatesNoEntitlementAndRetainsTheFact() {
        UUID tenantId = createFreeTenant("S14-5b 失败隔离租户");
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 2, null);
        Timestamp subscriptionEndsAt = activeSubscriptionEndsAt(tenantId);
        int subscriptionRows = subscriptionCount(tenantId);

        OrderPaymentFailure failure = tenantPaymentFailureService.recordSimulatedPaymentFailure(
                tenantId, order.id(), "INSUFFICIENT_FUNDS", "sim-fail-1", null);

        assertThat(failure.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(failure.amountCents()).as("失败快照取订单金额").isEqualTo(order.amountCents());
        assertThat(failure.currency()).isEqualTo("CNY");
        assertThat(orderState(order.id()))
                .as("失败不得改变订单状态，订单必须仍可重试")
                .containsEntry("status", "CREATED")
                .containsEntry("refunded_cents", 0L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_event_id FROM sys_tenant_order WHERE id = ?", String.class, order.id()))
                .as("失败不得写支付事件 ID（它只属于支付成功）")
                .isNull();
        assertThat(packageCount(tenantId)).as("失败不得创建资源包").isZero();
        assertThat(subscriptionCount(tenantId)).as("失败不得新建或替换订阅行").isEqualTo(subscriptionRows);
        assertThat(activeSubscriptionEndsAt(tenantId)).as("失败不得改动订阅服务期")
                .isEqualTo(subscriptionEndsAt);
        assertThat(effectiveDevicesMax(tenantId)).as("失败不得改变有效额度").isEqualTo(3);
        assertThat(auditCount(tenantId, "commercial.payment.failed")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isZero();

        // 失败之后重试成功：同一条生效路径照常工作，失败事实保留。
        tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(order.id(), "sim-pay-retry-1");
        assertThat(orderState(order.id())).containsEntry("status", "PAID");
        assertThat(packageCount(tenantId)).isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);
        assertThat(failureCount(tenantId)).as("成功支付不得删除或覆盖失败事实").isEqualTo(1);
    }

    /** 事件 ID 幂等：相同重放返回既有事实且只写一行一次审计；参数不同显式冲突。 */
    @Test
    void replayedFailureEventIsIdempotentAndConflictingParametersAreRefused() {
        UUID tenantId = createFreeTenant("S14-5b 失败幂等租户");
        TenantOrder first = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        TenantOrder second = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);

        OrderPaymentFailure original = tenantPaymentFailureService.recordSimulatedPaymentFailure(
                tenantId, first.id(), "TIMEOUT", "sim-fail-idem-1", null);
        OrderPaymentFailure replay = tenantPaymentFailureService.recordSimulatedPaymentFailure(
                tenantId, first.id(), "TIMEOUT", "sim-fail-idem-1", null);

        assertThat(replay.id()).as("相同重放返回既有事实").isEqualTo(original.id());
        assertThat(failureCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.payment.failed")).isEqualTo(1);

        assertThat(failureError(tenantId, first.id(), "CHANNEL_REJECTED", "sim-fail-idem-1", null))
                .as("同一事件 ID 换失败分类必须显式冲突")
                .isEqualTo(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT);
        assertThat(failureError(tenantId, second.id(), "TIMEOUT", "sim-fail-idem-1", null))
                .as("同一事件 ID 换订单必须显式冲突")
                .isEqualTo(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT);
        assertThat(failureCount(tenantId)).as("冲突不得留下第二行").isEqualTo(1);
    }

    /** 与已支付订单矛盾的迟到失败 fail-closed，且订单、订阅、权益、有效额度都不被改动。 */
    @Test
    void lateFailureAfterSettlementIsRefusedAndChangesNothing() {
        UUID tenantId = createFreeTenant("S14-5b 迟到失败租户");
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 2, null);
        tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(order.id(), "sim-pay-late-1");
        Timestamp subscriptionEndsAt = activeSubscriptionEndsAt(tenantId);

        assertThat(failureError(tenantId, order.id(), "TIMEOUT", "sim-fail-late-1", null))
                .as("已支付订单收到失败回调必须 fail-closed")
                .isEqualTo(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT);

        assertThat(failureCount(tenantId)).as("被拒的失败不得留下事实").isZero();
        assertThat(orderState(order.id())).containsEntry("status", "PAID");
        assertThat(packageCount(tenantId)).isEqualTo(1);
        assertThat(packageStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(effectiveDevicesMax(tenantId))
                .as("矛盾失败不得让已生效权益回退（支付故障不得破坏既有订阅）")
                .isEqualTo(5);
        assertThat(activeSubscriptionEndsAt(tenantId)).isEqualTo(subscriptionEndsAt);
    }

    /** 已取消订单拒绝失败回调；同一事件的并发重复回调恰好落一行。 */
    @Test
    void cancelledOrderRefusesFailureAndConcurrentDuplicatesProduceOneRow() throws Exception {
        UUID tenantId = createFreeTenant("S14-5b 并发失败租户");
        TenantOrder cancelled = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        tenantOrderService.cancelSimulatedOrder(cancelled.id());
        assertThat(failureError(tenantId, cancelled.id(), "TIMEOUT", "sim-fail-cancel-1", null))
                .as("已取消订单收到失败回调必须 fail-closed")
                .isEqualTo(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT);

        TenantOrder retryable = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        List<Object> outcomes = runConcurrently(List.of(
                () -> tenantPaymentFailureService.recordSimulatedPaymentFailure(
                        tenantId, retryable.id(), "TIMEOUT", "sim-fail-race-1", null),
                () -> tenantPaymentFailureService.recordSimulatedPaymentFailure(
                        tenantId, retryable.id(), "TIMEOUT", "sim-fail-race-1", null)));

        // 并发同事件有两种合法收敛：一方写入、另一方走幂等重放返回同一条事实；或一方写入、
        // 另一方在唯一索引上冲突。两种情况下都必须只有一行、一次审计。
        List<OrderPaymentFailure> recorded = outcomes.stream()
                .filter(OrderPaymentFailure.class::isInstance)
                .map(OrderPaymentFailure.class::cast)
                .toList();
        assertThat(recorded).as("并发同事件至少一次成功").isNotEmpty();
        assertThat(recorded.stream().map(OrderPaymentFailure::id).distinct())
                .as("所有成功结果必须收敛到同一条失败事实")
                .hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .map(BusinessException::errorCode))
                .as("失败方只能是事件冲突")
                .allMatch(code -> code == ProjectErrorCode.PAYMENT_FAILURE_CONFLICT);
        assertThat(failureCountForOrder(retryable.id())).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.payment.failed"))
                .as("只有实际落库的那次写审计")
                .isEqualTo(1);
        assertThat(orderState(retryable.id())).containsEntry("status", "CREATED");
    }

    /** 拒绝面：未知/跨租户订单、空白事件 ID、非法失败分类、明显未来的发生时刻。 */
    @Test
    void refusesUnknownForeignOrdersAndMalformedInputs() {
        UUID tenantId = createFreeTenant("S14-5b 失败拒绝租户");
        UUID otherTenant = createFreeTenant("S14-5b 失败拒绝租户乙");
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        TenantOrder foreign = tenantResourcePackageService.createSimulatedPackageOrder(
                otherTenant, "DEVICES_MAX", 1, null);

        assertThat(failureError(tenantId, UUID.randomUUID(), "TIMEOUT", "sim-fail-reject-1", null))
                .isEqualTo(ProjectErrorCode.ORDER_NOT_FOUND);
        assertThat(failureError(tenantId, foreign.id(), "TIMEOUT", "sim-fail-reject-2", null))
                .as("跨租户传别人的订单与不存在同码")
                .isEqualTo(ProjectErrorCode.ORDER_NOT_FOUND);
        assertThat(failureError(tenantId, order.id(), "TIMEOUT", "   ", null))
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        assertThat(failureError(tenantId, order.id(), "timeout", "sim-fail-reject-3", null))
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        assertThat(failureError(tenantId, order.id(), "TIMEOUT", "sim-fail-reject-4",
                Instant.now().plus(2, ChronoUnit.HOURS)))
                .as("明显未来的渠道时刻不可信")
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);

        assertThat(failureCount(tenantId)).isZero();
        assertThat(auditCount(tenantId, "commercial.payment.failed")).isZero();
    }

    /** 一致性不变量：已支付订单不得存在失败事实；失败不产生包与订阅。 */
    @Test
    void failureConsistencyInvariantsHold() {
        UUID tenantId = createFreeTenant("S14-5b 失败不变量租户");
        TenantOrder failed = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        tenantPaymentFailureService.recordSimulatedPaymentFailure(
                tenantId, failed.id(), "TIMEOUT", "sim-fail-invariant-1", null);
        TenantOrder paid = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 2, null);
        tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(
                paid.id(), "sim-pay-invariant-1");

        Long paidOrdersWithFailures = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_order_payment_failure f
                  JOIN sys_tenant_order o ON o.id = f.order_id
                 WHERE f.tenant_id = ? AND o.status <> 'CREATED'
                """, Long.class, tenantId);
        assertThat(paidOrdersWithFailures)
                .as("已结算订单不得存在失败事实（冲突必须在写入前被拒绝）")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_package
                 WHERE tenant_id = ? AND source_order_id = ?
                """, Long.class, tenantId, failed.id()))
                .as("失败订单不得产出资源包")
                .isZero();
        assertThat(failureCount(tenantId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助
    // ------------------------------------------------------------------

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
     * 执行一次失败回调并返回业务错误码。
     *
     * @param tenantId 租户 ID
     * @param orderId 订单 ID
     * @param failureCode 失败分类
     * @param providerEventId 渠道事件 ID
     * @param occurredAt 渠道报告时刻
     * @return 抛出的错误码
     */
    private com.things.link.shared.error.ErrorCode failureError(UUID tenantId, UUID orderId,
                                                                String failureCode,
                                                                String providerEventId,
                                                                Instant occurredAt) {
        BusinessException exception = catchThrowableOfType(
                () -> tenantPaymentFailureService.recordSimulatedPaymentFailure(
                        tenantId, orderId, failureCode, providerEventId, occurredAt),
                BusinessException.class);
        assertThat(exception).as("该失败回调必须被拒绝").isNotNull();
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
     * 并发执行一组失败回调，返回结果或业务异常。
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

    /** @param orderId 订单 ID @return 订单状态与已退金额 */
    private Map<String, Object> orderState(UUID orderId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, refunded_cents FROM sys_tenant_order WHERE id = ?", orderId);
        row.put("refunded_cents", ((Number) row.get("refunded_cents")).longValue());
        return row;
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

    /** @param tenantId 租户 ID @return 该租户全部资源包行数 */
    private int packageCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_resource_package WHERE tenant_id = ?",
                Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户唯一资源包的状态 */
    private String packageStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM sys_tenant_resource_package WHERE tenant_id = ?
                """, String.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户全部失败事实行数 */
    private int failureCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_order_payment_failure WHERE tenant_id = ?
                """, Integer.class, tenantId);
    }

    /** @param orderId 订单 ID @return 该订单的失败事实行数 */
    private int failureCountForOrder(UUID orderId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_order_payment_failure WHERE order_id = ?
                """, Integer.class, orderId);
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
