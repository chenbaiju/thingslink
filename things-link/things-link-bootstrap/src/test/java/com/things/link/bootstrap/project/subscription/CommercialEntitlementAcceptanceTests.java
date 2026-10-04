package com.things.link.bootstrap.project.subscription;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.EntitlementAdjustmentRequest;
import com.things.link.project.application.EntitlementAdjustmentResult;
import com.things.link.project.application.TenantEntitlementAdjustmentService;
import com.things.link.project.application.TenantPaymentFailureService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantRefundService;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantRefund;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S14-6b 商业整合验收（真实 PostgreSQL + 真实 HTTP）。
 *
 * <p>本类把 S14-1～S14-5 的事实放在**同一条时间轴**上验收，而不是各片各自为政：
 * <ol>
 *   <li><b>时间推进 × 独立扩容</b>：订阅到期→宽限→受限切换与包的独立有效期组合语义，
 *       裁决并钉住 D-171（受限后既有扩容仍按自身窗口贡献）；续费换行后仍一致；</li>
 *   <li><b>三路回调的重复与乱序</b>：支付成功重放、退款重放、失败重放、失败后成功、成功后的迟到失败，
 *       全部收敛为唯一事实且不产生第二次权益后果；</li>
 *   <li><b>显示与强制面的一致性</b>：每次商业事实变化（买包/授予调整/撤销/退款）之后，
 *       控制台读到的有效额度与设备准入 SQL 面逐值相等——缓存失效必须同时覆盖两条路径；</li>
 *   <li><b>跨租户隔离与财务对账不变量</b>：别人的包/调整/退款既不影响本租户有效额度，也不能被本租户操作，
 *       且「订单↔权益」「退款↔已退金额」「失败↔未结算订单」三条不变量在全流程后仍然成立。</li>
 * </ol>
 *
 * <p>不使用方法级 {@code @Transactional}：时间推进与并发语义必须看到已提交事实；清理按外键依赖顺序执行，
 * 审计表由触发器钉成不可变（按每用例独有的 tenant_id 过滤断言）。
 */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("S14-6b 商业整合验收")
class CommercialEntitlementAcceptanceTests extends AbstractIntegrationTest {

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试账号固定口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** 真实 HTTP 入口（控制台读面）。 */
    @Autowired
    private MockMvc mockMvc;
    /** 夹具事实写入与核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 资源包下单与生效入口。 */
    @Autowired
    private TenantResourcePackageService tenantResourcePackageService;
    /** 人工调整入口。 */
    @Autowired
    private TenantEntitlementAdjustmentService tenantEntitlementAdjustmentService;
    /** 退款入口。 */
    @Autowired
    private TenantRefundService tenantRefundService;
    /** 支付失败入口。 */
    @Autowired
    private TenantPaymentFailureService tenantPaymentFailureService;
    /** 订阅生命周期状态机（显式时间轴）。 */
    @Autowired
    private com.things.link.project.application.SubscriptionLifecycleService subscriptionLifecycleService;
    /** 套餐订单入口（购买/续费）。 */
    @Autowired
    private com.things.link.project.application.TenantOrderService tenantOrderService;
    /** 租户有效权益投影入口。 */
    @Autowired
    private EffectiveQuotaPolicyProvider effectiveQuotaPolicyProvider;
    /** 为 {@code MANDATORY} 的租户创建提供外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;
    /** 注册限流器。 */
    @Autowired
    private AuthRateLimiter rateLimiter;

    /** 本用例创建的租户，清理时按依赖顺序回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 本用例通过 HTTP 创建的项目。 */
    private final Set<UUID> projectIds = new LinkedHashSet<>();
    /** 本用例通过 HTTP 注册的账号。 */
    private final Set<UUID> accountIds = new LinkedHashSet<>();

