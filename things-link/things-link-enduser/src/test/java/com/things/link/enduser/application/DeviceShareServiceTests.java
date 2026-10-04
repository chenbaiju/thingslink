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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SHARE 签发、角色边界、幂等与冲突的 L1 合同（G2-A1f）。 */
class DeviceShareServiceTests {

    /** 当前项目代次；成功夹具显式使用该值，禁止Mockito默认0伪造通过。 */
    private static final long PROJECT_GENERATION = 7L;

    /** 设备查询端口。 */
    private AppDeviceDataPlaneService deviceDataPlane;
    /** 冻结项目应在任何授权查询和共享副作用前被拒绝。 */
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
    private DeviceShareService service;

    /** 测试租户。 */
    private UUID tenantId;
    /** 测试项目。 */
    private UUID projectId;
    /** 当前主控兼签发者。 */
    private UUID issuerId;
    /** 共享接收者。 */
    private UUID receiverId;
    /** 测试设备。 */
    private UUID deviceId;
    /** 当前主控关系。 */
    private AppUserDevice primary;

    /** 建立成功路径默认桩。 */
    @BeforeEach
    void setUp() {
        deviceDataPlane = mock(AppDeviceDataPlaneService.class);
        projectWriteGuard = mock(AppProjectWriteGuard.class);
        tokenRepository = mock(AppDeviceBindTokenRepository.class);
        roleRepository = mock(AppUserRoleRepository.class);
        bindingRepository = mock(AppUserDeviceRepository.class);
        auditLogService = mock(AuditLogService.class);
        service = new DeviceShareService(deviceDataPlane, projectWriteGuard, tokenRepository, roleRepository,
                bindingRepository, auditLogService);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        issuerId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        when(projectWriteGuard.requireWritable(tenantId, projectId)).thenReturn(PROJECT_GENERATION);
        primary = binding(issuerId, AppUserDevice.RelationRole.PRIMARY);
        when(deviceDataPlane.detail(projectId, deviceId)).thenReturn(Optional.of(device()));
        when(tokenRepository.save(any(), any(byte[].class))).thenReturn(true);
    }

