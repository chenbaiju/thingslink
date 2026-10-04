package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantSubscriptionChangeService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionPendingChange;
import com.things.link.project.domain.SubscriptionPendingChangeStatus;
import com.things.link.project.domain.TenantOrder;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
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
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S14-3b 预约降级的真实 PostgreSQL 验收（架构文档 §3.2，S14-0 P5）。
 *
 * <p>本类钉住六件事：① 预约降级把「下周期生效的目标」持久化成 PENDING，生效时刻恰好等于当前
 * ACTIVE 订阅的服务期终点，且**完全不触碰**订阅状态机（ACTIVE 仍是 ACTIVE、绑定版本不变、
 * 不新增订阅行）；② 预约可被列出；③ 周期结束前可撤销，重复撤销是 no-op 且不重复审计；
 * ④ 同目标重复预约是幂等重放（一行、一次审计）；⑤ 已有目标不同的 PENDING 时显式冲突
 * （50029），不静默替换；⑥ 拒绝面用已登记错误码：不是降级 50027、无生效订阅 50028、
 * 无服务期终点 50030。<b>真正在周期末套用属 S14-3c，本片不实现时间驱动切换。</b>
 *
 * <p>不使用方法级 {@code @Transactional}：需要看到其他连接已提交的事实。清理按外键依赖顺序，
 * 探针档位（无维度/权益行，可删除）也在清理范围内；{@code sys_audit_log} 刻意不清理。
 */
@DisplayName("S14-3b 预约降级与撤销")
class TenantSubscriptionDowngradeIntegrationTests extends AbstractIntegrationTest {

    /** 服务期起点。 */
    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    /** 服务期终点：预约降级的生效时刻必须等于它。 */
    private static final Instant PERIOD_END = Instant.parse("2027-01-01T00:00:00Z");
    /** 审计详情（jsonb）解析器；PG 的 jsonb 文本带空格，直接断言字符串不可靠。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 预约、订阅、租户与审计事实的核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 生产的模拟订单服务；用于把租户买成付费档。 */
    @Autowired
    private TenantOrderService tenantOrderService;
    /** 被测的预约降级服务。 */
    @Autowired
    private TenantSubscriptionChangeService tenantSubscriptionChangeService;
    /** 为 {@code MANDATORY} 的租户创建提供外层业务事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户。 */
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

    /** 预约 → 列出 → 撤销的完整闭环，且全程不触碰订阅状态机。 */
    @Test
    void schedulesListsAndCancelsDowngradeWithoutTouchingLifecycle() {
        UUID tenantId = createPaidStandardTenant("S14-3b 预约降级租户");
        UUID activeSubscriptionId = activeSubscriptionId(tenantId);
        long versionBefore = assignmentVersion(tenantId);
        int subscriptionsBefore = subscriptionCount(tenantId);

        SubscriptionPendingChange scheduled =
                tenantSubscriptionChangeService.requestDowngrade(tenantId, revisionId("FREE"));

        assertThat(scheduled.status()).isEqualTo(SubscriptionPendingChangeStatus.PENDING);
        assertThat(scheduled.subscriptionId()).isEqualTo(activeSubscriptionId);
        assertThat(scheduled.fromPlanRevisionId()).isEqualTo(revisionId("STANDARD"));
        assertThat(scheduled.targetPlanRevisionId()).isEqualTo(revisionId("FREE"));
        assertThat(scheduled.effectiveAt())
                .as("降级在下个周期生效，生效时刻就是当前服务期终点")
                .isEqualTo(PERIOD_END);

        assertThat(tenantSubscriptionChangeService.findPendingChange(tenantId))
                .as("预约必须可被列出")
                .contains(scheduled);
        assertThat(pendingRowCount(tenantId)).isEqualTo(1);
        Map<String, Object> row = pendingRow(tenantId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("cancelled_at")).isNull();
        assertThat(row.get("applied_at")).isNull();
        assertThat(((Timestamp) row.get("effective_at")).toInstant()).isEqualTo(PERIOD_END);

        assertThat(assignmentVersion(tenantId)).as("预约不推进策略绑定版本").isEqualTo(versionBefore);
        assertThat(subscriptionCount(tenantId)).as("预约不新增订阅行").isEqualTo(subscriptionsBefore);
        assertThat(activeSubscriptionCount(tenantId)).isEqualTo(1);
        assertThat(activeEndsAt(tenantId)).isEqualTo(PERIOD_END);
        assertThat(activePlanCode(tenantId)).isEqualTo("STANDARD");
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.scheduled")).isEqualTo(1);

        assertThat(tenantSubscriptionChangeService.cancelDowngrade(tenantId)).isTrue();

        assertThat(tenantSubscriptionChangeService.findPendingChange(tenantId)).isEmpty();
        Map<String, Object> cancelled = pendingRow(tenantId);
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelled_at")).isNotNull();
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.cancelled")).isEqualTo(1);
        JsonNode cancelledAudit =
                latestAuditJson(tenantId, "commercial.subscription.downgrade.cancelled");
        assertThat(cancelledAudit.get("reason").asString()).isEqualTo("TENANT_REQUEST");
        assertThat(activePlanCode(tenantId)).as("撤销不得改动当前订阅").isEqualTo("STANDARD");
        assertThat(assignmentVersion(tenantId)).isEqualTo(versionBefore);

        assertThat(tenantSubscriptionChangeService.cancelDowngrade(tenantId))
                .as("重复撤销是 no-op")
                .isFalse();
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.cancelled"))
                .as("no-op 不得重复写审计")
                .isEqualTo(1);
    }

