package com.things.link.bootstrap.project.subscription;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.project.application.ResourcePackageActivation;
import com.things.link.project.application.ResourcePackageAdvanceReport;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.TenantOrder;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * S14-4a 资源包事实与有效权益合成的真实 PostgreSQL / HTTP 验收（S14-0 P6）。
 *
 * <p>本类钉住七件事：① 买设备包后有效设备上限 = 基础档 + 包，且**真实设备准入入口**接受多出来的
 * 设备（证明强制面跟随合成）；② 同维度两个包求和、异维度互不影响；③ 到期推进恰好标记一次、
 * 重跑不变、有效上限回落；④ 非 ACTIVE 包不贡献（取消面）；⑤ 支付事件重放与并发恰好一个包；
 * ⑥ 包不延长订阅 {@code ends_at}、也不结转（跨窗口有效值是上限不是余额）；⑦ 跨租户隔离。
 *
 * <p>不使用方法级 {@code @Transactional}：并发用例必须看到其他连接已提交的事实，清理显式按外键
 * 依赖顺序执行。有效权益断言通过既有 {@code EffectiveQuotaPolicyProvider} 读取（需要真实租户上下文
 * 才能命中带 RLS 的权威查询），因此辅助方法负责设置/清理 {@code TenantContext}。
 */
@AutoConfigureMockMvc
@DisplayName("S14-4a 资源包与有效权益合成")
class TenantResourcePackageIntegrationTests extends AbstractIntegrationTest {

    /** 真实 JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试账号固定口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** 真实 HTTP 入口。 */
    @Autowired
    private MockMvc mockMvc;
    /** 夹具事实写入与核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅与 PLAN_R1_FREE 绑定。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 被测生产服务：资源包下单、生效与时间推进。 */
    @Autowired
    private TenantResourcePackageService tenantResourcePackageService;
    /** 套餐订单服务：用于验证「订单种类与入口不匹配」的拒绝码。 */
    @Autowired
    private TenantOrderService tenantOrderService;
    /** 有效权益读取入口（合成后的 devicesMax 等）。 */
    @Autowired
    private EffectiveQuotaPolicyProvider effectiveQuotaPolicyProvider;
    /** 既有缓存失效协议入口：取消/退款面在本片未实现，用例用它模拟一次包变化后的失效。 */
    @Autowired
    private QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 为 {@code MANDATORY} 的租户创建提供外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;
    /** 注册限流器；同一 remoteAddr 连续注册前清理。 */
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
                jdbcTemplate.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id = ?", tenantId);
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

