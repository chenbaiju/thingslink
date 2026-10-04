package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** S12-4d管理配置发现只读用例；权限、项目真实归属和看板可见性先于部署信息读取。 */
@Service
public class DashboardShareConfigurationService {
    /** 项目角色和真实归属公开端口。 */
    private final ProjectService projects;
    /** 当前普通事务的双轴RLS范围。 */
    private final TransactionLocalRlsScope rls;
    /** 本领域可见看板目录。 */
    private final DashboardRepository dashboards;
    /** 启用时构造器已经校验精确Origin及安全日志目录。 */
    private final DashboardShareRuntimeProperties runtime;
    /** 每次实际核验受管制品的资格端口，不读取未经验证的配置版本。 */
    private final DashboardHostQualificationPort hosts;

    /** 创建独立读取编排，不改变签发服务既有依赖和权限边界。 */
    public DashboardShareConfigurationService(ProjectService projects, TransactionLocalRlsScope rls,
            DashboardRepository dashboards, DashboardShareRuntimeProperties runtime,
            DashboardHostQualificationPort hosts) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.rls = Objects.requireNonNull(rls, "rls");
        this.dashboards = Objects.requireNonNull(dashboards, "dashboards");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
    }

    /**
     * ARCHIVED沿现有分享历史的管理只读规则，不调用写生命周期许可。
     * @param projectId 已选择项目
     * @param dashboardId 已存在且可见的看板
     * @return 最小公开宿主配置；不得据此绕过签发时的完整资格复验
     */
    @Transactional(readOnly = true)
    public DashboardShareConfiguration read(UUID projectId, UUID dashboardId) {
        ProjectRole role = projects.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);
        }
        UUID tenantId = projects.requireProjectTenant(projectId);
        rls.establish(tenantId, projectId);
        if (dashboards.find(projectId, dashboardId).isEmpty()) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_NOT_FOUND);
        }
        if (!runtime.enabled()) return DashboardShareConfiguration.unavailable();
        // 适配器已定义空值和损坏制品的诊断语义；不捕获端口异常伪装成未启用。
        var host = hosts.current();
        if (host.isEmpty()) return DashboardShareConfiguration.unavailable();
        String version = host.orElseThrow().hostVersion();
        String upper = successor(version);
        if (upper == null) return DashboardShareConfiguration.unavailable();
        return new DashboardShareConfiguration(true, runtime.hostOrigin(), version,
                new DashboardShareConfiguration.HostCompatibility(version, upper));
    }

    /** 三段整数限定0..65535；进位不会扩大到另外一个合法稳定版本，最大值无可表达上界。 */
    private static String successor(String version) {
        if (!version.matches("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})")) {
            throw new IllegalStateException("受管宿主返回非法稳定版本");
        }
        String[] values = version.split("\\.");
        int[] parts = new int[3];
        for (int index = 0; index < parts.length; index++) {
            parts[index] = Integer.parseInt(values[index]);
            if (parts[index] > 65535) throw new IllegalStateException("受管宿主版本段超过65535");
        }
        for (int index = parts.length - 1; index >= 0; index--) {
            if (parts[index] < 65535) {
                parts[index]++;
                return parts[0] + "." + parts[1] + "." + parts[2];
            }
            parts[index] = 0;
        }
        return null;
    }
}
