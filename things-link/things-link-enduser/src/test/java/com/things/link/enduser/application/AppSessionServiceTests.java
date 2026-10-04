package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppRefreshToken;
import com.things.link.enduser.domain.AppRefreshTokenRepository;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.support.tenant.TenantTransactionLocalRlsScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/** App 会话轮换 / 撤销用例的单元测试（S11-2a）。 */
class AppSessionServiceTests {

    private AppRefreshTokenRepository refreshTokenRepository;
    private AppUserRepository appUserRepository;
    private AppUserRoleRepository appUserRoleRepository;
    private AppTokenIssuer tokenIssuer;
    /** 每例单独设精确可信二元组许可，禁止全局允许桩掩盖无资格分支。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 项目会话完整范围组件替身。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 跨项目会话撤销的租户范围组件替身。 */
    private TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;
    private AppSessionService service;
    /** 真实执行TransactionTemplate回调，并观察复用撤销独立提交或回滚。 */
    private PlatformTransactionManager transactionManager;

    private UUID tenantId;
    private UUID projectId;
    private UUID appUserId;
    private UUID familyId;
    private UUID tokenId;

    /** 原分支各自声明是否需要许可，不配置全局或宽泛的允许返回。 */
    @BeforeEach
    void setUp() {
        refreshTokenRepository = mock(AppRefreshTokenRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        appUserRoleRepository = mock(AppUserRoleRepository.class);
        tokenIssuer = mock(AppTokenIssuer.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        tenantTransactionLocalRlsScope = mock(TenantTransactionLocalRlsScope.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new AppSessionService(refreshTokenRepository, appUserRepository,
                appUserRoleRepository, tokenIssuer, lifecycle, transactionLocalRlsScope,
                tenantTransactionLocalRlsScope,
                new AppSessionProperties(Duration.ofDays(30)), transactionManager);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        familyId = UUID.randomUUID();
        tokenId = UUID.randomUUID();
    }

    /** 缺失/空白令牌统一 60007，且不触仓储。 */
    @Test
    void rotateRejectsMissingToken() {
        assertRefreshInvalid(() -> service.rotate(null));
        assertRefreshInvalid(() -> service.rotate("   "));
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void rotateRejectsUnknownToken() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.empty());
        assertRefreshInvalid(() -> service.rotate("forged-token"));
    }

    /** 复用检测：已被轮换的令牌再次出现，整族作废并 60007。 */
    @Test
    void reuseDetectedRevokesWholeFamily() {
        allowUserLock();
        AppRefreshToken rotated = token(Instant.now().plusSeconds(3600), null, UUID.randomUUID());
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(rotated));
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(true);

        assertRefreshInvalid(() -> service.rotate("replayed-token"));

        // 整族作废发生在独立事务里（复用检测命中即安全事件），这里只验证最终效果
        verify(refreshTokenRepository).revokeFamily(eq(familyId), any(Instant.class));
        verify(lifecycle).lockActiveForWrite(tenantId, projectId, 0L);
        verifyNoInteractions(tokenIssuer, appUserRoleRepository);
        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
        ArgumentCaptor<TransactionDefinition> transaction = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(transaction.capture());
        assertThat(transaction.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        InOrder order = inOrder(lifecycle, transactionLocalRlsScope, appUserRepository, refreshTokenRepository);
        order.verify(lifecycle).lockActiveForWrite(tenantId, projectId, 0L);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(appUserRepository).lockByIdAndTenant(tenantId, appUserId);
        order.verify(refreshTokenRepository).findByHash(any());
        order.verify(refreshTokenRepository).revokeFamily(eq(familyId), any());
    }

    /** 旧代次refresh即使已经轮换，也不得撤销恢复后新代次的任何会话族。 */
    @Test
    void generationMismatchPrecedesReuseSideEffect() {
        AppRefreshToken rotated = token(
                Instant.now().plusSeconds(3600), null, UUID.randomUUID(), 3L);
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(rotated));
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 3L)).thenReturn(false);

        assertRefreshInvalid(() -> service.rotate("stale-replayed-token"));

