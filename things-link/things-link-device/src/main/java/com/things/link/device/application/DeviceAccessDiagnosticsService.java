package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 接入连接诊断读取用例：在一个项目范围内组装配置、活跃会话与最近断开三段事实。
 *
 * <p>三段事实必须来自同一事务与同一 RLS 范围，否则「刚断开」的并发会让快照出现自相矛盾的组合（例如
 * 同时报告在线与会话行缺失）。设备不存在时返回空，由读取入口决定 404，而不是伪造一条全空快照。</p>
 */
@Service
public class DeviceAccessDiagnosticsService implements DeviceAccessDiagnosticsPort {

    /** 会话与配置事实仓储。 */
    private final DeviceAccessSessionRepository sessionRepository;

    /** 设备档案仓储，用于确认设备存在于该范围。 */
    private final DeviceRepository deviceRepository;

    /** 事务局部 RLS 范围组件。 */
    private final TransactionLocalRlsScope rlsScope;

    /** 保证三段事实读自同一事务连接。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param sessionRepository 会话与配置事实仓储
     * @param deviceRepository 设备档案仓储
     * @param rlsScope 事务局部 RLS 范围组件
     * @param transactionTemplate 事务模板
     */
    public DeviceAccessDiagnosticsService(DeviceAccessSessionRepository sessionRepository,
                                          DeviceRepository deviceRepository,
                                          TransactionLocalRlsScope rlsScope,
                                          TransactionTemplate transactionTemplate) {
        this.sessionRepository = sessionRepository;
        this.deviceRepository = deviceRepository;
        this.rlsScope = rlsScope;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<ConnectionDiagnostics> connection(UUID tenantId, UUID projectId, UUID deviceId) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            rlsScope.establish(tenantId, projectId);
            if (deviceRepository.findById(projectId, deviceId).isEmpty()) {
                return Optional.<ConnectionDiagnostics>empty();
            }
            DeviceAccessBinding binding = sessionRepository.findBinding(projectId, deviceId)
                    .orElseGet(() -> DeviceAccessBinding.legacyMqtt(tenantId, projectId, deviceId));
            Optional<DeviceAccessSessionRepository.ActiveSession> active =
                    sessionRepository.findActive(projectId, deviceId);
            Optional<DeviceAccessSessionRepository.LastDisconnect> lastDisconnect =
                    sessionRepository.findLastDisconnect(projectId, deviceId);
            return Optional.of(new ConnectionDiagnostics(
                    binding.protocol(), binding.configVersion(), binding.enabled(),
                    active.isPresent(),
                    active.map(DeviceAccessSessionRepository.ActiveSession::rowId).orElse(null),
                    active.map(DeviceAccessSessionRepository.ActiveSession::sessionId).orElse(null),
                    active.map(DeviceAccessSessionRepository.ActiveSession::ownerInstance).orElse(null),
                    active.map(DeviceAccessSessionRepository.ActiveSession::generation).orElse(0L),
                    active.map(DeviceAccessSessionRepository.ActiveSession::connectedAt).orElse(null),
                    active.map(DeviceAccessSessionRepository.ActiveSession::lastSeenAt).orElse(null),
                    binding.lastActivityAt(),
                    lastDisconnect.map(DeviceAccessSessionRepository.LastDisconnect::reason).orElse(null),
                    lastDisconnect.map(DeviceAccessSessionRepository.LastDisconnect::disconnectedAt).orElse(null)));
        }), "接入连接诊断不能返回空事务结果");
    }

}
