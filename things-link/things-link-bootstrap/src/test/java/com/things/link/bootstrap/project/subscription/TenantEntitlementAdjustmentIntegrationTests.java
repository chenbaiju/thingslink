package com.things.link.bootstrap.project.subscription;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.EntitlementAdjustmentRequest;
import com.things.link.project.application.EntitlementAdjustmentResult;
import com.things.link.project.application.TenantEntitlementAdjustmentService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ResourcePackageAdjustment;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * S14-4b 有限期人工调整的真实 PostgreSQL / HTTP 验收。
 *
 * <p>本类钉住八件事：① 调整与购买包**同走一条有效权益合成**，且真实设备准入 SQL 面
 * （{@code device_project_quota_policy}）与真实 HTTP 设备创建入口都跟随；② 幂等键重放恰好一行、
 * 一次审计、不翻倍，换参数复用同一键被拒；③ 有效期语义（未来窗口不提前生效、到期推进恰好一次
 * 且重跑不变、上限回落）；④ 订阅受限时落 PENDING、恢复后由既有推进激活；⑤ 撤销立即停止贡献且
 * 幂等，购买包与已到期调整不可撤销；⑥ 跨租户隔离（值隔离 + 撤销不可跨租户猜测）；⑦ 共享配额模板
 * 逐列与版本号在调整前后完全相同（人工调整只改租户事实，绝不动共享模板）；⑧ 拒绝面（维度、
 * 额度、窗口、元数据、租户）与数据库端同生共死约束。
 *
 * <p>不使用方法级 {@code @Transactional}：并发用例必须看到其他连接已提交的事实；清理显式按外键
 * 依赖顺序执行（审计表有禁止 DELETE 的触发器，且无外键，因此不做审计清理——租户 ID 每用例都是
 * 新生成的，断言始终按租户限定）。
 */
@AutoConfigureMockMvc
@DisplayName("S14-4b 有限期人工调整")
class TenantEntitlementAdjustmentIntegrationTests extends AbstractIntegrationTest {

    /** 固定调整原因：断言审计里确实留下了「为什么给」。 */
    private static final String REASON = "工单 INC-2026-0916 补偿";

    /** 非 HTTP 用例使用的操作人：审计表无外键，运维账号注销不得让商业事实变孤儿。 */
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-0000000004b0");

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
    /** 被测生产服务：人工调整的授予与撤销。 */
    @Autowired
    private TenantEntitlementAdjustmentService tenantEntitlementAdjustmentService;
    /** 既有资源包状态机：调整的到期与待生效推进复用同一条时间推进。 */
    @Autowired
    private TenantResourcePackageService tenantResourcePackageService;
    /** 有效权益读取入口（合成后的 devicesMax 等）。 */
    @Autowired
    private EffectiveQuotaPolicyProvider effectiveQuotaPolicyProvider;
    /** 资源包事实端口：用于在指定时刻验证窗口语义（未来窗口不参与当前合成）。 */
    @Autowired
    private TenantResourcePackageRepository tenantResourcePackageRepository;
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
                jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
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

