package com.things.link.device.infrastructure.emqx;

import com.things.link.device.domain.DeviceMqttConnectionRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.device.application.DeviceMqttIdentity;
import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.application.ModbusConfigService;
import com.things.link.device.domain.DeviceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消费 EMQX Broker 的客户端连接与断开事件。
 *
 * <p>认证成功不代表 MQTT 会话已经建立，因此在线态只能在 connected 事件中改变；同理，
 * disconnected 事件关闭会话后，仅在设备已无其他活跃连接时置为 OFFLINE。</p>
 */
@Service
public class EmqxConnectionEventService {
    /** 事件写入失败必须可观测，否则设备会永久显示错误在线态。 */
    private static final Logger log = LoggerFactory.getLogger(EmqxConnectionEventService.class);
    /** 数据库访问入口。 */ private final JdbcTemplate jdbc;
    /** 保证 RLS 范围和事件写入位于同一连接。 */ private final TransactionTemplate tx;
    /** 只记录已经落库的上下线事件，拒绝与数据库失败不得虚增。 */ private final DeviceConnectionMetrics metrics;
    /** 网关掉线时级联子设备 OFFLINE 的在线状态机。 */ private final DeviceTopologyIngestionService topologyIngestion;
    /** 网关上线/重连时重推最新 Modbus 配置的端口。 */ private final ModbusConfigService modbusConfigService;
    private final com.things.link.device.application.DevicePresenceWebhookSource webhookSource;

    /** 原始连接账本，与在线来源使用同一事务。 */
    private final DeviceMqttConnectionRepository connections;
    /** 统一事务局部RLS，禁止业务事务中途切换范围。 */
    private final TransactionLocalRlsScope rls;

    /** @param jdbc 数据库访问入口 @param tx 事务模板 @param metrics 连接事件指标 @param topologyIngestion 拓扑状态机
     *  @param modbusConfigService Modbus 配置下发端口 */
    public EmqxConnectionEventService(JdbcTemplate jdbc, TransactionTemplate tx, DeviceConnectionMetrics metrics,
                                      DeviceTopologyIngestionService topologyIngestion,
                                      ModbusConfigService modbusConfigService, com.things.link.device.application.DevicePresenceWebhookSource webhookSource,
                                      DeviceMqttConnectionRepository connections, TransactionLocalRlsScope rls) {
        this.connections = connections; this.rls = rls;
        this.jdbc = jdbc;
        this.webhookSource = webhookSource;
        this.tx = tx;
        this.metrics = metrics;
        this.topologyIngestion = topologyIngestion;
        this.modbusConfigService = modbusConfigService;
    }

    /** @return 事件是否成功匹配并写入设备 */
    public boolean connected(String username, String clientId, String clientIp, String node, DeviceMqttIdentity original, UUID connectionId) {
        if (!validConnection(clientId, connectionId)) return false;
        boolean[] changed = {false};
        boolean accepted = execute(username, original, scope -> {
            if (scope.isSubDevice() || !scope.projectActive() || !scope.mqttEnabled()) {
                return false; // ADR0192：原生绑定和非ACTIVE项目不得被迟到MQTT连接复活。
            }
            var transition = connections.connected(connectionScope(original, clientId), connectionId, clientIp, node);
            if (!transition.accepted()) return false;
            if (!transition.changed()) return true;
            changed[0] = true;
            jdbc.update("""
                    UPDATE dev_device SET status = 'ONLINE', last_online_at = now(), updated_at = now()
                     WHERE project_id = ? AND id = ?
                    """, scope.projectId(), scope.deviceId());
            webhookSource.append(scope.tenantId(),scope.projectId(),scope.deviceId(),scope.generation(),scope.deviceStatus(),
                    "ONLINE","MQTT","CONNECTED",null,null,scope.observedAt());
            if (scope.isGateway()) {
                // 网关上线/重连时重推最新已发布配置，覆盖「离线期间发布」的缺口（无已发布点位时 no-op）。
                modbusConfigService.pushConfig(scope.projectId(), scope.deviceId());
            }
            return true;
        });
        if (accepted && changed[0]) {
            recordAfterCommit(metrics::recordConnected);
        }
        return accepted;
    }

    /** @return 事件是否成功匹配并关闭设备会话 */
    public boolean disconnected(String username, String clientId, String reason, DeviceMqttIdentity original, UUID connectionId) {
        if (!validConnection(clientId, connectionId)) return false;
        boolean[] changed = {false};
        boolean accepted = execute(username, original, scope -> {
            if (scope.isSubDevice()) {
                return false; // SUB_DEVICE 无独立接入，连接事件 no-op 兜底（ADR 0033）
            }
            var transition = connections.disconnected(connectionScope(original, clientId), connectionId, reason);
            if (!transition.accepted()) return false;
            if (!transition.changed() || transition.closedConnections() == 0) return true;
            changed[0] = true;
            // 切换后只清理历史MQTT事实；不能改写原生权威状态或伪造其离线边沿。
            if (!scope.mqttEnabled()) return true;
            int wentOffline = jdbc.update("""
                    UPDATE dev_device SET status = 'OFFLINE', updated_at = now()
                     WHERE project_id = ? AND id = ?
                       AND NOT EXISTS (
                           SELECT 1 FROM dev_connection
                            WHERE device_id = ? AND protocol = 'MQTT' AND config_version = ? AND disconnected_at IS NULL
                       )
                    """, scope.projectId(), scope.deviceId(), scope.deviceId(), original.configVersion());
            if(wentOffline==1)webhookSource.append(scope.tenantId(),scope.projectId(),scope.deviceId(),scope.generation(),scope.deviceStatus(),
                    "OFFLINE","MQTT","DISCONNECTED",null,null,scope.observedAt());
            if (wentOffline == 1 && scope.isGateway()) {
                topologyIngestion.cascadeGatewayOffline(scope.tenantId(), scope.projectId(),
                        scope.deviceId(), Instant.now());
            }
            return true;
        });
        if (accepted && changed[0]) {
            recordAfterCommit(metrics::recordDisconnected);
        }
        return accepted;
    }