    /** 同目标重复预约是幂等重放：一行、一次审计、同一个 ID。 */
    @Test
    void replayingSameTargetDowngradeIsIdempotent() {
        UUID tenantId = createPaidStandardTenant("S14-3b 幂等预约租户");

        SubscriptionPendingChange first =
                tenantSubscriptionChangeService.requestDowngrade(tenantId, revisionId("FREE"));
        SubscriptionPendingChange replay =
                tenantSubscriptionChangeService.requestDowngrade(tenantId, revisionId("FREE"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(pendingRowCount(tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.scheduled")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.cancelled")).isZero();
    }

    /** 已有目标不同的 PENDING：显式冲突（50029），既有预约原样保留。 */
    @Test
    void refusesDifferentTargetWhileAnotherPending() {
        UUID tenantId = createPaidStandardTenant("S14-3b 冲突预约租户");
        SubscriptionPendingChange existing =
                tenantSubscriptionChangeService.requestDowngrade(tenantId, revisionId("FREE"));
        UUID lowerProbeRevisionId = insertProbeRevision("S14-3b 中间档探针", 15, 198000L);

        BusinessException refusal = catchThrowableOfType(
                () -> tenantSubscriptionChangeService.requestDowngrade(tenantId, lowerProbeRevisionId),
                BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.PENDING_CHANGE_CONFLICT);
        assertThat(refusal.errorCode().code()).isEqualTo(50029);
        assertThat(pendingRowCount(tenantId)).isEqualTo(1);
        assertThat(tenantSubscriptionChangeService.findPendingChange(tenantId))
                .as("冲突不得替换既有预约")
                .contains(existing);
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.scheduled")).isEqualTo(1);
    }

    /** 拒绝面：不是降级、无生效订阅、长期 FREE 无服务期终点，各有登记错误码，且不留下任何预约行。 */
    @Test
    void refusesDowngradesWithRegisteredCodes() {
        UUID tenantId = createPaidStandardTenant("S14-3b 降级拒绝租户");

        BusinessException notDowngrade = catchThrowableOfType(
                () -> tenantSubscriptionChangeService.requestDowngrade(tenantId, revisionId("ENTERPRISE")),
                BusinessException.class);
        assertThat(notDowngrade).isNotNull();
        assertThat(notDowngrade.errorCode()).isEqualTo(ProjectErrorCode.NOT_A_DOWNGRADE);
        assertThat(notDowngrade.errorCode().code()).isEqualTo(50027);

        jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
        BusinessException noActive = catchThrowableOfType(
                () -> tenantSubscriptionChangeService.requestDowngrade(tenantId, revisionId("FREE")),
                BusinessException.class);
        assertThat(noActive).isNotNull();
        assertThat(noActive.errorCode()).isEqualTo(ProjectErrorCode.NO_ACTIVE_SUBSCRIPTION);
        assertThat(noActive.errorCode().code()).isEqualTo(50028);

        UUID freeTenantId = createFreeTenant("S14-3b 长期免费降级租户");
        UUID belowFreeRevisionId = insertProbeRevision("S14-3b 免费以下探针", 5, 0L);
        BusinessException unbounded = catchThrowableOfType(
                () -> tenantSubscriptionChangeService.requestDowngrade(freeTenantId, belowFreeRevisionId),
                BusinessException.class);
        assertThat(unbounded).isNotNull();
        assertThat(unbounded.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_PERIOD_UNBOUNDED);
        assertThat(unbounded.errorCode().code()).isEqualTo(50030);

        assertThat(pendingRowCount(tenantId)).isZero();
        assertThat(pendingRowCount(freeTenantId)).isZero();
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
     * 创建一个先购买 STANDARD、再把 ACTIVE 服务期改写成固定区间的租户。
     *
     * @param label 租户名
     * @return 租户 ID
     */
    private UUID createPaidStandardTenant(String label) {
        UUID tenantId = createFreeTenant(label);
        TenantOrder purchase = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(purchase.id(), "sim-purchase-" + tenantId);
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription SET starts_at = ?, ends_at = ?
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(PERIOD_START), Timestamp.from(PERIOD_END), tenantId);
        return tenantId;
    }

    /**
     * 插入一个仅用于档位方向用例的探针档位与其修订版（无维度/权益行，可删除）。
     *
     * @param codeSuffix 档位编码后缀说明，仅用于人工排查
     * @param displayOrder 档位展示顺序（决定高低）
     * @param referencePriceCents 参考年价（分）
     * @return 探针修订版 ID
     */
    private UUID insertProbeRevision(String codeSuffix, int displayOrder, long referencePriceCents) {
        UUID planId = Uuid7.generate();
        UUID revisionId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_plan (id, code, display_order, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, planId, "S14_3B_PROBE_" + planId.toString().substring(0, 8).toUpperCase(),
                displayOrder);
        jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (
                    id, plan_id, revision_code, revision_no, name, sale_status, billing_period,
                    currency, price_cents, reference_price_cents, reference_price_currency,
                    quota_policy_id, valid_from, valid_until)
                VALUES (?, ?, ?, 1, ?, 'NOT_FOR_SALE', 'YEAR',
                        'CNY', NULL, ?, 'CNY', ?, now(), NULL)
                """, revisionId, planId, "s14-3b-probe-" + revisionId, codeSuffix, referencePriceCents,
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

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅 ID */
    private UUID activeSubscriptionId(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, UUID.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅的档位编码 */
    private String activePlanCode(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT p.code FROM sys_tenant_subscription s
                  JOIN sys_plan_revision r ON r.id = s.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE s.tenant_id = ? AND s.status = 'ACTIVE'
                """, String.class, tenantId);
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

    /** @param tenantId 租户 ID @return 全部订阅行数（含历史） */
    private int subscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return PENDING 预约行数 */
    private int pendingRowCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription_pending_change
                 WHERE tenant_id = ? AND status = 'PENDING'
                """, Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户最新一条预约行 */
    private Map<String, Object> pendingRow(UUID tenantId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, effective_at, cancelled_at, applied_at
                  FROM sys_tenant_subscription_pending_change
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """, tenantId);
    }

    /** @param tenantId 租户 ID @return 运行时配额策略绑定版本 */
    private long assignmentVersion(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?",
                Long.class, tenantId);
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
}
