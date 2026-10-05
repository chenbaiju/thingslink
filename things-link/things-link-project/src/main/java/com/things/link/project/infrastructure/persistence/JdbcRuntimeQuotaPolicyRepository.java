package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.RuntimeQuotaPolicyCommand;
import com.things.link.project.application.QuotaPolicyTemplateChanged;
import com.things.link.project.domain.RuntimeQuotaPolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 以一个 PostgreSQL CTE 完成策略 CAS 更新和受影响租户版本投影。
 */
@Repository
public class JdbcRuntimeQuotaPolicyRepository implements RuntimeQuotaPolicyRepository {

    /** JDBC 数据库访问模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcRuntimeQuotaPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<QuotaPolicyTemplateChanged> update(UUID policyId, long expectedPolicyVersion,
                                                        RuntimeQuotaPolicyCommand command) {
        if (expectedPolicyVersion <= 0) {
            throw new IllegalArgumentException("期望的配额策略版本必须为正数");
        }
        return jdbcTemplate.query("""
                        UPDATE sys_quota_policy
                           SET uplink_device_refill_per_second = ?,
                               uplink_device_burst_capacity = ?,
                               uplink_tenant_per_second_limit = ?,
                               uplink_tenant_per_minute_limit = ?,
                               rest_api_read_rate_per_second = ?,
                               rest_api_write_rate_per_second = ?,
                               rest_api_read_rate_per_minute = ?,
                               rest_api_write_rate_per_minute = ?,
                               websocket_connection_limit = ?,
                               task_project_dispatch_per_second = ?,
                               task_tenant_dispatch_per_second = ?,
                               rule_tenant_concurrency_limit = ?,
                               rule_tenant_queue_capacity = ?,
                               rule_project_queue_capacity = ?,
                               daily_soft_limit_basis_points = ?,
                               daily_degrade_basis_points = ?,
                               version = version + 1,
                               updated_at = now()
                         WHERE id = ? AND version = ?
                     RETURNING id, version
                        """, (resultSet, rowNumber) -> new QuotaPolicyTemplateChanged(
                        resultSet.getObject("id", UUID.class), resultSet.getLong("version")),
                command.uplinkDeviceRefillPerSecond(), command.uplinkDeviceBurstCapacity(),
                command.uplinkTenantPerSecondLimit(), command.uplinkTenantPerMinuteLimit(),
                command.restApiReadRatePerSecond(), command.restApiWriteRatePerSecond(),
                command.restApiReadRatePerMinute(), command.restApiWriteRatePerMinute(),
                command.websocketConnectionLimit(), command.taskProjectDispatchPerSecond(),
                command.taskTenantDispatchPerSecond(), command.ruleTenantConcurrencyLimit(),
                command.ruleTenantQueueCapacity(), command.ruleProjectQueueCapacity(), command.dailySoftLimitBasisPoints(),
                command.dailyDegradeBasisPoints(), policyId, expectedPolicyVersion).stream().findFirst();
    }
}
