package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 显式版本解析与绑定 CAS 服务，冻结 X-01 5.2 的 current/history-only 边界。 */
@Service
public class ThingModelVersionBindingService {
    /** B-X1a 容量证据冻结：转换完成后十分钟内，只接受直接旧版写历史。 */
    public static final Duration HISTORY_ONLY_WINDOW = Duration.ofMinutes(10);
    /** 版本仓储。 */
    private final ThingModelVersionRepository repository;
    /** 平台生成入口在仓储 SQL 前集中建立可信租户/项目事务范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /** @param repository 版本与绑定仓储 @param transactionLocalRlsScope 事务局部RLS集中入口 */
    public ThingModelVersionBindingService(ThingModelVersionRepository repository,
                                           TransactionLocalRlsScope transactionLocalRlsScope) {
        this.repository = repository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
    }

    /**
     * 使用服务端 receivedAt 判定资格；设备 occurredAt 绝不能延长旧版本窗口。
     * @param projectId 项目 ID @param deviceId 已认证设备 ID @param declaredVersion modelVersion 文本
     * @param receivedAt 服务端可信接收时刻 @return 不可再次解释的摄入上下文
     */
    @Transactional(readOnly = true, timeout = 3)
    public DeviceIngestionContext resolveForIngestion(UUID projectId, UUID deviceId, String declaredVersion,
                                                      Instant receivedAt) {
        if (declaredVersion == null) {
            return resolveLegacyOmission(projectId, deviceId);
        }
        if (!declaredVersion.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"))
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_REQUIRED);
        ThingModelVersionRepository.ResolvedBinding binding = repository.resolve(projectId, deviceId, declaredVersion)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND));
        if (binding.declaredVersion().id().equals(binding.currentVersionId()))
            return DeviceIngestionContext.from(binding.declaredVersion(), DeviceIngestionContext.Eligibility.CURRENT);
        boolean eligible = repository.wasDirectlyReplacedWithinWindow(projectId, deviceId,
                binding.declaredVersion().id(), binding.currentVersionId(), receivedAt,
                receivedAt.minus(HISTORY_ONLY_WINDOW));
        if (!eligible) throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_HISTORY_EXPIRED);
        return DeviceIngestionContext.from(binding.declaredVersion(), DeviceIngestionContext.Eligibility.HISTORY_ONLY);
    }

    /**
     * X-01 §5.2 存量标量省略兼容窗口：省略 modelVersion 只允许解析到设备已绑定的初始版本，
     * 且设备不得已经历任何非 INITIAL 转换（升级/回滚/人工切换后必须显式声明）。
     * 推断结果资格为 CURRENT——初始版本即当前绑定，只缺了显式版本文本，不能降级为 HISTORY_ONLY。
     * Object/List 强制声明由 {@code DeviceIngestionService} 拿到类型映射后二次校验，本方法不接触 payload。
     */
    private DeviceIngestionContext resolveLegacyOmission(UUID projectId, UUID deviceId) {
        ThingModelVersionRepository.ResolvedBinding current = repository.findCurrent(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND));
        if (repository.hasNonInitialTransition(projectId, deviceId)) {
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_REQUIRED);
        }
        return DeviceIngestionContext.from(current.declaredVersion(),
                DeviceIngestionContext.Eligibility.CURRENT).withLegacyInferred();
    }

    /**
     * 为 Modbus 平台轮询等无设备原始报文的链路冻结当前可读版本。
     * @param tenantId 引擎核验的可信租户 ID
     * @param projectId 项目 ID
     * @param deviceId 平台轮询目标设备
     * @return 可读当前版本号
     */
    @Transactional(readOnly = true, timeout = 3)
    public String resolveCurrentVersionForPlatformGenerated(UUID tenantId, UUID projectId, UUID deviceId) {
        transactionLocalRlsScope.establish(tenantId, projectId);
        return requireCurrentVersionNumber(projectId, deviceId);
    }

    /**
     * 在调用方已经用权威事实建立完整事务范围后冻结当前版本，不重复解释或切换隔离域。
     *
     * <p>仅供 Modbus 接受等拥有显式旧切域区间的内部链路使用；{@link Propagation#MANDATORY}
     * 保证脱离现有事务的误用在访问仓储前失败。</p>
     *
     * @param projectId 已建立范围中的项目 ID
     * @param deviceId 平台生成上报的目标设备
     * @return 可读当前版本号
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, timeout = 3)
    public String resolveCurrentVersionWithinEstablishedScope(UUID projectId, UUID deviceId) {
        return requireCurrentVersionNumber(projectId, deviceId);
    }

    /** 已有可信事务范围下只执行当前版本事实查询。 */
    private String requireCurrentVersionNumber(UUID projectId, UUID deviceId) {
        return repository.findCurrent(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND))
                .declaredVersion().versionNumber();
    }

    /**
     * 追加转换事实后 CAS 当前指针；相同 transitionKey 重试返回既有事实，换目标则协议冲突。
     * @param projectId 项目 ID @param deviceId 设备 ID @param targetVersionId 目标版本
     * @param transitionKey 调用方稳定幂等键 @param type 转换类型 @param effectiveAt 服务端生效时刻
     * @return 新增或重放的转换事实
     */
    @Transactional
    public BindingTransition bind(UUID projectId, UUID deviceId, UUID targetVersionId, UUID transitionKey,
                                  BindingTransition.TransitionType type, Instant effectiveAt) {
        BindingTransition replay = repository.findTransition(projectId, deviceId, transitionKey).orElse(null);
        if (replay != null) {
            if (!replay.toVersionId().equals(targetVersionId))
                throw new BusinessException(DeviceErrorCode.THING_MODEL_BINDING_IDEMPOTENCY_CONFLICT);
            return replay;
        }
        ThingModelVersionRepository.ResolvedBinding current = repository.lockCurrent(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        // 同设备行锁串行化后必须再次查幂等事实；并发同键重试此时应返回首个成功，而非误报 CAS 冲突。
        replay = repository.findTransition(projectId, deviceId, transitionKey).orElse(null);
        if (replay != null) {
            if (!replay.toVersionId().equals(targetVersionId))
                throw new BusinessException(DeviceErrorCode.THING_MODEL_BINDING_IDEMPOTENCY_CONFLICT);
            return replay;
        }
        ThingModelVersion target = repository.findById(projectId, current.deviceTypeId(), targetVersionId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND));
        if (target.id().equals(current.currentVersionId()))
            throw new BusinessException(DeviceErrorCode.THING_MODEL_BINDING_CONFLICT);
        BindingTransition transition = new BindingTransition(Uuid7.generate(), target.tenantId(), projectId,
                deviceId, current.currentVersionId(), target.id(), transitionKey, type, effectiveAt);
        repository.appendTransition(transition);
        if (!repository.compareAndSetCurrent(projectId, deviceId, current.currentVersionId(), target.id()))
            throw new BusinessException(DeviceErrorCode.THING_MODEL_BINDING_CONFLICT);
        return transition;
    }
}