    /** 设备包把有效设备上限从 FREE 的 3 提到 5，真实准入入口放行第 4、5 台并拒绝第 6 台。 */
    @Test
    void devicePackageRaisesEffectiveLimitAndRealAdmissionPathAcceptsExtraDevices() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Session session = registerAndLogin("s14-4a-device-" + nonce + "@example.com");
        UUID tenantId = session.tenantId();
        MvcResult created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"S14-4a 设备扩容项目","region":"sh-1"}"""))
                .andReturn();
        JsonNode project = JSON.readTree(created.getResponse().getContentAsString());
        UUID projectId = UUID.fromString(project.get("id").asString());
        projectIds.add(projectId);
        session = switchProject(session, projectId);
        Timestamp subscriptionEndsAt = activeSubscriptionEndsAt(tenantId);
        int subscriptionRows = subscriptionCount(tenantId);

        assertThat(effectiveDevicesMax(tenantId)).as("FREE 基础档设备上限为 3").isEqualTo(3);

        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 2, null);
        assertThat(order.amountCents()).as("2 单位 x 冻结单位参考价 1000 分").isEqualTo(2_000L);
        ResourcePackageActivation activation = tenantResourcePackageService
                .applySimulatedPackagePaymentSucceeded(order.id(), "sim-pkg-device-1");
        assertThat(activation.activated()).isTrue();
        assertThat(activation.status()).isEqualTo(ResourcePackageStatus.ACTIVE);
        assertThat(activation.endsAt())
                .as("P6 默认 12 个 UTC 日历月")
                .isEqualTo(activation.startsAt().atZone(java.time.ZoneOffset.UTC).plusMonths(12).toInstant());
        assertThat(effectiveDevicesMax(tenantId))
                .as("有效设备上限 = 基础 3 + 包 2，且必须经既有投影看到")
                .isEqualTo(5);
        assertThat(activeSubscriptionEndsAt(tenantId))
                .as("买包绝不改订阅服务期（P6：包不随订阅续期，也不影响订阅）")
                .isEqualTo(subscriptionEndsAt);
        assertThat(subscriptionCount(tenantId)).as("买包不得新建或替换订阅行").isEqualTo(subscriptionRows);

        for (int index = 1; index <= 5; index++) {
            MvcResult device = createDevice(session.accessToken(), "s144a_device_" + index);
            assertThat(device.getResponse().getStatus()).as("第 %d 台设备应被合成后的 5 台上限允许", index)
                    .isEqualTo(201);
        }
        MvcResult sixth = createDevice(session.accessToken(), "s144a_device_6");
        assertThat(sixth.getResponse().getStatus()).as("合成上限之外仍被既有设备配额拒绝").isEqualTo(429);
        assertThat(JSON.readTree(sixth.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(30035);
    }

    /** 同维度两个包求和；异维度包不影响其它维度。 */
    @Test
    void twoPackagesOnSameDimensionSumAndDifferentDimensionDoesNotAffectOthers() {
        UUID tenantId = createFreeTenant("S14-4a 求和租户");

        buyDevicePackage(tenantId, 2, "sim-pkg-sum-1");
        buyDevicePackage(tenantId, 3, "sim-pkg-sum-2");
        assertThat(effectiveDevicesMax(tenantId)).as("3 + 2 + 3").isEqualTo(8);

        TenantOrder messageOrder = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "UPLINK_MESSAGE_DAILY", 1_000, null);
        tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(
                messageOrder.id(), "sim-pkg-sum-3");

        TenantContext.set(new TenantScope(tenantId, null, UUID.randomUUID()));
        try {
            var quota = effectiveQuotaPolicyProvider.resolveTrustedTenant(tenantId).planQuota();
            assertThat(quota.uplinkMessageDailyLimit()).as("FREE 700 + 包 1000").isEqualTo(1_700);
            assertThat(quota.devicesMax()).as("消息包不得影响设备维度").isEqualTo(8);
            assertThat(quota.downlinkMessageDailyLimit()).isEqualTo(300);
        } finally {
            TenantContext.clear();
        }
    }

    /** 到期推进恰好标记一次并让有效上限回落；重跑同一时刻不再产生任何变化。 */
    @Test
    void expiryPassMarksExpiredExactlyOnceAndEffectiveLimitDropsBack() {
        UUID tenantId = createFreeTenant("S14-4a 到期租户");
        ResourcePackageActivation activation = buyDevicePackage(tenantId, 2, "sim-pkg-expiry-1");
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);

        Instant afterExpiry = activation.endsAt().plusSeconds(1);
        ResourcePackageAdvanceReport first = tenantResourcePackageService.advance(afterExpiry);
        assertThat(first.expired()).isEqualTo(1);
        assertThat(first.activatedPending()).isZero();
        assertThat(first.tenantsInvalidated()).isEqualTo(1);
        assertThat(packageStatus(activation.packageId())).isEqualTo("EXPIRED");
        assertThat(effectiveDevicesMax(tenantId)).as("到期后有效上限回到基础档 3").isEqualTo(3);
        assertThat(auditCount(tenantId, "commercial.package.expired")).isEqualTo(1);

        ResourcePackageAdvanceReport replay = tenantResourcePackageService.advance(afterExpiry);
        assertThat(replay.expired()).isZero();
        assertThat(replay.activatedPending()).isZero();
        assertThat(replay.tenantsInvalidated()).isZero();
        assertThat(packageStatus(activation.packageId())).isEqualTo("EXPIRED");
        assertThat(auditCount(tenantId, "commercial.package.expired"))
                .as("重跑不得重复写审计")
                .isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(3);
    }

    /** 非 ACTIVE（取消）包不参与合成。取消/退款动作属 S14-5，本用例只证明合成只认 ACTIVE。 */
    @Test
    void cancelledPackageContributesNothing() {
        UUID tenantId = createFreeTenant("S14-4a 取消租户");
        ResourcePackageActivation activation = buyDevicePackage(tenantId, 2, "sim-pkg-cancel-1");
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);

        jdbcTemplate.update("UPDATE sys_tenant_resource_package SET status = 'CANCELLED' WHERE id = ?",
                activation.packageId());
        // 取消/退款（S14-5）会像本片一样复用既有缓存失效协议；用例显式触发一次以验证合成。
        invalidateTenantPolicyCache(tenantId);

        assertThat(effectiveDevicesMax(tenantId))
                .as("CANCELLED 包贡献 0，不得继续提升上限")
                .isEqualTo(3);
    }

    /** P6：订阅不在 ACTIVE/GRACE 时包落为 PENDING、暂不提升上限；订阅恢复后由时间推进激活。 */
    @Test
    void packagePurchasedWhileSubscriptionRestrictedBecomesPendingThenActivates() {
        UUID tenantId = createFreeTenant("S14-4a 待生效租户");
        // 直接构造受限订阅夹具（P4 的 RESTRICTED_FREE 切换已由 S14-3c 验收，这里只需要那个状态）。
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET status = 'RESTRICTED_FREE', grace_ends_at = now(), restricted_at = now()
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, tenantId);

        ResourcePackageActivation activation = buyDevicePackage(tenantId, 2, "sim-pkg-pending-1");
        assertThat(activation.status()).as("订阅受限时包必须待生效而不是立即生效")
                .isEqualTo(ResourcePackageStatus.PENDING);
        assertThat(effectiveDevicesMax(tenantId)).as("PENDING 包不提升上限").isEqualTo(3);
        assertThat(auditCount(tenantId, "commercial.package.pending")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isZero();

        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET status = 'ACTIVE', grace_ends_at = NULL, restricted_at = NULL
                 WHERE tenant_id = ?
                """, tenantId);
        ResourcePackageAdvanceReport report = tenantResourcePackageService.advance(Instant.now());

        assertThat(report.activatedPending()).isEqualTo(1);
        assertThat(packageStatus(activation.packageId())).isEqualTo("ACTIVE");
        assertThat(effectiveDevicesMax(tenantId)).as("订阅恢复后包参与合成").isEqualTo(5);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isEqualTo(1);
    }

    /** 支付事件重放与并发支付都只产生一个包。 */
    @Test
    void replayAndConcurrentPaymentProduceExactlyOnePackage() throws Exception {
        UUID tenantId = createFreeTenant("S14-4a 幂等租户");
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 2, null);

        List<Object> outcomes = payPackageConcurrently(List.of(
                () -> tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(
                        order.id(), "sim-pkg-race"),
                () -> tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(
                        order.id(), "sim-pkg-race")));

        long activated = outcomes.stream().filter(ResourcePackageActivation.class::isInstance)
                .map(ResourcePackageActivation.class::cast)
                .filter(ResourcePackageActivation::activated).count();
        assertThat(activated).as("并发重复支付恰好生效一次").isEqualTo(1);
        assertThat(packageCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.order.paid")).isEqualTo(1);

        ResourcePackageActivation replay = tenantResourcePackageService
                .applySimulatedPackagePaymentSucceeded(order.id(), "sim-pkg-race");
        assertThat(replay.activated()).isFalse();
        assertThat(packageCount(tenantId)).as("重放不得建第二个包").isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);
    }

    /** 包不延长订阅服务期，也不跨窗口结转：旧包到期后新包是「基础 + 新包」而不是累加余额。 */
    @Test
    void packagesDoNotExtendSubscriptionOrCarryOverAcrossWindows() {
        UUID tenantId = createFreeTenant("S14-4a 不结转租户");
        // 先买一份付费档，得到有明确 ends_at 的订阅：包的独立性必须体现在这条真实服务期上。
        TenantOrder planOrder = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(planOrder.id(), "sim-pkg-plan-1");
        Timestamp originalEndsAt = activeSubscriptionEndsAt(tenantId);
        int subscriptionRows = subscriptionCount(tenantId);
        assertThat(effectiveDevicesMax(tenantId)).as("STANDARD 基础设备上限").isEqualTo(100);

        ResourcePackageActivation firstWindow = buyDevicePackage(tenantId, 2, "sim-pkg-window-1");
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(102);
        tenantResourcePackageService.advance(firstWindow.endsAt().plusSeconds(1));
        assertThat(effectiveDevicesMax(tenantId)).as("第一个窗口结束后回到基础档 100").isEqualTo(100);

        buyDevicePackage(tenantId, 4, "sim-pkg-window-2");
        assertThat(effectiveDevicesMax(tenantId))
                .as("第二个窗口 = 基础 100 + 新包 4；已到期包不得结转成 106")
                .isEqualTo(104);

        assertThat(activeSubscriptionEndsAt(tenantId))
                .as("两次买包都不得改动订阅 ends_at")
                .isEqualTo(originalEndsAt);
        assertThat(subscriptionCount(tenantId))
                .as("买包不得新建或替换订阅行")
                .isEqualTo(subscriptionRows);
    }

    /** 跨租户隔离：另一租户的包永远不影响本租户有效策略。 */
    @Test
    void anotherTenantsPackageNeverAffectsThisTenant() {
        UUID tenantA = createFreeTenant("S14-4a 隔离租户 A");
        UUID tenantB = createFreeTenant("S14-4a 隔离租户 B");

        buyDevicePackage(tenantB, 5, "sim-pkg-isolation-b");

        assertThat(effectiveDevicesMax(tenantA)).as("A 的有效设备上限不受 B 的包影响").isEqualTo(3);
        assertThat(effectiveDevicesMax(tenantB)).isEqualTo(8);
    }

    /** 拒绝面：不可扩容维度、订单种类不匹配、非正额度与非未来起算时刻。 */
    @Test
    void refusesUnsupportedDimensionAndOrderKindMismatch() {
        UUID tenantId = createFreeTenant("S14-4a 拒绝租户");

        BusinessException unknown = catchThrowableOfType(
                () -> tenantResourcePackageService.createSimulatedPackageOrder(
                        tenantId, "NOT_A_DIMENSION", 1, null),
                BusinessException.class);
        assertThat(unknown).isNotNull();
        assertThat(unknown.errorCode()).isEqualTo(ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED);
        assertThat(unknown.errorCode().code()).isEqualTo(50034);

        BusinessException history = catchThrowableOfType(
                () -> tenantResourcePackageService.createSimulatedPackageOrder(
                        tenantId, "HISTORY_WINDOW", 1, null),
                BusinessException.class);
        assertThat(history).isNotNull();
        assertThat(history.errorCode()).isEqualTo(ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED);

        BusinessException nonPositive = catchThrowableOfType(
                () -> tenantResourcePackageService.createSimulatedPackageOrder(
                        tenantId, "DEVICES_MAX", 0, null),
                BusinessException.class);
        assertThat(nonPositive).isNotNull();

        BusinessException pastStart = catchThrowableOfType(
                () -> tenantResourcePackageService.createSimulatedPackageOrder(
                        tenantId, "DEVICES_MAX", 1, Instant.now().minusSeconds(60)),
                BusinessException.class);
        assertThat(pastStart).isNotNull();

        TenantOrder planOrder = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        BusinessException planThroughPackageEntry = catchThrowableOfType(
                () -> tenantResourcePackageService.applySimulatedPackagePaymentSucceeded(
                        planOrder.id(), "sim-pkg-wrong-kind-1"),
                BusinessException.class);
        assertThat(planThroughPackageEntry).isNotNull();
        assertThat(planThroughPackageEntry.errorCode()).isEqualTo(ProjectErrorCode.ORDER_KIND_MISMATCH);
        assertThat(planThroughPackageEntry.errorCode().code()).isEqualTo(50035);

        TenantOrder packageOrder = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", 1, null);
        BusinessException packageThroughSubscriptionEntry = catchThrowableOfType(
                () -> tenantOrderService.applySimulatedPaymentSucceeded(
                        packageOrder.id(), "sim-pkg-wrong-kind-2"),
                BusinessException.class);
        assertThat(packageThroughSubscriptionEntry).isNotNull();
        assertThat(packageThroughSubscriptionEntry.errorCode()).isEqualTo(ProjectErrorCode.ORDER_KIND_MISMATCH);
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
     * 买一个设备资源包并断言已生效。
     *
     * @param tenantId 租户 ID
     * @param amount 包额度
     * @param eventId 支付事件 ID
     * @return 生效结果
     */
    private ResourcePackageActivation buyDevicePackage(UUID tenantId, long amount, String eventId) {
        TenantOrder order = tenantResourcePackageService.createSimulatedPackageOrder(
                tenantId, "DEVICES_MAX", amount, null);
        ResourcePackageActivation activation = tenantResourcePackageService
                .applySimulatedPackagePaymentSucceeded(order.id(), eventId);
        assertThat(activation.activated()).isTrue();
        return activation;
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
     * 用既有 CAS 失效协议显式失效一次租户的策略缓存（模拟 S14-5 取消/退款后的包变化）。
     *
     * @param tenantId 租户 ID
     */
    private void invalidateTenantPolicyCache(UUID tenantId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
        UUID policyId = jdbcTemplate.queryForObject(
                "SELECT quota_policy_id FROM sys_tenant WHERE id = ?", UUID.class, tenantId);
        quotaPolicyAssignmentService.assign(tenantId, policyId, version);
    }

    /**
     * 并发执行一组包支付调用，返回成功结果或业务异常。
     *
     * @param calls 待并发执行的调用
     * @return 与调用顺序对应的结果列表
     * @throws Exception 线程池等待被中断时直接失败
     */
    private List<Object> payPackageConcurrently(List<java.util.concurrent.Callable<Object>> calls)
            throws Exception {
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

    /** @param packageId 包 ID @return 包状态 */
    private String packageStatus(UUID packageId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_resource_package WHERE id = ?", String.class, packageId);
    }

    /** @param tenantId 租户 ID @return 该租户全部资源包行数 */
    private int packageCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_resource_package WHERE tenant_id = ?", Integer.class, tenantId);
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

    /** 走真实注册/登录链路并记录账号、租户 ID 与刷新 Cookie 以便回收与切项目。 */
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
                .get("accessToken").asString(), refresh, tenantId);
    }

    /** 切换已选项目并返回轮换后的会话。 */
    private Session switchProject(Session session, UUID targetProjectId) throws Exception {
        MvcResult switched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .cookie(new jakarta.servlet.http.Cookie("tc_refresh", session.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(targetProjectId)))
                .andReturn();
        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        return new Session(JSON.readTree(switched.getResponse().getContentAsString())
                .get("accessToken").asString(), session.refreshToken(), session.tenantId());
    }

    /** 走既有设备创建入口建一台无类型设备。 */
    private MvcResult createDevice(String token, String deviceKey) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/projects/" + currentProjectId(token) + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"%s\",\"name\":\"S14-4a 设备\"}".formatted(deviceKey)))
                .andReturn();
    }

    /**
     * 取本用例唯一项目的 ID（设备创建 URL 需要它；每用例只建一个项目）。
     *
     * @param token 已切换项目后的令牌
     * @return 项目 ID
     */
    private UUID currentProjectId(String token) {
        assertThat(projectIds).hasSize(1);
        return projectIds.iterator().next();
    }

    /** 控制台会话的最小凭据。 */
    private record Session(String accessToken, String refreshToken, UUID tenantId) {
    }
}
