package com.things.link.ingestion.application;

import com.things.link.dashboard.application.DashboardShareRealtimeAccessService;
import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppRealtimeAccessService;
import com.things.link.enduser.application.AppRealtimeAlarmSubscription;
import java.util.List;
import com.things.link.enduser.application.WebAppRuntimeContext;
import com.things.link.project.application.AccountDirectory;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 数据运行合同§6：每次实际发送前，以不可变身份重建范围并一次核验全部订阅设备。 */
@Service
public class DashboardRealtimeAccessService {
    /** App域独占用户、角色与绑定事实。 */
    private final AppRealtimeAccessService apps;
    /** Console当前账号资格出口。 */
    private final AccountDirectory accounts;
    /** 项目成员与真实归属端口。 */
    private final ProjectService projects;
    /** 删除恢复不能复活旧连接。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 当前事务唯一建立真实租户/项目轴。 */
    private final TransactionLocalRlsScope scope;
    /** 一次有界读取设备集合，不逐设备SQL。 */
    private final AppDeviceDataPlaneService devices;

    /** 分享域独占固定版本、能力scope与真实设备模型复验。 */
    private final DashboardShareRealtimeAccessService shares;

    /** 装配公开领域端口；不跨域访问表或基础设施。 */
    public DashboardRealtimeAccessService(AppRealtimeAccessService apps, AccountDirectory accounts,
            ProjectService projects, ProjectLifecycleAccessService lifecycle,
            TransactionLocalRlsScope scope, AppDeviceDataPlaneService devices, DashboardShareRealtimeAccessService shares) {
        this.apps = apps;
        this.accounts = accounts;
        this.projects = projects;
        this.lifecycle = lifecycle;
        this.scope = scope;
        this.devices = devices;
        this.shares = shares;
    }

    /** 注册时再次复验身份；握手与注册之间的撤权也必须拒绝。 */
    public void requireIdentity(DashboardRealtimePrincipal principal) {
        requireUnexpired(principal);
        if (principal.share()) shares.requireIdentity(principal.sharePrincipal());
        else if (principal.app()) apps.validateIdentity(appIdentity(principal));
        else if (!accounts.isActive(principal.subjectId())) throw denied();
    }

    /**
     * 独立事务核验所有设备并返回绑定真实项目归属与代次的连接身份。
     * App范围由App公开端口建立；Console成员先于真实tenant解析，随后才允许设备读取。
     * @param principal 已验签或已经订阅的冻结身份
     * @param projectId 待选择或已冻结项目
     * @param subscriptions 至多20个设备及其键，不能以部分成功缩小原订阅
     * @param context App必须携带精确运行上下文，Console为空
     * @return 含真实项目归属和冻结代次的身份
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public DashboardRealtimePrincipal requireDevices(DashboardRealtimePrincipal principal,
                                                      UUID projectId, Map<UUID, Set<String>> subscriptions, WebAppRuntimeContext context) {
        Set<UUID> deviceIds = subscriptions == null ? null : subscriptions.keySet();
        requireUnexpired(principal);
        if (projectId == null || deviceIds == null || deviceIds.isEmpty() || deviceIds.size() > 20
                || deviceIds.stream().anyMatch(java.util.Objects::isNull)) throw denied();
        if (principal.projectId() != null && !principal.projectId().equals(projectId)) throw denied();
        if (principal.share()) throw denied();
        if (principal.app()) {
            apps.requireRuntime(appIdentity(principal), context, subscriptions);
            return principal;
        }
        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.set(new TenantScope(principal.tenantId(), projectId, principal.subjectId()));
        try {
            if (!accounts.isActive(principal.subjectId())) throw denied();
            projects.requireRoleInProject(projectId);
            UUID tenant = projects.requireProjectTenant(projectId);
            scope.establish(tenant, projectId);
            ProjectAccessPolicy policy = lifecycle.tokenSnapshot(principal.subjectId(), projectId);
            if (!policy.readAllowed() || (principal.projectId() != null
                    && !policy.matchesGeneration(principal.projectGeneration()))) throw denied();
            var page = devices.list(projectId, deviceIds, null, deviceIds.size());
            Set<UUID> actual = page.items().stream()
                    .map(item -> item.id()).collect(Collectors.toSet());
            if (page.hasMore() || page.nextCursor() != null || actual.size() != page.items().size()
                    || !deviceIds.containsAll(actual)) throw new IllegalStateException("设备端口返回越界或重复页");
            if (!actual.equals(deviceIds)) throw denied();
            return new DashboardRealtimePrincipal(false, principal.subjectId(), tenant, projectId,
                    policy.lifecycleGeneration(), principal.expiresAt());
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }

    /** ADR0109：App双域授权只由enduser开启唯一DATA只读事务，避免外层占池后挂起。 */
    public DashboardRealtimePrincipal requireDashboardRuntime(DashboardRealtimePrincipal principal,
            WebAppRuntimeContext context, Map<UUID, Set<String>> subscriptions,
            List<AppRealtimeAlarmSubscription> alarms) {
        requireUnexpired(principal);
        if (!principal.app() || principal.share()) throw denied();
        apps.requireDashboardRuntime(appIdentity(principal), context, subscriptions, alarms);
        return principal;
    }

    /** 分享分派本身不开事务，由dashboard权威入口先选DATA池再独立开启唯一只读事务。 */
    public DashboardRealtimePrincipal requireShareDevices(DashboardRealtimePrincipal principal,
            UUID projectId, Map<UUID, Set<String>> subscriptions) {
        requireUnexpired(principal);
        if (!principal.share() || projectId == null || !projectId.equals(principal.projectId())) throw denied();
        shares.requireSubscriptions(principal.sharePrincipal(), subscriptions);
        return principal;
    }

    /** 令牌截止是每次发送的硬边界，不复用decoder时钟宽容窗口。 */
    private static void requireUnexpired(DashboardRealtimePrincipal principal) {
        if (!Instant.now().isBefore(principal.expiresAt())) throw denied();
    }

    /** 将签名声明交给App域，避免在ingestion复制其业务授权。 */
    private static AppAuthenticatedPrincipal appIdentity(DashboardRealtimePrincipal principal) {
        return new AppAuthenticatedPrincipal(principal.tenantId(), principal.projectId(),
                principal.subjectId(), principal.projectGeneration());
    }

    /** 明确拒绝与基础设施异常分开，后者保持原异常直到1011关闭。 */
    private static RealtimeProjectAccessDeniedException denied() {
        return new RealtimeProjectAccessDeniedException(new IllegalStateException("实时设备授权失效"));
    }
}
