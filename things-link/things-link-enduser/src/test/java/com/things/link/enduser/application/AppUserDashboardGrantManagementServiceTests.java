package com.things.link.enduser.application;

import com.things.link.dashboard.application.DashboardGrantTarget;
import com.things.link.dashboard.application.DashboardGrantTargetService;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserDashboardGrant;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 运行访问冻结§4：管理授权、锁序、闭集语法与审计编排，真实SQL事务资格由Bootstrap覆盖。 */
class AppUserDashboardGrantManagementServiceTests {
    /** 项目权威身份与管理角色。 */
    private ProjectService projects;
    /** 保持原事务的ACTIVE共享许可。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 应在用户事实之前建立的真实项目两轴范围。 */
    private TransactionLocalRlsScope scope;
    /** 用户读与写锁是不同原语，测试不得互换。 */
    private AppUserRepository users;
    /** 用户锁后项目角色快照。 */
    private AppUserRoleRepository roles;
    /** 公开看板锁描述不能用于读历史时过滤软删事实。 */
    private DashboardGrantTargetService dashboards;
    /** 持久原子仲裁入口。 */
    private AppUserDashboardGrantRepository grants;
    /** 同事务最小审计。 */
    private AuditLogService audit;
    /** 被测应用服务，不以Controller校验替代本层权限。 */
    private AppUserDashboardGrantManagementService service;
    /** 项目权威租户，与协作者上下文租户刻意不同。 */
    private UUID tenant;
    /** 被授权项目。 */
    private UUID project;
    /** 被授权App用户。 */
    private UUID user;
    /** 稳定看板身份。 */
    private UUID dashboard;
    /** 已认证Console操作者，与被授权App用户不同。 */
    private UUID actor;

