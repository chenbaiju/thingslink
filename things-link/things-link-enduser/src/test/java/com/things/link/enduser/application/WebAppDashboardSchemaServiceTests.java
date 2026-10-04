package com.things.link.enduser.application;

import com.things.link.dashboard.application.ApplicationRuntimeSchemaService;
import com.things.link.dashboard.application.RuntimeDashboardSchema;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 冻结§3.4：精确Schema请求重验App身份与单目标grant，不复用current返回作为授权票据。 */
class WebAppDashboardSchemaServiceTests {
    /** 已认证租户。 */
    private final UUID tenant = UUID.randomUUID();
    /** 已认证项目。 */
    private final UUID project = UUID.randomUUID();
    /** 已认证App主体。 */
    private final UUID user = UUID.randomUUID();
    /** 精确应用版本。 */
    private final UUID applicationVersion = UUID.randomUUID();
    /** 精确看板版本。 */
    private final UUID dashboardVersion = UUID.randomUUID();
    /** 单目标授权使用目录身份，不能误用版本UUID。 */
    private final UUID dashboard = UUID.randomUUID();
    /** 请求选择器不决定JWT范围。 */
    private final String appKey = "app_" + "a".repeat(32);
    /** 建立事务局部可信范围。 */
    private TransactionLocalRlsScope scope;
    /** 真实身份门禁所用用户仓储。 */
    private AppUserRepository users;
    /** 真实身份门禁所用角色仓储。 */
    private AppUserRoleRepository roles;
    /** 项目只读资格与生命周期。 */
    private ProjectLifecycleAccessService lifecycle;
    /** Dashboard公开窄端口。 */
    private ApplicationRuntimeSchemaService schemas;
    /** 当前单目标授权。 */
    private AppUserDashboardGrantRepository grants;
    /** 公开端口已经核验的单Schema值。 */
    private RuntimeDashboardSchema result;
    /** 被测端到端应用编排，不启动HTTP。 */
    private WebAppDashboardSchemaService service;