        verify(lifecycle).lockActiveForWrite(tenantId, projectId, 3L);
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verify(refreshTokenRepository, never()).markRotated(any(), any());
        verifyNoInteractions(tokenIssuer, transactionLocalRlsScope, tenantTransactionLocalRlsScope,
                appUserRepository, appUserRoleRepository);
    }

    @Test
    void expiredTokenMapsToRefreshInvalid() {
        allowUserLock();
        AppRefreshToken expired = token(Instant.now().minusSeconds(1), null, null);
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(expired));
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(true);

        assertRefreshInvalid(() -> service.rotate("expired-token"));
        verify(lifecycle).lockActiveForWrite(tenantId, projectId, 0L);
        verify(refreshTokenRepository, times(2)).findByHash(any());
        verify(refreshTokenRepository, never()).markRotated(any(), any());
    }

    /** 刷新成功：回库复验通过后签发新令牌并标记旧令牌已轮换。 */
    @Test
    void rotateIssuesNewTokenAndMarksRotated() {
        allowUserLock();
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(true);
        AppRefreshToken existing = token(Instant.now().plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(existing));
        when(appUserRepository.findByIdAndTenant(tenantId, appUserId)).thenReturn(Optional.of(user()));
        when(appUserRoleRepository.findByProjectAndUser(projectId, appUserId)).thenReturn(Optional.of(role()));
        when(tokenIssuer.issue(any())).thenReturn(new AppAccessToken("new-at", Instant.now()));

        when(refreshTokenRepository.markRotated(eq(tokenId), any())).thenReturn(1);

        AppIssuedSession result = service.rotate("valid-token");

        assertThat(result.accessToken().value()).isEqualTo("new-at");
        assertThat(result.refreshToken()).isNotBlank();
        verify(refreshTokenRepository).markRotated(eq(tokenId), any(UUID.class));
        InOrder order = inOrder(lifecycle, transactionLocalRlsScope, appUserRepository,
                refreshTokenRepository, tokenIssuer);
        order.verify(lifecycle).lockActiveForWrite(tenantId, projectId, 0L);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(appUserRepository).lockByIdAndTenant(tenantId, appUserId);
        order.verify(refreshTokenRepository).findByHash(any());
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(appUserRepository).findByIdAndTenant(tenantId, appUserId);
        order.verify(refreshTokenRepository).save(any(), any());
        order.verify(tokenIssuer).issue(eq(new AppAuthenticatedPrincipal(tenantId, projectId, appUserId, 0L)));
        order.verify(refreshTokenRepository).markRotated(eq(tokenId), any(UUID.class));
        verifyNoInteractions(tenantTransactionLocalRlsScope);
    }

    /** 刷新时回库复验用户状态：已锁定用户即使持有有效刷新令牌也拿不到新令牌。 */
    @Test
    void rotateReverifiesUserStatus() {
        allowUserLock();
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(true);
        AppRefreshToken existing = token(Instant.now().plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(existing));
        when(appUserRepository.findByIdAndTenant(tenantId, appUserId))
                .thenReturn(Optional.of(new AppUser(appUserId, tenantId, "alice", "{bcrypt}x",
                        null, AppUser.Status.LOCKED, null, Instant.now())));

        assertRefreshInvalid(() -> service.rotate("valid-token"));
        verify(refreshTokenRepository, never()).markRotated(any(), any());
    }

    /** 新登录精确匹配已认证身份，取得许可之后才保存refresh和签发JWT。 */
    @Test
    void loginObtainsTrustedProjectPermissionBeforeIssuingSession() {
        allowUserLock();
        AppAuthenticatedPrincipal principal = new AppAuthenticatedPrincipal(tenantId, projectId, appUserId);
        when(lifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(true);
        when(lifecycle.snapshot(tenantId, projectId)).thenReturn(new ProjectAccessPolicy(true, true, 7L));
        AppAuthenticatedPrincipal qualified = new AppAuthenticatedPrincipal(tenantId, projectId, appUserId, 7L);
        when(tokenIssuer.issue(qualified)).thenReturn(new AppAccessToken("new-login", Instant.now()));

        AppIssuedSession result = service.issueForLogin(principal);

        assertThat(result.accessToken().value()).isEqualTo("new-login");
        InOrder order = inOrder(lifecycle, transactionLocalRlsScope, appUserRepository,
                refreshTokenRepository, tokenIssuer);
        order.verify(lifecycle).lockActiveForWrite(tenantId, projectId);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(appUserRepository).lockByIdAndTenant(tenantId, appUserId);
        order.verify(lifecycle).snapshot(tenantId, projectId);
        order.verify(refreshTokenRepository).save(any(), any());
        order.verify(tokenIssuer).issue(qualified);
        ArgumentCaptor<AppRefreshToken> saved = ArgumentCaptor.forClass(AppRefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture(), any());
        assertThat(saved.getValue().projectGeneration()).isEqualTo(7L);
        verifyNoInteractions(tenantTransactionLocalRlsScope);
    }

    /** 删除、归档或不匹配的已认证登录范围统一60006，不能产生不可用的新会话。 */
    @Test
    void rejectedLoginDoesNotSaveOrIssueAnyToken() {
        when(lifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        AppAuthenticatedPrincipal principal = new AppAuthenticatedPrincipal(tenantId, projectId, appUserId);

        assertThatThrownBy(() -> service.issueForLogin(principal)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode()).isEqualTo(EndUserErrorCode.END_USER_LOGIN_FAILED);

        verify(lifecycle).lockActiveForWrite(tenantId, projectId);
        verifyNoInteractions(refreshTokenRepository, tokenIssuer, transactionLocalRlsScope,
                tenantTransactionLocalRlsScope, appUserRepository, appUserRoleRepository);
    }

    /** 已存在refresh必须使用其持久归属；许可拒绝早于用户复验、范围SQL和轮换标记。 */
    @Test
    void rejectedRefreshDoesNotReverifyIssueSaveOrRotate() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(token(Instant.now().plusSeconds(3600), null, null)));
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(false);

        assertRefreshInvalid(() -> service.rotate("valid-but-frozen"));

        verify(lifecycle).lockActiveForWrite(tenantId, projectId, 0L);
        verifyNoInteractions(tokenIssuer, transactionLocalRlsScope, tenantTransactionLocalRlsScope,
                appUserRepository, appUserRoleRepository);
        verify(refreshTokenRepository, never()).save(any(), any());
        verify(refreshTokenRepository, never()).markRotated(any(), any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    /** 数据库故障不是确定的登录失败，原异常传播且没有产生任何新令牌。 */
    @Test
    void loginPermissionDatabaseFailurePropagatesWithoutIssuing() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("许可数据库不可用");
        when(lifecycle.lockActiveForWrite(tenantId, projectId)).thenThrow(failure);

        assertThatThrownBy(() -> service.issueForLogin(new AppAuthenticatedPrincipal(tenantId, projectId, appUserId)))
                .isSameAs(failure);
        verifyNoInteractions(refreshTokenRepository, tokenIssuer, transactionLocalRlsScope,
                tenantTransactionLocalRlsScope, appUserRepository, appUserRoleRepository);
    }

    /** 刷新许可查询故障不得伪装60007或轮换旧记录，恢复后原refresh仍具备继续处理的前置。 */
    @Test
    void refreshPermissionDatabaseFailurePropagatesWithoutRotation() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(token(Instant.now().plusSeconds(3600), null, null)));
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("许可数据库不可用");
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenThrow(failure);

        assertThatThrownBy(() -> service.rotate("valid-refresh")).isSameAs(failure);

        verifyNoInteractions(tokenIssuer, transactionLocalRlsScope, tenantTransactionLocalRlsScope,
                appUserRepository, appUserRoleRepository);
        verify(refreshTokenRepository, never()).save(any(), any());
        verify(refreshTokenRepository, never()).markRotated(any(), any());
    }

    /** 撤销是幂等的：令牌缺失/不存在都静默成功。 */
    @Test
    void revokeIsIdempotent() {
        service.revoke(null);
        service.revoke("   ");
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.empty());
        service.revoke("unknown-token");

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void revokeRevokesFamily() {
        allowUserLock();
        AppRefreshToken token = token(Instant.now().plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(token));

        service.revoke("valid-token");

        InOrder order = inOrder(transactionLocalRlsScope, appUserRepository, refreshTokenRepository);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(appUserRepository).lockByIdAndTenant(tenantId, appUserId);
        verify(refreshTokenRepository).revokeFamily(eq(familyId), any(Instant.class));
        verifyNoInteractions(lifecycle);
    }

    /** ADR0097：锁前有效快照不能越过已经提交的撤销，必须消费锁后第二次读取。 */
    @Test
    void rotateRejectsRevocationCommittedBeforeUserLock() {
        allowUserLock();
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(true);
        when(refreshTokenRepository.findByHash(any())).thenReturn(
                Optional.of(token(Instant.now().plusSeconds(3600), null, null)),
                Optional.of(token(Instant.now().plusSeconds(3600), Instant.now(), null)));

        assertRefreshInvalid(() -> service.rotate("revoked-while-waiting"));

        verify(refreshTokenRepository, times(2)).findByHash(any());
        verify(refreshTokenRepository, never()).save(any(), any());
        verifyNoInteractions(tokenIssuer, appUserRoleRepository);
    }

    /** ADR0097：用户锁等待期间项目清理可令token消失，退出重读为空仍幂等且不撤销其他族。 */
    @Test
    void logoutDoesNotRevokeTokenRemovedBeforeUserLock() {
        allowUserLock();
        when(refreshTokenRepository.findByHash(any())).thenReturn(
                Optional.of(token(Instant.now().plusSeconds(3600), null, null)), Optional.empty());

        service.revoke("removed-while-waiting");

        verify(refreshTokenRepository, times(2)).findByHash(any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verifyNoInteractions(lifecycle);
    }

    /** ADR0097：CAS未命中是原子签发不变量失败，不得把已生成的新token返回给调用方。 */
    @Test
    void rotateRejectsUnexpectedConditionalUpdateMiss() {
        allowUserLock();
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(true);
        when(refreshTokenRepository.findByHash(any()))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(3600), null, null)));
        when(appUserRepository.findByIdAndTenant(tenantId, appUserId)).thenReturn(Optional.of(user()));
        when(appUserRoleRepository.findByProjectAndUser(projectId, appUserId)).thenReturn(Optional.of(role()));
        when(tokenIssuer.issue(any())).thenReturn(new AppAccessToken("uncommitted", Instant.now()));
        when(refreshTokenRepository.markRotated(eq(tokenId), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rotate("unexpected-cas-miss"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("条件轮换");

        // 这里只证明异常不交付结果；真实事务回滚由Bootstrap资格覆盖。
        verify(refreshTokenRepository).save(any(), any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    /** ADR0097：内层撤销数据库错误须触发独立事务回滚，不能转译成已成功收束的60007。 */
    @Test
    void reuseRevocationDatabaseFailureRollsBackAndPropagates() {
        allowUserLock();
        when(lifecycle.lockActiveForWrite(tenantId, projectId, 0L)).thenReturn(true);
        when(refreshTokenRepository.findByHash(any()))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(3600), null, UUID.randomUUID())));
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("整族撤销数据库故障");
        when(refreshTokenRepository.revokeFamily(eq(familyId), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.rotate("reuse-with-database-failure")).isSameAs(failure);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(refreshTokenRepository, never()).save(any(), any());
    }

    /** ADR0097：全会话撤销使用可信租户恢复范围，并在用户锁之后更新全部项目会话。 */
    @Test
    void revokeAllWaitsForUserLockWithoutProjectAdmission() {
        allowUserLock();

        service.revokeAllForUser(tenantId, appUserId);

        InOrder order = inOrder(tenantTransactionLocalRlsScope, appUserRepository, refreshTokenRepository);
        order.verify(tenantTransactionLocalRlsScope).establish(tenantId);
        order.verify(appUserRepository).lockByIdAndTenant(tenantId, appUserId);
        order.verify(refreshTokenRepository).revokeAllForUser(eq(appUserId), any());
        verifyNoInteractions(transactionLocalRlsScope, lifecycle);
    }

    /** ADR0097：本例显式提供可信用户锁结果，拒绝分支不依赖全局宽泛桩。 */
    private void allowUserLock() {
        when(appUserRepository.lockByIdAndTenant(tenantId, appUserId)).thenReturn(Optional.of(user()));
    }

    private void assertRefreshInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_REFRESH_INVALID);
    }

    private AppRefreshToken token(Instant expiresAt, Instant revokedAt, UUID replacedBy) {
        return token(expiresAt, revokedAt, replacedBy, 0L);
    }

    /** 构造带显式项目代次的持久refresh事实。 */
    private AppRefreshToken token(
            Instant expiresAt, Instant revokedAt, UUID replacedBy, long projectGeneration) {
        return new AppRefreshToken(tokenId, appUserId, tenantId, projectId, projectGeneration, familyId,
                Instant.now(), expiresAt, revokedAt, replacedBy);
    }

    private AppUser user() {
        return new AppUser(appUserId, tenantId, "alice", "{bcrypt}x", null,
                AppUser.Status.ACTIVE, null, Instant.now());
    }

    private AppUserRole role() {
        return new AppUserRole(UUID.randomUUID(), tenantId, projectId, appUserId,
                EndUserRole.OPERATOR, AppUserRole.Status.ACTIVE, Instant.now());
    }

}