    /** 每例默认前置有效，测试逐项改变一个明确反例。 */
    @BeforeEach
    void setup() {
        projects = mock(ProjectService.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        scope = mock(TransactionLocalRlsScope.class);
        users = mock(AppUserRepository.class);
        roles = mock(AppUserRoleRepository.class);
        dashboards = mock(DashboardGrantTargetService.class);
        grants = mock(AppUserDashboardGrantRepository.class);
        audit = mock(AuditLogService.class);
        service = new AppUserDashboardGrantManagementService(projects, lifecycle, scope, users, roles,
                dashboards, grants, audit);
        tenant = UUID.randomUUID();
        project = UUID.randomUUID();
        user = UUID.randomUUID();
        dashboard = UUID.randomUUID();
        actor = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), project, actor));
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.ADMIN);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(users.lockByIdAndTenant(tenant, user)).thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE)));
        when(dashboards.lockForGrant(tenant, project, dashboard))
                .thenReturn(Optional.of(new DashboardGrantTarget(tenant, project, dashboard)));
    }

    /** 模拟认证上下文不得污染后续线程复用测试。 */
    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    /** 授予和撤销使用数据库事实审计；已认证协作者的actor不改变项目owner租户。 */
    @ParameterizedTest
    @EnumSource(AppUserDashboardGrant.Status.class)
    void stateChangeAuditsExactDatabaseFactInProjectUserDashboardOrder(AppUserDashboardGrant.Status status) {
        long expectedRevision = status == AppUserDashboardGrant.Status.ACTIVE ? 0 : 1;
        AppUserDashboardGrant grant = grant(status, expectedRevision + 1);
        AppUserDashboardGrantRepository.Outcome outcome = status == AppUserDashboardGrant.Status.ACTIVE
                ? AppUserDashboardGrantRepository.Outcome.CREATED : AppUserDashboardGrantRepository.Outcome.UPDATED;
        stubResult(outcome, grant);
        assertThat(service.update(project, user, dashboard, Long.toString(expectedRevision), status.name())).isSameAs(grant);
        var order = inOrder(projects, scope, lifecycle, users, roles, dashboards, grants, audit);
        order.verify(projects).requireRoleInProject(project);
        order.verify(projects).requireProjectTenant(project);
        order.verify(scope).establish(tenant, project);
        order.verify(lifecycle).requireActiveForWrite(tenant, project);
        order.verify(projects).requireRoleInProject(project);
        order.verify(users).lockByIdAndTenant(tenant, user);
        order.verify(roles).findByProjectAndUser(project, user);
        order.verify(dashboards).lockForGrant(tenant, project, dashboard);
        order.verify(grants).compareAndSet(any(UUID.class), eq(tenant), eq(project), eq(user), eq(dashboard),
                eq(expectedRevision), eq(status), eq(actor));
        ArgumentCaptor<AuditLogEntry> recorded = ArgumentCaptor.forClass(AuditLogEntry.class);
        order.verify(audit).record(recorded.capture());
        assertThat(recorded.getValue()).isEqualTo(new AuditLogEntry(tenant, project, actor,
                "enduser.dashboard_grant", grant.id(), status == AppUserDashboardGrant.Status.ACTIVE
                    ? "enduser.dashboard.granted" : "enduser.dashboard.revoked",
                Map.of("appUserId", user.toString(), "dashboardId", dashboard.toString(),
                        "status", status.name(), "revision", Long.toString(grant.revision()))));
        verify(users, never()).findByIdAndTenant(any(), any());
    }

    /** 同状态包括Long上限时完全返回旧事实，不写审计或尝试自动推进版本。 */
    @ParameterizedTest
    @EnumSource(AppUserDashboardGrant.Status.class)
    void unchangedHasNoAudit(AppUserDashboardGrant.Status status) {
        AppUserDashboardGrant grant = grant(status, Long.MAX_VALUE);
        stubResult(AppUserDashboardGrantRepository.Outcome.UNCHANGED, grant);
        assertThat(service.update(project, user, dashboard, Long.toString(Long.MAX_VALUE), status.name()))
                .isSameAs(grant);
        verify(grants).compareAndSet(any(UUID.class), eq(tenant), eq(project), eq(user), eq(dashboard),
                eq(Long.MAX_VALUE), eq(status), eq(actor));
        verifyNoInteractions(audit);
    }

    /** 普通成员三个入口都独立拒绝，不能借既有enduser:read或dashboard:read扩大权限。 */
    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    void allEntrypointsRejectNonManager(ProjectRole role) {
        when(projects.requireRoleInProject(project)).thenReturn(role);
        assertCode(() -> service.list(project, user, null, 50), EndUserErrorCode.DASHBOARD_GRANT_MANAGE_FORBIDDEN);
        assertCode(() -> service.get(project, user, dashboard), EndUserErrorCode.DASHBOARD_GRANT_MANAGE_FORBIDDEN);
        assertCode(() -> service.update(project, user, dashboard, "bad", "bad"),
                EndUserErrorCode.DASHBOARD_GRANT_MANAGE_FORBIDDEN);
        verifyNoInteractions(scope, lifecycle, users, roles, dashboards, grants, audit);
    }

    /** 项目端口的不可见异常原样传播，不能转换成grant错误泄露目标所属项目。 */
    @Test
    void projectFailurePreservesOriginalException() {
        BusinessException missingProject = mock(BusinessException.class);
        when(projects.requireRoleInProject(project)).thenThrow(missingProject);
        assertThatThrownBy(() -> service.get(project, user, dashboard)).isSameAs(missingProject);
        assertThatThrownBy(() -> service.list(project, user, null, 50)).isSameAs(missingProject);
        assertThatThrownBy(() -> service.update(project, user, dashboard, "0", "ACTIVE")).isSameAs(missingProject);
        verifyNoInteractions(grants, users, roles, audit);
    }

    /** 历史读取允许LOCKED/DISABLED，不申请写许可或查软删看板标题，从而保留授权历史身份。 */
    @Test
    void historicalReadsAllowLockedUserAndDisabledRoleWithoutDashboardJoin() {
        AppUserDashboardGrant grant = grant(AppUserDashboardGrant.Status.REVOKED);
        when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.of(user(AppUser.Status.LOCKED)));
        when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(role(AppUserRole.Status.DISABLED)));
        when(grants.find(tenant, project, user, dashboard)).thenReturn(Optional.of(grant));
        CursorPage<AppUserDashboardGrant> page = CursorPage.last(List.of(grant));
        when(grants.list(tenant, project, user, null, 50)).thenReturn(page);
        assertThat(service.get(project, user, dashboard)).isSameAs(grant);
        assertThat(service.list(project, user, null, 50)).isSameAs(page);
        verifyNoInteractions(lifecycle, dashboards, audit);
        verify(users, never()).lockByIdAndTenant(any(), any());
    }

    /** 用户、角色或授权缺失在管理读统一60025，不联查其他域猜测资源存在性。 */
    @ParameterizedTest
    @ValueSource(strings = {"USER", "ROLE", "GRANT"})
    void hiddenReadTargetHasSingleNotFound(String missing) {
        if ("USER".equals(missing)) when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.empty());
        if ("ROLE".equals(missing)) when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.empty());
        assertCode(() -> service.get(project, user, dashboard), EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND);
        if (!"GRANT".equals(missing)) {
            assertCode(() -> service.list(project, user, null, 50), EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND);
            verifyNoInteractions(grants);
        }
        verifyNoInteractions(dashboards, audit);
    }

    /** 规范Long字符串没有空白、符号、指数、前导零或溢出兼容别名。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", " 1", "1 ", "+1", "-1", "00", "01", "1.0", "1e2",
            "9223372036854775808", "99999999999999999999999999999"})
    void invalidRevisionRejectsBeforePersistentScope(String revision) {
        assertCode(() -> service.update(project, user, dashboard, revision, "ACTIVE"),
                EndUserErrorCode.DASHBOARD_GRANT_INVALID);
        verifyNoInteractions(scope, lifecycle, users, roles, dashboards, grants, audit);
    }

    /** 状态闭集大小写敏感，不自动trim或补默认授予。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "active", "REVOKE", " ACTIVE", "ACTIVE ", "UNKNOWN"})
    void invalidStatusRejectsBeforePersistentScope(String status) {
        assertCode(() -> service.update(project, user, dashboard, "0", status), EndUserErrorCode.DASHBOARD_GRANT_INVALID);
        verifyNoInteractions(scope, lifecycle, users, roles, dashboards, grants, audit);
    }

    /** 较新冻结要求所有写含revoke都具备ACTIVE用户/角色及未软删看板。 */
    @ParameterizedTest
    @ValueSource(strings = {"MISSING_USER", "LOCKED_USER", "MISSING_ROLE", "DISABLED_ROLE", "DASHBOARD"})
    void invalidWriteTargetRejectsBeforeCas(String invalid) {
        switch (invalid) {
            case "MISSING_USER" -> when(users.lockByIdAndTenant(tenant, user)).thenReturn(Optional.empty());
            case "LOCKED_USER" -> when(users.lockByIdAndTenant(tenant, user)).thenReturn(Optional.of(user(AppUser.Status.LOCKED)));
            case "MISSING_ROLE" -> when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.empty());
            case "DISABLED_ROLE" -> when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(role(AppUserRole.Status.DISABLED)));
            case "DASHBOARD" -> when(dashboards.lockForGrant(tenant, project, dashboard)).thenReturn(Optional.empty());
            default -> throw new AssertionError("未登记的目标反例");
        }
        assertCode(() -> service.update(project, user, dashboard, "1", "REVOKED"),
                EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND);
        verifyNoInteractions(grants, audit);
    }

    /** 函数已经给出的分类直接映射，不能另查当前行在并发窗口里重新推断原因。 */
    @ParameterizedTest
    @EnumSource(value = AppUserDashboardGrantRepository.Outcome.class, names = {"NOT_FOUND", "CONFLICT", "INVALID"})
    void failedCasMapsExactOutcomeWithoutFallbackLookup(AppUserDashboardGrantRepository.Outcome outcome) {
        stubResult(outcome, null);
        EndUserErrorCode expected = switch (outcome) {
            case NOT_FOUND -> EndUserErrorCode.DASHBOARD_GRANT_NOT_FOUND;
            case CONFLICT -> EndUserErrorCode.DASHBOARD_GRANT_CONFLICT;
            case INVALID -> EndUserErrorCode.DASHBOARD_GRANT_INVALID;
            default -> throw new AssertionError("本例只覆盖失败分类");
        };
        assertCode(() -> service.update(project, user, dashboard, "0", "REVOKED"), expected);
        verify(grants, never()).find(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    /** 项目许可失效与等待期间失去管理权限都在用户锁/CAS之前拒绝。 */
    @Test
    void roleIsRecheckedAfterProjectPermit() {
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.ADMIN, ProjectRole.VIEWER);
        assertCode(() -> service.update(project, user, dashboard, "0", "ACTIVE"),
                EndUserErrorCode.DASHBOARD_GRANT_MANAGE_FORBIDDEN);
        verify(lifecycle).requireActiveForWrite(tenant, project);
        verifyNoInteractions(users, roles, dashboards, grants, audit);
    }

    /** 数据库首因不能被包装成授权缺失或并发冲突；实际事务回滚由真实SQL用例证明。 */
    @Test
    void databaseFailurePropagatesUnchanged() {
        DataAccessResourceFailureException sql = new DataAccessResourceFailureException("保留连接首因");
        when(grants.compareAndSet(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenThrow(sql);
        assertThatThrownBy(() -> service.update(project, user, dashboard, "0", "ACTIVE")).isSameAs(sql);
        verifyNoInteractions(audit);
    }

    /** 审计失败不得伪称业务成功，必须向原事务传播以回滚已写授权。 */
    @Test
    void auditFailureEscapesOriginalTransaction() {
        stubResult(AppUserDashboardGrantRepository.Outcome.CREATED, grant(AppUserDashboardGrant.Status.ACTIVE));
        IllegalStateException failure = new IllegalStateException("审计写失败");
        doThrow(failure).when(audit).record(any());
        assertThatThrownBy(() -> service.update(project, user, dashboard, "0", "ACTIVE")).isSameAs(failure);
    }

    /** CAS输入匹配由各例另行verify，默认返回明确持久分类。 */
    private void stubResult(AppUserDashboardGrantRepository.Outcome outcome, AppUserDashboardGrant grant) {
        when(grants.compareAndSet(any(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(new AppUserDashboardGrantRepository.WriteResult(outcome, grant));
    }

    /** 安全错误只检查冻结码，不依赖可能调整的中文描述。 */
    private static void assertCode(Runnable action, EndUserErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }

    /** 真实作用域一致的用户快照，LOCKED反例不改变用户身份。 */
    private AppUser user(AppUser.Status status) {
        return new AppUser(user, tenant, "grant-user", "test-only-hash", "授权用户", status, null, Instant.EPOCH);
    }

    /** 用户角色状态独立于租户登录状态，不用角色枚举扩大READ授权。 */
    private AppUserRole role(AppUserRole.Status status) {
        return new AppUserRole(UUID.randomUUID(), tenant, project, user, EndUserRole.OBSERVER, status, Instant.EPOCH);
    }

    /** 固定数据库事实用于断言服务没有另造响应时间或操作者。 */
    private AppUserDashboardGrant grant(AppUserDashboardGrant.Status status) {
        return grant(status, 2);
    }

    /** 首次、历史与Long上限用例使用各自一致的持久revision，避免mock生成不可能事实。 */
    private AppUserDashboardGrant grant(AppUserDashboardGrant.Status status, long revision) {
        return new AppUserDashboardGrant(UUID.randomUUID(), tenant, project, user, dashboard, status, revision,
                Instant.EPOCH, Instant.EPOCH, status == AppUserDashboardGrant.Status.REVOKED ? Instant.EPOCH : null,
                actor, actor, status == AppUserDashboardGrant.Status.REVOKED ? actor : null);
    }
}