    /** 默认为ACTIVE OBSERVER显式单目标授权，身份门禁使用真实实现。 */
    @BeforeEach
    void setup() {
        scope = mock(TransactionLocalRlsScope.class);
        users = mock(AppUserRepository.class);
        roles = mock(AppUserRoleRepository.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        schemas = mock(ApplicationRuntimeSchemaService.class);
        grants = mock(AppUserDashboardGrantRepository.class);
        service = new WebAppDashboardSchemaService(new AppRuntimeIdentityService(scope, users, roles, lifecycle), schemas, grants);
        when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.of(appUser(AppUser.Status.ACTIVE)));
        when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE)));
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 7));
        result = mock(RuntimeDashboardSchema.class);
        when(result.tenantId()).thenReturn(tenant);
        when(result.projectId()).thenReturn(project);
        when(result.applicationId()).thenReturn(UUID.randomUUID());
        when(result.appKey()).thenReturn(appKey);
        when(result.applicationVersionId()).thenReturn(applicationVersion);
        when(result.publicationRevision()).thenReturn(Long.MAX_VALUE);
        when(result.dashboardId()).thenReturn(dashboard);
        when(result.dashboardVersionId()).thenReturn(dashboardVersion);
        when(schemas.findSchema(tenant, project, appKey, applicationVersion, Long.MAX_VALUE, dashboardVersion))
                .thenReturn(Optional.of(result));
        when(grants.findActiveDashboardIds(tenant, project, user, List.of(dashboard))).thenReturn(Set.of(dashboard));
    }

    /** 单Schema按身份→封闭版本观察→单目标grant顺序，不加载current或其他看板。 */
    @Test
    void returnsExactSchemaAfterIdentityAndOnlyTargetGrant() {
        assertThat(read()).isSameAs(result);
        var order = inOrder(scope, users, roles, lifecycle, schemas, grants);
        order.verify(scope).establish(tenant, project);
        order.verify(users).findByIdAndTenant(tenant, user);
        order.verify(roles).findByProjectAndUser(project, user);
        order.verify(lifecycle).snapshot(tenant, project);
        order.verify(schemas).findSchema(tenant, project, appKey, applicationVersion, Long.MAX_VALUE, dashboardVersion);
        order.verify(grants).findActiveDashboardIds(tenant, project, user, List.of(dashboard));
    }

    /** 归档项目可读；读路径不意外使用ACTIVE写许可。 */
    @Test
    void archivedProjectStillAllowsExplicitSchemaRead() {
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, false, 7));
        assertThat(read()).isSameAs(result);
    }

    /** 身份失效不查询资源，保持60009与资源隐藏60023的区别。 */
    @ParameterizedTest
    @ValueSource(strings = {"USER_MISSING", "USER_LOCKED", "ROLE_MISSING", "ROLE_DISABLED", "PROJECT", "GENERATION"})
    void rejectsInvalidIdentityBeforeSchema(String change) {
        switch (change) {
            case "USER_MISSING" -> when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.empty());
            case "USER_LOCKED" -> when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.of(appUser(AppUser.Status.LOCKED)));
            case "ROLE_MISSING" -> when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.empty());
            case "ROLE_DISABLED" -> when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(role(AppUserRole.Status.DISABLED)));
            case "PROJECT" -> when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(false, false, 7));
            case "GENERATION" -> when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 8));
            default -> throw new AssertionError(change);
        }
        assertCode(this::read, 60009);
        verifyNoInteractions(schemas, grants);
    }

    /** 身份仓储返回跨轴结果必须作为内部故障拒绝，不把可信JWT静默改成仓储中的其他身份。 */
    @ParameterizedTest
    @ValueSource(strings = {"USER_TENANT", "USER_ID", "ROLE_TENANT", "ROLE_PROJECT", "ROLE_USER"})
    void rejectsIdentityRepositoryDrift(String field) {
        if (field.startsWith("USER_")) {
            when(users.findByIdAndTenant(tenant, user)).thenReturn(Optional.of(new AppUser(
                    "USER_ID".equals(field) ? UUID.randomUUID() : user,
                    "USER_TENANT".equals(field) ? UUID.randomUUID() : tenant,
                    "schema-user", "test-only-hash", "Schema用户", AppUser.Status.ACTIVE, null, Instant.EPOCH)));
        } else {
            when(roles.findByProjectAndUser(project, user)).thenReturn(Optional.of(new AppUserRole(
                    UUID.randomUUID(), "ROLE_TENANT".equals(field) ? UUID.randomUUID() : tenant,
                    "ROLE_PROJECT".equals(field) ? UUID.randomUUID() : project,
                    "ROLE_USER".equals(field) ? UUID.randomUUID() : user,
                    EndUserRole.OBSERVER, AppUserRole.Status.ACTIVE, Instant.EPOCH)));
        }
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class).hasMessageContaining("不一致");
        verifyNoInteractions(schemas, grants);
    }

    /** 缺当前版本、发布代次不匹配或精确引用不可用均由窄端口隐藏，不查询grant。 */
    @Test
    void unavailableExactContextDoesNotQueryGrant() {
        when(schemas.findSchema(tenant, project, appKey, applicationVersion, Long.MAX_VALUE, dashboardVersion))
                .thenReturn(Optional.empty());
        assertCode(this::read, 60023);
        verifyNoInteractions(grants);
    }

    /** 下一请求必须重新查grant，第一次成功不能成为长期票据。 */
    @Test
    void grantRevocationIsObservedOnNextRequest() {
        when(grants.findActiveDashboardIds(tenant, project, user, List.of(dashboard)))
                .thenReturn(Set.of(dashboard), Set.of());
        assertThat(read()).isSameAs(result);
        assertCode(this::read, 60023);
        verify(users, times(2)).findByIdAndTenant(tenant, user);
        verify(schemas, times(2)).findSchema(tenant, project, appKey, applicationVersion, Long.MAX_VALUE, dashboardVersion);
    }

    /** 任一可信轴或精确版本漂移均为内部错误，不能拿错Schema继续确权。 */
    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "PROJECT", "KEY", "APPLICATION_VERSION", "REVISION", "DASHBOARD_VERSION", "APPLICATION_NULL", "DASHBOARD_NULL"})
    void rejectsPortIdentityDriftBeforeGrant(String field) {
        switch (field) {
            case "TENANT" -> when(result.tenantId()).thenReturn(UUID.randomUUID());
            case "PROJECT" -> when(result.projectId()).thenReturn(UUID.randomUUID());
            case "KEY" -> when(result.appKey()).thenReturn("app_" + "b".repeat(32));
            case "APPLICATION_VERSION" -> when(result.applicationVersionId()).thenReturn(UUID.randomUUID());
            case "REVISION" -> when(result.publicationRevision()).thenReturn(1L);
            case "DASHBOARD_VERSION" -> when(result.dashboardVersionId()).thenReturn(UUID.randomUUID());
            case "APPLICATION_NULL" -> when(result.applicationId()).thenReturn(null);
            case "DASHBOARD_NULL" -> when(result.dashboardId()).thenReturn(null);
            default -> throw new AssertionError(field);
        }
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class).hasMessageContaining("不一致");
        verifyNoInteractions(grants);
    }

    /** 授权仓储越界或空契约值是内部故障，不能视作目标已被授权。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsOutOfTargetGrant(boolean nullResult) {
        when(grants.findActiveDashboardIds(tenant, project, user, List.of(dashboard)))
                .thenReturn(nullResult ? null : Set.of(UUID.randomUUID()));
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class).hasMessageContaining("目标外");
    }

    /** 资源参数必须精确合法，任何失败都不得先建立范围或读取持久层。 */
    @Test
    void rejectsInvalidExactResourceParameters() {
        for (String key : new String[]{null, "", "app_short", "app_" + "A".repeat(32), " " + appKey}) {
            assertCode(() -> service.schema(tenant, project, user, 7, key, applicationVersion, 1, dashboardVersion), 10001);
        }
        assertCode(() -> service.schema(tenant, project, user, 7, appKey, null, 1, dashboardVersion), 10001);
        assertCode(() -> service.schema(tenant, project, user, 7, appKey, applicationVersion, 1, null), 10001);
        for (long revision : new long[]{0, -1, Long.MIN_VALUE}) {
            assertCode(() -> service.schema(tenant, project, user, 7, appKey, applicationVersion, revision, dashboardVersion), 10001);
        }
        verifyNoInteractions(scope, users, roles, lifecycle, schemas, grants);
    }

    /** 缺JWT轴或负代次不能由资源请求补齐。 */
    @Test
    void rejectsMissingTrustedIdentity() {
        assertCode(() -> service.schema(null, project, user, 7, appKey, applicationVersion, 1, dashboardVersion), 60009);
        assertCode(() -> service.schema(tenant, null, user, 7, appKey, applicationVersion, 1, dashboardVersion), 60009);
        assertCode(() -> service.schema(tenant, project, null, 7, appKey, applicationVersion, 1, dashboardVersion), 60009);
        assertCode(() -> service.schema(tenant, project, user, -1, appKey, applicationVersion, 1, dashboardVersion), 60009);
        verifyNoInteractions(scope, users, roles, lifecycle, schemas, grants);
    }

    /** 身份、Schema和grant任意数据库故障保留同一首因，不降格成业务隐藏。 */
    @ParameterizedTest
    @ValueSource(strings = {"IDENTITY", "SCHEMA", "GRANT"})
    void preservesDatabaseFirstCause(String source) {
        var failure = new DataAccessResourceFailureException("Schema数据库不可用");
        switch (source) {
            case "IDENTITY" -> when(users.findByIdAndTenant(tenant, user)).thenThrow(failure);
            case "SCHEMA" -> when(schemas.findSchema(tenant, project, appKey, applicationVersion, Long.MAX_VALUE, dashboardVersion))
                    .thenThrow(failure);
            case "GRANT" -> when(grants.findActiveDashboardIds(tenant, project, user, List.of(dashboard))).thenThrow(failure);
            default -> throw new AssertionError(source);
        }
        assertThatThrownBy(this::read).isSameAs(failure);
    }

    /** 默认请求使用最大Long发布代次，编排不得经过浮点转换。 */
    private RuntimeDashboardSchema read() {
        return service.schema(tenant, project, user, 7, appKey, applicationVersion, Long.MAX_VALUE, dashboardVersion);
    }

    /** 状态反例保留同一真实用户身份。 */
    private AppUser appUser(AppUser.Status status) {
        return new AppUser(user, tenant, "schema-user", "test-only-hash", "Schema用户", status, null, Instant.EPOCH);
    }

    /** OBSERVER不能自动获得看板权限，仍需独立READ grant。 */
    private AppUserRole role(AppUserRole.Status status) {
        return new AppUserRole(UUID.randomUUID(), tenant, project, user, EndUserRole.OBSERVER, status, Instant.EPOCH);
    }

    /** 错误码稳定断言，不依赖可调整中文短文。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
