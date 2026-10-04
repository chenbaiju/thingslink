package com.things.link.enduser.application;

import com.things.link.dashboard.application.DashboardGrantTargetService;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserDashboardGrant;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 运行访问冻结§4：Console管理稳定用户×看板READ授权；不授予设备权限或提供App运行入口。
 * 每个公开方法独立检查dashboard_definition:manage的OWNER/ADMIN闭集，不能依赖Controller预检查。
 */
@Service
public class AppUserDashboardGrantManagementService {
    /** 项目成员与真实归属端口，跨租户协作者不能以自己的租户替代项目租户。 */
    private final ProjectService projects;
    /** 项目ACTIVE共享许可，必须先于用户与看板锁并持有至提交。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 原物理事务的集中双轴RLS范围。 */
    private final TransactionLocalRlsScope scope;
    /** 稳定用户身份与ADR0097互斥原语。 */
    private final AppUserRepository users;
    /** 用户锁后的项目角色权威读取。 */
    private final AppUserRoleRepository roles;
    /** 仅经dashboard公开应用端口检查归属和软删并持目录锁。 */
    private final DashboardGrantTargetService dashboards;
    /** 本域历史读取与受控CAS。 */
    private final AppUserDashboardGrantRepository grants;
    /** 同事务记录真实状态变化，审计失败不得保留授权半成品。 */
    private final AuditLogService audit;

    /**
     * 创建授权管理服务，各持久组件使用同一事务管理器与数据源。
     * @param projects 项目成员与归属
     * @param lifecycle 项目写许可
     * @param scope 集中RLS范围
     * @param users 用户查询和互斥
     * @param roles 项目角色读取
     * @param dashboards 看板稳定目录锁描述
     * @param grants 授权持久事实
     * @param audit 同事务审计
     */
    public AppUserDashboardGrantManagementService(ProjectService projects, ProjectLifecycleAccessService lifecycle,
            TransactionLocalRlsScope scope, AppUserRepository users, AppUserRoleRepository roles,
            DashboardGrantTargetService dashboards, AppUserDashboardGrantRepository grants, AuditLogService audit) {
        this.projects = projects;
        this.lifecycle = lifecycle;
        this.scope = scope;
        this.users = users;
        this.roles = roles;
        this.dashboards = dashboards;
        this.grants = grants;
        this.audit = audit;
    }

    /**
     * 读取全部显式授权历史，ARCHIVED及LOCKED/DISABLED用户仍可管理读取。
     * @param projectId 已选择项目
     * @param appUserId 目标终端用户
     * @param cursor 不透明稳定看板ID游标，首页为空
     * @param limit 单页1至200行
     * @return 授权历史有界页，不联查或返回看板名称
     */
    @Transactional(readOnly = true)
    public CursorPage<AppUserDashboardGrant> list(UUID projectId, UUID appUserId, String cursor, int limit) {
        UUID tenantId = requireReadableUser(projectId, appUserId);
        return grants.list(tenantId, projectId, appUserId, cursor, limit);
    }