    /** 定位设备并注入 RLS 范围；数据库故障时拒绝事件，交由 EMQX 重试/告警。 */
    private boolean execute(String username, DeviceMqttIdentity original, java.util.function.Function<DeviceScope, Boolean> operation) {
        if (original == null) return false;
        if (username == null || !username.matches("^[^/]+/[^/]+$")) return false;
        String[] parts = username.split("/", -1);
        try {
            Boolean result = tx.execute(status -> {
                List<Map<String, Object>> projects = jdbc.queryForList(
                        "SELECT id, tenant_id, status FROM sys_project WHERE project_key = ? AND deleted_at IS NULL FOR SHARE", parts[0]);
                if (projects.size() != 1) return false;
                UUID projectId = (UUID) projects.getFirst().get("id");
                UUID tenantId = (UUID) projects.getFirst().get("tenant_id");
                rls.establish(tenantId, projectId);
                var generation=webhookSource.capture(tenantId,projectId);
                List<Map<String, Object>> devices = jdbc.queryForList(
                        "SELECT d.id, d.status, d.credential_version, t.device_kind FROM dev_device d "
                                + "LEFT JOIN dev_type t ON t.id = d.device_type_id AND t.deleted_at IS NULL "
                                + "WHERE d.tenant_id = ? AND d.project_id = ? AND d.device_key = ? AND d.deleted_at IS NULL FOR NO KEY UPDATE OF d",
                        tenantId, projectId, parts[1]);
                if (devices.size() != 1) return false;
                UUID deviceId = (UUID) devices.getFirst().get("id");
                String deviceKind = (String) devices.getFirst().get("device_kind");
                if (!tenantId.equals(original.identity().tenantId()) || !projectId.equals(original.identity().projectId())
                        || !deviceId.equals(original.identity().deviceId())
                        || ((Number) devices.getFirst().get("credential_version")).longValue() != original.identity().credentialVersion()) return false;
                long configVersion = jdbc.queryForObject("SELECT COALESCE((SELECT config_version FROM dev_access_binding WHERE tenant_id=? AND project_id=? AND device_id=?),0)",
                        Long.class, tenantId, projectId, deviceId);
                if (configVersion != original.configVersion()) return false;
                // 设备锁与配置写者共享；没有绑定行才按存量MQTT解释。
                boolean mqttEnabled = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT NOT EXISTS (SELECT 1 FROM dev_access_binding
                          WHERE project_id=? AND device_id=? AND (protocol<>'MQTT' OR NOT enabled))
                        """, Boolean.class, projectId, deviceId));
                return operation.apply(new DeviceScope(tenantId, projectId, deviceId, deviceKind,(String)devices.getFirst().get("status"),generation,
                        jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant(),
                        "ACTIVE".equals(projects.getFirst().get("status")), mqttEnabled));
            });
            return Boolean.TRUE.equals(result);
        } catch (DataAccessException e) {
            log.warn("EMQX 连接事件写入失败 username={}", username, e);
            throw e; // 必须由HTTP错误链返回5xx，不能以200 accepted=false吞掉数据库重试。
        }
    }

    /** 外层业务事务尚未提交时，指标随最终提交确认；回滚不能虚增。 */
    private static void recordAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { action.run(); }
                    });
        } else action.run();
    }

    /** 回调必须携带服务器有效会话名及原连接UUID。 */
    private static boolean validConnection(String clientId, UUID connectionId) {
        return connectionId != null && clientId != null && clientId.matches("tc-device-[0-9a-f]{64}");
    }

    /** 固定原认证范围，不从当前设备补齐旧身份。 */
    private static DeviceMqttConnectionRepository.Scope connectionScope(DeviceMqttIdentity original, String clientId) {
        var identity = original.identity();
        return new DeviceMqttConnectionRepository.Scope(identity.tenantId(), identity.projectId(), identity.deviceId(),
                identity.credentialVersion(), original.configVersion(), clientId);
    }

    /** 当前项目/协议许可在锁内读取，设备类型用于拒绝子设备独立接入与识别网关级联。 */
    private record DeviceScope(UUID tenantId, UUID projectId, UUID deviceId, String deviceKind,String deviceStatus,java.util.OptionalLong generation,Instant observedAt,boolean projectActive,boolean mqttEnabled) {
        /** @return 是否为无独立接入的子设备 */
        boolean isSubDevice() {
            return DeviceType.DeviceKind.SUB_DEVICE.name().equals(deviceKind);
        }

        /** @return 是否为代理子设备接入的网关 */
        boolean isGateway() {
            return DeviceType.DeviceKind.GATEWAY.name().equals(deviceKind);
        }
    }
}
