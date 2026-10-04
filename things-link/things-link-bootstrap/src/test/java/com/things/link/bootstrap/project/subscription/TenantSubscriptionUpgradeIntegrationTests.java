package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.SubscriptionActivation;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantSubscriptionChangeService;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionPendingChange;
import com.things.link.project.domain.SubscriptionPendingChangeStatus;
import com.things.link.project.domain.SubscriptionUpgradeProration;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.audit.AuditLogService;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S14-3b 即时升级与按整日折算的真实 PostgreSQL 验收（架构文档 §3.2，S14-0 P5）。
 *
 * <p>本类钉住七件事：① 首日/中点/末日升级的补差逐分可复算，且新 ACTIVE 订阅的
 * {@code ends_at} 与原服务期终点**完全一致**（升级不悄悄延长也不缩短已付周期）；
 * ② 折算事实（剩余整日、新旧日价、抵扣、应收、补差）落到订单列并写进审计；
 * ③ 补差为 0 的升级仍然落单、仍然换档、仍然写审计；④ 拒绝面用已登记错误码
 * （不是升级 50026、无生效订阅 50028、无服务期终点 50030、服务期已结束 50031、
 * 报价过期 50032）；⑤ 升级同事务清除待生效的降级预约并写撤销审计；
 * ⑥ 报价之后发生续费时，旧报价整体拒绝、订单保持 CREATED；⑦ 生效恰好推进一次策略绑定版本。
 *
 * <p><b>时间注入方式</b>：沿用仓库既有的「生产构造器默认 {@code Clock.systemUTC()} + 可注入
 * {@code Clock} 的构造器」模式。集成测试用真实的 JDBC 仓储/绑定服务/审计构造一个
 * {@code Clock.fixed} 的 {@code TenantOrderService}，把「升级报价时刻」冻结成固定 Instant，
 * 因此剩余整日与金额都可独立重算；购买/续费仍用生产 Bean，随后直接把 ACTIVE 订阅的服务期
 * 改写成测试选定的区间（与 S14-3a 续费用例同一手法）。
 *
 * <p>不使用方法级 {@code @Transactional}：需要看到其他连接已提交的事实。清理按外键依赖顺序，
 * 探针档位（无维度/权益行，可删除）也在清理范围内；{@code sys_audit_log} 刻意不清理。
 */
@DisplayName("S14-3b 即时升级与按整日折算")
class TenantSubscriptionUpgradeIntegrationTests extends AbstractIntegrationTest {

    /** 服务期起点：2026 年不是闰年，到 2027-01-01 恰好 365 天。 */
    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    /** 服务期终点。 */
    private static final Instant PERIOD_END = Instant.parse("2027-01-01T00:00:00Z");
    /** 中点：距终点 183 天。 */
    private static final Instant MIDDLE_DAY = Instant.parse("2026-07-02T00:00:00Z");
    /** 末日：距终点整 1 天。 */
    private static final Instant LAST_DAY = Instant.parse("2026-12-31T00:00:00Z");
    /** 审计详情（jsonb）解析器；PG 的 jsonb 文本带空格，直接断言字符串不可靠。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 新支付来源快照使用真实仓储。 */
    @Autowired
    private com.things.link.project.domain.SubscriptionProvenanceRepository provenance;

    /** 订单、订阅、预约与审计事实的核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅与 PLAN_R1_FREE 绑定。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 生产的模拟订单服务（购买/续费用它，服务期随后由测试改写）。 */
    @Autowired
    private TenantOrderService tenantOrderService;
    /** 预约降级服务（撤销路径与升级清除路径共用）。 */
    @Autowired
    private TenantSubscriptionChangeService tenantSubscriptionChangeService;
    /** 订单事实仓储；用于构造可冻结时钟的服务。 */
    @Autowired
    private TenantOrderRepository tenantOrderRepository;
    /** 订阅生命周期仓储；用于构造可冻结时钟的服务。 */
    @Autowired
    private TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository;
    /** S7 配额策略绑定用例。 */
    @Autowired
    private QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 商业审计写入。 */
    @Autowired
    private AuditLogService auditLogService;
    /** 为 {@code MANDATORY} 的租户创建提供外层业务事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户，清理时按依赖顺序回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 本用例创建的探针档位身份。 */
    private final Set<UUID> probePlanIds = new LinkedHashSet<>();
    /** 本用例创建的探针档位修订版。 */
    private final Set<UUID> probeRevisionIds = new LinkedHashSet<>();