    /** 调整把有效设备上限从 FREE 的 3 提到 5；真实设备准入 SQL 面与真实 HTTP 入口都跟随。 */
    @Test
    void adjustmentRaisesEffectiveLimitInAdmissionSurfaceAndRealDeviceEntry() throws Exception {
        String nonce = UUID.randomUUID().toString();
        Session session = registerAndLogin("s14-4b-device-" + nonce + "@example.com");
        UUID tenantId = session.tenantId();
        MvcResult created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"S14-4b 调整项目","region":"sh-1"}"""))
                .andReturn();
        UUID projectId = UUID.fromString(JSON.readTree(created.getResponse()
                .getContentAsString()).get("id").asString());
        projectIds.add(projectId);
        session = switchProject(session, projectId);
        Instant now = Instant.now();
        assertThat(effectiveDevicesMax(tenantId)).as("FREE 基础档设备上限为 3").isEqualTo(3);

        EntitlementAdjustmentResult result = tenantEntitlementAdjustmentService.createAdjustment(tenantId,
                request("DEVICES_MAX", 2, now, now.plus(30, ChronoUnit.DAYS), "inc-device-0001",
                        session.accountId()));

        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo(ResourcePackageStatus.ACTIVE);
        assertThat(effectiveDevicesMax(tenantId)).as("有效 = 基础 3 + 调整 2").isEqualTo(5);
        Map<String, Object> row = adjustmentRow(result.adjustmentId());
        assertThat(row.get("source")).isEqualTo("OPERATION_ADJUSTMENT");
        assertThat(row.get("source_order_id")).as("人工调整没有订单，不得伪造来源订单").isNull();
        assertThat(row.get("adjustment_reason")).isEqualTo(REASON);
        assertThat(row.get("adjustment_operator_id")).isEqualTo(session.accountId());
        assertThat(row.get("adjustment_key")).isEqualTo("inc-device-0001");
        assertThat(row.get("unit")).isEqualTo("COUNT");
        assertThat(row.get("window_kind")).isEqualTo("NONE");

        Long admissionLimit = jdbcTemplate.queryForObject(
                "SELECT device_limit_value FROM device_project_quota_policy(?, ?)",
                Long.class, tenantId, projectId);
        assertThat(admissionLimit)
                .as("设备数据面强制面读到的上限必须与租户有效权益一致")
                .isEqualTo(5);

        for (int index = 1; index <= 5; index++) {
            MvcResult device = createDevice(session.accessToken(), projectId, "s144b_device_" + index);
            assertThat(device.getResponse().getStatus()).as("第 %d 台设备应被调整后的 5 台上限允许", index)
                    .isEqualTo(201);
        }
        MvcResult sixth = createDevice(session.accessToken(), projectId, "s144b_device_6");
        assertThat(sixth.getResponse().getStatus()).as("合成上限之外仍被既有设备配额拒绝").isEqualTo(429);
        assertThat(JSON.readTree(sixth.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(30035);

        JsonNode details = latestAuditDetails(tenantId, "commercial.adjustment.granted");
        assertThat(details.get("reason").asString()).isEqualTo(REASON);
        assertThat(details.get("operatorId").asString()).isEqualTo(session.accountId().toString());
        assertThat(details.get("idempotencyKey").asString()).isEqualTo("inc-device-0001");
        assertThat(details.get("source").asString()).isEqualTo("OPERATION_ADJUSTMENT");
        assertThat(details.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(details.get("dimensionCode").asString()).isEqualTo("DEVICES_MAX");
        assertThat(details.get("amount").asLong()).isEqualTo(2L);
    }

    /** 同一幂等键与同一参数重放恰好一行、一次审计，不翻倍；换参数复用同一键显式冲突。 */
    @Test
    void replayedKeyWritesOneRowOneAuditAndDifferentParametersConflict() {
        UUID tenantId = createFreeTenant("S14-4b 幂等租户");
        Instant now = Instant.now();
        EntitlementAdjustmentRequest request = request("DEVICES_MAX", 2, now,
                now.plus(30, ChronoUnit.DAYS), "inc-idem-0001", OPERATOR);

        EntitlementAdjustmentResult first = tenantEntitlementAdjustmentService
                .createAdjustment(tenantId, request);
        EntitlementAdjustmentResult replay = tenantEntitlementAdjustmentService
                .createAdjustment(tenantId, request);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).as("同键同参重放返回既有事实").isFalse();
        assertThat(replay.adjustmentId()).isEqualTo(first.adjustmentId());
        assertThat(adjustmentCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.adjustment.granted")).isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).as("重放不得把 2 变成 4").isEqualTo(5);

        BusinessException conflict = catchThrowableOfType(
                () -> tenantEntitlementAdjustmentService.createAdjustment(tenantId,
                        request("DEVICES_MAX", 3, now, now.plus(30, ChronoUnit.DAYS),
                                "inc-idem-0001", OPERATOR)),
                BusinessException.class);
        assertThat(conflict).isNotNull();
        assertThat(conflict.errorCode()).isEqualTo(ProjectErrorCode.ADJUSTMENT_KEY_CONFLICT);
        assertThat(conflict.errorCode().code()).isEqualTo(50041);
        assertThat(adjustmentCount(tenantId)).as("拒绝不得留下第二行").isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);
    }

    /** 并发同键提交恰好落一行、一次审计；失败方拿到可重试的冲突码。 */
    @Test
    void concurrentSubmissionsWithTheSameKeyProduceExactlyOneAdjustment() throws Exception {
        UUID tenantId = createFreeTenant("S14-4b 并发租户");
        Instant now = Instant.now();
        EntitlementAdjustmentRequest request = request("DEVICES_MAX", 2, now,
                now.plus(30, ChronoUnit.DAYS), "inc-race-0001", OPERATOR);

        List<Object> outcomes = runConcurrently(List.of(
                () -> tenantEntitlementAdjustmentService.createAdjustment(tenantId, request),
                () -> tenantEntitlementAdjustmentService.createAdjustment(tenantId, request)));

        long created = outcomes.stream().filter(EntitlementAdjustmentResult.class::isInstance)
                .map(EntitlementAdjustmentResult.class::cast)
                .filter(EntitlementAdjustmentResult::created).count();
        assertThat(created).as("并发同键恰好一次真正落库").isEqualTo(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .map(BusinessException::errorCode))
                .as("失败方只能是同键冲突；重放路径则返回既有事实而不是异常")
                .allMatch(code -> code == ProjectErrorCode.ADJUSTMENT_KEY_CONFLICT);
        assertThat(adjustmentCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.adjustment.granted")).isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);
    }

    /** 有效期语义：未来窗口不提前参与当前合成，但在窗口内按同一规则求和；到期推进幂等。 */
    @Test
    void futureWindowAppliesOnlyInsideItsOwnPeriodAndExpiryPassIsIdempotent() {
        UUID tenantId = createFreeTenant("S14-4b 窗口租户");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant startsAt = now.plus(24, ChronoUnit.HOURS);
        Instant endsAt = startsAt.plus(30, ChronoUnit.DAYS);
        EntitlementAdjustmentResult result = tenantEntitlementAdjustmentService.createAdjustment(tenantId,
                request("DEVICES_MAX", 2, startsAt, endsAt, "inc-window-0001", OPERATOR));

        assertThat(result.status()).as("订阅有效时调整落 ACTIVE，但窗口未到不参与当前合成")
                .isEqualTo(ResourcePackageStatus.ACTIVE);
        assertThat(effectiveDevicesMax(tenantId)).as("未来窗口现在不提升上限").isEqualTo(3);
        assertThat(tenantResourcePackageRepository.findActiveAdditions(tenantId, now)).isEmpty();
        assertThat(tenantResourcePackageRepository.findActiveAdditions(tenantId,
                startsAt.plusSeconds(1)))
                .as("窗口内同一求和规则给出加数 2")
                .singleElement()
                .satisfies(addition -> {
                    assertThat(addition.dimensionCode()).isEqualTo("DEVICES_MAX");
                    assertThat(addition.amount()).isEqualTo(2);
                });

        int expectedExpirations = tenantResourcePackageRepository
                .findDueForExpiry(endsAt.plusSeconds(1), 500).size();
        var expiryPass = tenantResourcePackageService.advance(endsAt.plusSeconds(1));
        assertThat(expiryPass.expired()).isEqualTo(expectedExpirations);
        assertThat(packageStatus(result.adjustmentId())).isEqualTo("EXPIRED");
        assertThat(auditCount(tenantId, "commercial.package.expired")).isEqualTo(1);

        var replay = tenantResourcePackageService.advance(endsAt.plusSeconds(1));
        assertThat(replay.expired()).isZero();
        assertThat(replay.tenantsInvalidated()).isZero();
        assertThat(auditCount(tenantId, "commercial.package.expired"))
                .as("重跑同一时刻不得重复写审计")
                .isEqualTo(1);
        assertThat(effectiveDevicesMax(tenantId)).as("到期后回到基础档").isEqualTo(3);
    }

    /** 订阅受限时调整落 PENDING 且不提升上限；订阅恢复后由既有推进激活。 */
    @Test
    void adjustmentWhileSubscriptionRestrictedStaysPendingThenActivatesAfterRecovery() {
        UUID tenantId = createFreeTenant("S14-4b 待生效租户");
        // 直接构造受限订阅夹具（P4 的 RESTRICTED_FREE 切换已由 S14-3c 验收，这里只需要那个状态）。
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET status = 'RESTRICTED_FREE', grace_ends_at = now(), restricted_at = now()
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, tenantId);
        Instant now = Instant.now();

        EntitlementAdjustmentResult result = tenantEntitlementAdjustmentService.createAdjustment(tenantId,
                request("DEVICES_MAX", 2, now, now.plus(30, ChronoUnit.DAYS), "inc-pending-0001", OPERATOR));

        assertThat(result.status()).as("订阅受限时调整必须待生效而不是立即生效")
                .isEqualTo(ResourcePackageStatus.PENDING);
        assertThat(effectiveDevicesMax(tenantId)).as("PENDING 调整不提升上限").isEqualTo(3);

        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription
                   SET status = 'ACTIVE', grace_ends_at = NULL, restricted_at = NULL
                 WHERE tenant_id = ?
                """, tenantId);
        var report = tenantResourcePackageService.advance(Instant.now());

        assertThat(report.activatedPending()).isEqualTo(1);
        assertThat(packageStatus(result.adjustmentId())).isEqualTo("ACTIVE");
        assertThat(effectiveDevicesMax(tenantId)).as("订阅恢复后调整参与合成").isEqualTo(5);
        assertThat(auditCount(tenantId, "commercial.package.activated")).isEqualTo(1);
    }

    /** 撤销立即停止贡献并留审计；重复撤销是 no-op；购买包与已到期调整不可撤销。 */
    @Test
    void revokeStopsContributionImmediatelyAndRefusesNonAdjustmentsAndExpiredRows() {
        UUID tenantId = createFreeTenant("S14-4b 撤销租户");
        Instant now = Instant.now();
        EntitlementAdjustmentResult active = tenantEntitlementAdjustmentService.createAdjustment(tenantId,
                request("DEVICES_MAX", 2, now, now.plus(30, ChronoUnit.DAYS), "inc-revoke-0001", OPERATOR));
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(5);

        boolean revoked = tenantEntitlementAdjustmentService.revokeAdjustment(tenantId,
                active.adjustmentId(), OPERATOR, "工单撤销：补偿发放对象错误");

        assertThat(revoked).isTrue();
        assertThat(packageStatus(active.adjustmentId())).isEqualTo("CANCELLED");
        assertThat(effectiveDevicesMax(tenantId)).as("撤销后立刻回到基础档 3").isEqualTo(3);
        assertThat(auditCount(tenantId, "commercial.adjustment.revoked")).isEqualTo(1);
        JsonNode details = latestAuditDetails(tenantId, "commercial.adjustment.revoked");
        assertThat(details.get("previousStatus").asString()).isEqualTo("ACTIVE");
        assertThat(details.get("grantReason").asString()).isEqualTo(REASON);

        assertThat(tenantEntitlementAdjustmentService.revokeAdjustment(tenantId,
                active.adjustmentId(), OPERATOR, "重复撤销"))
                .as("重复撤销是幂等 no-op").isFalse();
        assertThat(auditCount(tenantId, "commercial.adjustment.revoked"))
                .as("no-op 不得重复写审计").isEqualTo(1);

        // 已到期的调整不可撤销：到期是时间事实，改写状态只会篡改历史。
        EntitlementAdjustmentResult expiring = tenantEntitlementAdjustmentService.createAdjustment(tenantId,
                request("DEVICES_MAX", 1, now, now.plus(1, ChronoUnit.MINUTES), "inc-revoke-0002", OPERATOR));
        tenantResourcePackageService.advance(now.plus(2, ChronoUnit.MINUTES));
        BusinessException notRevocable = catchThrowableOfType(
                () -> tenantEntitlementAdjustmentService.revokeAdjustment(tenantId,
                        expiring.adjustmentId(), OPERATOR, "太晚了"),
                BusinessException.class);
        assertThat(notRevocable).isNotNull();
        assertThat(notRevocable.errorCode()).isEqualTo(ProjectErrorCode.ADJUSTMENT_NOT_REVOCABLE);
        assertThat(notRevocable.errorCode().code()).isEqualTo(50038);

        // 购买包必须走退款（S14-5），不能借调整撤销把「收了钱、权益没了、账上没退」写进台账。
        UUID purchaseId = buyDevicePackage(tenantId, 2, "sim-pkg-4b-revoke-1");
        BusinessException notAdjustment = catchThrowableOfType(
                () -> tenantEntitlementAdjustmentService.revokeAdjustment(tenantId, purchaseId,
                        OPERATOR, "误退"),
                BusinessException.class);
        assertThat(notAdjustment).isNotNull();
        assertThat(notAdjustment.errorCode()).isEqualTo(ProjectErrorCode.PACKAGE_NOT_ADJUSTMENT);
        assertThat(notAdjustment.errorCode().code()).isEqualTo(50036);
        assertThat(packageStatus(purchaseId)).as("购买包状态不得被撤销入口改动").isEqualTo("ACTIVE");
    }

    /** 跨租户隔离：A 的调整不影响 B，且不能用 A 的上下文撤销 B 的调整。 */
    @Test
    void adjustmentsAreIsolatedPerTenantAndRevokeCannotCrossTenants() {
        UUID tenantA = createFreeTenant("S14-4b 隔离租户 A");
        UUID tenantB = createFreeTenant("S14-4b 隔离租户 B");
        Instant now = Instant.now();

        EntitlementAdjustmentResult onB = tenantEntitlementAdjustmentService.createAdjustment(tenantB,
                request("DEVICES_MAX", 5, now, now.plus(30, ChronoUnit.DAYS), "inc-isolation-b", OPERATOR));

        assertThat(effectiveDevicesMax(tenantA)).as("A 的有效上限不受 B 的调整影响").isEqualTo(3);
        assertThat(effectiveDevicesMax(tenantB)).isEqualTo(8);

        BusinessException crossTenant = catchThrowableOfType(
                () -> tenantEntitlementAdjustmentService.revokeAdjustment(tenantA, onB.adjustmentId(),
                        OPERATOR, "跨租户探测"),
                BusinessException.class);
        assertThat(crossTenant).isNotNull();
        assertThat(crossTenant.errorCode()).isEqualTo(ProjectErrorCode.ADJUSTMENT_NOT_FOUND);
        assertThat(crossTenant.errorCode().code()).isEqualTo(50037);
        assertThat(packageStatus(onB.adjustmentId())).as("B 的调整不得被 A 撤销").isEqualTo("ACTIVE");
        assertThat(effectiveDevicesMax(tenantA)).isEqualTo(3);
        assertThat(effectiveDevicesMax(tenantB)).isEqualTo(8);
    }

    /** 人工调整只改租户事实：共享配额模板逐列与版本号在调整前后完全相同。 */
    @Test
    void adjustmentNeverMutatesSharedQuotaTemplates() {
        UUID tenantId = createFreeTenant("S14-4b 模板不可变租户");
        List<Map<String, Object>> before = planTemplateSnapshot();
        int templateCountBefore = before.size();
        assertThat(templateCountBefore).as("四档配额模板必须已物化").isEqualTo(4);

        Instant now = Instant.now();
        EntitlementAdjustmentResult granted = tenantEntitlementAdjustmentService.createAdjustment(tenantId,
                request("DEVICES_MAX", 7, now, now.plus(30, ChronoUnit.DAYS), "inc-template-0001", OPERATOR));
        tenantEntitlementAdjustmentService.revokeAdjustment(tenantId, granted.adjustmentId(), OPERATOR,
                "工单撤销");

        assertThat(planTemplateSnapshot())
                .as("调整与撤销都不得改动任何共享模板行（含 version 与全部冻结额度列）")
                .isEqualTo(before);
        assertThat(effectiveDevicesMax(tenantId)).as("撤销后有效值回到基础档").isEqualTo(3);
    }

    /** 拒绝面：维度、额度、窗口、元数据、租户，以及数据库端的同生共死约束。 */
    @Test
    void refusesInvalidDimensionAmountPeriodMetadataTenantAndHalfFormedRows() {
        UUID tenantId = createFreeTenant("S14-4b 拒绝租户");
        Instant now = Instant.now();

        assertThat(adjustmentError(tenantId, request("NOT_A_DIMENSION", 1, now,
                now.plus(1, ChronoUnit.DAYS), "inc-reject-0001", OPERATOR)))
                .as("未知维度").isEqualTo(ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED);
        assertThat(adjustmentError(tenantId, request("HISTORY_WINDOW", 1, now,
                now.plus(1, ChronoUnit.DAYS), "inc-reject-0002", OPERATOR)))
                .as("历史窗口的单位随档位变化，无可信读面").isEqualTo(
                        ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED);
        assertThat(adjustmentError(tenantId, request("DEVICES_MAX", 0, now,
                now.plus(1, ChronoUnit.DAYS), "inc-reject-0003", OPERATOR)))
                .as("额度必须为正").isEqualTo(com.things.link.shared.error.CommonErrorCode.INVALID_PARAMETER);
        assertThat(adjustmentError(tenantId, request("DEVICES_MAX", 1, now,
                now.minus(1, ChronoUnit.DAYS), "inc-reject-0004", OPERATOR)))
                .as("终点不得早于起点").isEqualTo(ProjectErrorCode.ADJUSTMENT_PERIOD_INVALID);
        assertThat(adjustmentError(tenantId, request("DEVICES_MAX", 1,
                now.minus(10, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), "inc-reject-0005", OPERATOR)))
                .as("终点已过去").isEqualTo(ProjectErrorCode.ADJUSTMENT_PERIOD_INVALID);
        assertThat(adjustmentError(tenantId, request("DEVICES_MAX", 1, now,
                now.plus(800, ChronoUnit.DAYS), "inc-reject-0006", OPERATOR)))
                .as("超过 24 个 UTC 日历月").isEqualTo(ProjectErrorCode.ADJUSTMENT_PERIOD_INVALID);
        assertThat(adjustmentError(tenantId, new EntitlementAdjustmentRequest("DEVICES_MAX", 1, now,
                now.plus(1, ChronoUnit.DAYS), null, OPERATOR, "inc-reject-0007")))
                .as("缺少原因").isEqualTo(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED);
        assertThat(adjustmentError(tenantId, new EntitlementAdjustmentRequest("DEVICES_MAX", 1, now,
                now.plus(1, ChronoUnit.DAYS), "   ", OPERATOR, "inc-reject-0008")))
                .as("原因不得是空白串").isEqualTo(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED);
        assertThat(adjustmentError(tenantId, new EntitlementAdjustmentRequest("DEVICES_MAX", 1, now,
                now.plus(1, ChronoUnit.DAYS), REASON, null, "inc-reject-0009")))
                .as("缺少操作人").isEqualTo(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED);
        assertThat(adjustmentError(tenantId, request("DEVICES_MAX", 1, now,
                now.plus(1, ChronoUnit.DAYS), "short", OPERATOR)))
                .as("幂等键过短").isEqualTo(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED);
        assertThat(adjustmentError(UUID.randomUUID(), request("DEVICES_MAX", 1, now,
                now.plus(1, ChronoUnit.DAYS), "inc-reject-0010", OPERATOR)))
                .as("租户不存在").isEqualTo(com.things.link.shared.error.CommonErrorCode.RESOURCE_NOT_FOUND);

        assertThat(adjustmentCount(tenantId)).as("全部拒绝都不得留下任何调整行").isZero();
        assertThat(effectiveDevicesMax(tenantId)).isEqualTo(3);

        // 数据库是最终仲裁者：绕过服务直插一条「要么有订单、要么带元数据」的半真半假行必须被拒。
        DataIntegrityViolationException halfFormed = catchThrowableOfType(
                () -> jdbcTemplate.update("""
                        INSERT INTO sys_tenant_resource_package (
                            id, tenant_id, dimension_code, amount, unit, window_kind, starts_at, ends_at,
                            source, status, revision, created_at, updated_at)
                        VALUES (?, ?, 'DEVICES_MAX', 2, 'COUNT', 'NONE', now(), now() + interval '30 days',
                                'OPERATION_ADJUSTMENT', 'ACTIVE', 1, now(), now())
                        """, UUID.randomUUID(), tenantId),
                DataIntegrityViolationException.class);
        assertThat(halfFormed).isNotNull();
        assertThat(halfFormed.getMessage()).as("必须由同生共死 CHECK 拒绝")
                .contains("sys_tenant_resource_package_adjustment_ck");
        assertThat(packageCount(tenantId)).isZero();
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助
    // ------------------------------------------------------------------

    /**
     * 构造一条人工调整请求。
     *
     * @param dimension 目标维度编码
     * @param amount 额度
     * @param startsAt 起点
     * @param endsAt 终点
     * @param key 幂等键
     * @param operator 操作人
     * @return 提交请求
     */
    private EntitlementAdjustmentRequest request(String dimension, long amount, Instant startsAt,
                                                 Instant endsAt, String key, UUID operator) {
        return new EntitlementAdjustmentRequest(dimension, amount, startsAt, endsAt, REASON, operator, key);
    }

    /**
     * 执行一次调整请求并返回业务错误码。
     *
     * @param tenantId 租户 ID
     * @param request 提交请求
     * @return 抛出的业务错误码
     */
    private com.things.link.shared.error.ErrorCode adjustmentError(
            UUID tenantId, EntitlementAdjustmentRequest request) {
        BusinessException exception = catchThrowableOfType(
                () -> tenantEntitlementAdjustmentService.createAdjustment(tenantId, request),
                BusinessException.class);
        assertThat(exception).as("该请求必须被拒绝").isNotNull();
        return exception.errorCode();
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
     * 走 S14-4a 的真实模拟购买路径买一个设备包，用于验证撤销入口只接受人工调整。
     *
     * @param tenantId 租户 ID
     * @param amount 包额度
     * @param eventId 模拟支付事件 ID
     * @return 包 ID
     */
    private UUID buyDevicePackage(UUID tenantId, long amount, String eventId) {
        com.things.link.project.domain.TenantOrder order = tenantResourcePackageService
                .createSimulatedPackageOrder(tenantId, "DEVICES_MAX", amount, null);
        return tenantResourcePackageService
                .applySimulatedPackagePaymentSucceeded(order.id(), eventId).packageId();
    }

    /**
     * 读取调整行的关键列。
     *
     * @param adjustmentId 调整 ID
     * @return 列名到值的映射
     */
    private Map<String, Object> adjustmentRow(UUID adjustmentId) {
        return jdbcTemplate.queryForMap("""
                SELECT source, source_order_id, adjustment_reason, adjustment_operator_id, adjustment_key,
                       status, dimension_code, amount, unit, window_kind
                  FROM sys_tenant_resource_package WHERE id = ?
                """, adjustmentId);
    }

    /**
     * 读取最近一条指定动作的审计详情。
     *
     * @param tenantId 租户 ID
     * @param action 动作编码
     * @return 结构化详情
     */
    private JsonNode latestAuditDetails(UUID tenantId, String action) {
        String details = jdbcTemplate.queryForObject("""
                SELECT details::text FROM sys_audit_log
                 WHERE tenant_id = ? AND action = ?
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, String.class, tenantId, action);
        return JSON.readTree(details);
    }

    /**
     * 读取共享配额模板快照（仅 {@code plan_template = true} 的冻结行）。
     *
     * @return 逐行逐列的模板事实
     */
    private List<Map<String, Object>> planTemplateSnapshot() {
        return jdbcTemplate.queryForList("""
                SELECT q.code, q.version, q.projects_max, q.device_count_limit, q.end_users_max,
                       q.dashboards_max, q.external_collaborator_seats, q.history_window_unit,
                       q.history_window_amount, q.uplink_message_daily_limit,
                       q.downlink_message_daily_limit, q.rest_api_call_daily_limit,
                       q.rest_api_rate_per_minute, q.websocket_connection_limit,
                       q.script_execution_daily_limit, q.script_cpu_millis_daily_limit,
                       q.notification_delivery_daily_limit, q.storage_bytes_limit
                  FROM sys_quota_policy q
                 WHERE q.plan_template
                 ORDER BY q.code
                """);
    }

    /** @param packageId 包 ID @return 包状态 */
    private String packageStatus(UUID packageId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_resource_package WHERE id = ?", String.class, packageId);
    }

    /** @param tenantId 租户 ID @return 该租户的人工调整行数 */
    private int adjustmentCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_package
                 WHERE tenant_id = ? AND source = 'OPERATION_ADJUSTMENT'
                """, Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户全部资源包行数（含购买包） */
    private int packageCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_resource_package WHERE tenant_id = ?",
                Integer.class, tenantId);
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
     * 并发执行一组调用，返回结果或业务异常。
     *
     * @param calls 待并发执行的调用
     * @return 与调用顺序对应的结果列表
     * @throws Exception 线程池等待被中断时直接失败
     */
    private List<Object> runConcurrently(List<java.util.concurrent.Callable<Object>> calls) throws Exception {
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
                .get("accessToken").asString(), refresh, tenantId, accountId);
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
                .get("accessToken").asString(), session.refreshToken(), session.tenantId(),
                session.accountId());
    }

    /** 走既有设备创建入口建一台无类型设备。 */
    private MvcResult createDevice(String token, UUID projectId, String deviceKey) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"%s\",\"name\":\"S14-4b 设备\"}".formatted(deviceKey)))
                .andReturn();
    }

    /** 控制台会话的最小凭据。 */
    private record Session(String accessToken, String refreshToken, UUID tenantId, UUID accountId) {
    }
}
