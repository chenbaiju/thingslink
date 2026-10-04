package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppRefreshTokenRepository;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TenantTransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** App 改密用例的单元测试（S11-2a）。 */
class AppPasswordServiceTests {

    /** 口令与改密时间写入端口。 */
    private AppUserRepository appUserRepository;
    /** 全项目会话撤销端口。 */
    private AppRefreshTokenRepository refreshTokenRepository;
    /** 保留原口令策略的可观察替身。 */
    private PasswordEncoder passwordEncoder;
    /** 观察许可是否早于租户范围建立。 */
    private TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;
    /** 冻结及数据库故障必须阻止全部下游交互。 */
    private AppProjectWriteGuard projectWriteGuard;
    /** 被测原事务编排。 */
    private AppPasswordService service;

    /** 已验证身份的租户轴。 */
    private UUID tenantId;
    /** 已验证身份的用户轴。 */
    private UUID appUserId;
    /** 已验证身份的项目轴，不从客户端正文取得。 */
    private UUID projectId;

    /** 完整支持旧成功路径，确保新增拒绝测试不因无关桩缺失而失败。 */
    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        refreshTokenRepository = mock(AppRefreshTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tenantTransactionLocalRlsScope = mock(TenantTransactionLocalRlsScope.class);
        projectWriteGuard = mock(AppProjectWriteGuard.class);
        service = new AppPasswordService(appUserRepository, refreshTokenRepository,
                passwordEncoder, tenantTransactionLocalRlsScope, projectWriteGuard);

        tenantId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        when(appUserRepository.lockByIdAndTenant(tenantId, appUserId))
                .thenReturn(Optional.of(user("{bcrypt}old-hash")));
        when(passwordEncoder.matches("old-password", "{bcrypt}old-hash")).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}new-hash");
    }

    /** 改密成功后更新口令哈希并撤销该用户全部会话。 */
    @Test
    void changePasswordUpdatesHashAndRevokesAllSessions() {
        service.changePassword(appUserId, tenantId, projectId, "old-password", "new-password-123");

        InOrder order = inOrder(projectWriteGuard, tenantTransactionLocalRlsScope,
                appUserRepository, passwordEncoder, refreshTokenRepository);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(tenantTransactionLocalRlsScope).establish(tenantId);
        order.verify(appUserRepository).lockByIdAndTenant(tenantId, appUserId);
        order.verify(passwordEncoder).matches("old-password", "{bcrypt}old-hash");
        order.verify(passwordEncoder).encode("new-password-123");
        order.verify(appUserRepository).updatePassword(eq(tenantId), eq(appUserId),
                eq("{bcrypt}new-hash"), any(Instant.class));
        order.verify(refreshTokenRepository).revokeAllForUser(eq(appUserId), any(Instant.class));
    }

    /** 项目冻结拒绝须早于租户SQL、口令匹配、口令更新和全部会话撤销。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsPasswordChangeBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.changePassword(appUserId, tenantId, projectId,
                "old-password", "new-password-123")).isSameAs(failure);

        verifyNoInteractions(tenantTransactionLocalRlsScope, appUserRepository,
                passwordEncoder, refreshTokenRepository);
    }

    /** 项目锁SQL故障原样传播，不得误报错误密码或继续安全事实写入。 */
    @Test
    void projectDatabaseFailureStopsPasswordChangeBeforeAnyDownstreamAccess() {
        QueryTimeoutException failure = new QueryTimeoutException("改密项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.changePassword(appUserId, tenantId, projectId,
                "old-password", "new-password-123")).isSameAs(failure);

        verifyNoInteractions(tenantTransactionLocalRlsScope, appUserRepository,
                passwordEncoder, refreshTokenRepository);
    }

    /** 生命周期允许后仍须校验原口令，不能把项目许可当作本人凭据。 */
    @Test
    void wrongOldPasswordMapsToPasswordIncorrect() {
        when(passwordEncoder.matches("wrong", "{bcrypt}old-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(appUserId, tenantId, projectId, "wrong", "new-password-123"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_PASSWORD_INCORRECT);
        verify(appUserRepository, never()).updatePassword(any(), any(), any(), any());
    }

    /** 用户不存在时保持原统一访问失效错误。 */
    @Test
    void unknownUserMapsToAccessInvalid() {
        when(appUserRepository.lockByIdAndTenant(tenantId, appUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(appUserId, tenantId, projectId, "old-password", "new-password-123"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_ACCESS_INVALID);
    }

    /** 新口令过短在改密前被拦截，不落库也不撤销会话。 */
    @Test
    void shortNewPasswordIsRejected() {
        assertThatThrownBy(() -> service.changePassword(appUserId, tenantId, projectId, "old-password", "short"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        verify(appUserRepository, never()).updatePassword(any(), any(), any(), any());
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    /** 构造原策略可正常改密的ACTIVE用户，不预先修改密码时间。 */
    private AppUser user(String passwordHash) {
        return new AppUser(appUserId, tenantId, "alice", passwordHash, null,
                AppUser.Status.ACTIVE, null, Instant.now());
    }

}
