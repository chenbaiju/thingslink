package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.domain.AppPushTokenRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 安装实例注册、原位轮换、恢复和幂等吊销的应用层合同（G2-A2a L1）。 */
class AppPushTokenServiceTests {

    /** 冻结业务时刻。 */
    private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");
    /** 仓储桩。 */
    private AppPushTokenRepository repository;
    /** 加密端口桩。 */
    private PushTokenCipher cipher;
    /** 冻结许可须早于仓储锁与加密，不能改写跨项目共用安装。 */
    private AppProjectWriteGuard projectWriteGuard;
    /** 被测服务。 */
    private AppPushTokenService service;
    /** 租户。 */
    private UUID tenantId;
    /** 已验证App声明中的当前项目。 */
    private UUID projectId;
    /** 当前 App 用户。 */
    private UUID appUserId;
    /** 安装实例。 */
    private UUID installationId;
    /** 固定信封。 */
    private EncryptedPushToken encrypted;

    /** 建立默认成功路径。 */
    @BeforeEach
    void setUp() {
        repository = mock(AppPushTokenRepository.class);
        cipher = mock(PushTokenCipher.class);
        projectWriteGuard = mock(AppProjectWriteGuard.class);
        service = new AppPushTokenService(repository, cipher, projectWriteGuard, Clock.fixed(NOW, ZoneOffset.UTC));
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        installationId = UUID.randomUUID();
        encrypted = new EncryptedPushToken(new byte[32], new byte[12], "new");
        when(cipher.encrypt(any(), any())).thenReturn(encrypted);
        when(repository.insert(any(), any())).thenReturn(1);
        when(repository.activate(any(), any())).thenReturn(1);
    }

    /** 首次注册先锁不存在键，再加密并只写一条 ACTIVE 事实。 */
    @Test
    void firstRegistrationCreatesEncryptedActiveFact() {
        when(repository.findForUpdate(tenantId, appUserId, installationId)).thenReturn(Optional.empty());

        service.register(tenantId, projectId, appUserId, installationId, AppPushToken.Provider.HUAWEI, "plain-secret");

        ArgumentCaptor<AppPushToken> token = ArgumentCaptor.forClass(AppPushToken.class);
        verify(cipher).encrypt(token.capture(), org.mockito.ArgumentMatchers.eq("plain-secret"));
        assertThat(token.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(token.getValue().appUserId()).isEqualTo(appUserId);
        assertThat(token.getValue().installationId()).isEqualTo(installationId);
        assertThat(token.getValue().status()).isEqualTo(AppPushToken.Status.ACTIVE);
        assertThat(token.getValue().createdAt()).isEqualTo(NOW);
        verify(repository).insert(token.getValue(), encrypted);
        InOrder order = inOrder(projectWriteGuard, repository, cipher);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(repository).lockRegistration(tenantId, appUserId, installationId);
        order.verify(repository).findForUpdate(tenantId, appUserId, installationId);
        order.verify(cipher).encrypt(any(), org.mockito.ArgumentMatchers.eq("plain-secret"));
        order.verify(repository).insert(any(), any());
    }

    /** 重新注册复用原事实 ID/createdAt，同时可更换 provider 并恢复 REVOKED。 */
    @Test
    void reregistrationRotatesInPlaceAndRestoresRevokedFact() {
        UUID stableId = UUID.randomUUID();
        Instant createdAt = NOW.minusSeconds(3600);
        AppPushToken existing = new AppPushToken(
                stableId, tenantId, appUserId, installationId, AppPushToken.Provider.XIAOMI,
                AppPushToken.Status.REVOKED, createdAt, NOW.minusSeconds(60), NOW.minusSeconds(60));
        when(repository.findForUpdate(tenantId, appUserId, installationId)).thenReturn(Optional.of(existing));

        service.register(tenantId, projectId, appUserId, installationId, AppPushToken.Provider.OPPO, "rotated-secret");

        ArgumentCaptor<AppPushToken> token = ArgumentCaptor.forClass(AppPushToken.class);
        verify(repository).activate(token.capture(), org.mockito.ArgumentMatchers.eq(encrypted));
        assertThat(token.getValue().id()).isEqualTo(stableId);
        assertThat(token.getValue().createdAt()).isEqualTo(createdAt);
        assertThat(token.getValue().provider()).isEqualTo(AppPushToken.Provider.OPPO);
        assertThat(token.getValue().status()).isEqualTo(AppPushToken.Status.ACTIVE);
        assertThat(token.getValue().revokedAt()).isNull();
        verify(repository, never()).insert(any(), any());
    }

    /** 唯一事实未写入必须抛错使事务回滚，不能把 0 行当成功。 */
    @Test
    void registrationWriteMissFailsClosed() {
        when(repository.findForUpdate(tenantId, appUserId, installationId)).thenReturn(Optional.empty());
        when(repository.insert(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.register(
                tenantId, projectId, appUserId, installationId, AppPushToken.Provider.VIVO, "secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PUSH 安装实例注册未写入唯一事实");
    }

    /** 吊销只按 JWT 用户范围条件更新，0 行仍按幂等成功。 */
    @Test
    void revokeIsIdempotentAndScopedToCurrentUser() {
        service.revoke(tenantId, projectId, appUserId, installationId);

        InOrder order = inOrder(projectWriteGuard, repository);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(repository).revoke(tenantId, appUserId, installationId, NOW);
        verify(cipher, never()).encrypt(any(), any());
    }

    /** 项目冻结必须在安装锁、加密、注册/轮换之前拒绝，不能仅依赖HTTP过滤。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsRegistrationBeforeRepositoryAndCipher(EndUserErrorCode code) {
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.register(tenantId, projectId, appUserId, installationId,
                AppPushToken.Provider.HUAWEI, "plain-secret")).isSameAs(failure);

        verifyNoInteractions(repository, cipher);
    }

    /** 吊销即使原本会返回零行幂等成功，冻结项目也不得越过许可读取或更新安装。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsIdempotentRevokeBeforeRepositoryAndCipher(EndUserErrorCode code) {
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.revoke(tenantId, projectId, appUserId, installationId))
                .isSameAs(failure);

        verifyNoInteractions(repository, cipher);
    }

    /** 注册项目锁故障原样传播，不能降级为成功或触发加密端口。 */
    @Test
    void projectDatabaseFailureStopsRegistrationBeforeRepositoryAndCipher() {
        QueryTimeoutException failure = new QueryTimeoutException("PUSH注册项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.register(tenantId, projectId, appUserId, installationId,
                AppPushToken.Provider.HUAWEI, "plain-secret")).isSameAs(failure);

        verifyNoInteractions(repository, cipher);
    }

    /** 吊销项目锁故障不得被零行幂等语义吞掉，也不能撤销其他项目仍在使用的安装。 */
    @Test
    void projectDatabaseFailureStopsRevokeBeforeRepositoryAndCipher() {
        QueryTimeoutException failure = new QueryTimeoutException("PUSH吊销项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.revoke(tenantId, projectId, appUserId, installationId))
                .isSameAs(failure);

        verifyNoInteractions(repository, cipher);
    }

    /** Service 直调不能绕过 token 非空与长度边界。 */
    @Test
    void invalidPlainTokenIsRejectedBeforeRepository() {
        assertThatThrownBy(() -> service.register(
                tenantId, projectId, appUserId, installationId, AppPushToken.Provider.MOCK, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PUSH 厂商 token 长度非法");
        verify(repository, never()).lockRegistration(any(), any(), any());
    }
}
