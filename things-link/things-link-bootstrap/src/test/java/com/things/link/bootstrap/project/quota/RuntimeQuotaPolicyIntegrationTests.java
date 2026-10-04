package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.project.application.QuotaPolicyCommandService;
import com.things.link.project.application.RuntimeQuotaPolicyCommand;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S7-3 运行时配额策略、RLS 边界和事务后缓存失效的真实 PostgreSQL 验收。 */
class RuntimeQuotaPolicyIntegrationTests extends AbstractIntegrationTest {

    /** PostgreSQL 权威数据与目录约束的验证入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 仅接收已授权项目或服务端身份的策略读取端口。 */
    @Autowired
    private EffectiveQuotaPolicyProvider effectiveQuotaPolicyProvider;
    /** 租户绑定套餐的 CAS 应用服务。 */
    @Autowired
    private QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 可复用套餐模板的 CAS 应用服务。 */
    @Autowired
    private QuotaPolicyCommandService quotaPolicyCommandService;
    /** 路径项目与已选项目一致性闸门；测试不新增任何管理 HTTP API。 */
    @Autowired
    private ProjectQuotaService projectQuotaService;
    /** 显式提交或回滚内层服务事务，以验证 afterCompletion 语义。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例插入的租户，测试结束后按依赖顺序回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 本用例插入的项目，必须先于租户删除。 */
    private final Set<UUID> projectIds = new LinkedHashSet<>();
    /** 本用例插入的账号，成员关系移除后才可删除。 */
    private final Set<UUID> accountIds = new LinkedHashSet<>();
    /** 本用例插入的可复用套餐模板，最后删除以避免外键残留。 */
    private final Set<UUID> policyIds = new LinkedHashSet<>();

