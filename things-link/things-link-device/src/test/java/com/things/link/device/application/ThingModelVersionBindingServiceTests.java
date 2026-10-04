package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 版本线路单元测试，钉住 current/history-only、receivedAt 窗口、CAS 与幂等冲突。 */
@ExtendWith(MockitoExtension.class)
class ThingModelVersionBindingServiceTests {
    /** 仓储替身。 */ @Mock private ThingModelVersionRepository repository;
    /** 事务局部RLS集中入口替身。 */ @Mock private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 被测服务。 */ private ThingModelVersionBindingService service;
    /** 固定身份。 */ private UUID projectId, tenantId, deviceId, typeId, currentId, oldId;
    /** 固定接收时刻。 */ private Instant receivedAt;

    /** 初始化固定版本事实。 */
    @BeforeEach void setUp() {
        service = new ThingModelVersionBindingService(repository, transactionLocalRlsScope);
        projectId = UUID.randomUUID(); tenantId = UUID.randomUUID(); deviceId = UUID.randomUUID();
        typeId = UUID.randomUUID(); currentId = UUID.randomUUID(); oldId = UUID.randomUUID();
        receivedAt = Instant.parse("2026-08-31T12:00:00Z");
    }

    /** 当前绑定具备完整当前链资格。 */
    @Test void resolvesCurrentVersion() {
        ThingModelVersion current = version(currentId, "2.0.0");
        when(repository.resolve(projectId, deviceId, "2.0.0")).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, currentId, current)));
        assertThat(service.resolveForIngestion(projectId, deviceId, "2.0.0", receivedAt).eligibility())
                .isEqualTo(DeviceIngestionContext.Eligibility.CURRENT);
    }

    /** 直接旧版只在服务端十分钟窗口内降级为 history-only。 */
    @Test void resolvesRecentDirectOldVersionAsHistoryOnly() {
        ThingModelVersion old = version(oldId, "1.0.0");
        when(repository.resolve(projectId, deviceId, "1.0.0")).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, currentId, old)));
        when(repository.wasDirectlyReplacedWithinWindow(projectId, deviceId, oldId, currentId,
                receivedAt, receivedAt.minus(ThingModelVersionBindingService.HISTORY_ONLY_WINDOW))).thenReturn(true);
        assertThat(service.resolveForIngestion(projectId, deviceId, "1.0.0", receivedAt).eligibility())
                .isEqualTo(DeviceIngestionContext.Eligibility.HISTORY_ONLY);
    }

    /** 设备 occurredAt 不参与资格；仓储窗口不命中即稳定拒绝。 */
    @Test void rejectsExpiredOrNeverAuthorizedVersion() {
        ThingModelVersion old = version(oldId, "1.0.0");
        when(repository.resolve(projectId, deviceId, "1.0.0")).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, currentId, old)));
        assertThatThrownBy(() -> service.resolveForIngestion(projectId, deviceId, "1.0.0", receivedAt))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(DeviceErrorCode.THING_MODEL_VERSION_HISTORY_EXPIRED);
    }

    /** 平台轮询生成上报在本事务内自建 project RLS 范围后冻结当前可读版本号。 */
    @Test void resolvesCurrentVersionForPlatformGenerated() {
        ThingModelVersion current = version(currentId, "2.0.0");
        when(repository.findCurrent(projectId, deviceId)).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, currentId, current)));
        assertThat(service.resolveCurrentVersionForPlatformGenerated(tenantId, projectId, deviceId))
                .isEqualTo("2.0.0");
        var scopeBeforeVersionLookup = inOrder(transactionLocalRlsScope, repository);
        scopeBeforeVersionLookup.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        scopeBeforeVersionLookup.verify(repository).findCurrent(projectId, deviceId);
    }

    /** 调用方已经建立完整范围时只查询版本事实，不能再次调用集中组件解释或切换范围。 */
    @Test void resolvesCurrentVersionWithinEstablishedScope() {
        ThingModelVersion current = version(currentId, "2.0.0");
        when(repository.findCurrent(projectId, deviceId)).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, currentId, current)));

        assertThat(service.resolveCurrentVersionWithinEstablishedScope(projectId, deviceId))
                .isEqualTo("2.0.0");

        verifyNoInteractions(transactionLocalRlsScope);
        verify(repository).findCurrent(projectId, deviceId);
    }

    /** 存量标量省略 modelVersion 解析到已绑定初始版本：资格为 CURRENT 且标记推断来源（X-01 §5.2）。 */
    @Test void resolvesOmittedVersionToInitialCurrentWithLegacyInferred() {
        ThingModelVersion initial = version(currentId, "1.0.0");
        when(repository.findCurrent(projectId, deviceId)).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, currentId, initial)));
        when(repository.hasNonInitialTransition(projectId, deviceId)).thenReturn(false);
        DeviceIngestionContext context = service.resolveForIngestion(projectId, deviceId, null, receivedAt);
        assertThat(context.eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.CURRENT);
        assertThat(context.legacyInferred()).isTrue();
        assertThat(context.versionNumber()).isEqualTo("1.0.0");
    }

    /** 已经历升级/回滚/人工切换后省略 modelVersion 必须拒绝，不能猜测设备想写哪个版本。 */
    @Test void rejectsOmittedVersionAfterNonInitialTransition() {
        ThingModelVersion initial = version(currentId, "1.0.0");
        when(repository.findCurrent(projectId, deviceId)).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, currentId, initial)));
        when(repository.hasNonInitialTransition(projectId, deviceId)).thenReturn(true);
        assertThatThrownBy(() -> service.resolveForIngestion(projectId, deviceId, null, receivedAt))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(DeviceErrorCode.THING_MODEL_VERSION_REQUIRED);
    }

    /** 从未绑定任何版本的设备省略 modelVersion 无法推断，报版本不存在而非静默放行。 */
    @Test void rejectsOmittedVersionWithoutAnyBoundVersion() {
        when(repository.findCurrent(projectId, deviceId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveForIngestion(projectId, deviceId, null, receivedAt))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND);
    }

    /** 相同幂等键换目标必须报协议冲突，不能复用旧成功。 */
    @Test void rejectsTransitionKeyReusedForAnotherTarget() {
        UUID key = UUID.randomUUID();
        BindingTransition existing = new BindingTransition(UUID.randomUUID(), tenantId, projectId, deviceId,
                oldId, currentId, key, BindingTransition.TransitionType.UPGRADE, receivedAt);
        when(repository.findTransition(projectId, deviceId, key)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.bind(projectId, deviceId, UUID.randomUUID(), key,
                BindingTransition.TransitionType.UPGRADE, receivedAt))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(DeviceErrorCode.THING_MODEL_BINDING_IDEMPOTENCY_CONFLICT);
    }

    /** 新转换先追加不可变事实，再以锁定的旧指针做 CAS。 */
    @Test void appendsTransitionAndMovesCurrentPointer() {
        UUID key = UUID.randomUUID();
        ThingModelVersion old = version(oldId, "1.0.0");
        ThingModelVersion current = version(currentId, "2.0.0");
        when(repository.findTransition(projectId, deviceId, key)).thenReturn(Optional.empty());
        when(repository.lockCurrent(projectId, deviceId)).thenReturn(Optional.of(
                new ThingModelVersionRepository.ResolvedBinding(deviceId, typeId, oldId, old)));
        when(repository.findById(projectId, typeId, currentId)).thenReturn(Optional.of(current));
        when(repository.compareAndSetCurrent(projectId, deviceId, oldId, currentId)).thenReturn(true);
        BindingTransition result = service.bind(projectId, deviceId, currentId, key,
                BindingTransition.TransitionType.UPGRADE, receivedAt);
        verify(repository).appendTransition(result);
        assertThat(result.fromVersionId()).isEqualTo(oldId);
    }

    /** 构造不可变版本。 */
    private ThingModelVersion version(UUID id, String number) {
        return new ThingModelVersion(id, tenantId, projectId, typeId, number,
                ThingModelVersion.ChangeLevel.MAJOR, "{}", "a".repeat(64),
                "PG_JSONB_TEXT_V1_SHA256", receivedAt);
    }
}
