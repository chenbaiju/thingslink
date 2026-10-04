package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.SubscriptionLifecycleWorker;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.TenantOrder;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S14-6a 订阅生命周期触发器默认路径的真实 PostgreSQL 验收（关闭 D-169）。
 *
 * <p>本类钉住三件事：① 开关显式开启时生产推进入口
 * {@link SubscriptionLifecycleWorker#advanceNextBatch()} 真的能把到期订阅推进为 {@code GRACE}、
 * 再推进为 {@code RESTRICTED_FREE}（不是只有 {@code advance(Instant)} 这条测试入口可用）；
 * ② 宽限终点按 P4 冻结为「到期日 + 14 自然日」；③ 反复触发不产生第二次状态、审计或通知意图——
 * 调度频率因此只影响「多久推进一次」，不影响收敛结果。
 *
 * <p>测试 profile 显式关闭该开关（避免后台线程与用例的显式时间轴竞争），本类用
 * {@code @TestPropertySource} 显式开启，验证的正是生产默认会走的入口；开关的**缺省即开启**语义由
 * {@code SubscriptionLifecycleWorkerContractTests} 以注解契约钉住。
 */
// 本类显式启用后台维护Bean；结束后关闭上下文，避免定时线程污染其他类的可控时间轴。
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "things-link.commercial.subscription-lifecycle.enabled=true",
        // 本类直接驱动生产入口；延后自动扫描，避免后台调度在手动时间轴中途抢先补通知。
        "things-link.commercial.subscription-lifecycle.initial-delay-millis=3600000",
        "things-link.commercial.subscription-lifecycle.fixed-delay-millis=3600000"
})
@DisplayName("S14-6a 生命周期触发器默认路径")
class SubscriptionLifecycleWorkerIntegrationTests extends AbstractIntegrationTest {

