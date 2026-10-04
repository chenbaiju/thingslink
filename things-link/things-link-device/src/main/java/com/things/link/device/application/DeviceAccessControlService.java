package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/** ADR0193：配置、旧权威事实、可靠来源和审计由同一控制事务提交。 */
@Service
public class DeviceAccessControlService {
    private final ProjectService projects;
    private final ProjectLifecycleAccessService lifecycle;
    private final TransactionLocalRlsScope rls;
    private final JdbcTemplate jdbc;
    private final DeviceRepository devices;
    private final DeviceTopologyRoleGuard roles;
    private final DeviceAccessSessionRepository bindings;
    private final DevicePresenceWebhookSource source;
    private final DeviceSessionTerminator terminator;
    private final DeviceTopologyIngestionService topology;
    private final AuditLogService audit;

    /** 装配同库控制依赖；Broker网络调用仅在事务提交后执行。 */
    public DeviceAccessControlService(ProjectService projects, ProjectLifecycleAccessService lifecycle,
            TransactionLocalRlsScope rls, JdbcTemplate jdbc, DeviceRepository devices,
            DeviceTopologyRoleGuard roles, DeviceAccessSessionRepository bindings,
            DevicePresenceWebhookSource source, DeviceSessionTerminator terminator,
            DeviceTopologyIngestionService topology, AuditLogService audit) {
        this.projects = projects; this.lifecycle = lifecycle; this.rls = rls; this.jdbc = jdbc;
        this.devices = devices; this.roles = roles; this.bindings = bindings; this.source = source;
        this.terminator = terminator; this.topology = topology; this.audit = audit;
    }