    /**
     * 公共幂等完成墓碑后刷新当前授权事实；软删看板的历史grant仍按原身份可读。
     * @param projectId 已选择项目
     * @param appUserId 目标终端用户
     * @param dashboardId 稳定看板身份
     * @return 授权数据库事实，不存在统一60025
     */
    @Transactional(readOnly = true)
    public AppUserDashboardGrant get(UUID projectId, UUID appUserId, UUID dashboardId) {
        UUID tenantId = requireReadableUser(projectId, appUserId);
        return grants.find(tenantId, projectId, appUserId, dashboardId).orElseThrow(
                () -> new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND));
    }

    /**
     * 在项目→用户→看板锁序下变更显式授权，同状态且revision匹配时返回原事实而不写审计。
     * @param projectId 已选择项目
     * @param appUserId 目标终端用户
     * @param dashboardId 稳定看板身份，不要求已发布但必须未软删
     * @param expectedRevision 规范Long十进制字符串，缺行首次为0
     * @param status ACTIVE或REVOKED
     * @return CAS成功后原事务中的数据库事实
     */
    @Transactional
    public AppUserDashboardGrant update(UUID projectId, UUID appUserId, UUID dashboardId,
                                         String expectedRevision, String status) {
        requireManager(projectId);
        long revision = parseRevision(expectedRevision);
        AppUserDashboardGrant.Status desired = parseStatus(status);
        UUID tenantId = projects.requireProjectTenant(projectId);
        scope.establish(tenantId, projectId);
        lifecycle.requireActiveForWrite(tenantId, projectId);
        requireManager(projectId);
        users.lockByIdAndTenant(tenantId, appUserId).filter(user -> user.status() == AppUser.Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND));
        // 必须在用户锁后的新语句读取角色，不能复用锁前快照越过已提交的suspend。
        roles.findByProjectAndUser(projectId, appUserId).filter(role -> role.status() == AppUserRole.Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND));
        dashboards.lockForGrant(tenantId, projectId, dashboardId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND));
        UUID actorId = TenantContext.require().accountId();
        AppUserDashboardGrantRepository.WriteResult result = grants.compareAndSet(Uuid7.generate(), tenantId,
                projectId, appUserId, dashboardId, revision, desired, actorId);
        switch (result.outcome()) {
            case NOT_FOUND -> throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND);
            case CONFLICT -> throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_CONFLICT);
            case INVALID -> throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_INVALID);
            case CREATED, UPDATED -> recordChange(result.grant(), actorId);
            case UNCHANGED -> { /* 匹配的同状态请求保留原时间与操作者，不能制造审计事件。 */ }
        }
        return result.grant();
    }

    /** 读侧只要求同租户用户与项目角色存在，保留停用身份的管理历史，不访问看板目录。 */
    private UUID requireReadableUser(UUID projectId, UUID appUserId) {
        requireManager(projectId);
        UUID tenantId = projects.requireProjectTenant(projectId);
        scope.establish(tenantId, projectId);
        users.findByIdAndTenant(tenantId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND));
        roles.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND));
        return tenantId;
    }

    /** dashboard_definition:manage固定为OWNER/ADMIN；其他成员60024，非成员由项目端口保持50001。 */
    private void requireManager(UUID projectId) {
        ProjectRole role = projects.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_MANAGE_FORBIDDEN);
        }
    }

    /** 只接受规范非负Long字符串，拒绝空白、正号、前导零、小数、指数与溢出。 */
    private static long parseRevision(String value) {
        if (value == null || value.length() > 19 || !value.matches("0|[1-9][0-9]*")) {
            throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_INVALID);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_INVALID);
        }
    }

    /** 状态闭集不做trim或大小写容错，以免同一管理意图产生多个候选语义。 */
    private static AppUserDashboardGrant.Status parseStatus(String value) {
        if ("ACTIVE".equals(value)) return AppUserDashboardGrant.Status.ACTIVE;
        if ("REVOKED".equals(value)) return AppUserDashboardGrant.Status.REVOKED;
        throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_INVALID);
    }

    /** 唯一真实状态变化产生闭集审计；没有Schema、凭据、标题或其他不可见资源字段。 */
    private void recordChange(AppUserDashboardGrant grant, UUID actorId) {
        audit.record(new AuditLogEntry(grant.tenantId(), grant.projectId(), actorId, "enduser.dashboard_grant",
                grant.id(), grant.status() == AppUserDashboardGrant.Status.ACTIVE
                    ? "enduser.dashboard.granted" : "enduser.dashboard.revoked",
                Map.of("appUserId", grant.appUserId().toString(), "dashboardId", grant.dashboardId().toString(),
                        "status", grant.status().name(), "revision", Long.toString(grant.revision()))));
    }
}
