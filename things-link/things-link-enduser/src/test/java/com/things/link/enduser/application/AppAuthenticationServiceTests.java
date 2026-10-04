package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import org.mockito.InOrder;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** App 登录用例的单元测试（S11-2a）。 */
class AppAuthenticationServiceTests {

    private static final String PROJECT_KEY = "proj-key-123";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "secret123";
    private static final String CLIENT_IP = "127.0.0.1";

    private AppAuthRateLimiter rateLimiter;
    private ProjectService projectService;
    private AppUserRepository appUserRepository;
    private AppUserRoleRepository appUserRoleRepository;
    private AppSessionService sessionService;
    private PasswordEncoder passwordEncoder;
    private AppAuthenticationService service;
    /** 跨账号排序锁桥，旧登录不应调用。 */
    private com.things.link.enduser.domain.AppBrowserSessionReplacementRepository replacement;
    /** 锁后旧族读取与撤销。 */
    private com.things.link.enduser.domain.AppRefreshTokenRepository refresh;
    /** ADR0097：观察项目许可先于用户锁与验密。 */
    private ProjectLifecycleAccessService lifecycle;
    /** projectKey可信路由进入受RLS事实前的集中范围入口。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;

    private UUID tenantId;
    private UUID projectId;
    private UUID appUserId;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(AppAuthRateLimiter.class);
        projectService = mock(ProjectService.class);
        appUserRepository = mock(AppUserRepository.class);
        appUserRoleRepository = mock(AppUserRoleRepository.class);
        sessionService = mock(AppSessionService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        replacement = mock(com.things.link.enduser.domain.AppBrowserSessionReplacementRepository.class);
        refresh = mock(com.things.link.enduser.domain.AppRefreshTokenRepository.class);
        service = new AppAuthenticationService(rateLimiter, projectService, appUserRepository,
                appUserRoleRepository, sessionService, passwordEncoder, transactionLocalRlsScope, lifecycle,
                replacement, refresh);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        when(lifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(true);

        when(rateLimiter.tryAcquire(PROJECT_KEY, USERNAME, CLIENT_IP)).thenReturn(true);
        when(projectService.findDeviceAccessScope(PROJECT_KEY))
                .thenReturn(Optional.of(new ProjectService.DeviceAccessScope(projectId, tenantId)));
    }

    /** 锁前口令不可信；跨用户桥后必须重新读取用户并复用原验密签发。 */
    @Test
    void browserReplacementUsesOnlyLockedCredentialsAndRevokesAfterIssuance() {
        byte[] hash = new byte[32];
        AppUser user = user(AppUser.Status.ACTIVE);
        UUID family = UUID.randomUUID();
        when(appUserRepository.findByTenantAndUsername(tenantId, USERNAME)).thenReturn(Optional.of(user));
        when(appUserRepository.findByIdAndTenant(tenantId, appUserId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "{bcrypt}hashed")).thenReturn(true);
        when(appUserRoleRepository.findByProjectAndUser(projectId, appUserId)).thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE)));
        when(refresh.findByHash(hash)).thenReturn(Optional.of(new com.things.link.enduser.domain.AppRefreshToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, family,
                Instant.now(), Instant.now().plusSeconds(60), null, null)));
        service.loginReplacingBrowserSession(PROJECT_KEY, USERNAME, PASSWORD, CLIENT_IP, hash);
        InOrder order = inOrder(lifecycle, appUserRepository, replacement, passwordEncoder, sessionService, refresh);
        order.verify(lifecycle).lockActiveForWrite(tenantId, projectId);
        order.verify(appUserRepository).findByTenantAndUsername(tenantId, USERNAME);
        order.verify(replacement).lockUsers(appUserId, hash);
        order.verify(appUserRepository).findByIdAndTenant(tenantId, appUserId);
        order.verify(refresh).findByHash(hash);
        order.verify(passwordEncoder).matches(PASSWORD, "{bcrypt}hashed");
        order.verify(sessionService).issueForLogin(any());
        order.verify(refresh).revokeFamily(org.mockito.ArgumentMatchers.eq(family), any());
    }

    /** 未找到目标仍哑比对，不能凭旧Cookie先撤旧族。 */
    @Test
    void missingBrowserLoginTargetNeverLocksOrRevokesOldSession() {
        when(appUserRepository.findByTenantAndUsername(tenantId, USERNAME)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loginReplacingBrowserSession(PROJECT_KEY, USERNAME, PASSWORD, CLIENT_IP, new byte[32]))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(replacement, refresh, sessionService);
        verify(passwordEncoder).matches(org.mockito.ArgumentMatchers.eq(PASSWORD), any());
    }

    /** 锁后用户删除或改名不能继续使用锁前身份签发。 */
    @Test
    void browserLoginRejectsTargetDisappearingAfterOrderedLocks() {
        when(appUserRepository.findByTenantAndUsername(tenantId, USERNAME)).thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(appUserRepository.findByIdAndTenant(tenantId, appUserId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loginReplacingBrowserSession(PROJECT_KEY, USERNAME, PASSWORD, CLIENT_IP, null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(refresh, sessionService);
    }

    /** 登录成功：以 projectKey 解析出的租户/项目签发会话，subject 是 appUserId。 */
    @Test
    void loginIssuesSessionWithResolvedScope() {
        when(appUserRepository.lockByTenantAndUsername(tenantId, USERNAME))
                .thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(passwordEncoder.matches(PASSWORD, "{bcrypt}hashed")).thenReturn(true);
        when(appUserRoleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE)));

        AppIssuedSession expected = new AppIssuedSession(
                new AppAccessToken("at", Instant.now()), "rt", Instant.now());
        when(sessionService.issueForLogin(any())).thenReturn(expected);

        AppIssuedSession result = service.login(PROJECT_KEY, USERNAME, PASSWORD, CLIENT_IP);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(transactionLocalRlsScope, lifecycle, appUserRepository,
                passwordEncoder, sessionService);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(lifecycle).lockActiveForWrite(tenantId, projectId);
        order.verify(appUserRepository).lockByTenantAndUsername(tenantId, USERNAME);
        order.verify(passwordEncoder).matches(PASSWORD, "{bcrypt}hashed");
        order.verify(sessionService).issueForLogin(any());
        ArgumentCaptor<AppAuthenticatedPrincipal> captor =
                ArgumentCaptor.forClass(AppAuthenticatedPrincipal.class);
        verify(sessionService).issueForLogin(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().projectId()).isEqualTo(projectId);
        assertThat(captor.getValue().appUserId()).isEqualTo(appUserId);
    }

    /** 用户名大小写与首尾空白被规范化。 */
    @Test
    void usernameIsNormalizedBeforeLookup() {
        when(appUserRepository.lockByTenantAndUsername(tenantId, "alice"))
                .thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(passwordEncoder.matches(PASSWORD, "{bcrypt}hashed")).thenReturn(true);
        when(appUserRoleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE)));
        when(sessionService.issueForLogin(any())).thenReturn(new AppIssuedSession(
                new AppAccessToken("at", Instant.now()), "rt", Instant.now()));

        service.login(PROJECT_KEY, "  ALICE ", PASSWORD, CLIENT_IP);

        verify(appUserRepository).lockByTenantAndUsername(tenantId, "alice");
    }

    /** 口令错误与用户不存在合并为同一个 60006，且都走了口令比对抹平时序。 */
    @Test
    void wrongPasswordMapsToLoginFailed() {
        when(appUserRepository.lockByTenantAndUsername(tenantId, USERNAME))
                .thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(passwordEncoder.matches(PASSWORD, "{bcrypt}hashed")).thenReturn(false);

        assertErrorCode(60006);
    }

    @Test
    void unknownUserMapsToLoginFailed() {
        when(appUserRepository.lockByTenantAndUsername(tenantId, USERNAME))
                .thenReturn(Optional.empty());

        assertErrorCode(60006);
        // 用户不存在时仍与哑哈希比对一次，抹平「存在/不存在」的时序差
        verify(passwordEncoder).matches(any(), any());
    }

    @Test
    void invalidProjectKeyMapsToLoginFailed() {
        when(projectService.findDeviceAccessScope(PROJECT_KEY)).thenReturn(Optional.empty());

        assertErrorCode(60006);
    }

    /** 租户级锁定拒绝登录（与项目级停用不同，见 app_user.status 注释）。 */
    @Test
    void lockedUserMapsToLoginFailed() {
        when(appUserRepository.lockByTenantAndUsername(tenantId, USERNAME))
                .thenReturn(Optional.of(user(AppUser.Status.LOCKED)));
        when(passwordEncoder.matches(PASSWORD, "{bcrypt}hashed")).thenReturn(true);

        assertErrorCode(60006);
    }

    @Test
    void userWithoutRoleMapsToLoginFailed() {
        when(appUserRepository.lockByTenantAndUsername(tenantId, USERNAME))
                .thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(passwordEncoder.matches(PASSWORD, "{bcrypt}hashed")).thenReturn(true);
        when(appUserRoleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.empty());

        assertErrorCode(60006);
    }

    /** 项目角色停用同样拒签：不产生「认证成功但每个请求必 403」的废令牌。 */
    @Test
    void disabledRoleMapsToLoginFailed() {
        when(appUserRepository.lockByTenantAndUsername(tenantId, USERNAME))
                .thenReturn(Optional.of(user(AppUser.Status.ACTIVE)));
        when(passwordEncoder.matches(PASSWORD, "{bcrypt}hashed")).thenReturn(true);
        when(appUserRoleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(role(AppUserRole.Status.DISABLED)));

        assertErrorCode(60006);
    }

    @Test
    void rateLimitReturnsTooManyRequests() {
        when(rateLimiter.tryAcquire(PROJECT_KEY, USERNAME, CLIENT_IP)).thenReturn(false);

        assertThatThrownBy(() -> service.login(PROJECT_KEY, USERNAME, PASSWORD, CLIENT_IP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);
    }

    /** ADR0097：冻结许可拒绝先于用户锁、验密和会话签发，错误仍是60006。 */
    @Test
    void projectPermissionDenialPrecedesUserLockAndPasswordCheck() {
        when(lifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        assertErrorCode(60006);
        verifyNoInteractions(appUserRepository, passwordEncoder, sessionService);
    }

    private void assertErrorCode(int expectedCode) {
        assertThatThrownBy(() -> service.login(PROJECT_KEY, USERNAME, PASSWORD, CLIENT_IP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode().code())
                .isEqualTo(expectedCode);
    }

    private AppUser user(AppUser.Status status) {
        return new AppUser(appUserId, tenantId, USERNAME, "{bcrypt}hashed", null,
                status, null, Instant.now());
    }

    private AppUserRole role(AppUserRole.Status status) {
        return new AppUserRole(UUID.randomUUID(), tenantId, projectId, appUserId,
                EndUserRole.OPERATOR, status, Instant.now());
    }

}
