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
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
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

/** 主控转移签发、原子关系编排、幂等与冲突的 L1 合同（G2-A1e）。 */
class DeviceTransferServiceTests {

    /** 当前项目代次；成功夹具显式使用该值，禁止Mockito默认0掩盖传播缺口。 */
    private static final long PROJECT_GENERATION = 7L;

    /** 设备查询端口。 */
    private AppDeviceDataPlaneService deviceDataPlane;
    /** 冻结项目应在任何授权查询和转移副作用前被拒绝。 */
    private AppProjectWriteGuard projectWriteGuard;
    /** 令牌仓储。 */
    private AppDeviceBindTokenRepository tokenRepository;
    /** 项目角色仓储。 */
    private AppUserRoleRepository roleRepository;
    /** 设备关系仓储。 */
    private AppUserDeviceRepository bindingRepository;
    /** 审计服务。 */
    private AuditLogService auditLogService;
    /** 被测服务。 */
    private DeviceTransferService service;

    /** 测试租户。 */
    private UUID tenantId;
    /** 测试项目。 */
    private UUID projectId;
    /** 旧主控兼签发者。 */
    private UUID issuerId;
    /** 转移接收者。 */
    private UUID receiverId;
    /** 测试设备。 */
    private UUID deviceId;
    /** 旧主控关系。 */
    private AppUserDevice previousPrimary;

    /** 建立成功路径默认桩。 */
    @BeforeEach
    void setUp() {
        deviceDataPlane = mock(AppDeviceDataPlaneService.class);
        projectWriteGuard = mock(AppProjectWriteGuard.class);
        tokenRepository = mock(AppDeviceBindTokenRepository.class);
        roleRepository = mock(AppUserRoleRepository.class);
        bindingRepository = mock(AppUserDeviceRepository.class);
        auditLogService = mock(AuditLogService.class);
        service = new DeviceTransferService(deviceDataPlane, projectWriteGuard, tokenRepository, roleRepository,
                bindingRepository, auditLogService);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        issuerId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        when(projectWriteGuard.requireWritable(tenantId, projectId)).thenReturn(PROJECT_GENERATION);
        previousPrimary = binding(issuerId, AppUserDevice.RelationRole.PRIMARY);
        when(deviceDataPlane.detail(projectId, deviceId)).thenReturn(Optional.of(device()));
        when(tokenRepository.save(any(), any(byte[].class))).thenReturn(true);
    }

    /** 当前 PRIMARY 签发的令牌必须持久化签发者且审计不含明文。 */
    @Test
    void primaryIssuesTransferTokenBoundToIssuer() {
        when(roleRepository.findByProjectAndUser(projectId, issuerId))
                .thenReturn(Optional.of(activeRole(issuerId)));
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(previousPrimary));

        DeviceTransferIssuedToken issued = service.issue(tenantId, projectId, issuerId, deviceId);

