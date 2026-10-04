package com.things.link.bootstrap.project.plan;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.project.application.plan.PlanCatalogSeedService;
import com.things.link.project.domain.plan.EffectiveEntitlement;
import com.things.link.project.domain.plan.EffectiveEntitlementProvider;
import com.things.link.project.domain.plan.PlanQuotaTemplate;
import com.things.link.project.domain.plan.ProductRevision1;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-1b 四档配额模板与权益投影的真实 PostgreSQL 验收。
 *
 * <p>用例钉住五件事：四档模板逐值等于冻结正文（含 P2 上下行拆分与历史窗口单位/数量）且重复播种
 * 幂等；{@code DISABLED} 能力解析为禁用且不携带数值额度；通过既有 {@code EffectiveQuotaPolicyProvider}
 * 解析出的有效策略等于冻结值；新建修订版不污染旧修订版的模板与有效结果；用 NULL/0 冒充未知的模板行
 * 被数据库 CHECK 拒绝。
 */
@DisplayName("S14-1b 四档配额模板与权益投影")
class PlanQuotaTemplateIntegrationTests extends AbstractIntegrationTest {

    /** 目录与模板事实、数据库守卫的验证入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 幂等播种用例。 */
    @Autowired
    private PlanCatalogSeedService planCatalogSeedService;
    /** 既有 S7 有效策略提供者；S14-1b 的模板侧解析也必须走同一端口。 */
    @Autowired
    private EffectiveQuotaPolicyProvider effectiveQuotaPolicyProvider;
    /** S14-1b 权益投影端口。 */
    @Autowired
    private EffectiveEntitlementProvider effectiveEntitlementProvider;
    /** 租户绑定套餐的合法路径，用于验证「投影为租户有效策略」。 */
    @Autowired
    private QuotaPolicyAssignmentService quotaPolicyAssignmentService;

    /** 本用例新建的探针租户，最后回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 本用例新建的探针配额模板，最后回收。 */
    private final Set<UUID> policyIds = new LinkedHashSet<>();
    /** 本用例新建的探针修订版，必须先于模板回收。 */
    private final Set<UUID> revisionIds = new LinkedHashSet<>();

