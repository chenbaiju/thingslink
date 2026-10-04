package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.SubscriptionLifecycleReport;
import com.things.link.project.application.SubscriptionLifecycleService;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantSubscriptionChangeService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionPendingChange;
import com.things.link.project.domain.TenantOrder;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S14-3c 到期宽限与受限切换的真实 PostgreSQL 验收（S14-0 P4 / P5）。
 *
 * <p>本类钉住七件事：① 到期日推进 ACTIVE → GRACE 并冻结 {@code graceEndsAt = expiresAt + 14 自然日}；
 * ② 宽限结束推进 GRACE → RESTRICTED_FREE，超额自有项目保留<b>最早创建</b>的 1 个可写、其余转商业只读
 * （status 仍 ACTIVE，不删除）；③ 同一时刻重跑 worker 后状态、行数、审计、通知意图全部不变；
 * ④ 永久 FREE（{@code ends_at IS NULL}）永不被时间推进触碰；⑤ 宽限期内新建项目被冻结码 50033 拒绝，
 * 而既有项目的写许可（设备连接/上行共用的生命周期门禁）仍然放行；⑥ 预约降级在周期末恰好套用一次并推进
 * 绑定版本，订阅已非 ACTIVE 时不套用且记录原因；⑦ P4 五个通知时间点各恰好一条意图 + 一条审计。
 *
 * <p>时间由 {@link SubscriptionLifecycleService#advance(Instant)} 显式驱动（生产入口用注入的 Clock），
 * 不用方法级 {@code @Transactional}：需要看到每次推进已提交的事实。清理按外键依赖顺序执行；
 * {@code sys_audit_log} 由数据库触发器钉成不可变，刻意不清理，按本用例独有的 tenant_id 过滤断言。
 */
@DisplayName("S14-3c 到期宽限与受限切换")
class TenantSubscriptionExpiryIntegrationTests extends AbstractIntegrationTest {

    /** 服务期起点。 */
    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    /** 服务期终点：也是预约降级的生效时刻。 */
    private static final Instant PERIOD_END = Instant.parse("2027-01-01T00:00:00Z");
    /** 宽限终点：P4 冻结为到期日 + 14 自然日。 */
    private static final Instant GRACE_END = Instant.parse("2027-01-15T00:00:00Z");
    /** 审计详情（jsonb）解析器；PG 文本格式带空格，直接断言字符串不可靠。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 订阅、项目、通知与审计事实的核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口；新租户带默认 FREE 订阅与 PLAN_R1_FREE 绑定。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 生产的模拟订单服务；把租户买成付费档并支持续费。 */
    @Autowired
    private TenantOrderService tenantOrderService;
    /** 生产的预约降级服务。 */
    @Autowired
    private TenantSubscriptionChangeService tenantSubscriptionChangeService;
    /** 被测的到期宽限与受限切换状态机。 */
    @Autowired
    private SubscriptionLifecycleService subscriptionLifecycleService;
    /** 项目创建入口；宽限期禁扩大的真实守卫点。 */
    @Autowired
    private ProjectService projectService;
    /** 项目写许可（设备连接/上行共用的生命周期门禁）。 */
    @Autowired
    private ProjectLifecycleAccessService projectLifecycleAccessService;
    /** 设备配额准入；宽限期禁新增设备的真实守卫点。 */
    @Autowired
    private ProjectQuotaService projectQuotaService;
    /** 为 MANDATORY 的租户创建与写许可用例提供外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 本用例创建的账号。 */
    private final Set<UUID> accountIds = new LinkedHashSet<>();
    /** 本用例创建的项目。 */
    private final Set<UUID> projectIds = new LinkedHashSet<>();

    /** 按外键依赖顺序回收共享容器夹具；审计表不可变，不清理。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update(
                        "DELETE FROM sys_tenant_subscription_notification_intent WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update(
                        "DELETE FROM sys_project_commercial_restriction WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update(
                        "DELETE FROM sys_tenant_subscription_pending_change WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
            }
            for (UUID projectId : projectIds) {
                jdbcTemplate.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
            }
            for (UUID accountId : accountIds) {
                jdbcTemplate.update("DELETE FROM sys_account WHERE id = ?", accountId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            tenantIds.clear();
            accountIds.clear();
            projectIds.clear();
            TenantContext.clear();
        }
    }

    /** 到期进入宽限 + 宽限结束受限 + 重跑不变：一个用例覆盖状态、超额矩阵与幂等。 */
    @Test
    void expiryEntersGraceAndGraceEndRestrictsOverLimitProjectsIdempotently() {
        UUID tenantId = createPaidTenant("S14-3c 到期受限租户");
        UUID earliest = insertOwnedProject(tenantId, "最早项目", PERIOD_START.minus(Duration.ofDays(3)));
        UUID second = insertOwnedProject(tenantId, "第二项目", PERIOD_START.minus(Duration.ofDays(2)));
        UUID third = insertOwnedProject(tenantId, "第三项目", PERIOD_START.minus(Duration.ofDays(1)));
        long versionBeforeGrace = assignmentVersion(tenantId);

        SubscriptionLifecycleReport atExpiry = subscriptionLifecycleService.advance(PERIOD_END);

        assertThat(atExpiry.graceEntered()).as("到期日必须把 ACTIVE 推进为 GRACE").isGreaterThanOrEqualTo(1);
        assertThat(subscriptionStatus(tenantId)).isEqualTo("GRACE");
        assertThat(graceEndsAt(tenantId)).as("graceEndsAt = expiresAt + 14 自然日").isEqualTo(GRACE_END);
        assertThat(restrictedAt(tenantId)).isNull();
        assertThat(assignmentVersion(tenantId)).as("进入宽限不换策略、不推进绑定版本").isEqualTo(versionBeforeGrace);
        assertThat(intentCount(tenantId)).as("到期日只到点「到期前 7 天」与「到期日」两点").isEqualTo(2);
        assertThat(intentCount(tenantId, "EXPIRY_MINUS_7_DAYS")).isEqualTo(1);
        assertThat(intentCount(tenantId, "EXPIRY_DAY")).isEqualTo(1);
        assertThat(projectStatus(second)).as("宽限期内还没有任何项目被转只读").isEqualTo("ACTIVE");

        SubscriptionLifecycleReport atGraceEnd = subscriptionLifecycleService.advance(GRACE_END);

        assertThat(atGraceEnd.restrictedFreeEntered()).isGreaterThanOrEqualTo(1);
        assertThat(atGraceEnd.projectsRestricted()).isGreaterThanOrEqualTo(2);
        assertThat(subscriptionStatus(tenantId)).isEqualTo("RESTRICTED_FREE");
        assertThat(restrictedAt(tenantId)).as("受限时刻就是本次推进时刻").isEqualTo(GRACE_END);
        assertThat(assignmentVersion(tenantId)).as("切换到 FREE 必须经 S7 推进一次绑定版本").isEqualTo(versionBeforeGrace + 1);
        assertThat(projectStatus(earliest)).as("最早创建的项目保留可写").isEqualTo("ACTIVE");
        assertThat(projectStatus(second)).as("其余项目转为既有 ARCHIVED 只读语义").isEqualTo("ARCHIVED");
        assertThat(projectStatus(third)).isEqualTo("ARCHIVED");
        assertThat(projectCount(tenantId)).as("三个项目一个都不能少").isEqualTo(3);
        assertThat(activeRestrictionCount(tenantId)).as("台账登记两条生效受限").isEqualTo(2);
        assertThat(intentCount(tenantId)).as("宽限结束补齐五个时间点").isEqualTo(5);
        assertThat(auditCount(tenantId, "commercial.subscription.grace.entered")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.subscription.restricted_free.entered")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.project.write_restricted")).isEqualTo(2);

        // 幂等：同一时刻重跑必须什么都不产生。
        SubscriptionLifecycleReport replay = subscriptionLifecycleService.advance(GRACE_END);

        assertThat(replay.graceEntered()).isZero();
        assertThat(replay.restrictedFreeEntered()).isZero();
        assertThat(replay.downgradesApplied()).isZero();
        assertThat(replay.notificationIntentsCreated()).isZero();
        assertThat(replay.projectsRestricted()).isZero();
        assertThat(subscriptionStatus(tenantId)).isEqualTo("RESTRICTED_FREE");
        assertThat(subscriptionCount(tenantId)).isEqualTo(2);
        assertThat(intentCount(tenantId)).isEqualTo(5);
        assertThat(projectStatus(earliest)).isEqualTo("ACTIVE");
        assertThat(projectStatus(second)).isEqualTo("ARCHIVED");
        assertThat(activeRestrictionCount(tenantId)).isEqualTo(2);
        assertThat(auditCount(tenantId, "commercial.subscription.grace.entered")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.subscription.restricted_free.entered")).isEqualTo(1);
        assertThat(auditCount(tenantId, "commercial.project.write_restricted")).isEqualTo(2);
        assertThat(auditCount(tenantId, "commercial.subscription.notification.intent")).isEqualTo(5);
    }

    /** 永久 FREE（ends_at IS NULL）不是「已到期」：时间推进必须原样跳过。 */
    @Test
    void perpetualFreeSubscriptionIsNeverTouched() {
        UUID tenantId = createFreeTenant("S14-3c 永久免费租户");
        long versionBefore = assignmentVersion(tenantId);

        SubscriptionLifecycleReport report = subscriptionLifecycleService.advance(Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(report.graceEntered()).isZero();
        assertThat(report.restrictedFreeEntered()).isZero();
        assertThat(report.notificationIntentsCreated()).isZero();
        assertThat(subscriptionStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(graceEndsAt(tenantId)).isNull();
        assertThat(restrictedAt(tenantId)).isNull();
        assertThat(subscriptionEndsAt(tenantId)).as("长期 FREE 的服务期终点必须保持 NULL").isNull();
        assertThat(intentCount(tenantId)).isZero();
        assertThat(assignmentVersion(tenantId)).isEqualTo(versionBefore);
    }

    /** 宽限期禁扩大：新建项目 50033、新建设备 50033，而既有项目的写许可（连接/上行门禁）仍放行。 */
    @Test
    void graceRefusesExpansionButKeepsExistingProjectWriteGateOpen() {
        UUID tenantId = createPaidTenant("S14-3c 宽限禁扩大租户");
        UUID accountId = createAccountAndMembership(tenantId, "s14-3c-grace");
        UUID projectId = createOwnedProject(tenantId, accountId, "宽限内既有项目");

        subscriptionLifecycleService.advance(PERIOD_END);
        assertThat(subscriptionStatus(tenantId)).isEqualTo("GRACE");

        TenantContext.set(new TenantScope(tenantId, null, accountId));
        BusinessException projectRefusal;
        try {
            projectRefusal = catchThrowableOfType(
                    () -> transactionTemplate.execute(status -> projectService.create("宽限新项目", "sh-1", null)),
                    BusinessException.class);
        } finally {
            TenantContext.clear();
        }
        assertThat(projectRefusal).isNotNull();
        assertThat(projectRefusal.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_GRACE_NO_EXPANSION);
        assertThat(projectRefusal.errorCode().code()).isEqualTo(50033);
        assertThat(projectRefusal.errorCode().httpStatus()).isEqualTo(409);
        assertThat(projectCount(tenantId)).as("被拒的新建不能留下项目行").isEqualTo(1);

        BusinessException deviceRefusal = catchThrowableOfType(
                () -> projectQuotaService.deviceQuotaStatus(tenantId, projectId), BusinessException.class);
        assertThat(deviceRefusal).isNotNull();
        assertThat(deviceRefusal.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_GRACE_NO_EXPANSION);

        // 设备连接与上行共用的项目写许可在宽限期内必须照常放行（不拒绝连接，避免重连风暴）。
        transactionTemplate.executeWithoutResult(status ->
                projectLifecycleAccessService.requireActiveForWrite(tenantId, projectId));
        assertThat(projectStatus(projectId)).as("宽限期内既有项目仍是 ACTIVE、仍可写").isEqualTo("ACTIVE");
    }

    /** 续费/升级恢复：重新生效后下一轮推进把商业受限项目恢复为可写（P4「OWNER 可续费恢复」）。 */
    @Test
    void activeSubscriptionRestoresCommerciallyRestrictedProjects() {
        UUID tenantId = createPaidTenant("S14-3c 续费恢复租户");
        UUID earliest = insertOwnedProject(tenantId, "恢复-最早", PERIOD_START.minus(Duration.ofDays(3)));
        UUID second = insertOwnedProject(tenantId, "恢复-第二", PERIOD_START.minus(Duration.ofDays(2)));
        UUID third = insertOwnedProject(tenantId, "恢复-第三", PERIOD_START.minus(Duration.ofDays(1)));

        subscriptionLifecycleService.advance(PERIOD_END);
        subscriptionLifecycleService.advance(GRACE_END);
        assertThat(projectStatus(second)).isEqualTo("ARCHIVED");
        assertThat(projectStatus(third)).isEqualTo("ARCHIVED");
        assertThat(activeRestrictionCount(tenantId)).isEqualTo(2);

        // 模拟支付成功续费：当前没有 ACTIVE 订阅（RESTRICTED_FREE），因此按首次购买生成新的 ACTIVE 服务期。
        TenantOrder renewal = tenantOrderService.createSimulatedOrder(tenantId, revisionId("STANDARD"));
        tenantOrderService.applySimulatedPaymentSucceeded(renewal.id(), "sim-renew-" + tenantId);
        assertThat(activeSubscriptionCount(tenantId)).isEqualTo(1);

        SubscriptionLifecycleReport resumed = subscriptionLifecycleService.advance(Instant.parse("2027-01-20T00:00:00Z"));

        assertThat(resumed.projectsRestored()).as("最早项目本就未受限，恢复的是其余两个").isEqualTo(2);
        assertThat(projectStatus(earliest)).isEqualTo("ACTIVE");
        assertThat(projectStatus(second)).isEqualTo("ACTIVE");
        assertThat(projectStatus(third)).isEqualTo("ACTIVE");
        assertThat(activeRestrictionCount(tenantId)).as("恢复后台账不再有生效受限").isZero();
        assertThat(auditCount(tenantId, "commercial.project.write_restored")).isEqualTo(2);
    }

    /** 预约降级：周期末恰好套用一次、推进绑定版本；订阅已非 ACTIVE 时不套用并记录原因。 */
    @Test
    void pendingDowngradeAppliesOnceAtPeriodEndAndSkipsNonActiveSubscription() {
        UUID tenantId = createPaidTenant("S14-3c 预约降级套用租户");
        long versionBefore = assignmentVersion(tenantId);
        SubscriptionPendingChange pending = tenantSubscriptionChangeService
                .requestDowngrade(tenantId, revisionId("FREE"));
        assertThat(pending.effectiveAt()).isEqualTo(PERIOD_END);

        SubscriptionLifecycleReport applied = subscriptionLifecycleService.advance(PERIOD_END);

        assertThat(applied.downgradesApplied()).isEqualTo(1);
        assertThat(pendingStatus(tenantId)).isEqualTo("APPLIED");
        assertThat(currentPlanCode(tenantId)).as("目标修订版必须在服务期终点生效").isEqualTo("FREE");
        assertThat(assignmentVersion(tenantId)).as("套用降级恰好推进一次绑定版本").isEqualTo(versionBefore + 1);
        assertThat(subscriptionStatus(tenantId)).as("服务期已结束，套用后同轮进入宽限").isEqualTo("GRACE");
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.applied")).isEqualTo(1);

        subscriptionLifecycleService.advance(PERIOD_END);
        assertThat(assignmentVersion(tenantId)).isEqualTo(versionBefore + 1);
        assertThat(auditCount(tenantId, "commercial.subscription.downgrade.applied")).isEqualTo(1);

        UUID staleTenant = createPaidTenant("S14-3c 预约失效租户");
        tenantSubscriptionChangeService.requestDowngrade(staleTenant, revisionId("FREE"));
        // 模拟订阅已被并发生效/人工终结：预约指向的订阅不再是 ACTIVE。
        jdbcTemplate.update("""
                UPDATE sys_tenant_subscription SET status = 'CANCELLED'
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, staleTenant);
        long staleVersion = assignmentVersion(staleTenant);

        SubscriptionLifecycleReport skipped = subscriptionLifecycleService.advance(PERIOD_END);

        assertThat(skipped.downgradesApplied()).isZero();
        assertThat(skipped.downgradesNotApplied()).isGreaterThanOrEqualTo(1);
        assertThat(pendingStatus(staleTenant)).isEqualTo("CANCELLED");
        assertThat(assignmentVersion(staleTenant)).isEqualTo(staleVersion);
        assertThat(auditCount(staleTenant, "commercial.subscription.downgrade.not_applied")).isEqualTo(1);
        JsonNode reason = latestAuditJson(staleTenant, "commercial.subscription.downgrade.not_applied");
        assertThat(reason.get("reason").asString()).isEqualTo("SUBSCRIPTION_NOT_ACTIVE");
    }

    /** 五个通知时间点在时间轴上逐点到齐，每点恰好一条意图 + 一条审计。 */
    @Test
    void fiveNotificationPointsExistExactlyOnceEach() {
        UUID tenantId = createPaidTenant("S14-3c 通知时间点租户");

        subscriptionLifecycleService.advance(PERIOD_END.minus(Duration.ofDays(7)));
        assertThat(intentCount(tenantId)).as("推进到到期前 7 天只落第 1 点").isEqualTo(1);
        assertThat(intentCount(tenantId, "EXPIRY_MINUS_7_DAYS")).isEqualTo(1);

        subscriptionLifecycleService.advance(PERIOD_END);
        assertThat(intentCount(tenantId)).isEqualTo(2);

        subscriptionLifecycleService.advance(PERIOD_END.plus(Duration.ofDays(7)));
        assertThat(intentCount(tenantId)).as("宽限第 7 天落第 3 点").isEqualTo(3);

        subscriptionLifecycleService.advance(GRACE_END.minus(Duration.ofDays(1)));
        assertThat(intentCount(tenantId)).as("宽限结束前 1 天落第 4 点").isEqualTo(4);

        subscriptionLifecycleService.advance(GRACE_END);
        assertThat(intentCount(tenantId)).as("切入受限后即时落第 5 点").isEqualTo(5);
        for (String kind : new String[]{"EXPIRY_MINUS_7_DAYS", "EXPIRY_DAY", "GRACE_DAY_7",
                "GRACE_END_MINUS_1_DAY", "RESTRICTED_SWITCH"}) {
            assertThat(intentCount(tenantId, kind)).as(kind + " 恰好一条意图").isEqualTo(1);
        }
        assertThat(auditCount(tenantId, "commercial.subscription.notification.intent")).isEqualTo(5);

        subscriptionLifecycleService.advance(GRACE_END);
        assertThat(intentCount(tenantId)).as("重跑不新增意图").isEqualTo(5);
        assertThat(auditCount(tenantId, "commercial.subscription.notification.intent")).isEqualTo(5);
    }

    /**
     * 走真实租户创建入口，拿到默认 FREE 订阅。
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
    private UUID createPaidTenant(String label) {
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
     * 建一个已验证账号并加入租户，供项目创建的成员关系使用。
     *
     * @param tenantId 归属租户
     * @param prefix 邮箱前缀
     * @return 账号 ID
     */
    private UUID createAccountAndMembership(UUID tenantId, String prefix) {
        UUID accountId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S14-3c 到期账号')
                """, accountId, prefix + "-" + accountId + "@example.com");
        jdbcTemplate.update("INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                Uuid7.generate(), tenantId, accountId);
        accountIds.add(accountId);
        return accountId;
    }

    /**
     * 在独立事务里以指定租户身份创建自有项目。
     *
     * @param tenantId 项目归属租户
     * @param accountId 创建者账号
     * @param name 项目名
     * @return 项目 ID
     */
    private UUID createOwnedProject(UUID tenantId, UUID accountId, String name) {
        TenantContext.set(new TenantScope(tenantId, null, accountId));
        try {
            var membership = transactionTemplate.execute(status -> projectService.create(name, "sh-1", null));
            assertThat(membership).isNotNull();
            projectIds.add(membership.project().id());
            return membership.project().id();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 直接落一条未软删自有项目，用显式 created_at 控制「最早创建」的顺序。
     *
     * @param tenantId 归属租户
     * @param name 项目名
     * @param createdAt 创建时刻
     * @return 项目 ID
     */
    private UUID insertOwnedProject(UUID tenantId, String name, Instant createdAt) {
        UUID projectId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key, status, created_at,
                                         updated_at, lifecycle_generation)
                VALUES (?, ?, ?, 'sh-1', ?, 'ACTIVE', ?, now(), 0)
                """, projectId, tenantId, name, "s143c" + projectId.toString().replace("-", ""),
                Timestamp.from(createdAt));
        projectIds.add(projectId);
        return projectId;
    }

    /** @param planCode 档位编码 @return {@code product-revision-1} 对应档位的修订版 ID */
    private UUID revisionId(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT r.id FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = 'product-revision-1'
                """, UUID.class, planCode);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE/GRACE/RESTRICTED_FREE 订阅的状态 */
    private String subscriptionStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status IN ('ACTIVE', 'GRACE', 'RESTRICTED_FREE')
                 ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'GRACE' THEN 1 ELSE 2 END,
                          starts_at DESC, created_at DESC
                 LIMIT 1
                """, String.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 当前订阅的宽限终点；没有 GRACE/RESTRICTED_FREE 行时为 null */
    private Instant graceEndsAt(UUID tenantId) {
        return jdbcTemplate.query("""
                        SELECT grace_ends_at FROM sys_tenant_subscription
                         WHERE tenant_id = ? AND status IN ('GRACE', 'RESTRICTED_FREE')
                         ORDER BY starts_at DESC, created_at DESC LIMIT 1
                        """, (resultSet, rowNumber) -> resultSet.getTimestamp("grace_ends_at"), tenantId)
                .stream().findFirst().map(value -> value == null ? null : value.toInstant()).orElse(null);
    }

    /** @param tenantId 租户 ID @return 当前订阅的受限时刻；没有 RESTRICTED_FREE 行时为 null */
    private Instant restrictedAt(UUID tenantId) {
        return jdbcTemplate.query("""
                        SELECT restricted_at FROM sys_tenant_subscription
                         WHERE tenant_id = ? AND status = 'RESTRICTED_FREE'
                         ORDER BY starts_at DESC, created_at DESC LIMIT 1
                        """, (resultSet, rowNumber) -> resultSet.getTimestamp("restricted_at"), tenantId)
                .stream().findFirst().map(value -> value == null ? null : value.toInstant()).orElse(null);
    }

    /** @param tenantId 租户 ID @return 当前 ACTIVE 订阅的服务期终点 */
    private Instant subscriptionEndsAt(UUID tenantId) {
        Timestamp endsAt = jdbcTemplate.queryForObject("""
                SELECT ends_at FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Timestamp.class, tenantId);
        return endsAt == null ? null : endsAt.toInstant();
    }

    /** @param tenantId 租户 ID @return 当前订阅的档位编码 */
    private String currentPlanCode(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT p.code FROM sys_tenant_subscription s
                  JOIN sys_plan_revision r ON r.id = s.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE s.tenant_id = ? AND s.status IN ('ACTIVE', 'GRACE', 'RESTRICTED_FREE')
                 ORDER BY CASE s.status WHEN 'ACTIVE' THEN 0 WHEN 'GRACE' THEN 1 ELSE 2 END,
                          s.starts_at DESC, s.created_at DESC
                 LIMIT 1
                """, String.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 全部订阅行数（含历史） */
    private int subscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return ACTIVE 订阅行数 */
    private int activeSubscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 未软删自有项目数 */
    private int projectCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_project WHERE tenant_id = ? AND deleted_at IS NULL
                """, Integer.class, tenantId);
    }

    /** @param projectId 项目 ID @return 项目生命周期状态（商业受限复用 ARCHIVED） */
    private String projectStatus(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_project WHERE id = ?", String.class, projectId);
    }

    /** @param tenantId 租户 ID @return 该租户生效中的商业受限台账条数 */
    private int activeRestrictionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_project_commercial_restriction
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 当前预约行状态 */
    private String pendingStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM sys_tenant_subscription_pending_change
                 WHERE tenant_id = ? ORDER BY created_at DESC, id DESC LIMIT 1
                """, String.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 运行时配额策略绑定版本 */
    private long assignmentVersion(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 通知意图总数 */
    private int intentCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription_notification_intent WHERE tenant_id = ?
                """, Integer.class, tenantId);
    }

    /**
     * 统计某租户某时间点的通知意图数。
     *
     * @param tenantId 租户 ID
     * @param kind 时间点
     * @return 意图行数
     */
    private int intentCount(UUID tenantId, String kind) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription_notification_intent
                 WHERE tenant_id = ? AND kind = ?
                """, Integer.class, tenantId, kind);
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
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, String.class, tenantId, action).stream().findFirst();
        assertThat(details).as("缺少审计事件 " + action).isPresent();
        return JSON.readTree(details.get());
    }
}