        assertThat(issued.token()).hasSize(43);
        InOrder order = inOrder(projectWriteGuard, roleRepository, deviceDataPlane, tokenRepository);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(roleRepository).findByProjectAndUser(projectId, issuerId);
        order.verify(deviceDataPlane).detail(projectId, deviceId);
        order.verify(tokenRepository).save(any(), any(byte[].class));
        ArgumentCaptor<AppDeviceBindToken> token = ArgumentCaptor.forClass(AppDeviceBindToken.class);
        verify(tokenRepository).save(token.capture(), any(byte[].class));
        assertThat(token.getValue().purpose()).isEqualTo(AppDeviceBindToken.Purpose.TRANSFER);
        assertThat(token.getValue().projectGeneration()).isEqualTo(PROJECT_GENERATION);
        assertThat(token.getValue().issuedByAppUserId()).isEqualTo(issuerId);
        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().details().values())
                .allSatisfy(value -> assertThat(value).isNotEqualTo(issued.token()));
    }

    /** 非当前 PRIMARY 不得签发转移能力。 */
    @Test
    void nonPrimaryCannotIssueTransferToken() {
        when(roleRepository.findByProjectAndUser(projectId, issuerId))
                .thenReturn(Optional.of(activeRole(issuerId)));
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(binding(UUID.randomUUID(), AppUserDevice.RelationRole.PRIMARY)));

        assertCode(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND,
                () -> service.issue(tenantId, projectId, issuerId, deviceId));
        verify(tokenRepository, never()).save(any(), any());
    }

    /** ADR 0064 决策 4：拒绝签发先于原 PRIMARY 校验、令牌写入及安全审计。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsIssueBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        when(roleRepository.findByProjectAndUser(projectId, issuerId))
                .thenReturn(Optional.of(activeRole(issuerId)));
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(previousPrimary));
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.issue(tenantId, projectId, issuerId, deviceId)).isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** noRollbackFor 下冻结拒绝不能增加尝试计数，更不能先降旧 PRIMARY 留下半状态。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsConsumeBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        stubConsumableToken(receiverId);
        when(bindingRepository.demotePrimaryToMember(
                projectId, previousPrimary.id(), issuerId, deviceId)).thenReturn(1);
        when(bindingRepository.createActivePrimary(any())).thenReturn(1);
        when(tokenRepository.consume(any(), any(), any(), any())).thenReturn(1);
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.consume(tenantId, projectId, receiverId, "transfer-token"))
                .isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** 既有 PRIMARY 关系不是冻结后的写许可，已消费 TRANSFER 也不得幂等回读成功。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsConsumedTokenReplayBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        stubConsumableToken(receiverId);
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(transferToken(receiverId)));
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(binding(receiverId, AppUserDevice.RelationRole.PRIMARY)));
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.consume(tenantId, projectId, receiverId, "transfer-token"))
                .isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** 签发项目锁故障保持原SQL异常，不伪装业务拒绝或继续签发能力。 */
    @Test
    void projectDatabaseFailureStopsIssueBeforeAnyDownstreamAccess() {
        QueryTimeoutException failure = new QueryTimeoutException("转移签发项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.issue(tenantId, projectId, issuerId, deviceId)).isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** 消费项目锁故障必须原样传播，并确保主控降级、升级、消费和审计都未开始。 */
    @Test
    void projectDatabaseFailureStopsConsumeBeforeAnyDownstreamAccess() {
        QueryTimeoutException failure = new QueryTimeoutException("转移消费项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.consume(tenantId, projectId, receiverId, "transfer-token"))
                .isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** 未共享的接收者也能在一个事务编排中取得 PRIMARY，旧主控原位降 MEMBER。 */
    @Test
    void receiverWithoutBindingGetsPrimaryAndPreviousIsDemoted() {
        stubConsumableToken(receiverId);
        when(bindingRepository.findActiveForUpdate(projectId, receiverId, deviceId))
                .thenReturn(Optional.empty());
        when(bindingRepository.demotePrimaryToMember(
                projectId, previousPrimary.id(), issuerId, deviceId)).thenReturn(1);
        when(bindingRepository.createActivePrimary(any())).thenReturn(1);
        when(tokenRepository.consume(any(), any(), any(), any())).thenReturn(1);

        DeviceTransferResult result = service.consume(
                tenantId, projectId, receiverId, "transfer-token");

        assertThat(result.relationRole()).isEqualTo(AppUserDevice.RelationRole.PRIMARY);
        InOrder order = inOrder(projectWriteGuard, roleRepository, tokenRepository, bindingRepository, auditLogService);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(tokenRepository).findByProjectAndHashForUpdate(any(), any());
        order.verify(roleRepository).findByProjectAndUser(projectId, receiverId);
        order.verify(tokenRepository).incrementAttempt(any(), any());
        order.verify(bindingRepository).findActivePrimaryForUpdate(projectId, deviceId);
        order.verify(bindingRepository).demotePrimaryToMember(projectId, previousPrimary.id(), issuerId, deviceId);
        order.verify(bindingRepository).createActivePrimary(any());
        order.verify(tokenRepository).consume(any(), any(), any(), any());
        order.verify(auditLogService).record(any());
        verify(bindingRepository).demotePrimaryToMember(
                projectId, previousPrimary.id(), issuerId, deviceId);
        verify(bindingRepository).createActivePrimary(any(AppUserDevice.class));
        verify(tokenRepository).consume(any(), any(), any(), any());
        verify(auditLogService).record(any());
    }

    /** 已有 MEMBER/READ_ONLY 时必须原位升级，不能删除或新建关系。 */
    @Test
    void existingMemberIsPromotedInPlace() {
        AppUserDevice member = binding(receiverId, AppUserDevice.RelationRole.MEMBER);
        stubConsumableToken(receiverId);
        when(bindingRepository.findActiveForUpdate(projectId, receiverId, deviceId))
                .thenReturn(Optional.of(member));
        when(bindingRepository.demotePrimaryToMember(
                projectId, previousPrimary.id(), issuerId, deviceId)).thenReturn(1);
        when(bindingRepository.promoteActiveToPrimary(
                projectId, member.id(), receiverId, deviceId)).thenReturn(1);
        when(tokenRepository.consume(any(), any(), any(), any())).thenReturn(1);

        DeviceTransferResult result = service.consume(
                tenantId, projectId, receiverId, "transfer-token");

        assertThat(result.bindingId()).isEqualTo(member.id());
        verify(bindingRepository).promoteActiveToPrimary(
                projectId, member.id(), receiverId, deviceId);
        verify(bindingRepository, never()).createActivePrimary(any());
    }

    /** 签发后 PRIMARY 已变化时拒绝旧令牌，且不触碰任何关系角色。 */
    @Test
    void staleIssuerTokenCannotTakeOverNewPrimary() {
        stubConsumableToken(receiverId);
        when(bindingRepository.findActivePrimaryForUpdate(projectId, deviceId))
                .thenReturn(Optional.of(binding(UUID.randomUUID(), AppUserDevice.RelationRole.PRIMARY)));

        assertCode(EndUserErrorCode.DEVICE_TRANSFER_CONFLICT,
                () -> service.consume(tenantId, projectId, receiverId, "transfer-token"));
        verify(bindingRepository, never()).demotePrimaryToMember(any(), any(), any(), any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 签发人不能消费自己的转移令牌，避免无意义地改写关系历史。 */
    @Test
    void issuerCannotConsumeOwnTransferToken() {
        stubConsumableToken(issuerId);

        assertCode(EndUserErrorCode.DEVICE_TRANSFER_CONFLICT,
                () -> service.consume(tenantId, projectId, issuerId, "transfer-token"));
        verify(bindingRepository, never()).findActivePrimaryForUpdate(any(), any());
    }

    /** 同接收者在仍为 PRIMARY 时重放幂等返回，不重复写关系和审计。 */
    @Test
    void sameReceiverReplayIsIdempotent() {
        AppDeviceBindToken consumed = transferToken(receiverId);
        when(roleRepository.findByProjectAndUser(projectId, receiverId))
                .thenReturn(Optional.of(activeRole(receiverId)));
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(consumed));
        AppUserDevice current = binding(receiverId, AppUserDevice.RelationRole.PRIMARY);
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(current));

        DeviceTransferResult replay = service.consume(
                tenantId, projectId, receiverId, "transfer-token");

        assertThat(replay.bindingId()).isEqualTo(current.id());
        verify(tokenRepository, never()).incrementAttempt(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 删除前TRANSFER无论未消费或已消费，均在角色、关系、计数与审计前按60016拒绝。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleGenerationTransferCannotMutateOrReplay(boolean consumed) {
        AppDeviceBindToken stale = transferToken(
                consumed ? receiverId : null, PROJECT_GENERATION - 1);
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(stale));

        assertCode(EndUserErrorCode.DEVICE_TRANSFER_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, receiverId, "stale-transfer"));

        verifyNoInteractions(roleRepository, bindingRepository, deviceDataPlane, auditLogService);
        verify(tokenRepository, never()).incrementAttempt(any(), any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 已消费的 CLAIM 也不能借幂等分支伪装成 TRANSFER 成功。 */
    @Test
    void consumedClaimTokenCannotReplayThroughTransferEndpoint() {
        Instant now = Instant.now();
        AppDeviceBindToken consumedClaim = new AppDeviceBindToken(
                UUID.randomUUID(), tenantId, projectId, PROJECT_GENERATION, deviceId,
                AppDeviceBindToken.Purpose.CLAIM, AppUserDevice.RelationRole.PRIMARY,
                null, now.plusSeconds(60), 1, 5,
                now, receiverId, now.minusSeconds(60), now);
        when(roleRepository.findByProjectAndUser(projectId, receiverId))
                .thenReturn(Optional.of(activeRole(receiverId)));
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(consumedClaim));

        assertCode(EndUserErrorCode.DEVICE_TRANSFER_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, receiverId, "claim-token"));

        verify(bindingRepository, never()).findActivePrimary(any(), any());
        verify(tokenRepository, never()).incrementAttempt(any(), any());
    }

    /** 准备尚未消费的 TRANSFER 令牌与消费前置。 */
    private void stubConsumableToken(UUID consumerId) {
        when(roleRepository.findByProjectAndUser(projectId, consumerId))
                .thenReturn(Optional.of(activeRole(consumerId)));
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(transferToken(null)));
        when(tokenRepository.incrementAttempt(any(), any())).thenReturn(1);
        when(bindingRepository.findActivePrimaryForUpdate(projectId, deviceId))
                .thenReturn(Optional.of(previousPrimary));
    }

    /** 构造 TRANSFER 令牌；consumer 非空表示已消费。 */
    private AppDeviceBindToken transferToken(UUID consumer) {
        return transferToken(consumer, PROJECT_GENERATION);
    }

    /** 构造指定项目代次的TRANSFER能力。 */
    private AppDeviceBindToken transferToken(UUID consumer, long projectGeneration) {
        Instant now = Instant.now();
        return new AppDeviceBindToken(
                UUID.randomUUID(), tenantId, projectId, projectGeneration, deviceId,
                AppDeviceBindToken.Purpose.TRANSFER, AppUserDevice.RelationRole.PRIMARY,
                issuerId, now.plusSeconds(60), 0, 5,
                consumer == null ? null : now, consumer, now.minusSeconds(60), now);
    }

    /** 构造 ACTIVE App 项目角色。 */
    private AppUserRole activeRole(UUID userId) {
        return new AppUserRole(UUID.randomUUID(), tenantId, projectId, userId,
                EndUserRole.OBSERVER, AppUserRole.Status.ACTIVE, Instant.now());
    }

    /** 构造指定关系角色的 ACTIVE 设备关系。 */
    private AppUserDevice binding(UUID userId, AppUserDevice.RelationRole role) {
        return new AppUserDevice(UUID.randomUUID(), tenantId, projectId, userId, deviceId,
                role, AppUserDevice.Status.ACTIVE, Instant.now());
    }

    /** 构造可见设备投影。 */
    private AppDevice device() {
        return new AppDevice(deviceId, "transfer-device", "转移设备", null,
                "OFFLINE", null, null, Instant.now());
    }

    /** 精确断言业务错误码。 */
    private static void assertCode(EndUserErrorCode expected, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(expected);
    }
}
