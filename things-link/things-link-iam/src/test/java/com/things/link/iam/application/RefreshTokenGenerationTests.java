package com.things.link.iam.application;

import com.things.link.iam.domain.IamErrorCode;
import com.things.link.iam.domain.RefreshToken;
import com.things.link.iam.domain.RefreshTokenRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0073控制台刷新与项目切换必须在任何持久副作用前比较项目生命周期代次。 */
class RefreshTokenGenerationTests {

    /** 持久刷新事实所属账号。 */
    private final UUID accountId = UUID.randomUUID();
    /** 持久刷新事实所属租户。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 删除前选中的源项目。 */
    private final UUID projectId = UUID.randomUUID();
    /** 项目切换的恢复后目标项目。 */
    private final UUID targetProjectId = UUID.randomUUID();
    /** 原刷新令牌ID用于精确观察markRotated。 */
    private final UUID tokenId = UUID.randomUUID();
    /** 原轮换族必须由新令牌继承。 */
    private final UUID familyId = UUID.randomUUID();
    /** 只替换持久刷新仓储。 */
    private RefreshTokenRepository tokens;
    /** 账号与租户成员复核端口。 */
    private CurrentUserService users;
    /** 捕获最终访问令牌身份。 */
    private TokenIssuer issuer;
    /** 项目生命周期代次权威端口。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 使用生产服务验证调用次序与零副作用。 */
    private RefreshTokenService service;

    /** 每例创建独立替身；复用撤销分支若被错误触发会在verify中直接暴露。 */
    @BeforeEach
    void setUp() {
        tokens = mock(RefreshTokenRepository.class);
        users = mock(CurrentUserService.class);
        issuer = mock(TokenIssuer.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        service = new RefreshTokenService(tokens, users, issuer, lifecycle,
                new SessionProperties(Duration.ofDays(7), null, null, null, null, null),
                mock(PlatformTransactionManager.class));
    }

    /** 删除前已轮换令牌先因代次失配20020，不能撤销恢复后同族的新会话。 */
    @Test
    void staleRotatedTokenRejectsBeforeReuseRevocation() {
        RefreshToken stale = token(3L, UUID.randomUUID());
        when(tokens.findByHash(any(byte[].class))).thenReturn(Optional.of(stale));
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, projectId))
                .thenReturn(OptionalLong.of(4L));

