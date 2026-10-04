package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.QuotaMetric;
import com.things.link.project.domain.QuotaOverviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过数据库受限函数读取项目配额概览。
 *
 * <p>不能把函数体复制到这里：该函数是跨租户协作者读取“所属租户共享池”时唯一有意绕过
 * tenant RLS 的位置。Java SQL 一旦直接 join 计费表，要么被 RLS 拦截，要么有人会试图
 * 删除策略来“修复”协作问题。
 */
@Repository
public class JdbcQuotaOverviewRepository implements QuotaOverviewRepository {

    /** 已受 TenantAwareDataSource 管理的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 当前请求带 RLS 会话变量的 JDBC 访问器 */
    public JdbcQuotaOverviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public List<QuotaMetricUsageRow> findDailyUsage(LocalDate usageDate) {
        return jdbcTemplate.query("SELECT * FROM project_quota_overview(?)",
                (resultSet, rowNumber) -> new QuotaMetricUsageRow(
                        resultSet.getString("policy_code"),
                        resultSet.getLong("policy_version"),
                        resultSet.getObject("device_limit_value", Long.class),
                        QuotaMetric.valueOf(resultSet.getString("metric")),
                        resultSet.getObject("limit_value", Long.class),
                        resultSet.getLong("project_used_value"),
                        resultSet.getLong("tenant_used_value"),
                        resultSet.getInt("daily_soft_limit_basis_points"),
                        resultSet.getInt("daily_degrade_basis_points")),
                usageDate);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DeviceQuotaPolicyRow> findDeviceQuotaPolicy(UUID trustedTenantId, UUID trustedProjectId) {
        return jdbcTemplate.query("SELECT * FROM public.device_project_quota_policy(?, ?)",
                (resultSet, rowNumber) -> new DeviceQuotaPolicyRow(
                        resultSet.getObject("device_limit_value", Long.class),
                        resultSet.getInt("soft_limit_basis_points"),
                        resultSet.getInt("degrade_basis_points")),
                trustedTenantId, trustedProjectId).stream().findFirst();
    }
}