    /** 回收共享 Testcontainers 数据库中的夹具，并清理每个请求线程的租户范围。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID tenantId : tenantIds) {
                TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
                jdbcTemplate.update("DELETE FROM sys_usage_counter_daily WHERE tenant_id = ?", tenantId);
            }
            TenantContext.clear();
            for (UUID projectId : projectIds) {
                jdbcTemplate.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
            }
            for (UUID accountId : accountIds) {
                jdbcTemplate.update("DELETE FROM sys_account WHERE id = ?", accountId);
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

    /** V0120 必须落下全部运行时字段、绑定版本和数据库级 null/0/正数约束。 */
    @Test
    void migrationCreatesRuntimeFieldsAssignmentVersionAndDatabaseConstraints() {
        Set<String> runtimeColumns = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sys_quota_policy'
                   AND column_name IN (
                     'uplink_device_refill_per_second', 'uplink_device_burst_capacity',
                     'uplink_tenant_per_second_limit', 'uplink_tenant_per_minute_limit',
                     'rest_api_read_rate_per_second', 'rest_api_write_rate_per_second',
                     'rest_api_read_rate_per_minute', 'rest_api_write_rate_per_minute')
                """, String.class));
        assertThat(runtimeColumns).containsExactlyInAnyOrder(
                "uplink_device_refill_per_second", "uplink_device_burst_capacity",
                "uplink_tenant_per_second_limit", "uplink_tenant_per_minute_limit",
                "rest_api_read_rate_per_second", "rest_api_write_rate_per_second",
                "rest_api_read_rate_per_minute", "rest_api_write_rate_per_minute");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sys_tenant'
                   AND column_name = 'quota_policy_assignment_version'
                """, Integer.class)).isEqualTo(1);

        Set<String> constraints = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT conname
                  FROM pg_constraint
                 WHERE conname IN (
                   'sys_tenant_quota_policy_assignment_version_ck',
                   'sys_quota_policy_uplink_device_refill_per_second_ck',
                   'sys_quota_policy_uplink_device_burst_capacity_ck',
                   'sys_quota_policy_uplink_device_bucket_ck',
                   'sys_quota_policy_uplink_tenant_per_second_limit_ck',
                   'sys_quota_policy_uplink_tenant_per_minute_limit_ck',
                   'sys_quota_policy_rest_api_read_rate_per_second_ck',
                   'sys_quota_policy_rest_api_write_rate_per_second_ck',
                   'sys_quota_policy_rest_api_read_rate_per_minute_ck',
                   'sys_quota_policy_rest_api_write_rate_per_minute_ck')
                """, String.class));
        assertThat(constraints).hasSize(10);

        UUID policyId = insertPolicy("S7-MIGRATION-" + UUID.randomUUID());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE sys_quota_policy
                   SET uplink_device_refill_per_second = 10,
                       uplink_device_burst_capacity = 9
                 WHERE id = ?
                """, policyId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE sys_quota_policy
                   SET rest_api_read_rate_per_second = -1
                 WHERE id = ?
                """, policyId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** S8-2B 规则队列配额必须穿透 FREE 模板与两条受限有效策略投影。 */
    @Test
    void migrationPublishesRuleQueueLimitsThroughBothEffectivePolicyFunctions() {
        Set<String> columns = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sys_quota_policy'
                   AND column_name IN (
                     'rule_tenant_concurrency_limit', 'rule_tenant_queue_capacity',
                     'rule_project_queue_capacity')
                """, String.class));
        assertThat(columns).containsExactlyInAnyOrder(
                "rule_tenant_concurrency_limit", "rule_tenant_queue_capacity", "rule_project_queue_capacity");
        assertThat(jdbcTemplate.queryForList("""
                SELECT conname
                  FROM pg_constraint
                 WHERE conname IN (
                   'sys_quota_policy_rule_tenant_concurrency_limit_ck',
                   'sys_quota_policy_rule_tenant_queue_capacity_ck',
                   'sys_quota_policy_rule_project_queue_capacity_ck')
                """, String.class)).containsExactlyInAnyOrder(
                "sys_quota_policy_rule_tenant_concurrency_limit_ck",
                "sys_quota_policy_rule_tenant_queue_capacity_ck",
                "sys_quota_policy_rule_project_queue_capacity_ck");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE sys_quota_policy
                   SET rule_tenant_queue_capacity = -1
                 WHERE code = 'FREE'
                """)).isInstanceOf(DataIntegrityViolationException.class);

        Fixture fixture = seedFixture();
        TenantContext.set(fixture.ownerScope());
        try {
            assertThat(jdbcTemplate.queryForList("""
                    SELECT rule_tenant_concurrency_limit
                      FROM project_effective_quota_policy()
                    """, Long.class)).containsExactly(4L);
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbcTemplate.query("""
                SELECT rule_tenant_queue_capacity, rule_project_queue_capacity
                  FROM device_project_effective_quota_policy(?, ?)
                """, (resultSet, rowNumber) -> new long[] {
                    resultSet.getLong("rule_tenant_queue_capacity"),
                    resultSet.getLong("rule_project_queue_capacity")},
                fixture.ownerTenantId(), fixture.selectedProjectId()))
                .containsExactly(new long[] {100L, 20L});
    }

    /** 协作者只可从已选项目派生 owner 策略，RLS 仍不得让其直读 owner 日用量。 */
    @Test
    void collaboratorResolvesSelectedProjectOwnerPolicyWithoutRelaxingTenantRlsOrTrustingPathProject() {
        Fixture fixture = seedFixture();
        TenantContext.set(fixture.ownerScope());
        try {
            jdbcTemplate.update("""
                    INSERT INTO sys_usage_counter_daily
                        (id, tenant_id, project_id, usage_date, metric, used_value)
                    VALUES (?, ?, ?, ?, 'UPLINK_MESSAGE', 7)
                    """, Uuid7.generate(), fixture.ownerTenantId(), fixture.selectedProjectId(),
                    LocalDate.now(ZoneOffset.UTC));
        } finally {
            TenantContext.clear();
        }

        TenantContext.set(fixture.collaboratorScope());
        try {
            java.util.List<EffectiveQuotaPolicy> policies = jdbcTemplate.query("""
                    SELECT tenant_id, policy_id, assignment_version, policy_version,
                           uplink_device_refill_per_second, uplink_device_burst_capacity,
                           uplink_tenant_per_second_limit, uplink_tenant_per_minute_limit,
                           rest_api_read_rate_per_second, rest_api_write_rate_per_second,
                           rest_api_read_rate_per_minute, rest_api_write_rate_per_minute,
                           websocket_connection_limit,
                           task_project_dispatch_per_second, task_tenant_dispatch_per_second
                      FROM project_effective_quota_policy()
                    """, (resultSet, rowNumber) -> new EffectiveQuotaPolicy(
                    resultSet.getObject("tenant_id", UUID.class), resultSet.getObject("policy_id", UUID.class),
                    resultSet.getLong("assignment_version"), resultSet.getLong("policy_version"),
                    resultSet.getObject("uplink_device_refill_per_second", Long.class),
                    resultSet.getObject("uplink_device_burst_capacity", Long.class),
                    resultSet.getObject("uplink_tenant_per_second_limit", Long.class),
                    resultSet.getObject("uplink_tenant_per_minute_limit", Long.class),
                    resultSet.getObject("rest_api_read_rate_per_second", Long.class),
                    resultSet.getObject("rest_api_write_rate_per_second", Long.class),
                    resultSet.getObject("rest_api_read_rate_per_minute", Long.class),
                    resultSet.getObject("rest_api_write_rate_per_minute", Long.class),
                    resultSet.getObject("websocket_connection_limit", Long.class),
                    resultSet.getObject("task_project_dispatch_per_second", Long.class),
                    resultSet.getObject("task_tenant_dispatch_per_second", Long.class)));
            assertThat(policies).hasSize(1);
            EffectiveQuotaPolicy policy = policies.getFirst();
            assertThat(policy.tenantId()).isEqualTo(fixture.ownerTenantId());
            assertThat(policy.restApiReadRatePerSecond()).isEqualTo(20L);
            assertThat(policy.taskProjectDispatchPerSecond()).isEqualTo(20L);
            assertThat(policy.taskTenantDispatchPerSecond()).isEqualTo(100L);
            assertThat(jdbcTemplate.queryForList("""
                    SELECT id FROM sys_usage_counter_daily WHERE tenant_id = ?
                    """, UUID.class, fixture.ownerTenantId())).isEmpty();

            // EMQX 已确权投影必须核对同一设备解析出的 tenant/project 二元组；协作者 JWT tenant 不能匹配 owner 项目。
            assertThat(jdbcTemplate.queryForList("""
                    SELECT tenant_id
                      FROM device_project_effective_quota_policy(?, ?)
                    """, UUID.class, fixture.collaboratorTenantId(), fixture.selectedProjectId())).isEmpty();
            // 控制台协作者仍只使用无参投影，从 app_current_project 派生 owner，不能改用 EMQX 的二元组函数。
            assertThat(jdbcTemplate.queryForList("""
                    SELECT tenant_id
                      FROM project_effective_quota_policy()
                    """, UUID.class)).containsExactly(fixture.ownerTenantId());

            BusinessException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> projectQuotaService.get(fixture.unselectedProjectId()), BusinessException.class);
            assertThat(exception.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * MQTT 设备确权没有 JWT ThreadLocal；受限二元组投影仍必须只接受真实 owner tenant 与项目的匹配组合。
     */
    @Test
    void deviceScopeProjectionReturnsOnlyForVerifiedTenantProjectPair() {
        Fixture fixture = seedFixture();
        TenantContext.clear();

        assertThatThrownBy(() -> effectiveQuotaPolicyProvider.resolveTrustedDeviceProject(
                fixture.collaboratorTenantId(), fixture.selectedProjectId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> effectiveQuotaPolicyProvider.resolveTrustedDeviceProject(
                fixture.ownerTenantId(), Uuid7.generate()))
                .isInstanceOf(IllegalArgumentException.class);

        EffectiveQuotaPolicy policy = effectiveQuotaPolicyProvider.resolveTrustedDeviceProject(
                fixture.ownerTenantId(), fixture.selectedProjectId());
        assertThat(policy.tenantId()).isEqualTo(fixture.ownerTenantId());
        assertThat(policy.uplinkDeviceRefillPerSecond()).isEqualTo(10L);

        // 热路由缓存命中后仍先核对 project -> owner tenant，不能被错误二元组绕过。
        assertThatThrownBy(() -> effectiveQuotaPolicyProvider.resolveTrustedDeviceProject(
                fixture.collaboratorTenantId(), fixture.selectedProjectId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 绑定与模板 CAS 仅在提交后失效；回滚既不改 PostgreSQL 也不淘汰本地已缓存快照。 */
    @Test
    void assignmentAndTemplateCasInvalidateOnlyAfterCommitAndNeverAfterRollback() {
        Fixture fixture = seedFixture();
        UUID policyId = insertPolicy("S7-CAS-" + UUID.randomUUID());
        TenantContext.set(fixture.ownerScope());
        try {
            EffectiveQuotaPolicy initial = effectiveQuotaPolicyProvider.resolveTrustedTenant(fixture.ownerTenantId());
            assertThat(initial.assignmentVersion()).isEqualTo(1L);

            quotaPolicyAssignmentService.assign(fixture.ownerTenantId(), policyId, 1L);
            EffectiveQuotaPolicy assigned = effectiveQuotaPolicyProvider.resolveTrustedTenant(fixture.ownerTenantId());
            assertThat(assigned.policyId()).isEqualTo(policyId);
            assertThat(assigned.assignmentVersion()).isEqualTo(2L);

            transactionTemplate.executeWithoutResult(status -> {
                quotaPolicyAssignmentService.assign(fixture.ownerTenantId(), initial.policyId(), 2L);
                assertThat(effectiveQuotaPolicyProvider.resolveTrustedTenant(fixture.ownerTenantId()).policyId())
                        .isEqualTo(policyId);
                status.setRollbackOnly();
            });
            assertThat(effectiveQuotaPolicyProvider.resolveTrustedTenant(fixture.ownerTenantId()).policyId())
                    .isEqualTo(policyId);
            assertThat(assignmentVersion(fixture.ownerTenantId())).isEqualTo(2L);

            RuntimeQuotaPolicyCommand committed = command(31L);
            quotaPolicyCommandService.updateRuntimePolicy(policyId, 1L, committed);
            EffectiveQuotaPolicy updated = effectiveQuotaPolicyProvider.resolveTrustedTenant(fixture.ownerTenantId());
            assertThat(updated.policyVersion()).isEqualTo(2L);
            assertThat(updated.restApiReadRatePerSecond()).isEqualTo(31L);

            transactionTemplate.executeWithoutResult(status -> {
                quotaPolicyCommandService.updateRuntimePolicy(policyId, 2L, command(99L));
                assertThat(effectiveQuotaPolicyProvider.resolveTrustedTenant(fixture.ownerTenantId()).policyVersion())
                        .isEqualTo(2L);
                status.setRollbackOnly();
            });
            EffectiveQuotaPolicy afterRollback = effectiveQuotaPolicyProvider.resolveTrustedTenant(fixture.ownerTenantId());
            assertThat(afterRollback.policyVersion()).isEqualTo(2L);
            assertThat(afterRollback.restApiReadRatePerSecond()).isEqualTo(31L);
            assertThat(policyVersion(policyId)).isEqualTo(2L);

            assertThatThrownBy(() -> quotaPolicyAssignmentService.assign(fixture.ownerTenantId(), initial.policyId(), 1L))
                    .isInstanceOf(java.util.ConcurrentModificationException.class);
            assertThatThrownBy(() -> quotaPolicyCommandService.updateRuntimePolicy(policyId, 1L, command(40L)))
                    .isInstanceOf(java.util.ConcurrentModificationException.class);
        } finally {
            TenantContext.clear();
        }
    }

    /** @param readPerSecond 写入模板的读 REST 秒桶上限 @return 满足双层令牌桶约束的运行时命令 */
    private static RuntimeQuotaPolicyCommand command(long readPerSecond) {
        return new RuntimeQuotaPolicyCommand(10L, 20L, 1_000L, 60_000L,
                readPerSecond, 10L, 1_200L, 600L, 200L, 20L, 100L);
    }

    /** @param code 全局唯一套餐编码 @return 新增策略 ID */
    private UUID insertPolicy(String code) {
        UUID policyId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_quota_policy (id, code) VALUES (?, ?)", policyId,
                code.substring(0, Math.min(code.length(), 32)));
        policyIds.add(policyId);
        return policyId;
    }

    /** @return 真实 PostgreSQL 夹具，协作者与项目 owner 分属不同租户。 */
    private Fixture seedFixture() {
        UUID ownerTenantId = Uuid7.generate();
        UUID collaboratorTenantId = Uuid7.generate();
        UUID ownerAccountId = Uuid7.generate();
        UUID collaboratorAccountId = Uuid7.generate();
        UUID selectedProjectId = Uuid7.generate();
        UUID unselectedProjectId = Uuid7.generate();
        tenantIds.add(ownerTenantId);
        tenantIds.add(collaboratorTenantId);
        accountIds.add(ownerAccountId);
        accountIds.add(collaboratorAccountId);
        projectIds.add(selectedProjectId);
        projectIds.add(unselectedProjectId);
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", ownerTenantId, "S7-3 owner");
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", collaboratorTenantId, "S7-3 collaborator");
        jdbcTemplate.update("INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerAccountId, "s7-owner-" + ownerAccountId + "@example.com", "{noop}unused", "S7 owner");
        jdbcTemplate.update("INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)",
                collaboratorAccountId, "s7-collaborator-" + collaboratorAccountId + "@example.com", "{noop}unused", "S7 collaborator");
        jdbcTemplate.update("INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                Uuid7.generate(), ownerTenantId, ownerAccountId);
        jdbcTemplate.update("INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                Uuid7.generate(), collaboratorTenantId, collaboratorAccountId);
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, selectedProjectId, ownerTenantId, "S7-3 selected", projectKey(selectedProjectId));
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, unselectedProjectId, ownerTenantId, "S7-3 unselected", projectKey(unselectedProjectId));
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                Uuid7.generate(), selectedProjectId, ownerAccountId);
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'VIEWER')",
                Uuid7.generate(), selectedProjectId, collaboratorAccountId);
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                Uuid7.generate(), unselectedProjectId, ownerAccountId);
        return new Fixture(ownerTenantId, collaboratorTenantId, ownerAccountId, collaboratorAccountId,
                selectedProjectId, unselectedProjectId);
    }

    /** @param tenantId 租户 ID @return 数据库权威绑定版本 */
    private long assignmentVersion(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?",
                Long.class, tenantId);
    }

    /** @param policyId 策略 ID @return 数据库权威模板版本 */
    private long policyVersion(UUID policyId) {
        return jdbcTemplate.queryForObject("SELECT version FROM sys_quota_policy WHERE id = ?", Long.class, policyId);
    }

    /** @param projectId 项目 UUID @return 满足 MQTT project_key 字符集和全局唯一性的测试短标识 */
    private static String projectKey(UUID projectId) {
        return "s7" + projectId.toString().replace("-", "").substring(0, 16);
    }

    /** 夹具身份、项目所有权和已选项目范围。 */
    private record Fixture(UUID ownerTenantId, UUID collaboratorTenantId, UUID ownerAccountId,
                           UUID collaboratorAccountId, UUID selectedProjectId, UUID unselectedProjectId) {

        /** @return owner 在已选项目中的请求范围 */
        private TenantScope ownerScope() {
            return new TenantScope(ownerTenantId, selectedProjectId, ownerAccountId);
        }

        /** @return 跨租户协作者在 owner 项目中已选项目后的请求范围 */
        private TenantScope collaboratorScope() {
            return new TenantScope(collaboratorTenantId, selectedProjectId, collaboratorAccountId);
        }
    }
}
