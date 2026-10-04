package com.things.link.device.application;

import com.things.link.project.application.ProjectDeviceQuotaContributor.ProjectDeviceQuotaUsage;
import com.things.link.project.application.ProjectService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证项目设备配额贡献器从成员事实取得owner租户后建立完整范围。 */
class ProjectDeviceQuotaContributorAdapterTests {

    /** S12-2a1d：成员归属、范围建立和受RLS统计必须保持固定顺序。 */
    @Test
    @SuppressWarnings("unchecked")
    void establishesPersistedOwnerScopeBeforeCountingDevices() {
        UUID ownerTenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ProjectService projectService = mock(ProjectService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionLocalRlsScope rlsScope = mock(TransactionLocalRlsScope.class);
        ProjectDeviceQuotaUsage usage = new ProjectDeviceQuotaUsage(3, 8);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenantId);
        when(jdbcTemplate.queryForObject(eq("SELECT * FROM project_device_quota_usage()"),
                any(RowMapper.class))).thenReturn(usage);
        ProjectDeviceQuotaContributorAdapter adapter = new ProjectDeviceQuotaContributorAdapter(
                projectService, jdbcTemplate, rlsScope);

        ProjectDeviceQuotaUsage actual = adapter.countActiveDevices(projectId);

        assertThat(actual).isEqualTo(usage);
        InOrder order = inOrder(projectService, rlsScope, jdbcTemplate);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(rlsScope).establish(ownerTenantId, projectId);
        order.verify(jdbcTemplate).queryForObject(eq("SELECT * FROM project_device_quota_usage()"),
                any(RowMapper.class));
    }
}
