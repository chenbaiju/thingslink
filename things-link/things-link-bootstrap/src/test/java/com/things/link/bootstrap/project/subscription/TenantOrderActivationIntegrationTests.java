package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.SubscriptionActivation;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S14-3a 模拟订单与幂等订阅生效的真实 PostgreSQL 验收（架构文档 §3.2，S14-0 P5）。
 *
 * <p>本类钉住七件事：① 首次购买用参考价作为模拟金额快照，关闭旧 FREE 并把运行时策略绑到该修订的
 * 配额模板；② 同一支付事件重放是纯 no-op（订阅行、到期时刻、绑定版本、审计计数全不变）；
 * ③ 续费从上一 {@code endsAt} **延长**，绝不从 {@code paid_at} 重算；④ 同一订单的并发重复支付
 * 恰好生效一次；⑤ 同租户两个订单并发支付后仍只有一条 ACTIVE 订阅且服务期顺序叠加；
 * ⑥ 已取消/已支付订单与 FREE 档的拒绝码；⑦ 下单/支付/生效/取消都有商业审计。
 *
 * <p>不使用方法级 {@code @Transactional}：并发用例必须看到其他连接已提交的事实，清理显式按外键
 * 依赖顺序执行。金额断言写死 298000（￥2,980 参考年价）而不是复用生产常量，避免两边同时改错。
 */
@DisplayName("S14-3a 模拟订单与幂等订阅生效")
class TenantOrderActivationIntegrationTests extends AbstractIntegrationTest {

    /** 订单、订阅、租户指针与审计事实的核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅与 PLAN_R1_FREE 绑定。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 被测生产服务：模拟订单与支付成功生效。 */
    @Autowired
    private TenantOrderService tenantOrderService;
    /** 为 {@code MANDATORY} 的租户创建提供外层业务事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户，清理时按依赖顺序回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();

    /**
     * 按外键依赖顺序回收共享容器夹具。
     *
     * <p>{@code sys_audit_log} 刻意不清理：审计表由数据库触发器钉成不可变（不允许 UPDATE/DELETE），
     * 这是它的设计要求。断言按本用例独有的 {@code tenant_id} 过滤，因此残留审计不会干扰其他用例。
     */
    @AfterEach
    void cleanUp() {
        for (UUID tenantId : tenantIds) {
            jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        }
        tenantIds.clear();
    }