    /** 显式按依赖顺序回收夹具；审计表不可变，不清理。 */
    @AfterEach
    void cleanUp() {
        for (UUID tenantId : tenantIds) {
            jdbcTemplate.update(
                    "DELETE FROM sys_tenant_subscription_pending_change WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        }
        tenantIds.clear();
        for (UUID revisionId : probeRevisionIds) {
            jdbcTemplate.update("DELETE FROM sys_plan_revision WHERE id = ?", revisionId);
        }
        probeRevisionIds.clear();
        for (UUID planId : probePlanIds) {
            jdbcTemplate.update("DELETE FROM sys_plan WHERE id = ?", planId);
        }
        probePlanIds.clear();
    }

    /** 首日升级：剩余 365 天，补差 300030 分，服务期终点保持原到期日。 */
    @Test
    void firstDayUpgradeChargesWholePeriodDifferenceAndKeepsEndsAt() {
        UUID tenantId = createPaidStandardTenant("S14-3b 首日升级租户", PERIOD_START, PERIOD_END);
        long versionBefore = assignmentVersion(tenantId);
        TenantOrderService service = serviceAt(PERIOD_START);

        TenantOrder order = createUpgradeOrder(service, tenantId, revisionId("ENTERPRISE"));

        assertThat(order.kind()).isEqualTo(TenantOrderKind.UPGRADE);
        assertThat(order.amountCents()).as("816x365=297840 抵扣，1638x365=597870 应收，补差 300030")
                .isEqualTo(300030L);
        assertThat(order.provider()).isEqualTo(PaymentProvider.SIMULATED);
        assertThat(order.currency()).isEqualTo("CNY");
        SubscriptionUpgradeProration proration = order.proration();
        assertThat(proration.remainingDays()).isEqualTo(365);
        assertThat(proration.effectiveAt()).isEqualTo(PERIOD_START);
        assertThat(proration.periodEndsAt()).isEqualTo(PERIOD_END);
        assertThat(proration.oldDailyPriceCents()).isEqualTo(816L);
        assertThat(proration.newDailyPriceCents()).isEqualTo(1638L);
        assertThat(assignmentVersion(tenantId)).as("报价本身不推进策略绑定版本").isEqualTo(versionBefore);

        SubscriptionActivation activation = pay(service, order.id(), "sim-upgrade-first");
        assertThat(activation.startsAt()).isEqualTo(PERIOD_START);
        assertThat(activation.endsAt())
                .as("升级不得改动已付服务期终点")
                .isEqualTo(PERIOD_END);

        assertActivePlan(tenantId, "ENTERPRISE", 300030L, order.id());
        assertThat(activeEndsAt(tenantId)).isEqualTo(PERIOD_END);
        assertThat(supersededCount(tenantId))
                .as("FREE 与 STANDARD 两条历史订阅都因换行被取代")
                .isEqualTo(2);
        assertThat(activeSubscriptionCount(tenantId)).isEqualTo(1);
        assertThat(quotaPolicyIdOfTenant(tenantId)).isEqualTo(quotaPolicyId("ENTERPRISE"));
        assertThat(assignmentVersion(tenantId)).isEqualTo(versionBefore + 1);

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT order_kind, source_plan_revision_id, proration_remaining_days,
                       proration_old_daily_price_cents, proration_new_daily_price_cents,
                       proration_credit_cents, proration_charge_cents,
                       proration_period_ends_at, amount_cents, status
                  FROM sys_tenant_order WHERE id = ?
                """, order.id());
        assertThat(stored.get("order_kind")).isEqualTo("UPGRADE");
        assertThat(stored.get("source_plan_revision_id")).isEqualTo(revisionId("STANDARD"));
        assertThat(((Number) stored.get("proration_remaining_days")).intValue()).isEqualTo(365);
        assertThat(((Number) stored.get("proration_credit_cents")).longValue()).isEqualTo(297840L);
        assertThat(((Number) stored.get("proration_charge_cents")).longValue()).isEqualTo(597870L);
        assertThat(((Number) stored.get("amount_cents")).longValue()).isEqualTo(300030L);
        assertThat(((Timestamp) stored.get("proration_period_ends_at")).toInstant()).isEqualTo(PERIOD_END);
        assertThat(stored.get("status")).isEqualTo("PAID");

        assertThat(auditCount(tenantId, "commercial.order.paid")).isEqualTo(2);
        assertThat(auditCount(tenantId, "commercial.subscription.upgraded")).isEqualTo(1);
        JsonNode details = latestAuditJson(tenantId, "commercial.subscription.upgraded");
        assertThat(details.get("remainingDays").asInt()).isEqualTo(365);
        assertThat(details.get("oldDailyPriceCents").asLong()).isEqualTo(816L);
        assertThat(details.get("newDailyPriceCents").asLong()).isEqualTo(1638L);
        assertThat(details.get("creditCents").asLong()).isEqualTo(297840L);
        assertThat(details.get("chargeCents").asLong()).isEqualTo(597870L);
        assertThat(details.get("differenceCents").asLong()).isEqualTo(300030L);
        assertThat(details.get("periodEndUnchanged").asBoolean()).isTrue();
    }

    /** 中点升级：距终点 183 天，补差 150426 分。 */
    @Test
    void middleDayUpgradeCharges183DayDifference() {
        UUID tenantId = createPaidStandardTenant("S14-3b 中点升级租户", PERIOD_START, PERIOD_END);
        TenantOrderService service = serviceAt(MIDDLE_DAY);

        TenantOrder order = createUpgradeOrder(service, tenantId, revisionId("ENTERPRISE"));

        assertThat(order.proration().remainingDays()).isEqualTo(183);
        assertThat(order.amountCents()).as("149328 抵扣、299754 应收、补差 150426")
                .isEqualTo(150426L);

        SubscriptionActivation activation = pay(service, order.id(), "sim-upgrade-middle");
        assertThat(activation.startsAt()).isEqualTo(MIDDLE_DAY);
        assertThat(activation.endsAt()).isEqualTo(PERIOD_END);
        assertActivePlan(tenantId, "ENTERPRISE", 150426L, order.id());
    }

    /** 末日升级：距终点整 1 天，补差 822 分。 */
    @Test
    void lastDayUpgradeChargesSingleDayDifference() {
        UUID tenantId = createPaidStandardTenant("S14-3b 末日升级租户", PERIOD_START, PERIOD_END);
        TenantOrderService service = serviceAt(LAST_DAY);

        TenantOrder order = createUpgradeOrder(service, tenantId, revisionId("ENTERPRISE"));

        assertThat(order.proration().remainingDays()).isEqualTo(1);
        assertThat(order.amountCents()).as("1638 - 816 = 822").isEqualTo(822L);

        SubscriptionActivation activation = pay(service, order.id(), "sim-upgrade-last");
        assertThat(activation.startsAt()).isEqualTo(LAST_DAY);
        assertThat(activation.endsAt()).isEqualTo(PERIOD_END);
        assertActivePlan(tenantId, "ENTERPRISE", 822L, order.id());
    }

    /** 末日不足一整天仍按一整天算（ceil），补差不变。 */
    @Test
    void partialLastDayStillCountsAsOneDay() {
        UUID tenantId = createPaidStandardTenant("S14-3b 末日半天租户", PERIOD_START, PERIOD_END);
        TenantOrderService service = serviceAt(Instant.parse("2026-12-31T13:00:00Z"));

        TenantOrder order = createUpgradeOrder(service, tenantId, revisionId("ENTERPRISE"));

        assertThat(order.proration().remainingDays()).as("只剩 11 小时也算 1 天").isEqualTo(1);
        assertThat(order.amountCents()).isEqualTo(822L);
        assertThat(order.proration().effectiveAt()).isEqualTo(Instant.parse("2026-12-31T13:00:00Z"));
    }

    /** 零补差升级：金额 0 仍然落单、仍然换档、仍然写审计与策略重绑。 */
    @Test
    void zeroDifferenceUpgradeStillRecordsOrderAndAudit() {
        UUID tenantId = createPaidStandardTenant("S14-3b 零补差租户", PERIOD_START, PERIOD_END);
        UUID probeRevisionId = insertProbeRevision(298000L);
        TenantOrderService service = serviceAt(MIDDLE_DAY);

        TenantOrder order = createUpgradeOrder(service, tenantId, probeRevisionId);

        assertThat(order.amountCents()).as("新旧日价同为 816，补差正好为 0").isZero();
        assertThat(order.proration().creditCents()).isEqualTo(order.proration().chargeCents());
        assertThat(order.proration().differenceCents()).isZero();

        SubscriptionActivation activation = pay(service, order.id(), "sim-upgrade-zero");
        assertThat(activation.activated()).isTrue();
        assertThat(activation.endsAt()).isEqualTo(PERIOD_END);
        assertThat(activePlanRevisionId(tenantId)).isEqualTo(probeRevisionId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT price_cents FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Long.class, tenantId))
                .as("零补差订阅的成交价快照就是真实的 0")
                .isZero();

        JsonNode details = latestAuditJson(tenantId, "commercial.subscription.upgraded");
        assertThat(details.get("zeroDifference").asBoolean()).isTrue();
        assertThat(details.get("differenceFlooredAtZero").asBoolean()).isFalse();
        assertThat(details.get("differenceCents").asLong()).isZero();
        assertThat(auditCount(tenantId, "commercial.subscription.upgraded")).isEqualTo(1);
    }

    /** 拒绝面：同档不是升级、无生效订阅、长期 FREE 无服务期终点、服务期已结束，各有登记错误码。 */
    @Test
    void refusesUpgradesWithRegisteredCodes() {
        UUID tenantId = createPaidStandardTenant("S14-3b 升级拒绝租户", PERIOD_START, PERIOD_END);
        TenantOrderService service = serviceAt(MIDDLE_DAY);

        BusinessException sameTier = refuseUpgrade(service, tenantId, revisionId("STANDARD"));
        assertThat(sameTier).isNotNull();
        assertThat(sameTier.errorCode()).isEqualTo(ProjectErrorCode.NOT_AN_UPGRADE);
        assertThat(sameTier.errorCode().code()).isEqualTo(50026);

        BusinessException ended = refuseUpgrade(
                serviceAt(PERIOD_END), tenantId, revisionId("ENTERPRISE"));
        assertThat(ended).isNotNull();
        assertThat(ended.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_PERIOD_ENDED);
        assertThat(ended.errorCode().code()).isEqualTo(50031);

        UUID freeTenantId = createFreeTenant("S14-3b 长期免费租户");
        BusinessException unbounded = refuseUpgrade(service, freeTenantId, revisionId("ENTERPRISE"));
        assertThat(unbounded).isNotNull();
        assertThat(unbounded.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_PERIOD_UNBOUNDED);
        assertThat(unbounded.errorCode().code()).isEqualTo(50030);

        jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
        BusinessException noActive = refuseUpgrade(service, tenantId, revisionId("ENTERPRISE"));
        assertThat(noActive).isNotNull();
        assertThat(noActive.errorCode()).isEqualTo(ProjectErrorCode.NO_ACTIVE_SUBSCRIPTION);
        assertThat(noActive.errorCode().code()).isEqualTo(50028);
    }

    /** 升级立即生效时清除待生效的降级预约，并以 SUPERSEDED_BY_UPGRADE 写撤销审计。 */
    @Test
    void upgradeClearsPendingDowngradeWithAudit() {
        UUID tenantId = createPaidStandardTenant("S14-3b 升级清降级租户", PERIOD_START, PERIOD_END);

        SubscriptionPendingChange scheduled =
                tenantSubscriptionChangeService.requestDowngrade(tenantId, revisionId("FREE"));
        assertThat(scheduled.status()).isEqualTo(SubscriptionPendingChangeStatus.PENDING);
        assertThat(scheduled.effectiveAt()).isEqualTo(PERIOD_END);
        assertThat(tenantSubscriptionChangeService.findPendingChange(tenantId)).isPresent();

        TenantOrderService service = serviceAt(MIDDLE_DAY);
        TenantOrder order = createUpgradeOrder(service, tenantId, revisionId("ENTERPRISE"));
        pay(service, order.id(), "sim-upgrade-clears-downgrade");

        assertThat(tenantSubscriptionChangeService.findPendingChange(tenantId))
                .as("升级后不得再有待生效的降级预约")
                .isEmpty();
        Map<String, Object> cancelled = jdbcTemplate.queryForMap("""
                SELECT status, cancelled_at FROM sys_tenant_subscription_pending_change WHERE id = ?
                """, scheduled.id());
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelled_at")).isNotNull();

        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.scheduled")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.cancelled")).isEqualTo(1);
        JsonNode cancelledAudit =
                latestAuditJson(tenantId, "commercial.subscription.downgrade.cancelled");
        assertThat(cancelledAudit.get("reason").asString()).isEqualTo("SUPERSEDED_BY_UPGRADE");
        assertThat(cancelledAudit.get("upgradeOrderId").asString()).isEqualTo(order.id().toString());
    }

    /** 报价之后发生续费：旧报价整体拒绝（订单保持 CREATED），当前订阅与绑定版本都不被改动。 */
    @Test
    void staleUpgradeQuoteIsRefusedAfterRenewal() {
        UUID tenantId = createPaidStandardTenant("S14-3b 报价过期租户", PERIOD_START, PERIOD_END);
        TenantOrderService service = serviceAt(MIDDLE_DAY);
        TenantOrder staleQuote = createUpgradeOrder(service, tenantId, revisionId("ENTERPRISE"));

        TenantOrder renewal = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(renewal.id(), "sim-renew-after-quote");
        Instant renewedEndsAt = activeEndsAt(tenantId);
        assertThat(renewedEndsAt).as("续费从原到期日延长一年").isEqualTo(Instant.parse("2028-01-01T00:00:00Z"));
        long versionBeforeFailedPay = assignmentVersion(tenantId);

        BusinessException refusal = refuseUpgradePay(service, staleQuote.id(), "sim-stale-upgrade");

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.UPGRADE_QUOTE_STALE);
        assertThat(refusal.errorCode().code()).isEqualTo(50032);
        assertThat(orderStatus(staleQuote.id()))
                .as("拒绝必须整体回滚：订单不得停在 PAID")
                .isEqualTo("CREATED");
        assertThat(activeEndsAt(tenantId)).isEqualTo(renewedEndsAt);
        assertThat(assignmentVersion(tenantId)).isEqualTo(versionBeforeFailedPay);
        assertThat(auditCount(tenantId, "commercial.subscription.upgraded")).isZero();
    }

    /**
     * 创建一个带默认 FREE 订阅的租户。
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
     * 创建一个先购买 STANDARD、再把 ACTIVE 服务期改写成指定区间的租户。
     *
     * @param label 租户名
     * @param startsAt 服务期起点
     * @param endsAt 服务期终点
     * @return 租户 ID
     */
    private UUID createPaidStandardTenant(String label, Instant startsAt, Instant endsAt) {
        UUID tenantId = createFreeTenant(label);
        TenantOrder purchase = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(purchase.id(), "sim-purchase-" + tenantId);
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription SET starts_at = ?, ends_at = ?
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(startsAt), Timestamp.from(endsAt), tenantId);
        return tenantId;
    }

    /**
     * 构造一个把升级报价时刻冻结在指定 Instant 的订单服务（真仓储 + 假时钟）。
     *
     * @param quotedAt 冻结的报价时刻
     * @return 被测服务
     */
    private TenantOrderService serviceAt(Instant quotedAt) {
        return new TenantOrderService(tenantOrderRepository, subscriptionLifecycleRepository,
                quotaPolicyAssignmentService, tenantSubscriptionChangeService, provenance, auditLogService,
                Clock.fixed(quotedAt, ZoneOffset.UTC));
    }

    /**
     * 发起一次模拟支付并断言确实生效。
     *
     * @param service 被测服务
     * @param orderId 订单 ID
     * @param eventId 支付事件 ID
     * @return 生效结果
     */
    private SubscriptionActivation pay(TenantOrderService service, UUID orderId, String eventId) {
        SubscriptionActivation activation = transactionTemplate.execute(
                status -> service.applySimulatedPaymentSucceeded(orderId, eventId));
        assertThat(activation).isNotNull();
        assertThat(activation.activated()).isTrue();
        return activation;
    }

    /**
     * 插入一个仅用于零补差用例的探针档位与其修订版（无维度/权益行，可删除）。
     *
     * @param referencePriceCents 参考年价（分）
     * @return 探针修订版 ID
     */
    private UUID insertProbeRevision(long referencePriceCents) {
        UUID planId = Uuid7.generate();
        UUID revisionId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_plan (id, code, display_order, created_at, updated_at)
                VALUES (?, ?, 900, now(), now())
                """, planId, "S14_3B_PROBE_" + planId.toString().substring(0, 8).toUpperCase());
        jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (
                    id, plan_id, revision_code, revision_no, name, sale_status, billing_period,
                    currency, price_cents, reference_price_cents, reference_price_currency,
                    quota_policy_id, valid_from, valid_until)
                VALUES (?, ?, ?, 1, 'S14-3b 零补差探针档', 'NOT_FOR_SALE', 'YEAR',
                        'CNY', NULL, ?, 'CNY', ?, now(), NULL)
                """, revisionId, planId, "s14-3b-probe-" + revisionId, referencePriceCents,
                quotaPolicyId("STANDARD"));
        probePlanIds.add(planId);
        probeRevisionIds.add(revisionId);
        return revisionId;
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

    /**
     * 断言当前 ACTIVE 订阅的档位、成交价与来源订单。
     *
     * @param tenantId 租户 ID
     * @param planCode 期望档位编码
     * @param priceCents 期望成交价快照
     * @param orderId 期望来源订单
     */
    private void assertActivePlan(UUID tenantId, String planCode, long priceCents, UUID orderId) {
        Map<String, Object> active = jdbcTemplate.queryForMap("""
                SELECT s.price_cents, s.currency, s.billing_period, s.source_order_id, p.code AS plan_code
                  FROM sys_tenant_subscription s
                  JOIN sys_plan_revision r ON r.id = s.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE s.tenant_id = ? AND s.status = 'ACTIVE'
                """, tenantId);
        assertThat(active.get("plan_code")).isEqualTo(planCode);
        assertThat(((Number) active.get("price_cents")).longValue()).isEqualTo(priceCents);
        assertThat(active.get("currency")).isEqualTo("CNY");
        assertThat(active.get("billing_period")).isEqualTo("YEAR");
        assertThat(active.get("source_order_id")).isEqualTo(orderId);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅的修订版 ID */
    private UUID activePlanRevisionId(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT plan_revision_id FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, UUID.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅的服务期终点 */
    private Instant activeEndsAt(UUID tenantId) {
        Timestamp endsAt = jdbcTemplate.queryForObject("""
                SELECT ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.class, tenantId);
        assertThat(endsAt).as("付费 ACTIVE 订阅必须有服务期终点").isNotNull();
        return endsAt.toInstant();
    }

