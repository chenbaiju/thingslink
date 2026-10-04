package com.things.link.enduser.application;

import com.things.link.device.application.AppDevice;
import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.domain.AppDeviceBindToken;
import com.things.link.enduser.domain.AppDeviceBindTokenRepository;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CLAIM 签发与原子消费的纯应用层合同（G2-A1c L1）。 */
class DeviceClaimServiceTests {

    /** 当前项目代次；所有成功夹具必须显式携带，避免Mockito默认0掩盖传播缺口。 */
    private static final long PROJECT_GENERATION = 7L;

    /** 项目服务。 */
    private ProjectService projectService;
    /** 设备 application 端口。 */
    private AppDeviceDataPlaneService deviceDataPlane;
    /** 令牌仓储。 */
    private AppDeviceBindTokenRepository tokenRepository;
    /** App 项目角色仓储。 */
    private AppUserRoleRepository roleRepository;
    /** 设备关系仓储。 */
    private AppUserDeviceRepository bindingRepository;
    /** App项目许可先于令牌锁，控制台签发不能误用此错误码合同。 */
    private AppProjectWriteGuard projectWriteGuard;
    /** 控制台路由与App JWT身份进入受RLS事实前的集中范围入口。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 被测服务。 */
    private DeviceClaimService service;

    /** 测试租户。 */
    private UUID tenantId;
    /** 测试项目。 */
    private UUID projectId;
    /** 测试用户。 */
    private UUID appUserId;
    /** 测试设备。 */
    private UUID deviceId;

    /** 建立所有 happy-path 默认桩，单例只覆盖差异条件。 */
    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        deviceDataPlane = mock(AppDeviceDataPlaneService.class);
        tokenRepository = mock(AppDeviceBindTokenRepository.class);
        roleRepository = mock(AppUserRoleRepository.class);
        bindingRepository = mock(AppUserDeviceRepository.class);
        projectWriteGuard = mock(AppProjectWriteGuard.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        service = new DeviceClaimService(projectService, deviceDataPlane, tokenRepository,
                roleRepository, bindingRepository, transactionLocalRlsScope, projectWriteGuard);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        when(projectWriteGuard.requireWritable(tenantId, projectId)).thenReturn(PROJECT_GENERATION);
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(projectService.requireRoutingContext(projectId))
                .thenReturn(new ProjectService.ProjectRoutingContext(tenantId, "claim-project"));
        when(deviceDataPlane.detail(projectId, deviceId)).thenReturn(Optional.of(device()));
        when(bindingRepository.findActivePrimary(projectId, deviceId)).thenReturn(Optional.empty());
        when(bindingRepository.findActive(projectId, appUserId, deviceId)).thenReturn(Optional.empty());
        when(roleRepository.findByProjectAndUser(projectId, appUserId)).thenReturn(Optional.of(activeRole()));
        when(tokenRepository.save(any(), any())).thenReturn(true);
        when(tokenRepository.incrementAttempt(any(), any())).thenReturn(1);
        when(tokenRepository.consume(any(), any(), any(), any())).thenReturn(1);
        when(bindingRepository.createActivePrimary(any())).thenReturn(1);
    }

    /** 签发只返回一次 43 字符高熵明文，落库固定 CLAIM/PRIMARY、10 分钟和 5 次上限。 */
    @Test
    void issuePersistsFrozenClaimContract() {
        DeviceClaimIssuedToken issued = service.issue(projectId, deviceId);

        assertThat(issued.token()).hasSize(43).doesNotContain("=");
        InOrder scopeOrder = inOrder(transactionLocalRlsScope, projectWriteGuard, deviceDataPlane);
        scopeOrder.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        scopeOrder.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        scopeOrder.verify(deviceDataPlane).detail(projectId, deviceId);
        ArgumentCaptor<AppDeviceBindToken> token = ArgumentCaptor.forClass(AppDeviceBindToken.class);
        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(tokenRepository).save(token.capture(), hash.capture());
        assertThat(hash.getValue()).hasSize(32);
        assertThat(token.getValue().purpose()).isEqualTo(AppDeviceBindToken.Purpose.CLAIM);
        assertThat(token.getValue().projectGeneration()).isEqualTo(PROJECT_GENERATION);
        assertThat(token.getValue().targetRole()).isEqualTo(AppUserDevice.RelationRole.PRIMARY);
        assertThat(token.getValue().maxAttempts()).isEqualTo(5);
        assertThat(token.getValue().expiresAt()).isEqualTo(issued.expiresAt());
        assertThat(token.getValue().expiresAt().minusSeconds(600))
                .isEqualTo(token.getValue().createdAt());
    }