    /** 按外键依赖顺序回收共享容器夹具并清线程范围。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID projectId : projectIds) {
                jdbcTemplate.update("DELETE FROM dev_device WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update(
                        "DELETE FROM sys_tenant_order_payment_failure WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_refund WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update(
                        "DELETE FROM sys_tenant_subscription_notification_intent WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update(
                        "DELETE FROM sys_project_commercial_restriction WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription_pending_change WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
            }
            for (UUID projectId : projectIds) {
                jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
            }
            for (UUID accountId : accountIds) {
                jdbcTemplate.update("DELETE FROM sys_account WHERE id = ?", accountId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            TenantContext.clear();
            tenantIds.clear();
            projectIds.clear();
            accountIds.clear();
        }
    }

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int browserPort;
    @Autowired
    private com.things.link.project.application.TenantSubscriptionChangeService subscriptionChanges;

    /** G3-LOCAL-7b：仅夹具控制口驱动既有模拟服务，浏览器读取和设备准入均走真实 HTTP。 */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="thingslink.test.commercial-browser", matches="true")
    void browserObservesCommercialChangesAndEnforcesQuota() throws Exception {
        String email="commercial-browser-"+UUID.randomUUID()+"@example.com";
        Session session=registerAndLogin(email);
        UUID project=createProject(session,"商业旅程项目");
        switchProject(session,project);
        var control=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        String secret=UUID.randomUUID().toString();
        var stage=new java.util.concurrent.atomic.AtomicInteger();
        control.createContext("/"+secret, exchange -> {
            int status=200;
            byte[] body;
            try {
                if (!"POST".equals(exchange.getRequestMethod())) throw new IllegalArgumentException("POST required");
                int step=stage.incrementAndGet();
                if(step==1 || step==2) {
                    TenantOrder order=step==1
                            ? tenantOrderService.createSimulatedOrder(session.tenantId(),revisionId("STANDARD"))
                            : tenantOrderService.createSimulatedUpgradeOrder(session.tenantId(),revisionId("ENTERPRISE"));
                    tenantOrderService.applySimulatedPaymentSucceeded(order.id(),"browser-"+UUID.randomUUID());
                } else if(step==3) {
                    var pending=subscriptionChanges.requestDowngrade(session.tenantId(),revisionId("STANDARD"));
                    assertThat(pending).isNotNull();
                } else throw new IllegalArgumentException("unexpected stage");
                body=JSON.writeValueAsBytes(Map.of("stage",step,"limit",enforcedDeviceLimit(session.tenantId(),project)));
            } catch(Throwable error) {
                status=500; body=error.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        control.start();
        var root=java.nio.file.Path.of("../..").toAbsolutePath().normalize();
        var log=root.resolve("logs/verify/g3-local-7b/browser.log");
        java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("node",root.resolve("scripts/tests/console-commercial-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("ACCESS_BACKEND","http://127.0.0.1:"+browserPort,
                "ACCESS_EMAIL",email,"ACCESS_PASSWORD",PASSWORD,"COMMERCIAL_PROJECT",project.toString(),
                "COMMERCIAL_CONTROL","http://127.0.0.1:"+control.getAddress().getPort()+"/"+secret));
        Process process=null;
        try {
            process=builder.start();
            assertThat(process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)).as("browser deadline: %s",log).isTrue();
            assertThat(process.exitValue()).as("browser evidence: %s",log).isZero();
            assertThat(stage.get()).isEqualTo(3);
            assertEqualBothSides(session.tenantId(),project,enforcedDeviceLimit(session.tenantId(),project));
        } finally {
            if(process!=null && process.isAlive())process.destroyForcibly();
            control.stop(0);
        }
    }

    /**
     * 时间推进与独立扩容的组合语义（裁决并钉住 D-171）。
     *
     * <p>P6 冻结的是「包有独立有效期、不随订阅指针」；P4 冻结的是「订阅受限后基础档回落到 FREE」。
     * 两者组合的时候，本片裁决为：**已购买的扩容按其自身窗口继续贡献**（钱已付、窗口未到，
     * 不能因基础订阅降级而被没收），因此受限租户的设备上限可以是 FREE 基础 + 有效包。
     * 本用例以真实时间推进把这条语义钉住，避免它只存在于实现里。
     */
    @Test
    void timeAdvanceKeepsPurchasedExpansionOnItsOwnWindowThroughGraceAndRestriction() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Session session = registerAndLogin("s14-6b-time-" + nonce + "@example.com");
        UUID tenantId = session.tenantId();
        UUID projectId = createProject(session, "S14-6b 时间轴项目");
        session = switchProject(session, projectId);

        // 付费档 100 台 + 包 2 台，显示面与强制面必须一致。
        TenantOrder purchase = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(purchase.id(), "sim-pay-6b-time-1");
        UUID packageId = buyDevicePackage(tenantId, 2, "sim-pkg-6b-time-1");
        assertThat(displayEffectiveDevices(tenantId, projectId)).isEqualTo(102);
        assertThat(enforcedDeviceLimit(tenantId, projectId)).isEqualTo(102);

        // 到期 → 宽限：基础服务期进入宽限，但包的独立窗口未到，仍然贡献。
        Instant endsAt = subscriptionEndsAt(tenantId);
        var grace = subscriptionLifecycleService.advance(endsAt.plusSeconds(1));
        assertThat(grace.graceEntered()).isGreaterThanOrEqualTo(1);
        assertThat(liveSubscriptionStatus(tenantId)).isEqualTo("GRACE");
        assertThat(displayEffectiveDevices(tenantId, projectId))
                .as("D-171：宽限期内包仍按自身窗口贡献（P6 独立有效期）")
                .isEqualTo(102);
        assertThat(enforcedDeviceLimit(tenantId, projectId)).isEqualTo(102);

        // 宽限结束 → 受限免费：基础档回落到 FREE 的 3 台，但已购的 2 台仍在窗口内。
        var restricted = subscriptionLifecycleService.advance(graceEndsAt(tenantId).plusSeconds(1));
        assertThat(restricted.restrictedFreeEntered()).isGreaterThanOrEqualTo(1);
        assertThat(liveSubscriptionStatus(tenantId)).isEqualTo("RESTRICTED_FREE");
        assertThat(displayEffectiveDevices(tenantId, projectId))
                .as("D-171 裁决：受限租户上限 = FREE 基础 3 + 有效包 2")
                .isEqualTo(5);
        assertThat(enforcedDeviceLimit(tenantId, projectId)).isEqualTo(5);

        // 续费换行回到 ACTIVE：基础档回到 STANDARD，包仍然贡献，两侧继续一致。
        TenantOrder renewal = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(renewal.id(), "sim-pay-6b-time-2");
        assertThat(liveSubscriptionStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(displayEffectiveDevices(tenantId, projectId)).isEqualTo(102);
        assertThat(enforcedDeviceLimit(tenantId, projectId)).isEqualTo(102);

        // 续费后的服务期尚未到期：推进到「新服务期终点之前」不产生任何转换（时间轴幂等）。
        Instant renewedEndsAt = subscriptionEndsAt(tenantId);
        assertThat(renewedEndsAt).as("续费把服务期推到未来").isAfter(Instant.now());
        var noop = subscriptionLifecycleService.advance(renewedEndsAt.minusSeconds(1));
        assertThat(noop.graceEntered()).isZero();
        assertThat(noop.restrictedFreeEntered()).isZero();
        assertThat(liveSubscriptionStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(displayEffectiveDevices(tenantId, projectId)).isEqualTo(102);
        assertThat(packageStatus(packageId)).isEqualTo("ACTIVE");
    }

    /** 三路回调的重复与乱序：全部收敛为唯一事实，且不产生第二次权益后果。 */
    @Test
    void duplicateAndOutOfOrderCallbacksAcrossPaymentRefundAndFailureConvergeOnce() {
        UUID tenantId = createFreeTenant("S14-6b 回调租户");
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 2, null);

        // 乱序一：先失败（订单仍可重试），后成功。
        tenantPaymentFailureService.recordSimulatedPaymentFailure(
                tenantId, order.id(), "TIMEOUT", "sim-fail-6b-1", null);
        var activation = tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(
                order.id(), "sim-pay-6b-1");
        assertThat(activation.activated()).isTrue();
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);

        // 重复：同一支付事件重放是 no-op，不得再造第二个包或第二份审计。
        var replay = tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(
                order.id(), "sim-pay-6b-1");
        assertThat(replay.activated()).isFalse();
        assertThat(packageCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isEqualTo(1);

        // 乱序二：已结算之后到达的失败回调必须 fail-closed 且不改动任何事实。
        assertThat(failureError(tenantId, order.id(), "CHANNEL_REJECTED", "sim-fail-6b-2"))
                .isEqualTo(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT);
        assertThat(failureCount(tenantId)).isEqualTo(1);

        // 退款与其重放：一次退款、一次审计、权益收回；重复退款与复用的流水号都被拒。
        TenantRefund refund = tenantRefundService.refundPackageOrder(tenantId, order.id(),
                order.amountCents(), "整合验收退款", "sim-refund-6b-1");
        TenantRefund refundReplay = tenantRefundService.refundPackageOrder(tenantId, order.id(),
                order.amountCents(), "整合验收退款", "sim-refund-6b-1");
        assertThat(refundReplay.id()).isEqualTo(refund.id());
        assertThat(refundCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.refund.succeeded")).isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).as("退款即收回权益").isEqualTo(3);

        BusinessException secondRefund = catchThrowableOfType(
                () -> tenantRefundService.refundPackageOrder(tenantId, order.id(), order.amountCents(),
                        "再次退款", "sim-refund-6b-2"),
                BusinessException.class);
        assertThat(secondRefund).isNotNull();
        assertThat(secondRefund.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_REFUNDABLE);

        // 全流程结束后：订单仍只有一份 PAID 事实、一次退款、一次失败。
        assertThat(orderRow(order.id())).containsEntry("status", "PAID");
        assertThat(solePackageStatus(tenantId)).isEqualTo("REFUNDED");
        assertThat(failureCount(tenantId)).isEqualTo(1);
        assertThat(refundCount(tenantId)).isEqualTo(1);
    }

    /** 显示面与强制面在每次商业事实变化后逐值一致（缓存失效必须同时覆盖两条路径）。 */
    @Test
    void cacheInvalidationKeepsDisplayAndEnforcementEqualAcrossEveryCommercialChange() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Session session = registerAndLogin("s14-6b-cache-" + nonce + "@example.com");
        UUID tenantId = session.tenantId();
        UUID projectId = createProject(session, "S14-6b 一致性项目");
        session = switchProject(session, projectId);
        assertEqualBothSides(tenantId, projectId, 3);

        // 买包 +2
        UUID packageId = buyDevicePackage(tenantId, 2, "sim-pkg-6b-cache-1");
        assertEqualBothSides(tenantId, projectId, 5);

        // 授予人工调整 +1：真实 HTTP 设备准入放行第 6 台。
        Instant now = Instant.now();
        EntitlementAdjustmentResult adjustment = tenantEntitlementAdjustmentService.createAdjustment(
                tenantId, new EntitlementAdjustmentRequest("DEVICES_MAX", 1, now,
                        now.plus(30, ChronoUnit.DAYS), "S14-6b 一致性补偿", OPERATOR, "inc-6b-cache-1"));
        // 只等数据库时钟越过请求起点，不轮询额度，避免把缓存失效缺陷重试成绿色。
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                jdbcTemplate.queryForObject("SELECT clock_timestamp() >= ?", Boolean.class, java.sql.Timestamp.from(now)));
        assertEqualBothSides(tenantId, projectId, 6);
        for (int index = 1; index <= 6; index++) {
            MvcResult device = createDevice(session.accessToken(), projectId, "s146b_device_" + index);
            assertThat(device.getResponse().getStatus())
                    .as("第 %d 台设备应被合成后的 6 台上限允许", index)
                    .isEqualTo(201);
        }
        MvcResult seventh = createDevice(session.accessToken(), projectId, "s146b_device_7");
        assertThat(seventh.getResponse().getStatus()).isEqualTo(429);
        assertThat(JSON.readTree(seventh.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(30035);

        // 撤销调整 -1：两侧同时回落（缓存失效必须覆盖收窄方向）。
        assertThat(tenantEntitlementAdjustmentService.revokeAdjustment(tenantId,
                adjustment.adjustmentId(), OPERATOR, "S14-6b 撤销补偿")).isTrue();
        assertEqualBothSides(tenantId, projectId, 5);

        // 退款收回包 -2：两侧回到基础档。
        tenantRefundService.refundPackageOrder(tenantId, sourceOrderOf(packageId),
                orderAmountOf(packageId), "整合验收退款", "sim-refund-6b-cache-1");
        assertEqualBothSides(tenantId, projectId, 3);
    }

    /** 跨租户隔离与财务对账不变量。 */
    @Test
    void crossTenantIsolationAndReconciliationInvariantsHoldAfterFullFlow() {
        UUID tenantA = createFreeTenant("S14-6b 隔离租户甲");
        UUID tenantB = createFreeTenant("S14-6b 隔离租户乙");
        Instant now = Instant.now();

        // B 买包 + 授予调整；A 的有效额度不受影响。
        TenantOrder orderB = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantB, "DEVICES_MAX", 3, null);
        tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(orderB.id(), "sim-pay-6b-iso-b");
        EntitlementAdjustmentResult adjustmentB = tenantEntitlementAdjustmentService.createAdjustment(
                tenantB, new EntitlementAdjustmentRequest("DEVICES_MAX", 1, now,
                        now.plus(30, ChronoUnit.DAYS), "S14-6b 隔离补偿", OPERATOR, "inc-6b-iso-b"));
        assertThat(effectiveDevicesMax(tenantA)).as("A 不受 B 的商业事实影响").isEqualTo(3);
        assertThat(effectiveDevicesMax(tenantB)).isEqualTo(7);

        // A 既不能退 B 的订单，也不能撤 B 的调整（同码 404，不做存在性探测）。
        BusinessException crossRefund = catchThrowableOfType(
                () -> tenantRefundService.refundPackageOrder(tenantA, orderB.id(), orderB.amountCents(),
                        "跨租户退款", "sim-refund-6b-iso-a"),
                BusinessException.class);
        assertThat(crossRefund).isNotNull();
        assertThat(crossRefund.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_FOUND);
        BusinessException crossRevoke = catchThrowableOfType(
                () -> tenantEntitlementAdjustmentService.revokeAdjustment(tenantA,
                        adjustmentB.adjustmentId(), OPERATOR, "跨租户撤销"),
                BusinessException.class);
        assertThat(crossRevoke).isNotNull();
        assertThat(crossRevoke.errorCode()).isEqualTo(ProjectErrorCode.ADJUSTMENT_NOT_FOUND);
        assertThat(effectiveDevicesMax(tenantB)).as("跨租户操作不得改动 B 的权益").isEqualTo(7);

        // 对账不变量一：已支付订单必须有对应权益事实（包订单→包，套餐订单→订阅）。
        Long paidOrdersWithoutEntitlement = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_order o
                 WHERE o.tenant_id = ? AND o.status = 'PAID'
                   AND o.order_kind = 'PACKAGE'
                   AND NOT EXISTS (SELECT 1 FROM sys_tenant_resource_package p
                                    WHERE p.source_order_id = o.id)
                """, Long.class, tenantB);
        assertThat(paidOrdersWithoutEntitlement)
                .as("已支付的包订单必须产出资源包（订单与权益事实同生共死）")
                .isZero();

        // 对账不变量二：订单已退金额等于逐笔成功退款之和；无退款的行必须为 0。
        Long mismatchedRefundTotals = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_order o
                 WHERE o.tenant_id = ?
                   AND o.refunded_cents <> COALESCE((
                        SELECT SUM(r.amount_cents) FROM sys_tenant_refund r
                         WHERE r.order_id = o.id AND r.status = 'SUCCEEDED'), 0)
                """, Long.class, tenantB);
        assertThat(mismatchedRefundTotals).as("已退金额与逐笔退款必须逐值一致").isZero();

        // 对账不变量三：失败事实只允许落在未结算订单上。
        Long failuresOnSettledOrders = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_order_payment_failure f
                  JOIN sys_tenant_order o ON o.id = f.order_id
                 WHERE f.tenant_id = ? AND o.status <> 'CREATED'
                """, Long.class, tenantB);
        assertThat(failuresOnSettledOrders).isZero();

        // 对账不变量四：REFUNDED 的购买包必须有对应的成功退款。
        Long refundedPackagesWithoutRefund = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_package p
                 WHERE p.tenant_id = ? AND p.status = 'REFUNDED'
                   AND NOT EXISTS (SELECT 1 FROM sys_tenant_refund r
                                    WHERE r.order_id = p.source_order_id AND r.status = 'SUCCEEDED')
                """, Long.class, tenantB);
        assertThat(refundedPackagesWithoutRefund).isZero();
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助
    // ------------------------------------------------------------------

    /** 非 HTTP 用例使用的操作人（审计表无外键）。 */
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-0000000006b0");

    /**
     * 断言行侧（运行时有效维度）与强制侧（设备准入 SQL 面）逐值一致。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param expected 期望的设备上限
     * @throws Exception HTTP 调用失败
     */
    private void assertEqualBothSides(UUID tenantId, UUID projectId, long expected) throws Exception {
        assertThat(enforcedDeviceLimit(tenantId, projectId))
                .as("设备准入 SQL 面读到的上限").isEqualTo(expected);
        assertThat(displayEffectiveDevices(tenantId, projectId))
                .as("控制台读到的运行时有效维度必须与强制面相等（缓存失效覆盖两条路径）")
                .isEqualTo(expected);
    }

    /**
     * 走真实 HTTP 读面读取运行时有效设备额度。
     *
     * @param tenantId 租户 ID（用于断言租户一致）
     * @param projectId 项目 ID
     * @return 运行时有效设备上限
     * @throws Exception HTTP 调用失败
     */
    private long displayEffectiveDevices(UUID tenantId, UUID projectId) throws Exception {
        assertThat(tenantIds).contains(tenantId);
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(projectId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode plan = JSON.readTree(result.getResponse().getContentAsString()).get("planSummary");
        assertThat(plan).isNotNull();
        for (JsonNode dimension : plan.get("effectiveQuotaDimensions")) {
            if ("DEVICES_MAX".equals(dimension.get("code").asString())) {
                return dimension.get("value").asLong();
            }
        }
        throw new AssertionError("运行时有效维度缺少 DEVICES_MAX");
    }

    /**
     * 读取设备准入 SQL 面（真实设备创建走同一个函数）。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @return 设备上限
     */
    private long enforcedDeviceLimit(UUID tenantId, UUID projectId) {
        Long limit = jdbcTemplate.queryForObject(
                "SELECT device_limit_value FROM device_project_quota_policy(?, ?)",
                Long.class, tenantId, projectId);
        return limit == null ? 0L : limit;
    }

    /** 最近一次登录的令牌（本类每个用例只用一个账号，按项目定位即可）。 */
    private final Map<UUID, String> projectTokens = new java.util.HashMap<>();

    /**
     * 取该项目的访问令牌。
     *
     * @param projectId 项目 ID
     * @return Bearer 令牌
     */
    private String tokenFor(UUID projectId) {
        String token = projectTokens.get(projectId);
        assertThat(token).as("项目 %s 的会话令牌", projectId).isNotNull();
        return token;
    }

    /**
     * 径由投影读取合成后的设备上限（无需 HTTP 的用例使用）。
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
     * 走真实租户创建入口。
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
     * 买一个设备包并断言已生效。
     *
     * @param tenantId 租户 ID
     * @param amount 包额度
     * @param eventId 支付事件 ID
     * @return 包 ID
     */
    private UUID buyDevicePackage(UUID tenantId, long amount, String eventId) {
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", amount, null);
        return tenantResourcePackageService
                .applySimulatedPackagePaymentSucceeded(order.id(), eventId).packageId();
    }

    /** @param packageId 包 ID @return 包的来源订单 ID */
    private UUID sourceOrderOf(UUID packageId) {
        return jdbcTemplate.queryForObject(
                "SELECT source_order_id FROM sys_tenant_resource_package WHERE id = ?",
                UUID.class, packageId);
    }

    /** @param packageId 包 ID @return 来源订单金额 */
    private long orderAmountOf(UUID packageId) {
        Long amount = jdbcTemplate.queryForObject("""
                SELECT o.amount_cents FROM sys_tenant_order o
                  JOIN sys_tenant_resource_package p ON p.source_order_id = o.id
                 WHERE p.id = ?
                """, Long.class, packageId);
        return amount == null ? 0L : amount;
    }

    /** @param tenantId 租户 ID @return 当前活订阅状态 */
    private String liveSubscriptionStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status IN ('ACTIVE', 'GRACE', 'RESTRICTED_FREE')
                 ORDER BY starts_at DESC, id DESC LIMIT 1
                """, String.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅服务期终点 */
    private Instant subscriptionEndsAt(UUID tenantId) {
        Timestamp value = jdbcTemplate.queryForObject("""
                SELECT ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.class, tenantId);
        return value.toInstant();
    }

    /** @param tenantId 租户 ID @return 当前 GRACE 订阅的宽限终点 */
    private Instant graceEndsAt(UUID tenantId) {
        Timestamp value = jdbcTemplate.queryForObject("""
                SELECT grace_ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'GRACE'
                """, Timestamp.class, tenantId);
        return value.toInstant();
    }

    /** @param orderId 订单 ID @return 订单状态与已退金额 */
    private Map<String, Object> orderRow(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT status, refunded_cents FROM sys_tenant_order WHERE id = ?",
                orderId);
    }

    /** @param packageId 包 ID @return 包状态 */
    private String packageStatus(UUID packageId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_resource_package WHERE id = ?", String.class, packageId);
    }

    /** @param tenantId 租户 ID @return 该租户唯一资源包状态 */
    private String solePackageStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_resource_package WHERE tenant_id = ?",
                String.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户资源包行数 */
    private int packageCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_resource_package WHERE tenant_id = ?",
                Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户退款行数 */
    private int refundCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_refund WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户支付失败事实行数 */
    private int failureCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_order_payment_failure WHERE tenant_id = ?
                """, Integer.class, tenantId);
    }

    /**
     * 执行一次失败回调并返回错误码。
     *
     * @param tenantId 租户 ID
     * @param orderId 订单 ID
     * @param failureCode 失败分类
     * @param eventId 渠道事件 ID
     * @return 抛出的错误码
     */
    private com.things.link.shared.error.ErrorCode failureError(UUID tenantId, UUID orderId,
                                                                String failureCode, String eventId) {
        BusinessException exception = catchThrowableOfType(
                () -> tenantPaymentFailureService.recordSimulatedPaymentFailure(
                        tenantId, orderId, failureCode, eventId, null),
                BusinessException.class);
        assertThat(exception).isNotNull();
        return exception.errorCode();
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

    /**
     * 走真实注册/登录链路并记录账号、租户与刷新 Cookie。
     *
     * @param email 测试邮箱
     * @return 会话
     * @throws Exception HTTP 调用失败
     */
    private Session registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        UUID accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        accountIds.add(accountId);
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_tenant_member WHERE account_id = ?", UUID.class, accountId);
        tenantIds.add(tenantId);
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        return new Session(JSON.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asString(), refresh, tenantId, accountId);
    }

    /**
     * 通过真实 HTTP 创建项目。
     *
     * @param session 会话
     * @param name 项目名
     * @return 项目 ID
     * @throws Exception HTTP 调用失败
     */
    private UUID createProject(Session session, String name) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(name)))
                .andReturn();
        UUID projectId = UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .get("id").asString());
        projectIds.add(projectId);
        return projectId;
    }

    /**
     * 切换项目并返回轮换后的会话。
     *
     * @param session 会话
     * @param projectId 目标项目
     * @return 轮换后的会话
     * @throws Exception HTTP 调用失败
     */
    private Session switchProject(Session session, UUID projectId) throws Exception {
        MvcResult switched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", session.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(projectId)))
                .andReturn();
        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        String token = JSON.readTree(switched.getResponse().getContentAsString())
                .get("accessToken").asString();
        projectTokens.put(projectId, token);
        return new Session(token, session.refreshToken(), session.tenantId(), session.accountId());
    }

    /**
     * 走既有设备创建入口建一台无类型设备。
     *
     * @param token 访问令牌
     * @param projectId 项目 ID
     * @param deviceKey 设备键
     * @return HTTP 结果
     * @throws Exception HTTP 调用失败
     */
    private MvcResult createDevice(String token, UUID projectId, String deviceKey) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"%s\",\"name\":\"S14-6b 设备\"}".formatted(deviceKey)))
                .andReturn();
    }

    /** 控制台会话的最小凭据。 */
    private record Session(String accessToken, String refreshToken, UUID tenantId, UUID accountId) {
    }
}