    /**
     * ADR0197：项目/设备锁内读取配置与凭据的一致快照，GET不回填存量绑定。
     * @param project 可见项目 @param device 可见设备 @return 不含秘密的管理视图
     */
    @Transactional
    public DeviceAccessConfigurationView view(UUID project, UUID device) {
        projects.requireRoleInProject(project);
        UUID tenant = projects.requireProjectTenant(project);
        rls.establish(tenant, project);
        if (lifecycle.lockReadableGeneration(tenant, project).isEmpty()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        ProjectRole role = projects.requireRoleInProject(project);
        List<Long> versions = jdbc.query("""
                SELECT credential_version FROM dev_device
                WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL FOR SHARE
                """, (row, index) -> row.getLong(1), tenant, project, device);
        if (versions.isEmpty()) throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        Device current = devices.findById(project, device)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        DeviceType type = roles.findTypeForControlPlane(project, current.deviceTypeId()).orElse(null);
        var persisted = bindings.findBinding(project, device);
        DeviceAccessBinding binding = persisted.orElseGet(() -> DeviceAccessBinding.legacyMqtt(tenant, project, device));
        List<TransportProtocol> allowed = allowedProtocols(type);
        boolean canManage = (role == ProjectRole.OWNER || role == ProjectRole.ADMIN)
                && lifecycle.snapshot(tenant, project).writeAllowed() && !allowed.isEmpty();
        return new DeviceAccessConfigurationView(binding.protocol(), binding.enabled(), binding.configVersion(),
                versions.getFirst(), persisted.isPresent(), allowed, canManage);
    }

    /**
     * 原控制事务提交前构造响应，不以提交后的独立GET混入另一写者的版本。
     * @param project 项目 @param device 设备 @param expected 期望配置版本
     * @param protocol 新协议 @param enabled 新开关 @return 本次事务的视图
     */
    @Transactional
    public DeviceAccessConfigurationView changeView(UUID project, UUID device, long expected,
            TransportProtocol protocol, boolean enabled) {
        change(project, device, expected, protocol, enabled);
        return view(project, device);
    }

    /** 类型能力来自既定合同，不因管理者权限或当前绑定猜测扩展。 */
    private static List<TransportProtocol> allowedProtocols(DeviceType type) {
        if (type == null || type.deviceKind() == DeviceType.DeviceKind.SUB_DEVICE) return List.of();
        if (type.deviceKind() == DeviceType.DeviceKind.DIRECT && type.payloadProtocol() == DeviceType.PayloadProtocol.STANDARD) {
            return List.of(TransportProtocol.MQTT, TransportProtocol.HTTP, TransportProtocol.COAP, TransportProtocol.TCP);
        }
        return List.of(TransportProtocol.MQTT);
    }

    /** 当前权限与期望版本都在锁内重验；精确同值没有审计、关闭、来源或踢连接。 */
    @Transactional
    public DeviceAccessBinding change(UUID project, UUID device, long expected,
            TransportProtocol protocol, boolean enabled) {
        requireManager(project);
        UUID tenant = projects.requireProjectTenant(project);
        rls.establish(tenant, project);
        lifecycle.requireActiveForWrite(tenant, project);
        requireManager(project);
        OptionalLong generation = source.capture(tenant, project);
        if (jdbc.queryForList("""
                SELECT id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?
                  AND deleted_at IS NULL FOR NO KEY UPDATE
                """, UUID.class, tenant, project, device).isEmpty()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        Device current = devices.findById(project, device)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        // ADR0057已有设备锁后类型NOWAIT规则，不能阻塞等待反向类型编辑者。
        DeviceType type = roles.findTypeForControlPlane(project, current.deviceTypeId()).orElse(null);
        DeviceAccessBinding before = bindings.findBinding(project, device)
                .orElseGet(() -> DeviceAccessBinding.legacyMqtt(tenant, project, device));
        if (before.configVersion() != expected) throw new BusinessException(DeviceErrorCode.ACCESS_CONFIG_CONFLICT);
        requireProtocol(type, protocol);
        DeviceAccessBinding after = bindings.changeBinding(tenant, project, device, expected, protocol, enabled, false);
        if (after.configVersion() == before.configVersion()) return after;
        String reason = enabled ? "CONFIG_CHANGED" : "CONFIG_DISABLED";
        closeFacts(tenant, project, current, type, before, generation, reason);
        audit.record(new AuditLogEntry(tenant, project, TenantContext.require().accountId(), "device", device,
                "DEVICE_ACCESS_CONFIG_CHANGED", Map.of("before", auditValue(before), "after", auditValue(after))));
        return after;
    }

    /** 凭据写者先取得项目/设备和类型锁，禁止先写凭据再等待设备锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCredentialDevice(UUID tenant, UUID project, UUID device) {
        rls.establish(tenant, project);
        lifecycle.requireActiveForWrite(tenant, project);
        source.capture(tenant, project);
        if (jdbc.queryForList("""
                SELECT id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?
                  AND deleted_at IS NULL FOR NO KEY UPDATE
                """, UUID.class, tenant, project, device).isEmpty()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        Device current = devices.findById(project, device)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        roles.findTypeForControlPlane(project, current.deviceTypeId());
    }

    /** 实际凭据生成/撤销成功后，同事务强制推进一次配置代次并关闭原权威事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateCredentials(UUID tenant, UUID project, UUID device) {
        lockCredentialDevice(tenant, project, device);
        Device current = devices.findById(project, device).orElseThrow();
        DeviceType type = roles.findTypeForControlPlane(project, current.deviceTypeId()).orElse(null);
        DeviceAccessBinding before = bindings.findBinding(project, device)
                .orElseGet(() -> DeviceAccessBinding.legacyMqtt(tenant, project, device));
        DeviceAccessBinding after = bindings.changeBinding(tenant, project, device, before.configVersion(),
                before.protocol(), before.enabled(), true);
        closeFacts(tenant, project, current, type, before, source.capture(tenant, project), "CREDENTIAL_CHANGED");
        audit.record(new AuditLogEntry(tenant, project, TenantContext.require().accountId(), "device", device,
                "DEVICE_ACCESS_CONFIG_CHANGED", Map.of("before", auditValue(before), "after", auditValue(after),
                    "reason", "CREDENTIAL_CHANGED")));
    }

    /** 删除自己的接入事实；子设备公开边沿和网关子关系仍交给拓扑唯一写者。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeForDeletion(UUID project, UUID device) {
        requireManager(project);
        UUID tenant = projects.requireProjectTenant(project);
        lockCredentialDevice(tenant, project, device);
        requireManager(project);
        Device current = devices.findById(project, device).orElseThrow();
        DeviceType type = roles.findTypeForControlPlane(project, current.deviceTypeId()).orElse(null);
        DeviceAccessBinding before = bindings.findBinding(project, device)
                .orElseGet(() -> DeviceAccessBinding.legacyMqtt(tenant, project, device));
        closeFacts(tenant, project, current, type, before, source.capture(tenant, project), "DEVICE_DELETED");
        audit.record(new AuditLogEntry(tenant, project, TenantContext.require().accountId(), "device", device,
                "DEVICE_DELETED", Map.of("accessConfiguration", auditValue(before))));
    }

    /** 当前载荷/拓扑分类是接入能力边界，不因配置切换扩展解码器能力。 */
    private static void requireProtocol(DeviceType type, TransportProtocol protocol) {
        if (protocol == null || !allowedProtocols(type).contains(protocol)) {
            throw new BusinessException(DeviceErrorCode.ACCESS_PROTOCOL_UNSUPPORTED);
        }
    }

    /** 只从旧配置权威事实产生一条边沿，其他遗留会话仅清理；过期保留原截止时刻。 */
    private void closeFacts(UUID tenant, UUID project, Device device, DeviceType type, DeviceAccessBinding before,
            OptionalLong generation, String reason) {
        UUID id = device.id();
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        List<Connection> connections = jdbc.query("""
                SELECT id,protocol,config_version,last_seen_at,heartbeat_interval_millis
                  FROM dev_connection WHERE tenant_id=? AND project_id=? AND device_id=?
                   AND disconnected_at IS NULL FOR UPDATE
                """, (r, n) -> new Connection(r.getObject(1, UUID.class), r.getString(2), r.getLong(3),
                    r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant(), r.getObject(5, Long.class)),
                tenant, project, id);
        // 必须在关闭数据库事实前捕获；实现注册的回调不能提交后重新查询已关闭行。
        terminator.disconnectAfterCommit(project, id);
        boolean online = false;
        Instant at = now;
        String publicReason = reason;
        if (before.protocol() == TransportProtocol.HTTP || before.protocol() == TransportProtocol.COAP) {
            online = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT activity_online FROM dev_access_binding WHERE tenant_id=? AND project_id=? AND device_id=?
                    """, Boolean.class, tenant, project, id));
            if (online && !before.lastActivityAt().plusSeconds(DeviceAccessActivityService.WINDOW_SECONDS).isAfter(now)) {
                at = before.lastActivityAt().plusSeconds(DeviceAccessActivityService.WINDOW_SECONDS);
                publicReason = "ACTIVITY_EXPIRED";
            }
        } else {
            for (Connection connection : connections) {
                if (connection.protocol().equals(before.protocol().name()) && connection.version() == before.configVersion()) {
                    online = true;
                    if (connection.expired(now)) { at = connection.deadline(); publicReason = "HEARTBEAT_EXPIRED"; }
                }
            }
        }
        for (Connection connection : connections) {
            boolean expired = connection.expired(now);
            jdbc.update("""
                    UPDATE dev_connection SET disconnected_at=?,disconnect_reason=?
                     WHERE tenant_id=? AND project_id=? AND id=? AND disconnected_at IS NULL
                    """, Timestamp.from(expired ? connection.deadline() : now), expired ? "heartbeat_timeout" : reason,
                    tenant, project, connection.id());
        }
        jdbc.update("UPDATE dev_access_binding SET activity_online=false WHERE tenant_id=? AND project_id=? AND device_id=?",
                tenant, project, id);
        if (type == null || type.deviceKind() != DeviceType.DeviceKind.SUB_DEVICE) jdbc.update("UPDATE dev_device SET status='OFFLINE',updated_at=now() WHERE tenant_id=? AND project_id=? AND id=? AND status='ONLINE'",
                tenant, project, id);
        if (online && (type == null || type.deviceKind() != DeviceType.DeviceKind.SUB_DEVICE)) {
            source.append(tenant, project, id, generation, "ONLINE", "OFFLINE", before.protocol().name(), publicReason,
                    null, null, at);
            if (type != null && type.deviceKind() == DeviceType.DeviceKind.GATEWAY
                    && !"DEVICE_DELETED".equals(reason)) {
                topology.cascadeGatewayOffline(tenant, project, id, at);
            }
        }
    }

    /** 版本以规范字符串写审计，避免JSON数值精度和秘密信息混入。 */
    private static Map<String, Object> auditValue(DeviceAccessBinding binding) {
        return Map.of("protocol", binding.protocol().name(), "enabled", binding.enabled(),
                "configVersion", Long.toString(binding.configVersion()));
    }

    /** 共享真实项目成员检查，不以请求tenant推断归属。 */
    private void requireManager(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
        }
    }

    /** TCP截止点只来自该连接实际持久周期，MQTT没有推测的心跳过期。 */
    private record Connection(UUID id, String protocol, long version, Instant lastSeen, Long interval) {
        Instant deadline() { return lastSeen.plusMillis(Math.multiplyExact(interval, 3)); }
        boolean expired(Instant now) { return "TCP".equals(protocol) && !deadline().isAfter(now); }
    }
}
