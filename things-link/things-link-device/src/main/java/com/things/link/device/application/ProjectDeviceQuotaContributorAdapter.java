package com.things.link.device.application;

import com.things.link.project.application.ProjectDeviceQuotaContributor;
import com.things.link.project.application.ProjectService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * device 域对项目配额页面暴露的设备存量贡献实现。
 *
 * <p>协作者的 JWT tenantId 是他自己的租户，而项目归属租户可能是所有者的租户。先由
 * {@link ProjectService} 在成员事实中取得持久owner租户，再在当前事务建立完整范围；device
 * 受限函数仍只读取当前范围。这样 project 既不读 {@code dev_device}，也不会把所有者 tenantId
 * 暴露给控制台或由客户端传入。
 */
@Service
public class ProjectDeviceQuotaContributorAdapter implements ProjectDeviceQuotaContributor {

    /** 项目成员授权的公开应用端口。 */
    private final ProjectService projectService;
    /** 与当前事务绑定的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** 以项目成员查询返回的owner租户建立完整事务局部RLS范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /**
     * @param projectService 项目公开应用端口
     * @param jdbcTemplate JDBC 访问器
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     */
    public ProjectDeviceQuotaContributorAdapter(ProjectService projectService, JdbcTemplate jdbcTemplate,
                                                TransactionLocalRlsScope transactionLocalRlsScope) {
        this.projectService = projectService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ProjectDeviceQuotaUsage countActiveDevices(UUID projectId) {
        // 成员查询同时返回持久owner租户，避免把协作者JWT tenant误作项目归属或由客户端补tenant。
        UUID ownerTenantId = projectService.requireProjectTenant(projectId);
        transactionLocalRlsScope.establish(ownerTenantId, projectId);

        return jdbcTemplate.queryForObject("SELECT * FROM project_device_quota_usage()",
                (resultSet, rowNumber) -> new ProjectDeviceQuotaUsage(
                        resultSet.getLong("project_used_value"),
                        resultSet.getLong("tenant_used_value")));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ProjectDeviceQuotaUsage countActiveDevices(UUID trustedTenantId, UUID trustedProjectId) {
        return jdbcTemplate.query("SELECT * FROM public.project_device_quota_usage(?, ?)",
                (resultSet, rowNumber) -> new ProjectDeviceQuotaUsage(
                        resultSet.getLong("project_used_value"),
                        resultSet.getLong("tenant_used_value")),
                trustedTenantId, trustedProjectId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("设备确权租户与项目归属不匹配或策略无效"));
    }
}
