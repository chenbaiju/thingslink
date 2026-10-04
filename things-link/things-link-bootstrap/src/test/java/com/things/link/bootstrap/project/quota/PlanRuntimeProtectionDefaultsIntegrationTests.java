package com.things.link.bootstrap.project.quota;

import com.things.link.ingestion.application.DeviceUplinkRateLimiter;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.plan.PlanRuntimeDefaults;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S14-6f 验收：四档冻结模板**不得再用 NULL 表示「不限」**，且新租户拿到的有效策略必须真的带保护值。
 *
 * <p>背景（S14-6 审计发现）：S14-1b 物化四档模板时把 S7 的运行时保护旋钮留空，而 S14-2a 起新租户一律绑定
 * {@code PLAN_R1_FREE}；运行时把 NULL 解释为**不限**（`DeviceUplinkRateLimiter` 明写「null 字段代表不限」），
 * 于是 S7 的上行限流、任务派发与规则队列容量、字节与点位日额度对所有新租户实际失效。
 *
 * <p>S14-6l 追加第四层：用真实租户策略驱动真实限流器，证明这些值**真的执行**（而不只是非空）。
 *
 * <p>本类钉住三层：① 模板行本身（迁移只补空回填 + 迁移守卫 + `sys_quota_policy_plan_template_runtime_protection_ck`
 * 数据库不变量，后者的负向探针在 {@code PlanQuotaTemplateIntegrationTests}）；② 模板行与
 * {@link PlanRuntimeDefaults} 逐值一致（防止 SQL 与 Java 两侧漂移，被测试夹具放宽的列只断言非空）；
 * ③ **真实租户的有效策略**里这些字段非空——这一层才是「限流真的会执行」的证据。
 */
@DisplayName("S14-6f 运行时保护默认值")
class PlanRuntimeProtectionDefaultsIntegrationTests extends AbstractIntegrationTest {

    /** 必须在 plan_template 行上非空的保护与安全默认值列。 */
    private static final Map<String, Long> REQUIRED_COLUMNS = Map.ofEntries(
            Map.entry("uplink_device_refill_per_second", PlanRuntimeDefaults.UPLINK_DEVICE_REFILL_PER_SECOND),
            Map.entry("uplink_device_burst_capacity", PlanRuntimeDefaults.UPLINK_DEVICE_BURST_CAPACITY),
            Map.entry("uplink_tenant_per_second_limit", PlanRuntimeDefaults.UPLINK_TENANT_PER_SECOND_LIMIT),
            Map.entry("uplink_tenant_per_minute_limit", PlanRuntimeDefaults.UPLINK_TENANT_PER_MINUTE_LIMIT),
            Map.entry("rest_api_read_rate_per_second", PlanRuntimeDefaults.REST_API_READ_RATE_PER_SECOND),
            Map.entry("rest_api_write_rate_per_second", PlanRuntimeDefaults.REST_API_WRITE_RATE_PER_SECOND),
            Map.entry("task_project_dispatch_per_second", PlanRuntimeDefaults.TASK_PROJECT_DISPATCH_PER_SECOND),
            Map.entry("task_tenant_dispatch_per_second", PlanRuntimeDefaults.TASK_TENANT_DISPATCH_PER_SECOND),
            Map.entry("rule_tenant_queue_capacity", PlanRuntimeDefaults.RULE_TENANT_QUEUE_CAPACITY),
            Map.entry("rule_project_queue_capacity", PlanRuntimeDefaults.RULE_PROJECT_QUEUE_CAPACITY),
            Map.entry("uplink_bytes_daily_limit", PlanRuntimeDefaults.UPLINK_BYTES_DAILY_LIMIT),
            Map.entry("time_series_point_daily_limit", PlanRuntimeDefaults.TIME_SERIES_POINT_DAILY_LIMIT));

    /**
     * 上表里**值会被共享测试夹具全表放宽**的列：只断言非空，不断言等于 Java 默认值。
     *
     * <p>{@code AbstractIntegrationTest.RelaxRestQuotaTestConfiguration} 在上下文就绪后把整张
     * {@code sys_quota_policy} 的 {@code rest_api_write_rate_per_second} 抬到 1,000,000，以免功能夹具
     * 准备被限流；S14-1b 迁移注释也把该列定义为「会被运行时放宽的兜底值」（冻结产品速率另有
     * {@code rest_api_rate_per_minute}）。生产环境没有这段放宽，模板行保持 10。
     */
    private static final Set<String> TEST_RELAXED_COLUMNS = Set.of("rest_api_write_rate_per_second");

    /**
     * 只断言**非空**、不断言具体值的两列：它们随档位变化，不是与档位无关的平面默认值。
     *
     * <p>{@code rest_api_read/write_rate_per_minute} 取该档**已冻结**的 {@code rest_api_rate_per_minute}
     * （FREE 60 / STANDARD 600 / ENTERPRISE 1800 / PROFESSIONAL 6000）；其中写侧还会被共享测试夹具全表放宽，
     * 所以两列都只作非空断言，逐值口径由 `sys_plan_revision_dimension` 的冻结正文另行钉住。
     */
    private static final Set<String> PER_TIER_COLUMNS = Set.of(
            "rest_api_read_rate_per_minute", "rest_api_write_rate_per_minute");

