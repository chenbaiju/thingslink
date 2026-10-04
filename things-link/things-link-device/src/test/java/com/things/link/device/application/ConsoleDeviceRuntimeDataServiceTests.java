package com.things.link.device.application;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/** Console设备运行编排的二次授权、真实tenant范围与结果透传测试。 */
@ExtendWith(MockitoExtension.class)
class ConsoleDeviceRuntimeDataServiceTests {
    /** 路径项目。 */ private final UUID projectId = UUID.randomUUID();
    /** 项目真实tenant。 */ private final UUID tenantId = UUID.randomUUID();
    /** 请求设备。 */ private final RuntimeDeviceQuery query =
            new RuntimeDeviceQuery(UUID.randomUUID(), UUID.randomUUID(), List.of("temperature"));
    /** 项目公开服务。 */ @Mock private ProjectService projects;
    /** 集中RLS范围组件。 */ @Mock private TransactionLocalRlsScope scope;
    /** 设备核心服务。 */ @Mock private DeviceRuntimeDataService devices;
    /** 模型目录核心。 */ @Mock private ConsoleDeviceCatalogService catalog;
    /** 被测Console编排。 */ private ConsoleDeviceRuntimeDataService service;

    /** 每例建立独立编排并准备真实项目归属。 */
    @BeforeEach
    void setUp() {
        service = new ConsoleDeviceRuntimeDataService(projects, scope, devices, catalog);
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(projects.requireProjectTenant(projectId)).thenReturn(tenantId);
    }

    /** 快照查询必须先复核成员，再以项目真实tenant建立双轴范围，最后进入设备SQL。 */
    @Test
    void snapshotsEstablishAuthoritativeProjectScopeBeforeDeviceRead() {
        RuntimeDeviceSnapshotResult expected = new RuntimeDeviceSnapshotResult(List.of(), List.of());
        when(devices.querySnapshots(projectId, List.of(query), List.of())).thenReturn(expected);

        assertThat(service.querySnapshots(projectId, List.of(query), List.of())).isSameAs(expected);

        InOrder order = inOrder(projects, scope, devices);
        order.verify(projects).requireRoleInProject(projectId);
        order.verify(projects).requireProjectTenant(projectId);
        order.verify(scope).establish(tenantId, projectId);
        order.verify(devices).querySnapshots(projectId, List.of(query), List.of());
    }

    /** 元数据发现同样先证明当前成员与项目真实tenant，不复用账号所属tenant。 */
    @Test
    void bindingMetadataEstablishesScopeBeforeDiscovery() {
        RuntimeDeviceSnapshotResult expected = new RuntimeDeviceSnapshotResult(List.of(), List.of());
        when(devices.bindingMetadata(projectId, query.deviceId())).thenReturn(expected);
        assertThat(service.bindingMetadata(projectId, query.deviceId())).isSameAs(expected);
        InOrder order = inOrder(projects, scope, devices);
        order.verify(projects).requireRoleInProject(projectId);
        order.verify(projects).requireProjectTenant(projectId);
        order.verify(scope).establish(tenantId, projectId);
        order.verify(devices).bindingMetadata(projectId, query.deviceId());
    }

    /** 跨租户协作者按项目真实tenant建立RLS，游标主体保持当前账号。 */
    @Test
    void catalogUsesAuthoritativeTenantAndCurrentActorAfterMembershipCheck() {
        UUID actor = UUID.randomUUID(), model = UUID.randomUUID();
        com.things.link.shared.tenant.TenantContext.set(new com.things.link.shared.tenant.TenantScope(
                UUID.randomUUID(), projectId, actor));
        try {
            var result = new ConsoleDeviceCatalogService.Page(List.of(), null, false);
            when(catalog.query(tenantId, projectId, actor, model, null, 20)).thenReturn(result);
            assertThat(service.catalog(projectId, model, null, 20)).isSameAs(result);
            InOrder order = inOrder(projects, scope, catalog);
            order.verify(projects).requireRoleInProject(projectId);
            order.verify(projects).requireProjectTenant(projectId);
            order.verify(scope).establish(tenantId, projectId);
            order.verify(catalog).query(tenantId, projectId, actor, model, null, 20);
        } finally {
            com.things.link.shared.tenant.TenantContext.clear();
        }
    }

    /** 当前值查询沿用同一授权与范围顺序，不能让核心服务自行猜测tenant。 */
    @Test
    void currentValuesEstablishAuthoritativeProjectScopeBeforeDeviceRead() {
        RuntimeDeviceCurrentResult expected = new RuntimeDeviceCurrentResult(List.of());
        when(devices.queryCurrentValues(projectId, List.of(query))).thenReturn(expected);

        assertThat(service.queryCurrentValues(projectId, List.of(query))).isSameAs(expected);

        InOrder order = inOrder(projects, scope, devices);
        order.verify(projects).requireRoleInProject(projectId);
        order.verify(projects).requireProjectTenant(projectId);
        order.verify(scope).establish(tenantId, projectId);
        order.verify(devices).queryCurrentValues(projectId, List.of(query));
    }
}
