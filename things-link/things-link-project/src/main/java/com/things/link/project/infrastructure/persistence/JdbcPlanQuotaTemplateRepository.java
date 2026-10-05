package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.plan.PlanQuotaTemplate;
import com.things.link.project.domain.plan.PlanRuntimeDefaults;
import com.things.link.project.domain.plan.PlanQuotaTemplateRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 以 PostgreSQL 物化并读取 S14-1b 产品修订版的冻结配额模板。
 *
 * <p>写入端只有 {@code INSERT ... ON CONFLICT (code) DO NOTHING} 与一次性绑定：已存在的模板行
 * 一个字段都不改，漂移由播种后的逐值校验显式失败暴露；绑定只允许从空补值，已绑定的修订版
 * 若指向另一模板则直接失败，绝不原地改绑。
 *
 * <p>模板行是平台级事实（{@code plan_template = true}），不含租户列、不走 RLS；它与
 * {@code sys_plan_revision_dimension}（冻结文本）由播种用例逐值比对。
 */
@Repository
public class JdbcPlanQuotaTemplateRepository implements PlanQuotaTemplateRepository {

    /** JDBC 数据库访问模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcPlanQuotaTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void lockSeed(String revision) {
        // 与目录播种使用不同键，避免两个播种事务互相阻塞；锁只在本次事务内持有。
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                resultSet -> null, "plan-quota-template:" + revision);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Map<String, PlanQuotaTemplate> findByRevision(String revision) {
        Map<String, PlanQuotaTemplate> templates = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT p.code AS plan_code, q.projects_max, q.device_count_limit, q.end_users_max,
                       q.dashboards_max, q.external_collaborator_seats, q.history_window_unit,
                       q.history_window_amount, q.uplink_message_daily_limit,
                       q.downlink_message_daily_limit, q.rest_api_call_daily_limit,
                       q.rest_api_rate_per_minute, q.websocket_connection_limit,
                       q.rule_tenant_concurrency_limit, q.script_execution_daily_limit,
                       q.script_cpu_millis_daily_limit, q.notification_delivery_daily_limit,
                       q.storage_bytes_limit
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                  JOIN sys_quota_policy q ON q.id = r.quota_policy_id
                 WHERE r.revision_code = ?
                   AND q.plan_template
                 ORDER BY p.display_order
                """, resultSet -> {
            while (resultSet.next()) {
                templates.put(resultSet.getString("plan_code"), new PlanQuotaTemplate(
                        resultSet.getLong("projects_max"),
                        resultSet.getLong("device_count_limit"),
                        resultSet.getLong("end_users_max"),
                        resultSet.getLong("dashboards_max"),
                        resultSet.getLong("external_collaborator_seats"),
                        resultSet.getString("history_window_unit"),
                        resultSet.getLong("history_window_amount"),
                        resultSet.getLong("uplink_message_daily_limit"),
                        resultSet.getLong("downlink_message_daily_limit"),
                        resultSet.getLong("rest_api_call_daily_limit"),
                        resultSet.getLong("rest_api_rate_per_minute"),
                        resultSet.getLong("websocket_connection_limit"),
                        resultSet.getLong("rule_tenant_concurrency_limit"),
                        resultSet.getLong("script_execution_daily_limit"),
                        resultSet.getLong("script_cpu_millis_daily_limit"),
                        resultSet.getLong("notification_delivery_daily_limit"),
                        resultSet.getLong("storage_bytes_limit")));
            }
            return templates;
        }, revision);
        return templates;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID ensureTemplate(String planCode, String policyCode, PlanQuotaTemplate template) {
        jdbcTemplate.update("""
                INSERT INTO sys_quota_policy (
                    id, code, version, plan_template, projects_max, device_count_limit, end_users_max,
                    dashboards_max, external_collaborator_seats, history_window_unit,
                    history_window_amount, uplink_message_daily_limit, downlink_message_daily_limit,
                    rest_api_call_daily_limit, rest_api_rate_per_minute, rest_api_write_rate_per_minute,
                    websocket_connection_limit, rule_tenant_concurrency_limit,
                    script_execution_daily_limit, script_cpu_millis_daily_limit,
                    notification_delivery_daily_limit, storage_bytes_limit,
                    -- S14-6f：运行时保护与安全默认值必须显式写入。留 NULL 等于「不限」，会给每个绑定该模板的
                    -- 租户关掉上行限流、任务派发与规则队列容量、字节与点位日额度（见 PlanRuntimeDefaults）。
                    uplink_device_refill_per_second, uplink_device_burst_capacity,
                    uplink_tenant_per_second_limit, uplink_tenant_per_minute_limit,
                    rest_api_read_rate_per_second, rest_api_write_rate_per_second,
                    rest_api_read_rate_per_minute,
                    task_project_dispatch_per_second, task_tenant_dispatch_per_second,
                    rule_tenant_queue_capacity, rule_project_queue_capacity,
                    uplink_bytes_daily_limit, time_series_point_daily_limit)
                VALUES (?, ?, 1, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, Uuid7.generate(), policyCode,
                template.projectsMax(), template.devicesMax(), template.endUsersMax(),
                template.dashboardsMax(), template.externalCollaboratorSeats(),
                template.historyWindowUnit(), template.historyWindowAmount(),
                template.uplinkMessageDailyLimit(), template.downlinkMessageDailyLimit(),
                template.restApiWriteDailyLimit(), template.restApiRatePerMinute(),
                template.restApiRatePerMinute(), template.websocketConnectionLimit(),
                template.scriptRuleConcurrency(), template.scriptRuleExecutionDailyLimit(),
                template.scriptRuleCpuMillisDailyLimit(), template.notificationDeliveryDailyLimit(),
                template.storageBytesLimit(),
                PlanRuntimeDefaults.UPLINK_DEVICE_REFILL_PER_SECOND,
                PlanRuntimeDefaults.UPLINK_DEVICE_BURST_CAPACITY,
                PlanRuntimeDefaults.UPLINK_TENANT_PER_SECOND_LIMIT,
                PlanRuntimeDefaults.UPLINK_TENANT_PER_MINUTE_LIMIT,
                PlanRuntimeDefaults.REST_API_READ_RATE_PER_SECOND,
                PlanRuntimeDefaults.REST_API_WRITE_RATE_PER_SECOND,
                template.restApiRatePerMinute(),
                PlanRuntimeDefaults.TASK_PROJECT_DISPATCH_PER_SECOND,
                PlanRuntimeDefaults.TASK_TENANT_DISPATCH_PER_SECOND,
                PlanRuntimeDefaults.RULE_TENANT_QUEUE_CAPACITY,
                PlanRuntimeDefaults.RULE_PROJECT_QUEUE_CAPACITY,
                PlanRuntimeDefaults.UPLINK_BYTES_DAILY_LIMIT,
                PlanRuntimeDefaults.TIME_SERIES_POINT_DAILY_LIMIT);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = ?", UUID.class, policyCode);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void linkIfAbsent(String revision, String planCode, UUID policyId) {
        int updated = jdbcTemplate.update("""
                UPDATE sys_plan_revision r
                   SET quota_policy_id = ?
                  FROM sys_plan p
                 WHERE r.plan_id = p.id
                   AND p.code = ?
                   AND r.revision_code = ?
                   AND r.quota_policy_id IS NULL
                """, policyId, planCode, revision);
        if (updated > 0) {
            return;
        }
        UUID current = jdbcTemplate.query("""
                        SELECT r.quota_policy_id
                          FROM sys_plan_revision r
                          JOIN sys_plan p ON p.id = r.plan_id
                         WHERE p.code = ? AND r.revision_code = ?
                        """, (resultSet, rowNumber) ->
                        resultSet.getObject("quota_policy_id", UUID.class), planCode, revision)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "产品修订版不存在，无法绑定配额模板: " + revision + "/" + planCode));
        if (!policyId.equals(current)) {
            throw new IllegalStateException(
                    "产品修订版已绑定另一配额模板，禁止原地改绑: " + revision + "/" + planCode);
        }
    }
}
