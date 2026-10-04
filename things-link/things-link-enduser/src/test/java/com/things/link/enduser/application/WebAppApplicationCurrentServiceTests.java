package com.things.link.enduser.application;

import com.things.link.dashboard.application.ApplicationRuntimeCurrentService;
import com.things.link.dashboard.application.CurrentApplicationRuntime;
import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0100与运行访问冻结§3.3：App身份、当前精确引用和显式READ交集，不以Console或公开定位代替授权。 */
class WebAppApplicationCurrentServiceTests {
    /** 可信App JWT租户。 */
    private UUID tenant;
    /** 可信App JWT项目。 */
    private UUID project;
    /** 可信App JWT主体。 */
    private UUID user;
    /** 当前应用公开键只作资源选择，不用于改写JWT范围。 */
    private String appKey;
    /** 同一应用跨发布代次的稳定目录身份。 */
    private UUID applicationId;
    /** 集中只读事务RLS范围。 */
    private TransactionLocalRlsScope scope;
    /** 每请求权威用户读取。 */
    private AppUserRepository users;
    /** 每请求权威项目角色读取。 */
    private AppUserRoleRepository roles;
    /** 项目可读性和JWT项目代次。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 一次观察的当前应用公开端口。 */
    private ApplicationRuntimeCurrentService applications;
    /** 只访问当前候选的有界授权查询。 */
    private AppUserDashboardGrantRepository grants;
    /** 当前应用服务，不提供HTTP或会话生成能力。 */
    private WebAppApplicationCurrentService service;

