package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 接入会话用例：配置裁决、代次建立、心跳归属与关闭都落在同一份会话事实上。
 *
 * <p>拒绝原因在这里先于数据库判定：设备配置未开通该协议或已关闭时直接拒绝，既不会写会话行，也不会关闭
 * 设备现有的活跃会话——「配置不允许」不是「旧会话被接管」。允许之后由数据库串行化同一设备的会话建立，
 * 因此并发连接只会留下一个最新代次。</p>
 */
@Service
public class DeviceAccessSessionService implements DeviceAccessSessionPort {

    /** 会话与配置事实仓储。 */
    private final DeviceAccessSessionRepository repository;
    private final DeviceAccessActivityService activity;
    private final DeviceTcpSessionService tcp;

    /** 事务局部 RLS 范围组件。 */
    private final TransactionLocalRlsScope rlsScope;

    /** 保证范围设置与会话读写使用同一事务连接。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param repository 会话与配置事实仓储
     * @param rlsScope 事务局部 RLS 范围组件
     * @param transactionTemplate 事务模板
     */
    public DeviceAccessSessionService(DeviceAccessSessionRepository repository,
                                      TransactionLocalRlsScope rlsScope,
                                      TransactionTemplate transactionTemplate, DeviceAccessActivityService activity, DeviceTcpSessionService tcp) {
        this.repository = repository;
        this.activity = activity;
        this.tcp = tcp;
        this.rlsScope = rlsScope;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Establishment establish(EstablishmentRequest request) {
        if (request.protocol() == TransportProtocol.MQTT) {
            // 纯输入裁决，不查库：MQTT 会话只由 Broker 回调路径写入，接入面二次建立会形成两个写者。
            return new Establishment.Rejected(Rejection.MQTT_OWNED_BY_BROKER);
        }
        if(request.protocol()==TransportProtocol.TCP)return tcp.establish(request);
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            rlsScope.establish(request.tenantId(), request.projectId());
            Optional<DeviceAccessBinding> configured = repository.findBinding(request.projectId(), request.deviceId());
            DeviceAccessBinding binding = configured.orElseGet(() -> DeviceAccessBinding.legacyMqtt(
                    request.tenantId(), request.projectId(), request.deviceId()));
            if (!binding.enabled()) {
                return new Establishment.Rejected(Rejection.DISABLED);
            }
            if (!binding.allows(request.protocol())) {
                // 存量设备（无配置行）的协议是 MQTT，收到新协议连接即为未开通。
                return new Establishment.Rejected(Rejection.PROTOCOL_MISMATCH);
            }
            Instant at = Instant.now();
            DeviceAccessSessionRepository.EstablishedSession established = repository.establish(
                    request.tenantId(), request.projectId(), request.deviceId(), request.protocol(),
                    request.sessionId(), request.ownerInstance(), binding.configVersion(), request.clientIp(), at, null);
            return new Establishment.Allowed(new Established(established.rowId(), established.sessionId(),
                    established.generation(), established.configVersion(), established.replacedPriorSession()));
        }), "会话建立不能返回空事务结果");
    }

    @Override
    public Establishment establishAuthenticated(EstablishmentRequest request,
            com.things.link.shared.message.AuthenticatedDeviceIdentity identity) {
        return tcp.establishAuthenticated(request, identity);
    }

    @Override
    public boolean touch(UUID tenantId, UUID projectId, UUID deviceId, UUID rowId, String ownerInstance) {
        return tcp.touch(tenantId,projectId,deviceId,rowId,ownerInstance);
    }

    @Override
    public boolean close(UUID tenantId, UUID projectId, UUID deviceId, UUID rowId, String ownerInstance,
                         String reason) {
        return tcp.close(tenantId,projectId,deviceId,rowId,ownerInstance,reason);
    }

    @Override
    public boolean touchActivity(UUID tenantId, UUID projectId, UUID deviceId) {
        var binding=config(tenantId,projectId,deviceId);
        return binding.isPresent() && recordActivity(tenantId,projectId,deviceId,binding.get().protocol())==ActivityResult.ACCEPTED;
    }

    @Override
    public ActivityResult recordActivity(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol expected) {
        return activity.record(tenantId,projectId,deviceId,expected);
    }

    @Override
    public ActivityResult recordAuthenticatedActivity(com.things.link.shared.message.AuthenticatedDeviceIdentity identity,
            TransportProtocol expected) {
        return activity.recordAuthenticated(identity, expected);
    }

    @Override
    public Optional<Active> active(UUID tenantId, UUID projectId, UUID deviceId) {
        return transactionTemplate.execute(status -> {
            rlsScope.establish(tenantId, projectId);
            return repository.findActive(projectId, deviceId).map(session -> new Active(session.rowId(),
                    session.protocol(), session.sessionId(), session.ownerInstance(), session.generation(),
                    session.configVersion(), session.connectedAt(), session.lastSeenAt()));
        });
    }

    @Override
    public boolean planeEnabled(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol protocol) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            rlsScope.establish(tenantId, projectId);
            return repository.findBinding(projectId, deviceId)
                    .map(binding -> binding.allows(protocol))
                    .orElseGet(() -> TransportProtocol.MQTT == protocol);
        }));
    }

    @Override
    public Optional<Config> config(UUID tenantId, UUID projectId, UUID deviceId) {
        return transactionTemplate.execute(status -> {
            rlsScope.establish(tenantId, projectId);
            return repository.findBinding(projectId, deviceId)
                    .map(binding -> new Config(binding.protocol(), binding.configVersion(),
                            binding.heartbeatSeconds(), binding.enabled()));
        });
    }
}