    /** 当前 PRIMARY 可签发绑定自身与 MEMBER 目标的令牌，审计不得泄露明文。 */
    @Test
    void primaryIssuesMemberShareTokenBoundToIssuer() {
        stubIssuer();

        DeviceShareIssuedToken issued = service.issue(
                tenantId, projectId, issuerId, deviceId, AppUserDevice.RelationRole.MEMBER);

        assertThat(issued.token()).hasSize(43);
        assertThat(issued.targetRole()).isEqualTo(AppUserDevice.RelationRole.MEMBER);
        InOrder order = inOrder(projectWriteGuard, roleRepository, deviceDataPlane, tokenRepository);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(roleRepository).findByProjectAndUser(projectId, issuerId);
        order.verify(deviceDataPlane).detail(projectId, deviceId);
        order.verify(tokenRepository).save(any(), any(byte[].class));
        ArgumentCaptor<AppDeviceBindToken> token = ArgumentCaptor.forClass(AppDeviceBindToken.class);
        verify(tokenRepository).save(token.capture(), any(byte[].class));
        assertThat(token.getValue().purpose()).isEqualTo(AppDeviceBindToken.Purpose.SHARE);
        assertThat(token.getValue().projectGeneration()).isEqualTo(PROJECT_GENERATION);
        assertThat(token.getValue().issuedByAppUserId()).isEqualTo(issuerId);
        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().details().values())
                .allSatisfy(value -> assertThat(value).isNotEqualTo(issued.token()));
    }

    /** SHARE 不能签发 PRIMARY，防止绕开 CLAIM/TRANSFER 仲裁。 */
    @Test
    void shareCannotTargetPrimary() {
        assertCode(EndUserErrorCode.DEVICE_SHARE_CONFLICT,
                () -> service.issue(tenantId, projectId, issuerId, deviceId,
                        AppUserDevice.RelationRole.PRIMARY));
        verify(tokenRepository, never()).save(any(), any());
    }

    /** ADR 0064 决策 4：拒绝签发先于角色校验、令牌生成及审计，保留统一生命周期错误。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsIssueBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        stubIssuer();
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.issue(tenantId, projectId, issuerId, deviceId,
                AppUserDevice.RelationRole.MEMBER)).isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** noRollbackFor 不得让冻结后的消费计数、关系及审计先于生命周期拒绝落库。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsConsumeBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        stubConsumableToken(AppUserDevice.RelationRole.MEMBER);
        when(bindingRepository.createActiveShared(any())).thenReturn(1);
        when(tokenRepository.consume(any(), any(), any(), any())).thenReturn(1);
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.consume(tenantId, projectId, receiverId, "share-token"))
                .isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** 已消费 SHARE 的幂等回读也是业务入口，不能把旧成功当成冻结后的许可。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsConsumedTokenReplayBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        stubConsumableToken(AppUserDevice.RelationRole.MEMBER);
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(shareToken(AppUserDevice.RelationRole.MEMBER, receiverId)));
        when(bindingRepository.findActive(projectId, receiverId, deviceId))
                .thenReturn(Optional.of(binding(receiverId, AppUserDevice.RelationRole.MEMBER)));
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.consume(tenantId, projectId, receiverId, "share-token"))
                .isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** 项目锁异常应原样传播，不得降级为令牌业务错误或继续共享写入。 */
    @Test
    void projectDatabaseFailureStopsIssueAndConsumeBeforeAnyDownstreamAccess() {
        QueryTimeoutException failure = new QueryTimeoutException("共享项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.issue(tenantId, projectId, issuerId, deviceId,
                AppUserDevice.RelationRole.MEMBER)).isSameAs(failure);
        assertThatThrownBy(() -> service.consume(tenantId, projectId, receiverId, "share-token"))
                .isSameAs(failure);

        verifyNoInteractions(deviceDataPlane, roleRepository, tokenRepository, bindingRepository, auditLogService);
    }

    /** 非当前 PRIMARY 不得签发共享能力。 */
    @Test
    void nonPrimaryCannotIssueShareToken() {
        when(roleRepository.findByProjectAndUser(projectId, issuerId))
                .thenReturn(Optional.of(activeRole(issuerId)));
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(binding(UUID.randomUUID(), AppUserDevice.RelationRole.PRIMARY)));

        assertCode(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND,
                () -> service.issue(tenantId, projectId, issuerId, deviceId,
                        AppUserDevice.RelationRole.READ_ONLY));
        verify(tokenRepository, never()).save(any(), any());
    }

    /** 无既有关系的接收者取得 MEMBER，消费和审计各发生一次。 */
    @Test
    void receiverGetsMemberRelationship() {
        stubConsumableToken(AppUserDevice.RelationRole.MEMBER);
        when(bindingRepository.createActiveShared(any())).thenReturn(1);
        when(tokenRepository.consume(any(), any(), any(), any())).thenReturn(1);

        DeviceShareResult result = service.consume(
                tenantId, projectId, receiverId, "share-token");

        assertThat(result.relationRole()).isEqualTo(AppUserDevice.RelationRole.MEMBER);
        InOrder order = inOrder(projectWriteGuard, roleRepository, tokenRepository, bindingRepository);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(tokenRepository).findByProjectAndHashForUpdate(any(), any());
        order.verify(roleRepository).findByProjectAndUser(projectId, receiverId);
        order.verify(tokenRepository).incrementAttempt(any(), any());
        order.verify(bindingRepository).createActiveShared(any());
        ArgumentCaptor<AppUserDevice> relation = ArgumentCaptor.forClass(AppUserDevice.class);
        verify(bindingRepository).createActiveShared(relation.capture());
        assertThat(relation.getValue().relationRole()).isEqualTo(AppUserDevice.RelationRole.MEMBER);
        verify(tokenRepository).consume(any(), any(), any(), any());
        verify(auditLogService).record(any());
    }

    /** READ_ONLY 目标必须原样落入关系，不能默认提升为 MEMBER。 */
    @Test
    void receiverGetsReadOnlyRelationship() {
        stubConsumableToken(AppUserDevice.RelationRole.READ_ONLY);
        when(bindingRepository.createActiveShared(any())).thenReturn(1);
        when(tokenRepository.consume(any(), any(), any(), any())).thenReturn(1);

        DeviceShareResult result = service.consume(
                tenantId, projectId, receiverId, "share-token");

        assertThat(result.relationRole()).isEqualTo(AppUserDevice.RelationRole.READ_ONLY);
    }

    /** 主控变化后旧 SHARE 令牌必须失效且不得建立关系。 */
    @Test
    void staleIssuerCannotGrantShare() {
        stubConsumableToken(AppUserDevice.RelationRole.MEMBER);
        when(bindingRepository.findActivePrimaryForUpdate(projectId, deviceId))
                .thenReturn(Optional.of(binding(UUID.randomUUID(), AppUserDevice.RelationRole.PRIMARY)));

        assertCode(EndUserErrorCode.DEVICE_SHARE_CONFLICT,
                () -> service.consume(tenantId, projectId, receiverId, "share-token"));
        verify(bindingRepository, never()).createActiveShared(any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 已有任意 ACTIVE 关系时 SHARE 不得承担提权、降权或覆盖。 */
    @Test
    void existingRelationshipIsConflict() {
        stubConsumableToken(AppUserDevice.RelationRole.READ_ONLY);
        when(bindingRepository.findActiveForUpdate(projectId, receiverId, deviceId))
                .thenReturn(Optional.of(binding(receiverId, AppUserDevice.RelationRole.MEMBER)));

        assertCode(EndUserErrorCode.DEVICE_SHARE_CONFLICT,
                () -> service.consume(tenantId, projectId, receiverId, "share-token"));
        verify(bindingRepository, never()).createActiveShared(any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 同接收者且关系仍保持原目标角色时重放幂等返回。 */
    @Test
    void sameReceiverReplayIsIdempotent() {
        AppDeviceBindToken consumed = shareToken(AppUserDevice.RelationRole.MEMBER, receiverId);
        when(roleRepository.findByProjectAndUser(projectId, receiverId))
                .thenReturn(Optional.of(activeRole(receiverId)));
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(consumed));
        AppUserDevice member = binding(receiverId, AppUserDevice.RelationRole.MEMBER);
        when(bindingRepository.findActive(projectId, receiverId, deviceId))
                .thenReturn(Optional.of(member));

        DeviceShareResult replay = service.consume(
                tenantId, projectId, receiverId, "share-token");

        assertThat(replay.bindingId()).isEqualTo(member.id());
        verify(tokenRepository, never()).incrementAttempt(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 删除前SHARE无论未消费或已消费，均在角色、关系、计数与审计前按60019拒绝。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleGenerationShareCannotMutateOrReplay(boolean consumed) {
        AppDeviceBindToken stale = shareToken(
                AppUserDevice.RelationRole.MEMBER, consumed ? receiverId : null,
                PROJECT_GENERATION - 1);
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(stale));

        assertCode(EndUserErrorCode.DEVICE_SHARE_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, receiverId, "stale-share"));

        verifyNoInteractions(roleRepository, bindingRepository, deviceDataPlane, auditLogService);
        verify(tokenRepository, never()).incrementAttempt(any(), any());
        verify(tokenRepository, never()).consume(any(), any(), any(), any());
    }

    /** 已消费 TRANSFER 不能借 SHARE 的幂等分支跨用途返回成功。 */
    @Test
    void consumedTransferCannotReplayThroughShareEndpoint() {
        Instant now = Instant.now();
        AppDeviceBindToken transfer = new AppDeviceBindToken(
                UUID.randomUUID(), tenantId, projectId, PROJECT_GENERATION, deviceId,
                AppDeviceBindToken.Purpose.TRANSFER, AppUserDevice.RelationRole.PRIMARY,
                issuerId, now.plusSeconds(60), 1, 5,
                now, receiverId, now.minusSeconds(60), now);
        when(roleRepository.findByProjectAndUser(projectId, receiverId))
                .thenReturn(Optional.of(activeRole(receiverId)));
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(transfer));

        assertCode(EndUserErrorCode.DEVICE_SHARE_TOKEN_INVALID,
                () -> service.consume(tenantId, projectId, receiverId, "transfer-token"));
        verify(bindingRepository, never()).findActive(any(), any(), any());
    }

    /** 准备当前主控签发前置。 */
    private void stubIssuer() {
        when(roleRepository.findByProjectAndUser(projectId, issuerId))
                .thenReturn(Optional.of(activeRole(issuerId)));
        when(bindingRepository.findActivePrimary(projectId, deviceId))
                .thenReturn(Optional.of(primary));
    }

    /** 准备尚未消费的 SHARE 令牌与消费前置。 */
    private void stubConsumableToken(AppUserDevice.RelationRole targetRole) {
        when(roleRepository.findByProjectAndUser(projectId, receiverId))
                .thenReturn(Optional.of(activeRole(receiverId)));
        when(tokenRepository.findByProjectAndHashForUpdate(any(), any()))
                .thenReturn(Optional.of(shareToken(targetRole, null)));
        when(tokenRepository.incrementAttempt(any(), any())).thenReturn(1);
        when(bindingRepository.findActivePrimaryForUpdate(projectId, deviceId))
                .thenReturn(Optional.of(primary));
        when(bindingRepository.findActiveForUpdate(projectId, receiverId, deviceId))
                .thenReturn(Optional.empty());
    }

    /** 构造 SHARE 令牌；consumer 非空表示已消费。 */
    private AppDeviceBindToken shareToken(AppUserDevice.RelationRole targetRole, UUID consumer) {
        return shareToken(targetRole, consumer, PROJECT_GENERATION);
    }

    /** 构造指定项目代次的SHARE能力。 */
    private AppDeviceBindToken shareToken(
            AppUserDevice.RelationRole targetRole, UUID consumer, long projectGeneration) {
        Instant now = Instant.now();
        return new AppDeviceBindToken(
                UUID.randomUUID(), tenantId, projectId, projectGeneration, deviceId,
                AppDeviceBindToken.Purpose.SHARE, targetRole, issuerId,
                now.plusSeconds(60), 0, 5,
                consumer == null ? null : now, consumer, now.minusSeconds(60), now);
    }

    /** 构造 ACTIVE App 项目角色。 */
    private AppUserRole activeRole(UUID userId) {
        return new AppUserRole(UUID.randomUUID(), tenantId, projectId, userId,
                EndUserRole.OBSERVER, AppUserRole.Status.ACTIVE, Instant.now());
    }

    /** 构造指定角色的 ACTIVE 设备关系。 */
    private AppUserDevice binding(UUID userId, AppUserDevice.RelationRole role) {
        return new AppUserDevice(UUID.randomUUID(), tenantId, projectId, userId, deviceId,
                role, AppUserDevice.Status.ACTIVE, Instant.now());
    }

    /** 构造可见设备投影。 */
    private AppDevice device() {
        return new AppDevice(deviceId, "share-device", "共享设备", null,
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