    /** 夹具事实读取入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建入口（新租户绑定 PLAN_R1_FREE）。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 有效策略读取入口。 */
    @Autowired
    private EffectiveQuotaPolicyProvider effectiveQuotaPolicyProvider;
    /** 真实上行限流器：用来证明保护值不只是「非空」，而是真的会拦。 */
    @Autowired
    private DeviceUplinkRateLimiter uplinkRateLimiter;
    /** 为 {@code MANDATORY} 的租户创建提供外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();

    /** 清理夹具。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            TenantContext.clear();
            tenantIds.clear();
        }
    }

    /** ① 所有 plan_template 行在这些保护/安全默认值列上必须非空，平面默认值列还须等于 Java 侧取值。 */
    @Test
    void planTemplatesCarryRuntimeProtectionDefaults() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT code, uplink_device_refill_per_second, uplink_device_burst_capacity,
                       uplink_tenant_per_second_limit, uplink_tenant_per_minute_limit,
                       rest_api_read_rate_per_second, rest_api_write_rate_per_second,
                       rest_api_read_rate_per_minute, rest_api_write_rate_per_minute,
                       task_project_dispatch_per_second, task_tenant_dispatch_per_second,
                       rule_tenant_queue_capacity, rule_project_queue_capacity,
                       uplink_bytes_daily_limit, time_series_point_daily_limit
                  FROM sys_quota_policy WHERE plan_template ORDER BY code
                """);

        assertThat(rows).as("四档模板必须已物化").hasSize(4);
        for (Map<String, Object> row : rows) {
            REQUIRED_COLUMNS.forEach((column, expected) -> assertThat(row.get(column))
                    .as("模板 %s 的 %s 不得为 NULL（NULL=不限，S14 禁止）", row.get("code"), column)
                    .isNotNull());
            PER_TIER_COLUMNS.forEach(column -> assertThat(row.get(column))
                    .as("模板 %s 的 %s 不得为 NULL（随档位取值，但绝不允许不限）", row.get("code"), column)
                    .isNotNull());
            REQUIRED_COLUMNS.forEach((column, expected) -> {
                if (TEST_RELAXED_COLUMNS.contains(column)) {
                    return;
                }
                assertThat(((Number) row.get(column)).longValue())
                        .as("模板 %s 的 %s 必须等于 Java 侧默认值", row.get("code"), column)
                        .isEqualTo(expected);
            });
        }
    }

    /** ② 新租户的有效策略（真实入口读到的那份）必须带上保护值与字节/点位日额度。 */
    @Test
    void newlyProvisionedTenantReceivesNonNullProtectionValues() {
        UUID tenantId = transactionTemplate.execute(status -> tenantProvisioning.createTenant("S14-6f 保护租户"));
        assertThat(tenantId).isNotNull();
        tenantIds.add(tenantId);

        TenantContext.set(new TenantScope(tenantId, null, UUID.randomUUID()));
        try {
            var policy = effectiveQuotaPolicyProvider.resolveTrustedTenant(tenantId);
            assertThat(policy.uplinkDeviceRefillPerSecond())
                    .as("上行每设备令牌补充速率：NULL 会让 DeviceUplinkRateLimiter 直接放行")
                    .isEqualTo(PlanRuntimeDefaults.UPLINK_DEVICE_REFILL_PER_SECOND);
            assertThat(policy.uplinkDeviceBurstCapacity())
                    .isEqualTo(PlanRuntimeDefaults.UPLINK_DEVICE_BURST_CAPACITY);
            assertThat(policy.uplinkTenantPerSecondLimit())
                    .isEqualTo(PlanRuntimeDefaults.UPLINK_TENANT_PER_SECOND_LIMIT);
            assertThat(policy.uplinkTenantPerMinuteLimit())
                    .isEqualTo(PlanRuntimeDefaults.UPLINK_TENANT_PER_MINUTE_LIMIT);
            assertThat(policy.restApiReadRatePerSecond())
                    .isEqualTo(PlanRuntimeDefaults.REST_API_READ_RATE_PER_SECOND);
            assertThat(policy.restApiWriteRatePerSecond())
                    .as("REST 写速率：NULL 会让写接口绕过 S7 兜底（该列被测试夹具全表放宽，故只断言非空）")
                    .isNotNull();
            assertThat(policy.taskProjectDispatchPerSecond())
                    .isEqualTo(PlanRuntimeDefaults.TASK_PROJECT_DISPATCH_PER_SECOND);
            assertThat(policy.taskTenantDispatchPerSecond())
                    .isEqualTo(PlanRuntimeDefaults.TASK_TENANT_DISPATCH_PER_SECOND);
            assertThat(policy.ruleTenantQueueCapacity())
                    .isEqualTo(PlanRuntimeDefaults.RULE_TENANT_QUEUE_CAPACITY);
            assertThat(policy.ruleProjectQueueCapacity())
                    .isEqualTo(PlanRuntimeDefaults.RULE_PROJECT_QUEUE_CAPACITY);
        } finally {
            TenantContext.clear();
        }
    }

    /** ③ 每日字节与点位安全额度也必须落到账户上（它们没有目录维度，靠 S7 默认值承担）。 */
    @Test
    void newlyProvisionedTenantReceivesDailyByteAndPointLimits() {
        UUID tenantId = transactionTemplate.execute(status -> tenantProvisioning.createTenant("S14-6f 日额度租户"));
        assertThat(tenantId).isNotNull();
        tenantIds.add(tenantId);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT q.uplink_bytes_daily_limit, q.time_series_point_daily_limit
                  FROM sys_tenant t JOIN sys_quota_policy q ON q.id = t.quota_policy_id
                 WHERE t.id = ?
                """, tenantId);
        assertThat(((Number) row.get("uplink_bytes_daily_limit")).longValue())
                .as("字节日额度：NULL 会让新租户无限上行字节")
                .isEqualTo(PlanRuntimeDefaults.UPLINK_BYTES_DAILY_LIMIT);
        assertThat(((Number) row.get("time_series_point_daily_limit")).longValue())
                .isEqualTo(PlanRuntimeDefaults.TIME_SERIES_POINT_DAILY_LIMIT);
    }

    /**
     * ④（S14-6l）保护值必须**真的执行**：拿真实 FREE 租户的有效策略驱动真实上行限流器，
     * 连续突发必须被设备令牌桶拒绝一部分；再把同一组字段留成 NULL（历史「不限」语义）做对照，同样次数的突发必须全部放行。
     *
     * <p>为什么需要这一层：前三层只证明「值非空且等于 Java 默认值」，而 S14-6f 要修的是「限流实际失效」。
     * 本用例把「策略 → 限流器」的接线也钉住；对照臂同时说明：如果这些字段再被留空，本用例会变红，
     * 而不是像过去那样静默放行（数据库侧现已由 {@code sys_quota_policy_plan_template_runtime_protection_ck} 阻止写空）。
     */
    @Test
    void realPlanTenantPolicyActuallyThrottlesBurstUplink() {
        UUID tenantId = transactionTemplate.execute(status -> tenantProvisioning.createTenant("S14-6l 限流租户"));
        assertThat(tenantId).isNotNull();
        tenantIds.add(tenantId);

        EffectiveQuotaPolicy policy;
        TenantContext.set(new TenantScope(tenantId, null, UUID.randomUUID()));
        try {
            policy = effectiveQuotaPolicyProvider.resolveTrustedTenant(tenantId);
        } finally {
            TenantContext.clear();
        }
        assertThat(policy.uplinkDeviceBurstCapacity()).isNotNull();
        assertThat(policy.uplinkDeviceRefillPerSecond()).isNotNull();

        // 突发次数取令牌桶容量的 6 倍：即使极端慢的环境里循环耗时数秒、按 10/s 补充，也不可能全部放行。
        int attempts = (int) (policy.uplinkDeviceBurstCapacity() * 6);
        UUID deviceId = UUID.randomUUID();
        long allowed = 0;
        for (int index = 0; index < attempts; index++) {
            if (uplinkRateLimiter.tryAcquire(tenantId, deviceId, policy)) {
                allowed++;
            }
        }
        assertThat(allowed)
                .as("真实 FREE 租户的设备令牌桶（突发 %s、补充 %s/s）必须拒绝一部分突发，而不是全部放行",
                        policy.uplinkDeviceBurstCapacity(), policy.uplinkDeviceRefillPerSecond())
                .isLessThan(attempts);
        assertThat(allowed)
                .as("容量之内的申请必须被允许（否则说明限流器连正常突发都拦）")
                .isGreaterThanOrEqualTo(policy.uplinkDeviceBurstCapacity());

        // 对照臂：把上行保护字段留成 NULL（S14-6f 之前的模板状态），同样次数的突发必须全部放行——
        // 这正是本用例能识别该类缺陷的原因，也说明「全部放行」是缺陷特征而不是配置。
        EffectiveQuotaPolicy unlimited = new EffectiveQuotaPolicy(tenantId, policy.policyId(),
                policy.assignmentVersion(), policy.policyVersion(), null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                policy.dailySoftLimitBasisPoints(), policy.dailyDegradeBasisPoints());
        UUID controlDeviceId = UUID.randomUUID();
        for (int index = 0; index < attempts; index++) {
            assertThat(uplinkRateLimiter.tryAcquire(tenantId, controlDeviceId, unlimited))
                    .as("NULL 保护字段代表不限：对照组第 %d 次申请必须放行", index + 1)
                    .isTrue();
        }
    }
}
