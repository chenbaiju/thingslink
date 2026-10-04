package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.device.domain.DeviceMqttConnectionRepository;
import java.util.Optional;
import java.util.UUID;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.OptionalLong;

/** MQTT当前配置许可与原凭据身份比较，认证缓存不得被补查升级。 */
@Service
public class DeviceMqttAccessService {
    /** 既有设备路由及当前MQTT平面解析。 */
    private final DeviceAccessScopeService scopes;
    /** 项目生命周期锁与控制写者遵守同一顺序。 */
    private final ProjectLifecycleAccessService projects;
    /** 设备当前凭据版本及共享锁。 */
    private final JdbcTemplate jdbc;
    /** 锁后读取配置，避免等待设备锁前的连接快照。 */
    private final DeviceAccessSessionRepository bindings;

    /** 原连接持久账本。 */
    private final DeviceMqttConnectionRepository connections;

    /** 装配当前资格解析和同库事务依赖。 */
    public DeviceMqttAccessService(DeviceAccessScopeService scopes, ProjectLifecycleAccessService projects,
            JdbcTemplate jdbc, DeviceAccessSessionRepository bindings, DeviceMqttConnectionRepository connections) {
        this.connections = connections; this.scopes = scopes; this.projects = projects; this.jdbc = jdbc; this.bindings = bindings;
    }

    /** 比较原凭据快照，并在项目/设备锁内获取当前配置代次；不会替换原凭据版本。 */
    @Transactional
    public OptionalLong currentConfiguration(String projectKey, String deviceKey, AuthenticatedDeviceIdentity original) {
        if (original == null) return OptionalLong.empty();
        var scope = scopes.resolve(projectKey, deviceKey);
        if (scope.isEmpty() || !original.tenantId().equals(scope.get().tenantId())
                || !original.projectId().equals(scope.get().projectId())
                || !original.deviceId().equals(scope.get().deviceId())) return OptionalLong.empty();
        if (!projects.lockActiveForWrite(original.tenantId(), original.projectId())) return OptionalLong.empty();
        var versions = jdbc.queryForList("""
                SELECT credential_version FROM dev_device
                 WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL FOR NO KEY UPDATE
                """, Long.class, original.tenantId(), original.projectId(), original.deviceId());
        if (versions.size() != 1 || versions.getFirst() != original.credentialVersion()) return OptionalLong.empty();
        var binding = bindings.findBinding(original.projectId(), original.deviceId());
        if (binding.isPresent() && (!binding.get().tenantId().equals(original.tenantId())
                || !binding.get().allows(TransportProtocol.MQTT))) return OptionalLong.empty();
        return OptionalLong.of(binding.map(value -> value.configVersion()).orElse(0L));
    }

    /** 同一事务冻结当前配置并签发连接票据，提交前不得返回allow。 */
    @Transactional
    public Optional<ConnectionGrant> issueConnection(String projectKey, String deviceKey,
            AuthenticatedDeviceIdentity original, String wireClientId) {
        var current = currentConfiguration(projectKey, deviceKey, original);
        if (current.isEmpty()) return Optional.empty();
        var identity = new DeviceMqttIdentity(original, current.getAsLong());
        var session = new DeviceMqttSessionIdentity(identity, wireClientId);
        return connections.issue(scope(identity, session.effectiveClientId()))
                .map(ticket -> new ConnectionGrant(identity.configVersion(), ticket.id()));
    }

    /** 原配置及原连接均为当前有效身份，缺失元数据直接拒绝。 */
    @Transactional
    public boolean permits(String projectKey, String deviceKey, DeviceMqttIdentity original,
            UUID connectionId, String effectiveClientId) {
        if (original == null || connectionId == null || effectiveClientId == null
                || !effectiveClientId.matches("tc-device-[0-9a-f]{64}")) return false;
        var current = currentConfiguration(projectKey, deviceKey, original.identity());
        return current.isPresent() && current.getAsLong() == original.configVersion()
                && connections.permits(scope(original, effectiveClientId), connectionId);
    }

    /** 从原服务器身份构造仓储范围，不重新查询升级旧凭据。 */
    private DeviceMqttConnectionRepository.Scope scope(DeviceMqttIdentity original, String clientId) {
        var identity = original.identity();
        return new DeviceMqttConnectionRepository.Scope(identity.tenantId(), identity.projectId(), identity.deviceId(),
                identity.credentialVersion(), original.configVersion(), clientId);
    }

    /** 已提交的设备连接认证结果，不含秘密。 */
    public record ConnectionGrant(long configVersion, UUID connectionId) { }
}