    /** @param tenantId 租户 ID @return ACTIVE 订阅行数 */
    private int activeSubscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 被取代的历史订阅行数 */
    private int supersededCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status = 'SUPERSEDED'
                """, Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 运行时配额策略 ID */
    private UUID quotaPolicyIdOfTenant(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quota_policy_id FROM sys_tenant WHERE id = ?", UUID.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 运行时配额策略绑定版本 */
    private long assignmentVersion(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?",
                Long.class, tenantId);
    }

    /** @param orderId 订单 ID @return 订单状态 */
    private String orderStatus(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_order WHERE id = ?", String.class, orderId);
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

    /**
     * 读取某租户某动作最新一条审计的结构化详情并解析成 JSON。
     *
     * @param tenantId 租户 ID
     * @param action 动作编码
     * @return 详情 JSON 节点
     */
    private JsonNode latestAuditJson(UUID tenantId, String action) {
        Optional<String> details = jdbcTemplate.queryForList("""
                SELECT details::text FROM sys_audit_log
                 WHERE tenant_id = ? AND action = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """, String.class, tenantId, action).stream().findFirst();
        assertThat(details).as("缺少审计事件 " + action).isPresent();
        return JSON.readTree(details.get());
    }

    /**
     * 在真实事务里创建一条升级订单；手工构造的服务没有 Spring 事务代理，必须由外层事务提供原子性与行锁。
     *
     * @param service 被测服务
     * @param tenantId 租户 ID
     * @param targetRevisionId 目标修订版
     * @return 新建订单
     */
    private TenantOrder createUpgradeOrder(TenantOrderService service, UUID tenantId,
                                           UUID targetRevisionId) {
        TenantOrder order = transactionTemplate.execute(
                status -> service.createSimulatedUpgradeOrder(tenantId, targetRevisionId));
        assertThat(order).isNotNull();
        return order;
    }

    /**
     * 在真实事务里尝试创建升级订单并返回业务异常。
     *
     * @param service 被测服务
     * @param tenantId 租户 ID
     * @param targetRevisionId 目标修订版
     * @return 业务异常
     */
    private BusinessException refuseUpgrade(TenantOrderService service, UUID tenantId,
                                            UUID targetRevisionId) {
        BusinessException refusal = catchThrowableOfType(
                () -> transactionTemplate.execute(
                        status -> service.createSimulatedUpgradeOrder(tenantId, targetRevisionId)),
                BusinessException.class);
        assertThat(refusal).as("预期拒绝").isNotNull();
        return refusal;
    }

    /**
     * 在真实事务里尝试支付升级订单并返回业务异常。
     *
     * @param service 被测服务
     * @param orderId 订单 ID
     * @param eventId 支付事件 ID
     * @return 业务异常
     */
    private BusinessException refuseUpgradePay(TenantOrderService service, UUID orderId, String eventId) {
        BusinessException refusal = catchThrowableOfType(
                () -> transactionTemplate.execute(
                        status -> service.applySimulatedPaymentSucceeded(orderId, eventId)),
                BusinessException.class);
        assertThat(refusal).as("预期拒绝").isNotNull();
        return refusal;
    }
}