    /** 默认身份有效且项目代次7；每例从单一事实变化构造反例。 */
    @BeforeEach
    void setup() {
        tenant = UUID.randomUUID();
        project = UUID.randomUUID();
        user = UUID.randomUUID();
        appKey = "app_" + "a".repeat(32);
        applicationId = UUID.randomUUID();
        scope = mock(TransactionLocalRlsScope.class);
        users = mock(AppUserRepository.class);
        roles = mock(AppUserRoleRepository.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        applications = mock(ApplicationRuntimeCurrentService.class);
        grants = mock(AppUserDashboardGrantRepository.class);
        service = new WebAppApplicationCurrentService(
                new AppRuntimeIdentityService(scope, users, roles, lifecycle), applications, grants);
        when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.of(appUser(AppUser.Status.ACTIVE)));
        when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE)));
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 7));
    }

    /** 引用按应用顺序保留，入口不可见置null，不把其他可见看板偷偷变成入口。 */
    @Test
    void filtersExactReferencesInApplicationOrderAndHidesUnauthorizedEntry() {
        ApplicationPublishedDashboardReference first = reference();
        ApplicationPublishedDashboardReference hiddenEntry = reference();
        ApplicationPublishedDashboardReference third = reference();
        List<ApplicationPublishedDashboardReference> references = List.of(third, hiddenEntry, first);
        CurrentApplicationRuntime runtime = runtime(references, hiddenEntry.dashboardId(), 5, UUID.randomUUID());
        when(applications.findCurrent(tenant, project, appKey)).thenReturn(Optional.of(runtime));
        List<UUID> candidates = references.stream().map(ApplicationPublishedDashboardReference::dashboardId).toList();
        when(grants.findActiveDashboardIds(tenant, project, user, candidates))
                .thenReturn(Set.of(first.dashboardId(), third.dashboardId()));
        assertThat(TenantContext.current()).as("App路径不借Console上下文").isEmpty();
        CurrentWebAppApplication current = service.current(tenant, project, user, 7, appKey);
        assertThat(current.dashboards()).containsExactly(third, first);
        assertThat(current.entryDashboardId()).isNull();
        assertThat(current.applicationVersionId()).isEqualTo(runtime.applicationVersionId());
        assertThat(current.publicationRevision()).isEqualTo(5);
        assertThat(current.appUserId()).isEqualTo(user);
        assertThat(current.tenantId()).isEqualTo(tenant);
        var order = inOrder(scope, users, roles, lifecycle, applications, grants);
        order.verify(scope).establish(tenant, project);
        order.verify(users).findByIdAndTenant(tenant, user);
        order.verify(roles).findByProjectAndUser(project, user);
        order.verify(lifecycle).snapshot(tenant, project);
        order.verify(applications).findCurrent(tenant, project, appKey);
        order.verify(grants).findActiveDashboardIds(tenant, project, user, candidates);
    }

    /** 归档项目仍可只读；当前可见入口保留原身份，不因writeAllowed=false拒绝。 */
    @Test
    void archivedProjectRetainsReadableEntry() {
        ApplicationPublishedDashboardReference reference = reference();
        allow(List.of(reference), reference.dashboardId());
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, false, 7));
        assertThat(service.current(tenant, project, user, 7, appKey).entryDashboardId()).isEqualTo(reference.dashboardId());
    }

    /** Dashboard端保留原入口ID但过滤其不可运行引用，有其他有效grant时入口应投影为null。 */
    @Test
    void nonRunnableEntryBecomesNull() {
        ApplicationPublishedDashboardReference reference = reference();
        allow(List.of(reference), null);
        assertThat(service.current(tenant, project, user, 7, appKey).entryDashboardId()).isNull();
    }

    /** 一次获取上限5项，只向授权仓储传当前5个ID，不查询用户全量授权目录。 */
    @Test
    void fiveReferencesUseOneBoundedGrantLookup() {
        List<ApplicationPublishedDashboardReference> references = List.of(reference(), reference(), reference(), reference(), reference());
        allow(references, references.getFirst().dashboardId());
        assertThat(service.current(tenant, project, user, 7, appKey).dashboards()).containsExactlyElementsOf(references);
        verify(grants).findActiveDashboardIds(tenant, project, user,
                references.stream().map(ApplicationPublishedDashboardReference::dashboardId).toList());
    }

    /** 身份失效在读取应用或grant之前统一60009，不暴露资源层差异。 */
    @ParameterizedTest
    @ValueSource(strings = {"MISSING_USER", "LOCKED_USER", "MISSING_ROLE", "DISABLED_ROLE", "PROJECT_DENIED", "GENERATION"})
    void invalidIdentityStopsBeforeApplicationRead(String invalid) {
        switch (invalid) {
            case "MISSING_USER" -> when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.empty());
            case "LOCKED_USER" -> when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.of(appUser(AppUser.Status.LOCKED)));
            case "MISSING_ROLE" -> when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.empty());
            case "DISABLED_ROLE" -> when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(role(AppUserRole.Status.DISABLED)));
            case "PROJECT_DENIED" -> when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(false, false, -1));
            case "GENERATION" -> when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 8));
            default -> throw new AssertionError("未登记的身份反例");
        }
        assertCode(() -> service.current(tenant, project, user, 7, appKey), 60009);
        verifyNoInteractions(applications, grants);
    }

    /** 应用不可用、全部Dashboard停用或全部无grant统一60023，不把目录状态当用户授权。 */
    @ParameterizedTest
    @ValueSource(strings = {"APPLICATION", "NO_RUNNABLE_REFERENCES", "NO_GRANT"})
    void resourceUnavailabilityHasOneHiddenError(String missing) {
        if (!"APPLICATION".equals(missing)) {
            List<ApplicationPublishedDashboardReference> references = "NO_RUNNABLE_REFERENCES".equals(missing)
                    ? List.of() : List.of(reference());
            when(applications.findCurrent(tenant, project, appKey)).thenReturn(
                    Optional.of(runtime(references, null, 1, UUID.randomUUID())));
            when(grants.findActiveDashboardIds(any(), any(), any(), anyList())).thenReturn(Set.of());
        }
        assertCode(() -> service.current(tenant, project, user, 7, appKey), 60023);
        if (!"NO_GRANT".equals(missing)) verifyNoInteractions(grants);
    }

    /** 发布A→B→A版本身份可相同，但每次仍读新publicationRevision，不能缓存旧返回值。 */
    @Test
    void repeatedCurrentReadsObserveNewPublicationRevisionAndGrantRevocation() {
        ApplicationPublishedDashboardReference reference = reference();
        List<ApplicationPublishedDashboardReference> references = List.of(reference);
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        when(applications.findCurrent(tenant, project, appKey)).thenReturn(
                Optional.of(runtime(references, reference.dashboardId(), 1, versionA)),
                Optional.of(runtime(references, reference.dashboardId(), 2, versionB)),
                Optional.of(runtime(references, reference.dashboardId(), 3, versionA)));
        when(grants.findActiveDashboardIds(tenant, project, user, List.of(reference.dashboardId())))
                .thenReturn(Set.of(reference.dashboardId()), Set.of(reference.dashboardId()),
                        Set.of(reference.dashboardId()), Set.of());
        assertThat(service.current(tenant, project, user, 7, appKey).applicationVersionId()).isEqualTo(versionA);
        assertThat(service.current(tenant, project, user, 7, appKey).applicationVersionId()).isEqualTo(versionB);
        CurrentWebAppApplication restored = service.current(tenant, project, user, 7, appKey);
        assertThat(restored.applicationVersionId()).isEqualTo(versionA);
        assertThat(restored.publicationRevision()).isEqualTo(3);
        assertCode(() -> service.current(tenant, project, user, 7, appKey), 60023);
        verify(applications, times(4)).findCurrent(tenant, project, appKey);
    }

    /** 路径资源语法错误沿10001，不绕公开resolve查未知appKey真实归属。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "app_short", "app_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", " app_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidAppKeyRejectsBeforeScope(String invalid) {
        assertCode(() -> service.current(tenant, project, user, 7, invalid), 10001);
        verifyNoInteractions(scope, users, roles, lifecycle, applications, grants);
    }

    /** 参数只能来自已认证App身份，缺轴或负代次不得借用户输入补齐。 */
    @Test
    void missingTrustedIdentityDoesNotReachPersistence() {
        assertCode(() -> service.current(null, project, user, 7, appKey), 60009);
        assertCode(() -> service.current(tenant, null, user, 7, appKey), 60009);
        assertCode(() -> service.current(tenant, project, null, 7, appKey), 60009);
        assertCode(() -> service.current(tenant, project, user, -1, appKey), 60009);
        verifyNoInteractions(scope, users, roles, lifecycle, applications, grants);
    }

    /** 公开端口身份漂移是内部完整性故障，不能折叠为正常60023隐藏错误。 */
    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "PROJECT", "APP_KEY"})
    void runtimeIdentityDriftIsInternalFailure(String field) {
        CurrentApplicationRuntime wrong = mock(CurrentApplicationRuntime.class);
        when(wrong.tenantId()).thenReturn("TENANT".equals(field) ? UUID.randomUUID() : tenant);
        when(wrong.projectId()).thenReturn("PROJECT".equals(field) ? UUID.randomUUID() : project);
        when(wrong.appKey()).thenReturn("APP_KEY".equals(field) ? "app_" + "b".repeat(32) : appKey);
        when(applications.findCurrent(tenant, project, appKey)).thenReturn(Optional.of(wrong));
        assertThatThrownBy(() -> service.current(tenant, project, user, 7, appKey))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不一致");
        verifyNoInteractions(grants);
    }

    /** 授权查询返回候选外ID是仓储损坏，不得借额外ID扩大运行可见集合。 */
    @Test
    void unexpectedGrantIdentityFailsInternally() {
        ApplicationPublishedDashboardReference reference = reference();
        allow(List.of(reference), reference.dashboardId());
        when(grants.findActiveDashboardIds(tenant, project, user, List.of(reference.dashboardId())))
                .thenReturn(Set.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.current(tenant, project, user, 7, appKey))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("候选外");
    }

    /** 数据库基础设施异常保留同一个首因，不猜测成App失效或资源缺失。 */
    @Test
    void databaseFailurePropagatesUnchanged() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("当前应用查询数据库失败");
        when(applications.findCurrent(tenant, project, appKey)).thenThrow(failure);
        assertThatThrownBy(() -> service.current(tenant, project, user, 7, appKey)).isSameAs(failure);
        verifyNoInteractions(grants);
    }

    /** 默认允许当前所有引用，仅为编排输入，不宣称mock证明真实SQL/RLS。 */
    private void allow(List<ApplicationPublishedDashboardReference> references, UUID entry) {
        when(applications.findCurrent(tenant, project, appKey)).thenReturn(
                Optional.of(runtime(references, entry, 1, UUID.randomUUID())));
        when(grants.findActiveDashboardIds(tenant, project, user,
                references.stream().map(ApplicationPublishedDashboardReference::dashboardId).toList()))
                .thenReturn(Set.copyOf(references.stream().map(ApplicationPublishedDashboardReference::dashboardId).toList()));
    }

    /** 构造真实公开描述类型，精确版本号与发布代次分离。 */
    private CurrentApplicationRuntime runtime(List<ApplicationPublishedDashboardReference> references, UUID entry,
                                               long revision, UUID version) {
        return new CurrentApplicationRuntime(tenant, project, applicationId, appKey, "当前应用", revision,
                version, 1, "tc.application/v1", "1.0.0", "2.0.0", entry == null ? UUID.randomUUID() : entry, references);
    }

    /** 只保存可见导航所需精确元数据，不内联Schema或设备事实。 */
    private ApplicationPublishedDashboardReference reference() {
        return new ApplicationPublishedDashboardReference(UUID.randomUUID(), UUID.randomUUID(), 1, "看板导航",
                "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "a".repeat(64),
                List.of(new ApplicationPublishedDashboardReference.Page("home", "首页")));
    }

    /** 可信租户用户快照，状态反例不更换其身份。 */
    private AppUser appUser(AppUser.Status status) {
        return new AppUser(user, tenant, "current-user", "test-only-hash", "当前用户", status, null, Instant.EPOCH);
    }

    /** OBSERVER也可读取显式grant，角色本身不给全看板授权。 */
    private AppUserRole role(AppUserRole.Status status) {
        return new AppUserRole(UUID.randomUUID(), tenant, project, user, EndUserRole.OBSERVER, status, Instant.EPOCH);
    }

    /** 错误码闭集不依赖可调整的中文描述。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