    /** 回收探针行并清空线程上的租户范围，避免共享容器污染后续用例。 */
    @AfterEach
    void cleanUp() {
        try {
            TenantContext.clear();
            for (UUID revisionId : revisionIds) {
                jdbcTemplate.update("DELETE FROM sys_plan_revision WHERE id = ?", revisionId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
            for (UUID policyId : policyIds) {
                jdbcTemplate.update("DELETE FROM sys_quota_policy WHERE id = ?", policyId);
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** 四档模板必须逐值等于冻结正文，且重复播种零新增、快照不变。 */
    @Test
    void fourTierTemplatesMaterializeFrozenValuesAndReseedIsIdempotent() {
        assertThat(policyCode("FREE")).isEqualTo("PLAN_R1_FREE");
        assertThat(policyCode("STANDARD")).isEqualTo("PLAN_R1_STANDARD");
        assertThat(policyCode("ENTERPRISE")).isEqualTo("PLAN_R1_ENTERPRISE");
        assertThat(policyCode("PROFESSIONAL")).isEqualTo("PLAN_R1_PROFESSIONAL");
        assertThat(jdbcTemplate.queryForList("""
                SELECT q.plan_template
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                  JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                 WHERE r.revision_code = 'product-revision-1'
                """, Boolean.class)).containsOnly(true);

        assertTier("FREE", 1, 3, 3, 1, 0, "DAY", 7, 700, 300, 10000, 60, 10, 1, 10000, 1000, 1000,
                100L * 1024 * 1024);
        assertTier("STANDARD", 5, 100, 100, 10, 5, "MONTH", 6, 105000, 45000, 200000, 600, 500, 3, 100000,
                10000, 50000, 5L * 1024 * 1024 * 1024);
        assertTier("ENTERPRISE", 10, 300, 300, 30, 15, "MONTH", 9, 315000, 135000, 1000000, 1800, 2000, 10,
                500000, 60000, 200000, 20L * 1024 * 1024 * 1024);
        assertTier("PROFESSIONAL", 20, 1000, 1000, 100, 50, "MONTH", 12, 1050000, 450000, 5000000, 6000,
                10000, 30, 2000000, 300000, 1000000, 100L * 1024 * 1024 * 1024);

        // P2：上下行两池之和恒等于冻结正文的公开总量。
        assertThat(quotaColumn("FREE", "uplink_message_daily_limit")
                + quotaColumn("FREE", "downlink_message_daily_limit")).isEqualTo(1000);
        assertThat(quotaColumn("STANDARD", "uplink_message_daily_limit")
                + quotaColumn("STANDARD", "downlink_message_daily_limit")).isEqualTo(150000);
        assertThat(quotaColumn("ENTERPRISE", "uplink_message_daily_limit")
                + quotaColumn("ENTERPRISE", "downlink_message_daily_limit")).isEqualTo(450000);
        assertThat(quotaColumn("PROFESSIONAL", "uplink_message_daily_limit")
                + quotaColumn("PROFESSIONAL", "downlink_message_daily_limit")).isEqualTo(1500000);

        List<String> before = quotaSnapshot();
        assertThat(planCatalogSeedService.ensureProductRevision1()).isZero();
        assertThat(planCatalogSeedService.ensureProductRevision1()).isZero();
        assertThat(quotaSnapshot()).isEqualTo(before);
    }

    /** 未交付能力在四档都解析为 DISABLED，且不占用任何数值额度（与「额度 0」分离）。 */
    @Test
    void disabledCapabilitiesResolveAsDisabledWithoutNumericQuota() {
        List<String> disabledCapabilities = List.of("SMS_CHANNEL", "OTA", "OPEN_API", "APP_SUBSCRIPTION");
        for (String planCode : List.of("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL")) {
            EffectiveEntitlement entitlement =
                    effectiveEntitlementProvider.resolvePlanRevision(ProductRevision1.CODE, planCode);
            assertThat(entitlement.planCode()).isEqualTo(planCode);
            assertThat(entitlement.entitlements()).hasSize(12);
            for (String capability : disabledCapabilities) {
                assertThat(entitlement.disabled(capability))
                        .as(planCode + " 的 " + capability + " 必须显式 DISABLED").isTrue();
                assertThat(entitlement.enabled(capability)).isFalse();
            }
            assertThat(entitlement.enabled("OBJECT_STORAGE")).isTrue();
            assertThat(entitlement.enabled("OTA")).isFalse();
        }
        // 禁用能力不得在任何修订版的数值维度或模板列里出现，避免用 0 额度冒充未交付。
        assertThat(jdbcTemplate.queryForList("""
                SELECT dimension_code FROM sys_plan_revision_dimension
                 WHERE dimension_code IN ('SMS_CHANNEL', 'OTA', 'OPEN_API', 'APP_SUBSCRIPTION')
                """, String.class)).isEmpty();
    }

    /** 通过既有提供者解析的档位有效策略必须等于冻结值，并且能作为租户有效策略投影出来。 */
    @Test
    void effectiveQuotaPolicyForTierEqualsFrozenValuesThroughExistingProvider() {
        UUID professionalRevisionId = revisionId("PROFESSIONAL");
        UUID professionalPolicyId = policyId("PROFESSIONAL");
        EffectiveQuotaPolicy template = effectiveQuotaPolicyProvider.resolvePlanRevision(professionalRevisionId);

        assertThat(template.tenantId()).isNull();
        assertThat(template.assignmentVersion()).isZero();
        assertThat(template.policyId()).isEqualTo(professionalPolicyId);
        assertThat(template.planQuota()).isNotNull();
        assertThat(template.planQuota().template())
                .isEqualTo(ProductRevision1.quotaTemplates().get("PROFESSIONAL"));
        assertThat(template.planQuota().projectsMax()).isEqualTo(20);
        assertThat(template.planQuota().devicesMax()).isEqualTo(1000);
        assertThat(template.planQuota().endUsersMax()).isEqualTo(1000);
        assertThat(template.planQuota().dashboardsMax()).isEqualTo(100);
        assertThat(template.planQuota().externalCollaboratorSeats()).isEqualTo(50);
        assertThat(template.planQuota().historyWindowUnit()).isEqualTo("MONTH");
        assertThat(template.planQuota().historyWindowAmount()).isEqualTo(12);
        assertThat(template.planQuota().uplinkMessageDailyLimit()).isEqualTo(1050000);
        assertThat(template.planQuota().downlinkMessageDailyLimit()).isEqualTo(450000);
        assertThat(template.planQuota().restApiWriteDailyLimit()).isEqualTo(5000000);
        assertThat(template.planQuota().restApiRatePerMinute()).isEqualTo(6000);
        assertThat(template.planQuota().websocketConnectionLimit()).isEqualTo(10000);
        assertThat(template.planQuota().scriptRuleConcurrency()).isEqualTo(30);
        assertThat(template.planQuota().scriptRuleExecutionDailyLimit()).isEqualTo(2000000);
        assertThat(template.planQuota().scriptRuleCpuMillisDailyLimit()).isEqualTo(300000);
        assertThat(template.planQuota().notificationDeliveryDailyLimit()).isEqualTo(1000000);
        assertThat(template.planQuota().storageBytesLimit()).isEqualTo(100L * 1024 * 1024 * 1024);

        // 投影为租户有效策略：绑定后经既有 resolveTrustedTenant 读到的冻结值必须一致。
        UUID tenantId = Uuid7.generate();
        tenantIds.add(tenantId);
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "S14-1b 配额租户");
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        quotaPolicyAssignmentService.assign(tenantId, professionalPolicyId, 1L);
        EffectiveQuotaPolicy tenantPolicy = effectiveQuotaPolicyProvider.resolveTrustedTenant(tenantId);

        assertThat(tenantPolicy.tenantId()).isEqualTo(tenantId);
        assertThat(tenantPolicy.policyId()).isEqualTo(professionalPolicyId);
        assertThat(tenantPolicy.planQuota()).isNotNull();
        assertThat(tenantPolicy.planQuota().template())
                .isEqualTo(ProductRevision1.quotaTemplates().get("PROFESSIONAL"));
    }

    /** 新建修订版引用改动后的模板，不得改写旧修订版的绑定、模板行或有效结果。 */
    @Test
    void newRevisionWithChangedValuesDoesNotMutateOldRevision() {
        UUID oldRevisionId = revisionId("STANDARD");
        UUID oldPolicyId = policyId("STANDARD");
        EffectiveQuotaPolicy before = effectiveQuotaPolicyProvider.resolvePlanRevision(oldRevisionId);
        assertThat(before.planQuota().projectsMax()).isEqualTo(5);

        // 未来生效的探针修订版不会进入当前有效目录，避免污染 S14-1a 的目录端点期望值。
        UUID probePolicyId = Uuid7.generate();
        policyIds.add(probePolicyId);
        String probeCode = "PROBE_R2_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO sys_quota_policy (
                    id, code, version, plan_template, projects_max, device_count_limit, end_users_max,
                    dashboards_max, external_collaborator_seats, history_window_unit,
                    history_window_amount, uplink_message_daily_limit, downlink_message_daily_limit,
                    rest_api_call_daily_limit, rest_api_rate_per_minute, websocket_connection_limit,
                    rule_tenant_concurrency_limit, script_execution_daily_limit,
                    script_cpu_millis_daily_limit, notification_delivery_daily_limit, storage_bytes_limit,
                    uplink_device_refill_per_second, uplink_device_burst_capacity,
                    uplink_tenant_per_second_limit, uplink_tenant_per_minute_limit,
                    rest_api_read_rate_per_second, rest_api_write_rate_per_second,
                    rest_api_read_rate_per_minute, rest_api_write_rate_per_minute,
                    task_project_dispatch_per_second, task_tenant_dispatch_per_second,
                    rule_tenant_queue_capacity, rule_project_queue_capacity,
                    uplink_bytes_daily_limit, time_series_point_daily_limit)
                VALUES (?, ?, 1, true, 7, 7, 7, 7, 7, 'DAY', 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
                        10, 20, 1000, 60000, 20, 10, 7, 7, 20, 100, 100, 20, 1073741824, 1000000)
                """, probePolicyId, probeCode);

        UUID standardPlanId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_plan WHERE code = 'STANDARD'", UUID.class);
        UUID probeRevisionId = Uuid7.generate();
        revisionIds.add(probeRevisionId);
        jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents, valid_from, valid_until,
                                               quota_policy_id)
                VALUES (?, ?, ?, 99, '探针修订版', 'NOT_FOR_SALE', 'YEAR', 'CNY', NULL, ?, NULL, ?)
                """, probeRevisionId, standardPlanId, "probe-quota-revision-" + UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now().plus(365, ChronoUnit.DAYS)), probePolicyId);

        // 新修订版读到改动值，旧修订版与旧模板行保持冻结值。
        EffectiveQuotaPolicy probe = effectiveQuotaPolicyProvider.resolvePlanRevision(probeRevisionId);
        assertThat(probe.planQuota().projectsMax()).isEqualTo(7);
        assertThat(probe.planQuota().devicesMax()).isEqualTo(7);

        assertThat(policyId("STANDARD")).isEqualTo(oldPolicyId);
        assertThat(quotaColumn("STANDARD", "projects_max")).isEqualTo(5);
        EffectiveQuotaPolicy after = effectiveQuotaPolicyProvider.resolvePlanRevision(oldRevisionId);
        assertThat(after.planQuota()).isEqualTo(before.planQuota());
        assertThat(after.policyId()).isEqualTo(oldPolicyId);
    }

    /** 用 NULL/0 表示未知的模板行必须被数据库拒绝，既有非模板行仍可创建。 */
    @Test
    void policyRowRepresentingUnknownQuotaIsRejectedByDatabase() {
        String zeroCode = "PROBE_ZERO_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_quota_policy (
                    id, code, version, plan_template, projects_max, device_count_limit, end_users_max,
                    dashboards_max, external_collaborator_seats, history_window_unit,
                    history_window_amount, uplink_message_daily_limit, downlink_message_daily_limit,
                    rest_api_call_daily_limit, rest_api_rate_per_minute, websocket_connection_limit,
                    rule_tenant_concurrency_limit, script_execution_daily_limit,
                    script_cpu_millis_daily_limit, notification_delivery_daily_limit, storage_bytes_limit)
                VALUES (?, ?, 1, true, 0, 1, 1, 1, 0, 'DAY', 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
                """, Uuid7.generate(), zeroCode))
                .isInstanceOf(DataIntegrityViolationException.class);

        String nullCode = "PROBE_NULL_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_quota_policy (
                    id, code, version, plan_template, projects_max, device_count_limit, end_users_max,
                    dashboards_max, external_collaborator_seats, history_window_unit,
                    history_window_amount, uplink_message_daily_limit, downlink_message_daily_limit,
                    rest_api_call_daily_limit, rest_api_rate_per_minute, websocket_connection_limit,
                    rule_tenant_concurrency_limit, script_execution_daily_limit,
                    script_cpu_millis_daily_limit, notification_delivery_daily_limit, storage_bytes_limit)
                VALUES (?, ?, 1, true, 1, 1, 1, 1, 0, 'DAY', NULL, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
                """, Uuid7.generate(), nullCode))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 合法的模板行不能被原地改成未知值。
        UUID freePolicyId = policyId("FREE");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_quota_policy SET projects_max = NULL WHERE id = ?", freePolicyId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_quota_policy SET projects_max = 0 WHERE id = ?", freePolicyId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(quotaColumn("FREE", "projects_max")).isEqualTo(1);

