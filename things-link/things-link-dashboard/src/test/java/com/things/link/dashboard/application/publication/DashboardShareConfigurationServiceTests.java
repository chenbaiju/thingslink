package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 配置发现仍先管理授权和真实RLS，不把测试宿主替身宣称生产制品已核验。 */
class DashboardShareConfigurationServiceTests {
    /** 已存在可写目录满足运行配置本身的显式启动前置。 */ @TempDir Path log;
    /** 当前项目。 */ private final UUID project = UUID.randomUUID();
    /** 项目真实租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 已选择看板。 */ private final UUID dashboard = UUID.randomUUID();
    /** 项目授权替身。 */ private final ProjectService projects = mock(ProjectService.class);
    /** 双轴RLS替身。 */ private final TransactionLocalRlsScope rls = mock(TransactionLocalRlsScope.class);
    /** 本领域可见目录替身。 */ private final DashboardRepository dashboards = mock(DashboardRepository.class);
    /** 同一受管制品资格出口替身。 */ private final DashboardHostQualificationPort hosts = mock(DashboardHostQualificationPort.class);

    /** 启用开关不改变管理授权顺序。 */
    private DashboardShareConfigurationService service(boolean enabled) {
        return new DashboardShareConfigurationService(projects, rls, dashboards,
                new DashboardShareRuntimeProperties(enabled, "https://app.example.com", false, log.toString()), hosts);
    }

    /** 当前管理员在普通RLS中找到看板；不构造任何发布或签发操作。 */
    private void visible() {
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.ADMIN);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        when(dashboards.find(project, dashboard)).thenReturn(Optional.of(mock(DashboardCatalogEntry.class)));
    }

    /** 构造已被资格出口确认的最小描述，不向API返回这些内部清单。 */
    private DashboardHostQualificationDescriptor host(String version) {
        return new DashboardHostQualificationDescriptor("tc.webapp-host/v1", version,
                Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"), Map.of(), Map.of());
    }

    /** 只有角色、真实tenant及看板读取完成后才访问部署信息。 */
    @Test
    void verifiesManagementAndRlsBeforeReadingHost() {
        visible(); when(hosts.current()).thenReturn(Optional.of(host("1.0.0")));
        var value = service(true).read(project, dashboard);
        assertThat(value.available()).isTrue();
        assertThat(value.hostOrigin()).isEqualTo("https://app.example.com");
        assertThat(value.hostCompatibility()).isEqualTo(new DashboardShareConfiguration.HostCompatibility("1.0.0", "1.0.1"));
        var ordered = inOrder(projects, rls, dashboards, hosts);
        ordered.verify(projects).requireRoleInProject(project);
        ordered.verify(projects).requireProjectTenant(project);
        ordered.verify(rls).establish(tenant, project);
        ordered.verify(dashboards).find(project, dashboard);
        ordered.verify(hosts).current();
    }

    /** 普通成员不能从配置发现获得share管理能力。 */
    @Test
    void rejectsViewerAndOperatorBeforeDeploymentReads() {
        for (ProjectRole role : new ProjectRole[] {ProjectRole.VIEWER, ProjectRole.OPERATOR}) {
            when(projects.requireRoleInProject(project)).thenReturn(role);
            assertThatThrownBy(() -> service(true).read(project, dashboard))
                    .isInstanceOf(com.things.link.shared.error.BusinessException.class);
        }
        verifyNoInteractions(rls, dashboards, hosts);
    }

    /** 错项目或软删看板与不存在统一隐藏，不能用禁用状态绕开确权。 */
    @Test
    void rejectsInvisibleDashboardEvenWhenRuntimeDisabled() {
        visible(); when(dashboards.find(project, dashboard)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(false).read(project, dashboard))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
        verifyNoInteractions(hosts);
    }

    /** disabled和无资格均全null；disabled不需要读取磁盘制品。 */
    @Test
    void unavailableNeverReturnsPartialOriginOrVersion() {
        visible(); assertThat(service(false).read(project, dashboard)).isEqualTo(DashboardShareConfiguration.unavailable());
        verifyNoInteractions(hosts);
        when(hosts.current()).thenReturn(Optional.empty());
        assertThat(service(true).read(project, dashboard)).isEqualTo(DashboardShareConfiguration.unavailable());
    }

    /** 宿主端口已有首因必须原样传播，不作为可用性false吞掉。 */
    @Test
    void preservesInfrastructureCause() {
        visible(); var failure = new IllegalStateException("host infrastructure failed");
        when(hosts.current()).thenThrow(failure);
        assertThatThrownBy(() -> service(true).read(project, dashboard)).isSameAs(failure);
    }

    /** SemVer按数值进位，最大三段值无可表达半开上界，不签发虚假范围。 */
    @Test
    void carriesPatchMinorAndRejectsMaximumVersionRange() {
        visible(); var service = service(true);
        when(hosts.current()).thenReturn(Optional.of(host("1.2.65535")), Optional.of(host("1.65535.65535")), Optional.of(host("65535.65535.65535")));
        assertThat(service.read(project, dashboard).hostCompatibility().maxExclusive()).isEqualTo("1.3.0");
        assertThat(service.read(project, dashboard).hostCompatibility().maxExclusive()).isEqualTo("2.0.0");
        assertThat(service.read(project, dashboard)).isEqualTo(DashboardShareConfiguration.unavailable());
    }

    /** 非法描述符属于端口完整性故障，不偷偷改写成合法范围。 */
    @Test
    void refusesMalformedHostVersions() {
        visible(); var service = service(true);
        for (String value : new String[] {"01.0.0", "1.0.0-rc1", "1.0.65536", "1.2", "999999999999.0.0"}) {
            when(hosts.current()).thenReturn(Optional.of(host(value)));
            assertThatThrownBy(() -> service.read(project, dashboard)).isInstanceOf(IllegalStateException.class);
        }
    }
}
