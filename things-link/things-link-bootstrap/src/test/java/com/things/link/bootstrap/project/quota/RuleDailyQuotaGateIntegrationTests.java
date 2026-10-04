package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.queue.ProjectRuleQuotaGate;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionKey;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R4-4b真实PG规则日额度门禁；只验证生产gate读取租户共享快照的准入结果。
 * 不运行脚本/队列，不把构造的不可变信封称为真实上行链，也不声称逐条原子N+1或跨日资格。
 */
class RuleDailyQuotaGateIntegrationTests extends AbstractIntegrationTest {
    @Autowired private ProjectRuleQuotaGate gate;
    @Autowired private ProjectDailyQuotaDecisionService decisions;
    @Autowired private ObjectMapper mapper;

    private final UUID tenant = Uuid7.generate();
    private final UUID otherTenant = Uuid7.generate();
    private final UUID sourceProject = Uuid7.generate();
    private final UUID admittedProject = Uuid7.generate();
    private final UUID otherProject = Uuid7.generate();
    private final UUID policy = Uuid7.generate();
    private final UUID otherPolicy = Uuid7.generate();
    private JdbcTemplate owner;

    /** 单独耗尽次数或CPU均必须拒绝，另一项不限不能覆盖拒绝；同owner跨项目共享且其他租户独立。 */
    @ParameterizedTest(name = "{0}: limit={1}, used={2}, status={3}, allowed={4}")
    @CsvSource({
            "SCRIPT_EXECUTION, 0, 0, HARD_LIMIT, false",
            "SCRIPT_EXECUTION, 10, 8, SOFT_LIMIT, true",
            "SCRIPT_EXECUTION, 10, 10, HARD_LIMIT, false",
            "SCRIPT_EXECUTION, 10, 12, DEGRADED, false",
            "SCRIPT_CPU_MILLIS, 0, 0, HARD_LIMIT, false",
            "SCRIPT_CPU_MILLIS, 10, 8, SOFT_LIMIT, true",
            "SCRIPT_CPU_MILLIS, 10, 10, HARD_LIMIT, false",
            "SCRIPT_CPU_MILLIS, 10, 12, DEGRADED, false"
    })
    void realSharedSnapshotControlsExecutionAndCpuSeparately(
            QuotaMetric metric, long limit, long used, QuotaStatus expected, boolean allowed) {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        seedPolicy(policy, metric, limit);
        seedPolicy(otherPolicy, metric, 10L);
        seedTenant(tenant, policy);
        seedTenant(otherTenant, otherPolicy);
        seedProject(sourceProject, tenant);
        seedProject(admittedProject, tenant);
        seedProject(otherProject, otherTenant);
        if (used > 0) owner.update("""
                INSERT INTO sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value)
                VALUES (?,?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,?,?)
                """, Uuid7.generate(), tenant, sourceProject, metric.name(), used);
        for (QuotaMetric checked : List.of(QuotaMetric.SCRIPT_EXECUTION, QuotaMetric.SCRIPT_CPU_MILLIS)) {
            boolean selected = checked == metric;
            var fact = owner.queryForMap("""
                    SELECT limit_value,tenant_used_value FROM trusted_project_daily_quota_decision(
                        ?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,?)
                    """, tenant, admittedProject, checked.name());
            assertThat(fact.get("limit_value")).isEqualTo(selected ? Long.valueOf(limit) : null);
            assertThat(((Number) fact.get("tenant_used_value")).longValue()).isEqualTo(selected ? used : 0L);
            var actual = decisions.decisionTrustedProject(tenant, admittedProject, checked);
            assertThat(actual.status()).isEqualTo(selected ? expected : QuotaStatus.NORMAL);
            assertThat(actual.disabled()).isEqualTo(selected && limit == 0);
        }
        var initialCounters = counters();
        RuleExecutionEnvelope envelope = envelope(tenant, admittedProject);
        assertThat(gate.allows(envelope)).isEqualTo(allowed);
        assertThat(gate.allows(envelope.nextAttempt(Instant.now()))).isEqualTo(allowed);
        assertThat(gate.allows(envelope(otherTenant, otherProject)))
                .as("其他租户不会继承当前租户日量").isTrue();
        assertThatThrownBy(() -> gate.allows(envelope(otherTenant, admittedProject)))
                .as("错误owner/project不可借用其他租户剩余额度")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counters()).as("门禁和重试只读快照，不能在准入查询中重复计量").isEqualTo(initialCounters);
        assertThat(TenantContext.current()).isEmpty();
    }

    private void seedPolicy(UUID id, QuotaMetric metric, long limit) {
        owner.update("""
                INSERT INTO sys_quota_policy(id,code,script_execution_daily_limit,script_cpu_millis_daily_limit)
                VALUES (?,?,?,?)
                """, id, "rq" + id.toString().replace("-", "").substring(0, 20),
                metric == QuotaMetric.SCRIPT_EXECUTION ? limit : null,
                metric == QuotaMetric.SCRIPT_CPU_MILLIS ? limit : null);
    }

    private void seedTenant(UUID id, UUID policyId) {
        owner.update("INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES (?,'rule-quota',?)", id, policyId);
    }

    private void seedProject(UUID id, UUID tenantId) {
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'rule-quota','sh-1',?)",
                id, tenantId, "rule-quota-" + id);
    }

    private RuleExecutionEnvelope envelope(UUID tenantId, UUID projectId) {
        UUID messageId = Uuid7.generate();
        var key = new RuleExecutionKey(projectId, messageId, Uuid7.generate(), Uuid7.generate());
        var message = new RuleMessage(messageId, tenantId, projectId, Uuid7.generate(), "quota-gate-test",
                Instant.now(), "PROPERTY_REPORT", mapper.createObjectNode(), Map.of());
        return new RuleExecutionEnvelope(key, tenantId, message, 1, Instant.now());
    }

    private List<Map<String, Object>> counters() {
        return owner.queryForList("SELECT * FROM sys_usage_counter_daily WHERE tenant_id IN (?,?) ORDER BY id", tenant, otherTenant);
    }

    @AfterEach
    void cleanupOwnedFixtures() {
        try {
            if (owner != null) {
                owner.update("DELETE FROM sys_usage_counter_daily WHERE tenant_id IN (?,?)", tenant, otherTenant);
                owner.update("DELETE FROM sys_project WHERE id IN (?,?,?)", sourceProject, admittedProject, otherProject);
                owner.update("DELETE FROM sys_tenant WHERE id IN (?,?)", tenant, otherTenant);
                owner.update("DELETE FROM sys_quota_policy WHERE id IN (?,?)", policy, otherPolicy);
            }
        } finally {
            TenantContext.clear();
        }
    }
}