    /** 已有 PRIMARY 时不签发，避免制造注定失败的接管凭据。 */
    @Test
    void issueRejectsDeviceWithPrimary() {
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(binding(appUserId)));

        assertCode(EndUserErrorCode.DEVICE_ALREADY_CLAIMED,
                () -> service.issue(projectId, deviceId));
        verify(tokenRepository, never()).save(any(), any());
        verify(projectWriteGuard).requireWritable(tenantId, projectId);
    }

    /** 成功消费先取项目许可，再保留尝试计数、PRIMARY创建与消费标记的原顺序。 */
    @Test
    void consumeCreatesPrimaryAndConsumesToken() {
        AppDeviceBindToken token = availableToken();
        when(tokenRepository.findByProjectAndHashForUpdate(eq(projectId), any()))
                .thenReturn(Optional.of(token));

        DeviceClaimResult result = service.consume(tenantId, projectId, appUserId, "claim-token");

        assertThat(result.deviceId()).isEqualTo(deviceId);
        assertThat(result.relationRole()).isEqualTo(AppUserDevice.RelationRole.PRIMARY);
        InOrder order = inOrder(transactionLocalRlsScope, projectWriteGuard, roleRepository,
                tokenRepository, bindingRepository);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(tokenRepository).findByProjectAndHashForUpdate(eq(projectId), any());
        order.verify(roleRepository).findByProjectAndUser(projectId, appUserId);
        order.verify(tokenRepository).incrementAttempt(projectId, token.id());
        order.verify(bindingRepository).createActivePrimary(any(AppUserDevice.class));
        order.verify(tokenRepository).consume(eq(projectId), eq(token.id()), eq(appUserId), any());
    }

    /** 生命周期拒绝不触碰角色、令牌或关系；noRollbackFor不会留下尝试次数和消费痕迹。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsConsumeBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.consume(tenantId, projectId, appUserId, "claim-token"))
                .isSameAs(failure);

        verifyNoInteractions(roleRepository, tokenRepository, bindingRepository, deviceDataPlane, projectService);
    }

    /** 项目锁SQL超时保留首因，不能转成可提交的BusinessException。 */
    @Test
    void projectDatabaseFailureStopsConsumeBeforeAnyDownstreamAccess() {
        QueryTimeoutException failure = new QueryTimeoutException("CLAIM项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.consume(tenantId, projectId, appUserId, "claim-token"))
                .isSameAs(failure);

        verifyNoInteractions(roleRepository, tokenRepository, bindingRepository, deviceDataPlane, projectService);
    }

    /** 过期令牌仍计入一次已命中复核，但绝不创建关系或消费。 */
    @Test
    void consumeExpiredTokenRecordsAttemptWithoutMutation() {
        AppDeviceBindToken expired = token(
                Instant.now().minusSeconds(1), null, null, 1, 5);
        when(tokenRepository.findByProjectAndHashForUpdate(eq(projectId), any()))
                .thenReturn(Optional.of(expired));

        assertCode(EndUserErrorCode.DEVICE_CLAIM_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, appUserId, "expired"));
        verify(tokenRepository).incrementAttempt(projectId, expired.id());
        verify(bindingRepository, never()).createActivePrimary(any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 已耗尽 5 次复核额度后立即拒绝，不能继续递增或触发任何关系写入。 */
    @Test
    void consumeExhaustedTokenDoesNotRecordAnotherAttempt() {
        AppDeviceBindToken exhausted = token(
                Instant.now().plusSeconds(60), null, null, 5, 5);
        when(tokenRepository.findByProjectAndHashForUpdate(eq(projectId), any()))
                .thenReturn(Optional.of(exhausted));

        assertCode(EndUserErrorCode.DEVICE_CLAIM_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, appUserId, "exhausted"));
        verify(tokenRepository, never()).incrementAttempt(any(), any());
        verify(bindingRepository, never()).createActivePrimary(any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 同一消费人重放仍须先获项目许可，再回读原PRIMARY且不重复写事实。 */
    @Test
    void consumeReplayBySameUserIsIdempotent() {
        AppDeviceBindToken consumed = token(
                Instant.now().plusSeconds(60), Instant.now(), appUserId, 1, 5);
        AppUserDevice binding = binding(appUserId);
        when(tokenRepository.findByProjectAndHashForUpdate(eq(projectId), any()))
                .thenReturn(Optional.of(consumed));
        when(bindingRepository.findActive(projectId, appUserId, deviceId))
                .thenReturn(Optional.of(binding));

        DeviceClaimResult result = service.consume(tenantId, projectId, appUserId, "same-request");

        assertThat(result.bindingId()).isEqualTo(binding.id());
        InOrder order = inOrder(projectWriteGuard, roleRepository, tokenRepository, bindingRepository);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(tokenRepository).findByProjectAndHashForUpdate(eq(projectId), any());
        order.verify(roleRepository).findByProjectAndUser(projectId, appUserId);
        order.verify(bindingRepository).findActive(projectId, appUserId, deviceId);
        verify(tokenRepository, never()).incrementAttempt(any(), any());
        verify(bindingRepository, never()).createActivePrimary(any());
    }

    /** 已被其他用户消费的令牌统一 60012，不能泄露消费人或设备状态。 */
    @Test
    void consumeReplayByOtherUserIsInvalid() {
        AppDeviceBindToken consumed = token(
                Instant.now().plusSeconds(60), Instant.now(), UUID.randomUUID(), 1, 5);
        when(tokenRepository.findByProjectAndHashForUpdate(eq(projectId), any()))
                .thenReturn(Optional.of(consumed));

        assertCode(EndUserErrorCode.DEVICE_CLAIM_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, appUserId, "other-user"));
        verify(bindingRepository, never()).createActivePrimary(any());
    }

    /** 删除前CLAIM无论是否已消费，都在角色、关系、计数和幂等回读前按60012拒绝。 */
    @Test
    void staleGenerationClaimCannotMutateOrReplay() {
        AppDeviceBindToken staleConsumed = token(
                Instant.now().plusSeconds(60), Instant.now(), appUserId, 1, 5,
                PROJECT_GENERATION - 1);
        when(tokenRepository.findByProjectAndHashForUpdate(eq(projectId), any()))
                .thenReturn(Optional.of(staleConsumed));

        assertCode(EndUserErrorCode.DEVICE_CLAIM_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, appUserId, "stale-claim"));

        verifyNoInteractions(roleRepository, bindingRepository, deviceDataPlane);
        verify(tokenRepository, never()).incrementAttempt(any(), any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 关系冲突稳定返回 60013，且不提前消费令牌。 */
    @Test
    void consumePrimaryConflictDoesNotConsumeToken() {
        AppDeviceBindToken token = availableToken();
        when(tokenRepository.findByProjectAndHashForUpdate(eq(projectId), any()))
                .thenReturn(Optional.of(token));
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(binding(UUID.randomUUID())));

        assertCode(EndUserErrorCode.DEVICE_ALREADY_CLAIMED,
                () -> service.consume(tenantId, projectId, appUserId, "conflict"));
        verify(tokenRepository).incrementAttempt(projectId, token.id());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 构造设备 application 投影。 */
    private AppDevice device() {
        return new AppDevice(deviceId, "claim-device", "认领设备", null,
                "OFFLINE", null, null, Instant.now());
    }

    /** 构造 ACTIVE App 项目角色。 */
    private AppUserRole activeRole() {
        return new AppUserRole(UUID.randomUUID(), tenantId, projectId, appUserId,
                EndUserRole.APP_ADMIN, AppUserRole.Status.ACTIVE, Instant.now());
    }

    /** 构造尚未消费的有效 CLAIM。 */
    private AppDeviceBindToken availableToken() {
        return token(Instant.now().plusSeconds(60), null, null, 0, 5);
    }

    /** 构造指定状态的 CLAIM。 */
    private AppDeviceBindToken token(Instant expiresAt, Instant consumedAt, UUID consumer,
                                     int attempts, int maxAttempts) {
        return token(expiresAt, consumedAt, consumer, attempts, maxAttempts, PROJECT_GENERATION);
    }

    /** 构造指定生命周期代次的CLAIM，用于证明旧能力不能进入幂等分支。 */
    private AppDeviceBindToken token(Instant expiresAt, Instant consumedAt, UUID consumer,
                                     int attempts, int maxAttempts, long projectGeneration) {
        Instant createdAt = Instant.now().minusSeconds(60);
        return new AppDeviceBindToken(UUID.randomUUID(), tenantId, projectId, projectGeneration, deviceId,
                AppDeviceBindToken.Purpose.CLAIM, AppUserDevice.RelationRole.PRIMARY,
                null,
                expiresAt, attempts, maxAttempts, consumedAt, consumer, createdAt, createdAt);
    }

    /** 构造有效 PRIMARY 关系。 */
    private AppUserDevice binding(UUID userId) {
        return new AppUserDevice(UUID.randomUUID(), tenantId, projectId, userId, deviceId,
                AppUserDevice.RelationRole.PRIMARY, AppUserDevice.Status.ACTIVE, Instant.now());
    }

    /** 断言业务错误码，避免测试只停在异常类型。 */
    private static void assertCode(EndUserErrorCode expected, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(expected);
    }
}