    /** 订阅与审计事实的核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅与 PLAN_R1_FREE 绑定。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 模拟订单服务：把租户买成付费档，得到有明确到期日的订阅。 */
    @Autowired
    private TenantOrderService tenantOrderService;
    @Autowired
    private TenantResourcePackageService resourcePackages;
    @Autowired
    private EffectiveQuotaPolicyProvider quotaPolicies;
    /** 被测的生产推进入口（开关显式开启后才会装配）。 */
    @Autowired
    private SubscriptionLifecycleWorker subscriptionLifecycleWorker;
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
                        "DELETE FROM sys_tenant_subscription_notification_intent WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update(
                        "DELETE FROM sys_project_commercial_restriction WHERE tenant_id = ?", tenantId);
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

    /** R4-4a：受限时真实购买的包在续订后必须由生产维护入口激活，不能依赖人工 advance。 */
    @Test
    void productionTriggerActivatesPurchasedPendingPackageAfterRenewal() {
        UUID tenantId = createFreeTenant("R4-4a 待生效包维护");
        TenantOrder original = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(original.id(), "r44a-original-" + tenantId);
        // 仅本租户的日期夹具；真实服务推进状态，不冒充自然等待一年及十四天。
        Instant endedAt = Instant.now().minus(Duration.ofDays(16));
        assertThat(jdbcTemplate.update("""
                UPDATE sys_tenant_subscription SET starts_at = ?, ends_at = ?
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(endedAt.minus(Duration.ofDays(30))), Timestamp.from(endedAt), tenantId))
                .isEqualTo(1);
        subscriptionLifecycleWorker.advanceNextBatch();
        assertThat(subscriptionStatus(tenantId)).isEqualTo("RESTRICTED_FREE");

        TenantOrder purchase = resourcePackages.createSimulatedPackageOrder(tenantId, "DEVICES_MAX", 2, null);
        var pending = resourcePackages.applySimulatedPackagePaymentSucceeded(
                purchase.id(), "r44a-pending-" + tenantId);
        assertThat(pending.status()).isEqualTo(ResourcePackageStatus.PENDING);
        assertThat(effectiveDeviceLimit(tenantId)).isEqualTo(3);
        assertThat(auditCount(tenantId, "commercial.package.pending")).isEqualTo(1);

        TenantOrder renewal = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(renewal.id(), "r44a-renewal-" + tenantId);
        assertThat(subscriptionStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(packageStatus(pending.packageId())).isEqualTo("PENDING");
        assertThat(effectiveDeviceLimit(tenantId)).as("预热不含待生效包的真实策略缓存").isEqualTo(100);
        Instant subscriptionEnd = activeSubscriptionEndsAt(tenantId);

        subscriptionLifecycleWorker.advanceNextBatch();

        assertThat(packageStatus(pending.packageId())).isEqualTo("ACTIVE");
        assertThat(effectiveDeviceLimit(tenantId)).as("同维护入口必须失效缓存并让购买权益生效").isEqualTo(102);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT starts_at FROM sys_tenant_resource_package WHERE id = ?",
                Timestamp.class, pending.packageId()).toInstant()).isEqualTo(pending.startsAt());
        assertThat(jdbcTemplate.queryForObject("SELECT ends_at FROM sys_tenant_resource_package WHERE id = ?",
                Timestamp.class, pending.packageId()).toInstant()).isEqualTo(pending.endsAt());
        assertThat(activeSubscriptionEndsAt(tenantId)).isEqualTo(subscriptionEnd);

        long version = assignmentVersion(tenantId);
        subscriptionLifecycleWorker.advanceNextBatch();
        assertThat(packageStatus(pending.packageId())).isEqualTo("ACTIVE");
        assertThat(effectiveDeviceLimit(tenantId)).isEqualTo(102);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isEqualTo(1);
        assertThat(assignmentVersion(tenantId)).as("空轮次不得重复失效策略").isEqualTo(version);
    }

    /** R4-4a：本租户日期夹具到期后，生产维护入口应同步状态/审计/缓存且重放无副作用。 */
    @Test
    void productionTriggerExpiresPurchasedPackageAndInvalidatesQuotaOnce() {
        UUID tenantId = createFreeTenant("R4-4a 到期包维护");
        TenantOrder purchase = resourcePackages.createSimulatedPackageOrder(tenantId, "DEVICES_MAX", 2, null);
        var active = resourcePackages.applySimulatedPackagePaymentSucceeded(
                purchase.id(), "r44a-expiring-" + tenantId);
        assertThat(active.status()).isEqualTo(ResourcePackageStatus.ACTIVE);
        assertThat(effectiveDeviceLimit(tenantId)).as("预热包含包的真实策略缓存").isEqualTo(5);
        Instant expiredAt = Instant.now().minusSeconds(1);
        assertThat(jdbcTemplate.update("""
                UPDATE sys_tenant_resource_package SET starts_at = ?, ends_at = ?
                 WHERE id = ? AND tenant_id = ?
                """, Timestamp.from(expiredAt.minus(Duration.ofDays(1))), Timestamp.from(expiredAt),
                active.packageId(), tenantId)).isEqualTo(1);

        subscriptionLifecycleWorker.advanceNextBatch();

        assertThat(packageStatus(active.packageId())).isEqualTo("EXPIRED");
        assertThat(effectiveDeviceLimit(tenantId)).isEqualTo(3);
        assertThat(auditCount(tenantId, "commercial.package.expired")).isEqualTo(1);
        assertThat(subscriptionStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(activeSubscriptionEndsAt(tenantId)).as("包到期不得改永久FREE订阅").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sys_tenant_order WHERE id = ?",
                String.class, purchase.id())).as("购买支付历史不能被状态维护抹去").isEqualTo("PAID");

        long version = assignmentVersion(tenantId);
        subscriptionLifecycleWorker.advanceNextBatch();
        assertThat(packageStatus(active.packageId())).isEqualTo("EXPIRED");
        assertThat(effectiveDeviceLimit(tenantId)).isEqualTo(3);
        assertThat(auditCount(tenantId, "commercial.package.expired")).isEqualTo(1);
        assertThat(assignmentVersion(tenantId)).isEqualTo(version);
    }

    /** 开关开启后，生产推进入口把到期订阅推进为宽限、再把宽限推进为受限免费，且可重复触发。 */
    @Test
    void productionTriggerAdvancesExpiryToGraceThenRestrictedFreeExactlyOnce() {
        UUID tenantId = createFreeTenant("S14-6a 触发器租户");
        TenantOrder order = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(order.id(), "sim-pay-6a-trigger-1");

        // 夹具把服务期终点放到刚过去 1 秒：真实时钟下到期事实成立，宽限终点仍在未来。
        // 起点一并前移，保持 sys_tenant_subscription_period_ck（ends_at > starts_at）成立。
        Instant expiredAt = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET starts_at = ?, ends_at = ?, grace_ends_at = NULL, restricted_at = NULL
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(expiredAt.minus(Duration.ofDays(1))), Timestamp.from(expiredAt),
                tenantId);
        assertThat(subscriptionStatus(tenantId)).isEqualTo("ACTIVE");

        subscriptionLifecycleWorker.advanceNextBatch();

        assertThat(subscriptionStatus(tenantId)).as("到期必须触发宽限").isEqualTo("GRACE");
        assertThat(graceEndsAt(tenantId))
                .as("P4：宽限终点 = 到期日 + 14 自然日")
                .isEqualTo(expiredAt.plus(Duration.ofDays(14)));
        assertThat(auditCount(tenantId, "commercial.subscription.grace.entered")).isEqualTo(1);
        assertThat(notificationCount(tenantId)).as("到期日至少落「到期日」这一个时间点").isGreaterThanOrEqualTo(1);

        // 夹具把整段服务期前移到 16 天前结束（并同步宽限终点 = 到期日 + 14 自然日，保持数据库
        // 的宽限公式 CHECK 成立），再触发一次：宽限已过 → GRACE → RESTRICTED_FREE。
        Instant endedAt = Instant.now().minus(Duration.ofDays(16));
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET starts_at = ?, ends_at = ?, grace_ends_at = ?
                 WHERE tenant_id = ? AND status = 'GRACE'
                """, Timestamp.from(endedAt.minus(Duration.ofDays(30))), Timestamp.from(endedAt),
                Timestamp.from(endedAt.plus(Duration.ofDays(14))), tenantId);

        subscriptionLifecycleWorker.advanceNextBatch();

        assertThat(subscriptionStatus(tenantId)).as("宽限结束必须切到显式受限免费").isEqualTo("RESTRICTED_FREE");
        assertThat(auditCount(tenantId, "commercial.subscription.restricted_free.entered")).isEqualTo(1);

        // 反复触发不产生第二次状态、审计或通知意图：调度频率不是正确性依赖。
        int intents = notificationCount(tenantId);
        int audits = auditCount(tenantId, "commercial.subscription.restricted_free.entered");
        subscriptionLifecycleWorker.advanceNextBatch();
        subscriptionLifecycleWorker.advanceNextBatch();

        assertThat(subscriptionStatus(tenantId)).isEqualTo("RESTRICTED_FREE");
        assertThat(notificationCount(tenantId)).as("重跑不得新增通知意图").isEqualTo(intents);
        assertThat(auditCount(tenantId, "commercial.subscription.restricted_free.entered"))
                .as("重跑不得重复写状态审计")
                .isEqualTo(audits);
    }

    /** 永久 FREE（无到期日）不被生产推进入口触碰。 */
    @Test
    void productionTriggerNeverTouchesPerpetualFreeSubscription() {
        UUID tenantId = createFreeTenant("S14-6a 永久免费租户");
        assertThat(activeSubscriptionEndsAt(tenantId)).as("新租户 FREE 无服务期终点").isNull();

        subscriptionLifecycleWorker.advanceNextBatch();

        assertThat(subscriptionStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(activeSubscriptionEndsAt(tenantId)).isNull();
        assertThat(notificationCount(tenantId)).isZero();
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
     * 读取订阅当前所处的「活状态」。
     *
     * <p>只认 ACTIVE/GRACE/RESTRICTED_FREE：历史行（SUPERSEDED/EXPIRED/CANCELLED）与当前状态无关，
     * 而夹具会把到期订阅的起点前移，按 starts_at 排序会把更早创建、已被取代的旧行排到前面。
     *
     * @param tenantId 租户 ID
     * @return 当前活状态
     */
    private String subscriptionStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status IN ('ACTIVE', 'GRACE', 'RESTRICTED_FREE')
                 ORDER BY starts_at DESC, id DESC LIMIT 1
                """, String.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 宽限终点；无宽限时为空 */
    private Instant graceEndsAt(UUID tenantId) {
        Timestamp value = jdbcTemplate.queryForObject("""
                SELECT grace_ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'GRACE'
                """, Timestamp.class, tenantId);
        return value == null ? null : value.toInstant();
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅服务期终点；永久 FREE 为空 */
    private Instant activeSubscriptionEndsAt(UUID tenantId) {
        Timestamp value = jdbcTemplate.queryForObject("""
                SELECT ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.class, tenantId);
        return value == null ? null : value.toInstant();
    }

    /** @param tenantId 租户 ID @return 该租户的通知意图行数 */
    private int notificationCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription_notification_intent WHERE tenant_id = ?
                """, Integer.class, tenantId);
    }

    /**
     * 统计某租户某动作的订阅审计行数。
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

    private String packageStatus(UUID packageId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sys_tenant_resource_package WHERE id = ?",
                String.class, packageId);
    }

    private long assignmentVersion(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?",
                Long.class, tenantId);
    }

    private long effectiveDeviceLimit(UUID tenantId) {
        TenantContext.set(new TenantScope(tenantId, null, UUID.randomUUID()));
        try {
            return quotaPolicies.resolveTrustedTenant(tenantId).planQuota().devicesMax();
        } finally {
            TenantContext.clear();
        }
    }

    /** @param planCode 档位编码 @return {@code product-revision-1} 对应档位的修订版 ID */
    private UUID revisionId(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT r.id FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = 'product-revision-1'
                """, UUID.class, planCode);
    }
}