    /** 首次购买：参考价金额快照、旧 FREE 被取代、新 ACTIVE 服务期与策略绑定全部正确。 */
    @Test
    void firstPurchaseActivatesRevisionPolicyWithReferencePriceSnapshot() {
        UUID tenantId = createFreeTenant("S14-3a 首购租户");
        UUID freeSubscriptionId = activeSubscriptionId(tenantId);

        TenantOrder order = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        assertThat(order.status()).isEqualTo(TenantOrderStatus.CREATED);
        assertThat(order.provider()).isEqualTo(PaymentProvider.SIMULATED);
        assertThat(order.amountCents())
                .as("模拟订单金额取修订版参考价 ￥2,980，不是成交价")
                .isEqualTo(298000L);
        assertThat(order.currency()).isEqualTo("CNY");
        assertThat(order.providerEventId()).isNull();
        assertThat(order.paidAt()).isNull();
        assertThat(assignmentVersion(tenantId)).as("下单本身不推进策略绑定版本").isEqualTo(2L);

        SubscriptionActivation activation = pay(order.id(), "sim-evt-first");
        assertThat(activation.activated()).isTrue();
        assertThat(activation.endsAt())
                .as("首次购买按 UTC 日历加一年")
                .isEqualTo(activation.startsAt().atZone(ZoneOffset.UTC).plusYears(1).toInstant());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM sys_tenant_subscription WHERE id = ?
                """, String.class, freeSubscriptionId))
                .as("首次购买把长期 FREE 置为终态 SUPERSEDED，而不是删除")
                .isEqualTo("SUPERSEDED");
        assertThat(subscriptionCount(tenantId)).isEqualTo(2);
        assertThat(activeSubscriptionCount(tenantId)).isEqualTo(1);

        Map<String, Object> active = activeSubscriptionRow(tenantId);
        assertThat(active.get("plan_code")).isEqualTo("STANDARD");
        assertThat(active.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) active.get("price_cents")).longValue()).isEqualTo(298000L);
        assertThat(active.get("currency")).isEqualTo("CNY");
        assertThat(active.get("billing_period")).isEqualTo("YEAR");
        assertThat(active.get("renewal_mode"))
                .as("支付未开发，不得写 AUTO 暗示不存在的自动续费承诺")
                .isEqualTo("MANUAL");
        assertThat(active.get("source_order_id")).isEqualTo(order.id());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quota_policy_id FROM sys_tenant WHERE id = ?", UUID.class, tenantId))
                .as("生效后运行时策略指针必须绑到该修订版的 PLAN_R1_STANDARD")
                .isEqualTo(quotaPolicyId("STANDARD"));
        assertThat(assignmentVersion(tenantId))
                .as("注册推进到 2，首次付费生效恰好再推进一次到 3")
                .isEqualTo(3L);

        assertThat(orderStatus(order.id())).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_event_id FROM sys_tenant_order WHERE id = ?", String.class, order.id()))
                .isEqualTo("sim-evt-first");
        assertThat(auditCount(tenantId, "commercial.order.created")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.order.paid")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.subscription.activated")).isEqualTo(1);
    }

    /** 同一支付事件重放：不再建订阅、不延长服务期、不推进绑定版本、不重复审计。 */
    @Test
    void replayingSameProviderEventIsNoOp() {
        UUID tenantId = createFreeTenant("S14-3a 重放租户");
        TenantOrder order = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        SubscriptionActivation first = pay(order.id(), "sim-evt-replay");

        int subscriptions = subscriptionCount(tenantId);
        int activeCount = activeSubscriptionCount(tenantId);
        Instant activeEndsAt = activeEndsAt(tenantId);
        long version = assignmentVersion(tenantId);
        int createdAudits = auditCount(tenantId, "commercial.order.created");
        int paidAudits = auditCount(tenantId, "commercial.order.paid");
        int activatedAudits = auditCount(tenantId, "commercial.subscription.activated");

        SubscriptionActivation replay = tenantOrderService.applySimulatedPaymentSucceeded(
                order.id(), "sim-evt-replay");

        assertThat(replay.activated()).isFalse();
        assertThat(replay.subscriptionId())
                .as("重放返回该订单既有订阅，而不是新建一条")
                .isEqualTo(first.subscriptionId());
        assertThat(subscriptionCount(tenantId)).isEqualTo(subscriptions);
        assertThat(activeSubscriptionCount(tenantId)).isEqualTo(activeCount);
        assertThat(activeEndsAt(tenantId)).isEqualTo(activeEndsAt);
        assertThat(assignmentVersion(tenantId)).isEqualTo(version);
        assertThat(auditCount(tenantId, "commercial.order.created")).isEqualTo(createdAudits);
        assertThat(auditCount(tenantId, "commercial.order.paid")).isEqualTo(paidAudits);
        assertThat(auditCount(tenantId, "commercial.subscription.activated")).isEqualTo(activatedAudits);
    }

    /** 续费从上一到期日延长恰好一个周期：支付时刻早于服务期起点也不影响结果。 */
    @Test
    void renewalExtendsFromPreviousEndsAtExactlyOnceAndNeverFromPaidAt() {
        UUID tenantId = createFreeTenant("S14-3a 续费租户");
        TenantOrder firstOrder = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        pay(firstOrder.id(), "sim-evt-renew-1");

        Instant previousEndsAt = Instant.parse("2027-01-15T00:00:00Z");
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription SET ends_at = ?
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(previousEndsAt), tenantId);

        TenantOrder renewalOrder = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        SubscriptionActivation renewal = pay(renewalOrder.id(), "sim-evt-renew-2");

        assertThat(renewal.startsAt())
                .as("续费起点等于上一服务期终点")
                .isEqualTo(previousEndsAt);
        assertThat(renewal.endsAt())
                .as("续费终点是上一到期日加一年；若从 paid_at 重算会落在 2027 年而非 2028 年")
                .isEqualTo(Instant.parse("2028-01-15T00:00:00Z"));
        Instant paidAt = jdbcTemplate.queryForObject(
                "SELECT paid_at FROM sys_tenant_order WHERE id = ?", Timestamp.class, renewalOrder.id())
                .toInstant();
        assertThat(paidAt).as("支付确实发生在上一服务期终点之前").isBefore(previousEndsAt);

        assertThat(subscriptionCount(tenantId)).isEqualTo(3);
        assertThat(activeSubscriptionCount(tenantId)).isEqualTo(1);
        assertThat(activeEndsAt(tenantId)).isEqualTo(Instant.parse("2028-01-15T00:00:00Z"));
        assertThat(assignmentVersion(tenantId)).as("两次真实生效各推进一次：3 → 4").isEqualTo(4L);
        assertThat(orderStatus(firstOrder.id())).isEqualTo("PAID");
        assertThat(orderStatus(renewalOrder.id())).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status = 'SUPERSEDED'
                """, Integer.class, tenantId)).isEqualTo(2);
    }

    /** 同一订单的并发重复支付：订单行锁下恰好一个生效，另一个是幂等重放。 */
    @Test
    void concurrentDuplicatePaymentActivatesExactlyOnce() throws Exception {
        UUID tenantId = createFreeTenant("S14-3a 并发重放租户");
        TenantOrder order = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));

        List<Object> outcomes = payConcurrently(List.of(
                () -> tenantOrderService.applySimulatedPaymentSucceeded(order.id(), "sim-evt-race"),
                () -> tenantOrderService.applySimulatedPaymentSucceeded(order.id(), "sim-evt-race")));

        long activated = outcomes.stream()
                .filter(SubscriptionActivation.class::isInstance)
                .map(SubscriptionActivation.class::cast)
                .filter(SubscriptionActivation::activated)
                .count();
        long replayed = outcomes.stream()
                .filter(SubscriptionActivation.class::isInstance)
                .map(SubscriptionActivation.class::cast)
                .filter(outcome -> !outcome.activated())
                .count();
        assertThat(activated).as("并发重复支付恰好生效一次").isEqualTo(1);
        assertThat(replayed).as("另一个必须是幂等重放").isEqualTo(1);

        assertThat(subscriptionCount(tenantId)).isEqualTo(2);
        assertThat(activeSubscriptionCount(tenantId)).isEqualTo(1);
        assertThat(assignmentVersion(tenantId)).isEqualTo(3L);
        assertThat(auditCount(tenantId, "commercial.order.paid")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.subscription.activated")).isEqualTo(1);
    }

    /** 同租户两个订单并发支付：任一提交时刻只有一条 ACTIVE，服务期按顺序叠加。 */
    @Test
    void concurrentPaymentsOfTwoOrdersForSameTenantLeaveExactlyOneActiveSubscription() throws Exception {
        UUID tenantId = createFreeTenant("S14-3a 并发双单租户");
        TenantOrder firstOrder = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        TenantOrder secondOrder = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));

        List<Object> outcomes = payConcurrently(List.of(
                () -> tenantOrderService.applySimulatedPaymentSucceeded(firstOrder.id(), "sim-evt-two-1"),
                () -> tenantOrderService.applySimulatedPaymentSucceeded(secondOrder.id(), "sim-evt-two-2")));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome)
                .as("两个订单都必须成功生效，不能因并发而丢失一笔")
                .isInstanceOf(SubscriptionActivation.class));
        assertThat(activeSubscriptionCount(tenantId))
                .as("租户行锁 + 部分唯一索引保证任一提交时刻只有一条 ACTIVE")
                .isEqualTo(1);
        assertThat(subscriptionCount(tenantId)).isEqualTo(3);
        assertThat(orderStatus(firstOrder.id())).isEqualTo("PAID");
        assertThat(orderStatus(secondOrder.id())).isEqualTo("PAID");
        assertThat(assignmentVersion(tenantId)).isEqualTo(4L);

        List<Map<String, Object>> paidPeriods = jdbcTemplate.queryForList("""
                SELECT starts_at, ends_at FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status IN ('ACTIVE', 'SUPERSEDED')
                   AND price_cents > 0
                 ORDER BY starts_at
                """, tenantId);
        assertThat(paidPeriods).hasSize(2);
        assertThat(((Timestamp) paidPeriods.get(1).get("starts_at")).toInstant())
                .as("后一个服务期从前一个的终点开始，未重复延长")
                .isEqualTo(((Timestamp) paidPeriods.get(0).get("ends_at")).toInstant());
    }

    /** 拒绝面：FREE 不可下单、已取消/已支付订单不可支付、未知订单 404。 */
    @Test
    void refusesFreePlanCancelledPaidAndUnknownOrdersWithRegisteredCodes() {
        UUID tenantId = createFreeTenant("S14-3a 拒绝租户");

        BusinessException freeRefusal = catchThrowableOfType(
                () -> tenantOrderService.createSimulatedOrder(tenantId, revisionId("FREE")),
                BusinessException.class);
        assertThat(freeRefusal).isNotNull();
        assertThat(freeRefusal.errorCode()).isEqualTo(ProjectErrorCode.FREE_PLAN_NOT_ORDERABLE);
        assertThat(freeRefusal.errorCode().code()).isEqualTo(50021);

        TenantOrder cancelled = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.cancelSimulatedOrder(cancelled.id());
        assertThat(orderStatus(cancelled.id())).isEqualTo("CANCELLED");
        assertThat(auditCount(tenantId, "commercial.order.cancelled")).isEqualTo(1);
        BusinessException cancelledPayment = catchThrowableOfType(
                () -> tenantOrderService.applySimulatedPaymentSucceeded(cancelled.id(), "sim-evt-cancelled"),
                BusinessException.class);
        assertThat(cancelledPayment).isNotNull();
        assertThat(cancelledPayment.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_PAYABLE);
        assertThat(cancelledPayment.errorCode().code()).isEqualTo(50023);

        BusinessException secondCancel = catchThrowableOfType(
                () -> tenantOrderService.cancelSimulatedOrder(cancelled.id()), BusinessException.class);
        assertThat(secondCancel).isNotNull();
        assertThat(secondCancel.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(secondCancel.errorCode().code()).isEqualTo(50025);

        TenantOrder paid = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        pay(paid.id(), "sim-evt-paid-once");
        BusinessException secondPayment = catchThrowableOfType(
                () -> tenantOrderService.applySimulatedPaymentSucceeded(paid.id(), "sim-evt-paid-twice"),
                BusinessException.class);
        assertThat(secondPayment).isNotNull();
        assertThat(secondPayment.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_PAYABLE);
        assertThat(subscriptionCount(tenantId))
                .as("第二次不同的支付事件不得多建订阅")
                .isEqualTo(2);

        BusinessException unknown = catchThrowableOfType(
                () -> tenantOrderService.applySimulatedPaymentSucceeded(UUID.randomUUID(), "sim-evt-unknown"),
                BusinessException.class);
        assertThat(unknown).isNotNull();
        assertThat(unknown.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_FOUND);
        assertThat(unknown.errorCode().code()).isEqualTo(50024);
    }

    /**
     * 并发执行一组支付调用，返回成功结果或业务异常。
     *
     * @param calls 待并发执行的调用
     * @return 与调用顺序对应的结果列表
     * @throws Exception 线程池等待被中断时直接失败
     */
    private List<Object> payConcurrently(List<java.util.concurrent.Callable<Object>> calls) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(calls.size());
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (var call : calls) {
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
     * 发起一次模拟支付并断言确实生效。
     *
     * @param orderId 订单 ID
     * @param eventId 支付事件 ID
     * @return 生效结果
     */
    private SubscriptionActivation pay(UUID orderId, String eventId) {
        SubscriptionActivation activation =
                tenantOrderService.applySimulatedPaymentSucceeded(orderId, eventId);
        assertThat(activation.activated()).isTrue();
        return activation;
    }

    /** @param planCode 档位编码 @return {@code product-revision-1} 对应档位的修订版 ID */
    private UUID revisionId(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT r.id FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = 'product-revision-1'
                """, UUID.class, planCode);
    }

    /** @param planCode 档位编码 @return {@code PLAN_R1_*} 配额模板 ID */
    private UUID quotaPolicyId(String planCode) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_quota_policy WHERE code = ?",
                UUID.class, "PLAN_R1_" + planCode);
    }

    /** @param tenantId 租户 ID @return 该租户当前 ACTIVE 订阅 ID */
    private UUID activeSubscriptionId(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, UUID.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅头（含档位编码） */
    private Map<String, Object> activeSubscriptionRow(UUID tenantId) {
        return jdbcTemplate.queryForMap("""
                SELECT s.status, s.price_cents, s.currency, s.billing_period, s.renewal_mode,
                       s.source_order_id, p.code AS plan_code
                  FROM sys_tenant_subscription s
                  JOIN sys_plan_revision r ON r.id = s.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE s.tenant_id = ? AND s.status = 'ACTIVE'
                """, tenantId);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅服务期终点 */
    private Instant activeEndsAt(UUID tenantId) {
        Timestamp endsAt = jdbcTemplate.queryForObject("""
                SELECT ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.class, tenantId);
        assertThat(endsAt).as("付费 ACTIVE 订阅必须有服务期终点").isNotNull();
        return endsAt.toInstant();
    }

    /** @param tenantId 租户 ID @return 该租户全部订阅行数（含历史） */
    private int subscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户 ACTIVE 订阅行数 */
    private int activeSubscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Integer.class, tenantId);
    }

    /** @param orderId 订单 ID @return 订单状态 */
    private String orderStatus(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_order WHERE id = ?", String.class, orderId);
    }

    /** @param tenantId 租户 ID @return 运行时配额策略绑定版本 */
    private long assignmentVersion(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
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