        assertInvalid(() -> service.rotate("stale-rotated", ClientContext.UNKNOWN));

        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, projectId);
        verify(tokens, never()).revokeFamily(any(), any());
        verify(tokens, never()).save(any(), any(), any(), any());
        verify(tokens, never()).markRotated(any(), any());
        verifyNoInteractions(users, issuer);
    }

    /** 删除前可用令牌不能通过项目切换取得新代次，拒绝早于用户复核、save与mark。 */
    @Test
    void staleTokenCannotSwitchIntoCurrentGeneration() {
        when(tokens.findByHash(any(byte[].class))).thenReturn(Optional.of(token(3L, null)));
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, projectId))
                .thenReturn(OptionalLong.of(4L));

        assertInvalid(() -> service.switchProject("stale-switch", targetProjectId, ClientContext.UNKNOWN));

        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, projectId);
        verify(tokens, never()).save(any(), any(), any(), any());
        verify(tokens, never()).markRotated(any(), any());
        verify(tokens, never()).revokeFamily(any(), any());
        verifyNoInteractions(users, issuer);
    }

    /** 当前代次刷新保留项目与代次，真实执行新token保存后才标记旧token轮换。 */
    @Test
    void currentGenerationRotatePersistsSameGeneration() {
        RefreshToken current = token(4L, null);
        when(tokens.findByHash(any(byte[].class))).thenReturn(Optional.of(current));
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, projectId))
                .thenReturn(OptionalLong.of(4L));
        when(users.resolve(accountId, tenantId))
                .thenReturn(new CurrentUser(accountId, "owner@example.com", "Owner", tenantId));
        when(issuer.issue(any())).thenReturn(accessToken());

        IssuedSession result = service.rotate("current-refresh", ClientContext.UNKNOWN);

        assertThat(result.accessToken().value()).isEqualTo("new-access");
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(saved.capture(), any(byte[].class), eq(null), eq(null));
        assertThat(saved.getValue().projectId()).isEqualTo(projectId);
        assertThat(saved.getValue().projectLifecycleGeneration()).isEqualTo(4L);
        assertThat(saved.getValue().familyId()).isEqualTo(familyId);
        verify(tokens).markRotated(tokenId, saved.getValue().id());
        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, projectId);
        verify(issuer).issue(new AuthenticatedPrincipal(accountId, tenantId, projectId, 4L));
    }

    /** 合法切换读取目标项目当前代次，新refresh与JWT都绑定目标代次并轮换原token。 */
    @Test
    void currentGenerationSwitchPersistsTargetGeneration() {
        when(tokens.findByHash(any(byte[].class))).thenReturn(Optional.of(token(4L, null)));
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, projectId))
                .thenReturn(OptionalLong.of(4L));
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, targetProjectId))
                .thenReturn(OptionalLong.of(9L));
        when(users.resolve(accountId, tenantId))
                .thenReturn(new CurrentUser(accountId, "owner@example.com", "Owner", tenantId));
        when(issuer.issue(any())).thenReturn(accessToken());

        IssuedSession result = service.switchProject(
                "current-switch", targetProjectId, new ClientContext("agent", "127.0.0.1"));

        assertThat(result.accessToken().value()).isEqualTo("new-access");
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(saved.capture(), any(byte[].class), eq("agent"), eq("127.0.0.1"));
        assertThat(saved.getValue().projectId()).isEqualTo(targetProjectId);
        assertThat(saved.getValue().projectLifecycleGeneration()).isEqualTo(9L);
        assertThat(saved.getValue().familyId()).isEqualTo(familyId);
        verify(tokens).markRotated(tokenId, saved.getValue().id());
        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, projectId);
        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, targetProjectId);
        verify(issuer).issue(new AuthenticatedPrincipal(accountId, tenantId, targetProjectId, 9L));
    }

    /** 新登录未取得项目SHARE锁时立即拒绝，不能保存任何刷新令牌或签发JWT。 */
    @Test
    void loginLockFailureHasNoCredentialSideEffect() {
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, projectId))
                .thenReturn(OptionalLong.empty());

        assertInvalid(() -> service.issueForLogin(
                new AuthenticatedPrincipal(accountId, tenantId, projectId, 0L), ClientContext.UNKNOWN));

        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, projectId);
        verify(tokens, never()).save(any(), any(), any(), any());
        verify(tokens, never()).markRotated(any(), any());
        verify(tokens, never()).revokeFamily(any(), any());
        verifyNoInteractions(users, issuer);
    }

    /** 刷新源项目未取得SHARE锁时拒绝早于复用处置，不能污染恢复后的令牌族。 */
    @Test
    void rotateLockFailureHasNoCredentialOrRevocationSideEffect() {
        when(tokens.findByHash(any(byte[].class))).thenReturn(Optional.of(token(4L, UUID.randomUUID())));
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, projectId))
                .thenReturn(OptionalLong.empty());

        assertInvalid(() -> service.rotate("frozen-rotate", ClientContext.UNKNOWN));

        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, projectId);
        verify(tokens, never()).save(any(), any(), any(), any());
        verify(tokens, never()).markRotated(any(), any());
        verify(tokens, never()).revokeFamily(any(), any());
        verifyNoInteractions(users, issuer);
    }

    /** 项目切换在源项目锁未取得时终止，不能借目标项目签发新代次凭据。 */
    @Test
    void switchLockFailureHasNoCredentialOrRotationSideEffect() {
        when(tokens.findByHash(any(byte[].class))).thenReturn(Optional.of(token(4L, null)));
        when(lifecycle.lockActiveGenerationForProjectToken(accountId, projectId))
                .thenReturn(OptionalLong.empty());

        assertInvalid(() -> service.switchProject("frozen-switch", targetProjectId, ClientContext.UNKNOWN));

        verify(lifecycle).lockActiveGenerationForProjectToken(accountId, projectId);
        verify(lifecycle, never()).lockActiveGenerationForProjectToken(accountId, targetProjectId);
        verify(tokens, never()).save(any(), any(), any(), any());
        verify(tokens, never()).markRotated(any(), any());
        verify(tokens, never()).revokeFamily(any(), any());
        verifyNoInteractions(users, issuer);
    }

    /** 无项目登录固定签发代次0，不能访问与该身份无关的项目锁端口。 */
    @Test
    void projectlessLoginUsesGenerationZeroWithoutProjectLock() {
        when(issuer.issue(any())).thenReturn(accessToken());

        IssuedSession result = service.issueForLogin(
                new AuthenticatedPrincipal(accountId, tenantId, null, 0L), ClientContext.UNKNOWN);

        assertThat(result.accessToken().value()).isEqualTo("new-access");
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(saved.capture(), any(byte[].class), eq(null), eq(null));
        assertThat(saved.getValue().projectId()).isNull();
        assertThat(saved.getValue().projectLifecycleGeneration()).isZero();
        verify(issuer).issue(new AuthenticatedPrincipal(accountId, tenantId, null, 0L));
        verifyNoInteractions(lifecycle, users);
        verify(tokens, never()).markRotated(any(), any());
        verify(tokens, never()).revokeFamily(any(), any());
    }

    /** 构造仍在有效期内、带指定代次及可选后继的持久令牌。 */
    private RefreshToken token(long generation, UUID replacedBy) {
        return new RefreshToken(tokenId, accountId, tenantId, projectId, generation, familyId,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), null, replacedBy);
    }

    /** 固定访问令牌结果，签发声明本身由JwtTokenIssuerTests覆盖。 */
    private AccessToken accessToken() {
        return new AccessToken("new-access", Instant.now().plusSeconds(900));
    }

    /** 代次失效沿既有IAM 20020，不接受任意异常冒充正确拒绝。 */
    private void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(IamErrorCode.INVALID_TOKEN));
    }
}