        // S14-6f：保护旋钮同样不得用 NULL 表示「不限」——这是数据库不变量，不只靠迁移时的一次回填。
        String protectionNullCode = "PROBE_PROTECTION_"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_quota_policy (
                    id, code, version, plan_template, projects_max, device_count_limit, end_users_max,
                    dashboards_max, external_collaborator_seats, history_window_unit,
                    history_window_amount, uplink_message_daily_limit, downlink_message_daily_limit,
                    rest_api_call_daily_limit, rest_api_rate_per_minute, websocket_connection_limit,
                    rule_tenant_concurrency_limit, script_execution_daily_limit,
                    script_cpu_millis_daily_limit, notification_delivery_daily_limit, storage_bytes_limit)
                VALUES (?, ?, 1, true, 1, 1, 1, 1, 0, 'DAY', 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
                """, Uuid7.generate(), protectionNullCode))
                .as("模板行漏写运行时保护旋钮必须被 sys_quota_policy_plan_template_runtime_protection_ck 拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);

        // S14-6f 追加：其余保护值都写对、只把 REST 写分钟桶留空，同样必须被拒绝——
        // 这一列在 0120 时是唯一漏网的旋钮（当时以「1040 已播种」为由排除），0130 起并入同一不变量。
        String writeRateNullCode = "PROBE_WRITE_MIN_"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_quota_policy (
                    id, code, version, plan_template, projects_max, device_count_limit, end_users_max,
                    dashboards_max, external_collaborator_seats, history_window_unit,
                    history_window_amount, uplink_message_daily_limit, downlink_message_daily_limit,
                    rest_api_call_daily_limit, rest_api_rate_per_minute, websocket_connection_limit,
                    rule_tenant_concurrency_limit, script_execution_daily_limit,
                    script_cpu_millis_daily_limit, notification_delivery_daily_limit, storage_bytes_limit,
                    uplink_device_refill_per_second, uplink_device_burst_capacity,
                    uplink_tenant_per_second_limit, uplink_tenant_per_minute_limit,
                    rest_api_read_rate_per_second, rest_api_write_rate_per_second,
                    rest_api_read_rate_per_minute,
                    task_project_dispatch_per_second, task_tenant_dispatch_per_second,
                    rule_tenant_queue_capacity, rule_project_queue_capacity,
                    uplink_bytes_daily_limit, time_series_point_daily_limit)
                VALUES (?, ?, 1, true, 1, 1, 1, 1, 0, 'DAY', 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                        10, 20, 1000, 60000, 20, 10, 1, 20, 100, 100, 20, 1073741824, 1000000)
                """, Uuid7.generate(), writeRateNullCode))
                .as("模板行只漏 REST 写分钟桶也必须被拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);

        // 既有 S7 运行时模板（非 plan_template）继续允许 NULL=不限，不受新约束影响。
        UUID legacyPolicyId = Uuid7.generate();
        policyIds.add(legacyPolicyId);
        jdbcTemplate.update("INSERT INTO sys_quota_policy (id, code) VALUES (?, ?)",
                legacyPolicyId, "S14_PROBE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT plan_template FROM sys_quota_policy WHERE id = ?", Boolean.class, legacyPolicyId))
                .isFalse();
    }

    /**
     * 断言一档模板的全部冻结列。
     *
     * @param planCode 档位编码
     * @param projectsMax 项目数
     * @param devicesMax 设备数
     * @param endUsersMax 终端用户数
     * @param dashboardsMax 看板数
     * @param seats 外部协作者席位
     * @param historyWindowUnit 历史窗口单位
     * @param historyWindowAmount 历史窗口数量
     * @param uplink 上行日额度
     * @param downlink 下行日额度
     * @param restWriteDaily REST 写请求日额度
     * @param restRatePerMinute REST 每分钟速率
     * @param websocket WebSocket 并发
     * @param scriptConcurrency 脚本并发
     * @param scriptExecution 脚本执行日额度
     * @param scriptCpu 脚本 CPU 日额度
     * @param notification 通知投递日额度
     * @param storageBytes 对象存储字节上限
     */
    private void assertTier(String planCode, long projectsMax, long devicesMax, long endUsersMax,
                            long dashboardsMax, long seats, String historyWindowUnit, long historyWindowAmount,
                            long uplink, long downlink, long restWriteDaily, long restRatePerMinute,
                            long websocket, long scriptConcurrency, long scriptExecution, long scriptCpu,
                            long notification, long storageBytes) {
        assertThat(quotaColumn(planCode, "projects_max")).isEqualTo(projectsMax);
        assertThat(quotaColumn(planCode, "device_count_limit")).isEqualTo(devicesMax);
        assertThat(quotaColumn(planCode, "end_users_max")).isEqualTo(endUsersMax);
        assertThat(quotaColumn(planCode, "dashboards_max")).isEqualTo(dashboardsMax);
        assertThat(quotaColumn(planCode, "external_collaborator_seats")).isEqualTo(seats);
        assertThat(quotaText(planCode, "history_window_unit")).isEqualTo(historyWindowUnit);
        assertThat(quotaColumn(planCode, "history_window_amount")).isEqualTo(historyWindowAmount);
        assertThat(quotaColumn(planCode, "uplink_message_daily_limit")).isEqualTo(uplink);
        assertThat(quotaColumn(planCode, "downlink_message_daily_limit")).isEqualTo(downlink);
        assertThat(quotaColumn(planCode, "rest_api_call_daily_limit")).isEqualTo(restWriteDaily);
        assertThat(quotaColumn(planCode, "rest_api_rate_per_minute")).isEqualTo(restRatePerMinute);
        assertThat(quotaColumn(planCode, "websocket_connection_limit")).isEqualTo(websocket);
        assertThat(quotaColumn(planCode, "rule_tenant_concurrency_limit")).isEqualTo(scriptConcurrency);
        assertThat(quotaColumn(planCode, "script_execution_daily_limit")).isEqualTo(scriptExecution);
        assertThat(quotaColumn(planCode, "script_cpu_millis_daily_limit")).isEqualTo(scriptCpu);
        assertThat(quotaColumn(planCode, "notification_delivery_daily_limit")).isEqualTo(notification);
        assertThat(quotaColumn(planCode, "storage_bytes_limit")).isEqualTo(storageBytes);
        assertThat(ProductRevision1.quotaTemplates().get(planCode)).isEqualTo(
                new PlanQuotaTemplate(projectsMax, devicesMax, endUsersMax, dashboardsMax, seats,
                        historyWindowUnit, historyWindowAmount, uplink, downlink, restWriteDaily,
                        restRatePerMinute, websocket, scriptConcurrency, scriptExecution, scriptCpu,
                        notification, storageBytes));
    }

    /** @param planCode 档位编码 @param column 列名 @return 该档模板的数值列 */
    private long quotaColumn(String planCode, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT q.%s
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                  JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                 WHERE r.revision_code = 'product-revision-1' AND p.code = ?
                """.formatted(column), Long.class, planCode);
    }

    /** @param planCode 档位编码 @param column 列名 @return 该档模板的文本列 */
    private String quotaText(String planCode, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT q.%s
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                  JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                 WHERE r.revision_code = 'product-revision-1' AND p.code = ?
                """.formatted(column), String.class, planCode);
    }

    /** @param planCode 档位编码 @return 该档绑定的模板编码 */
    private String policyCode(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT q.code
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                  JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                 WHERE r.revision_code = 'product-revision-1' AND p.code = ?
                """, String.class, planCode);
    }

    /** @param planCode 档位编码 @return 该档绑定的模板 ID */
    private UUID policyId(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT q.id
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                  JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                 WHERE r.revision_code = 'product-revision-1' AND p.code = ?
                """, UUID.class, planCode);
    }

    /** @param planCode 档位编码 @return 该档 product-revision-1 的修订版 ID */
    private UUID revisionId(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT r.id
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE r.revision_code = 'product-revision-1' AND p.code = ?
                """, UUID.class, planCode);
    }

    /** @return 四档模板全列快照，用于证明重复播种不改值 */
    private List<String> quotaSnapshot() {
        return jdbcTemplate.queryForList("""
                SELECT q.code || '|' || q.plan_template || '|' || q.projects_max || '|' || q.device_count_limit
                       || '|' || q.end_users_max || '|' || q.dashboards_max
                       || '|' || q.external_collaborator_seats || '|' || q.history_window_unit
                       || '|' || q.history_window_amount || '|' || q.uplink_message_daily_limit
                       || '|' || q.downlink_message_daily_limit || '|' || q.rest_api_call_daily_limit
                       || '|' || q.rest_api_rate_per_minute || '|' || q.websocket_connection_limit
                       || '|' || q.rule_tenant_concurrency_limit || '|' || q.script_execution_daily_limit
                       || '|' || q.script_cpu_millis_daily_limit || '|' || q.notification_delivery_daily_limit
                       || '|' || q.storage_bytes_limit
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                  JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                 WHERE r.revision_code = 'product-revision-1'
                 ORDER BY p.display_order
                """, String.class);
    }
}
