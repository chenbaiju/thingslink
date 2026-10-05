package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.things.link.shared.message.TransportProtocol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 保存生效接入配置与会话代次；接管与代次递增由数据库仲裁。 */
@Repository
public class JdbcDeviceAccessSessionRepository implements DeviceAccessSessionRepository {

    /** 会话列清单，读取与 RETURNING 共用一份。 */
    private static final String SESSION_COLUMNS =
            "id, device_id, protocol, session_id, owner_instance, generation, config_version, connected_at,"
                    + " last_seen_at";

    /** JDBC 访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 访问入口
     */
    public JdbcDeviceAccessSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DeviceAccessBinding> findBinding(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT device_id, tenant_id, project_id, protocol, config_version, heartbeat_seconds, enabled,
                       last_activity_at, created_at, updated_at
                  FROM dev_access_binding WHERE project_id = ? AND device_id = ?
                """, this::mapBinding, projectId, deviceId).stream().findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>代次只在协议或心跳周期真正变化时递增：重复提交同一配置是幂等重放，不应让既有会话无故过期。</p>
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public DeviceAccessBinding bind(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol protocol,
                                    Integer heartbeatSeconds) {
        if (jdbcTemplate.queryForList("SELECT id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL FOR NO KEY UPDATE",UUID.class,tenantId,projectId,deviceId).isEmpty())
            throw new IllegalArgumentException("设备不属于当前接入配置范围");
        return jdbcTemplate.queryForObject("""
                INSERT INTO dev_access_binding
                    (device_id, tenant_id, project_id, protocol, config_version, heartbeat_seconds, enabled)
                VALUES (?, ?, ?, ?, 1, ?, true)
                ON CONFLICT (device_id) DO UPDATE
                   SET protocol = EXCLUDED.protocol,
                       heartbeat_seconds = EXCLUDED.heartbeat_seconds,
                       enabled = true,
                       config_version = CASE
                           WHEN dev_access_binding.protocol = EXCLUDED.protocol
                                AND dev_access_binding.heartbeat_seconds IS NOT DISTINCT FROM EXCLUDED.heartbeat_seconds
                                AND dev_access_binding.enabled
                           THEN dev_access_binding.config_version
                           ELSE dev_access_binding.config_version + 1 END,
                       updated_at = now()
                RETURNING device_id, tenant_id, project_id, protocol, config_version, heartbeat_seconds, enabled,
                          last_activity_at, created_at, updated_at
                """, this::mapBinding, deviceId, tenantId, projectId, protocol.name(), heartbeatSeconds);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DeviceAccessBinding changeBinding(UUID tenantId, UUID projectId, UUID deviceId, long expectedVersion,
            TransportProtocol protocol, boolean enabled, boolean forceAdvance) {
        if (protocol == null || expectedVersion < 0) {
            throw new IllegalArgumentException("协议及期望配置版本无效");
        }
        if (jdbcTemplate.queryForList("""
                SELECT id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?
                  AND deleted_at IS NULL FOR NO KEY UPDATE
                """, UUID.class, tenantId, projectId, deviceId).isEmpty()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        DeviceAccessBinding current = findBinding(projectId, deviceId)
                .orElseGet(() -> DeviceAccessBinding.legacyMqtt(tenantId, projectId, deviceId));
        if (!tenantId.equals(current.tenantId())) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        if (current.configVersion() != expectedVersion) {
            throw new BusinessException(DeviceErrorCode.ACCESS_CONFIG_CONFLICT);
        }
        if (!forceAdvance && current.protocol() == protocol && current.enabled() == enabled) {
            return current;
        }
        if (expectedVersion == Long.MAX_VALUE) {
            throw new BusinessException(DeviceErrorCode.ACCESS_CONFIG_EXHAUSTED);
        }
        return jdbcTemplate.query("""
                INSERT INTO dev_access_binding
                    (device_id, tenant_id, project_id, protocol, config_version, enabled)
                VALUES (?, ?, ?, ?, 1, ?)
                ON CONFLICT (device_id) DO UPDATE
                   SET protocol=EXCLUDED.protocol, enabled=EXCLUDED.enabled,
                       config_version=dev_access_binding.config_version+1, updated_at=clock_timestamp()
                 WHERE dev_access_binding.config_version=? AND dev_access_binding.tenant_id=?
                       AND dev_access_binding.project_id=?
                RETURNING device_id, tenant_id, project_id, protocol, config_version, heartbeat_seconds, enabled,
                          last_activity_at, created_at, updated_at
                """, this::mapBinding, deviceId, tenantId, projectId, protocol.name(), enabled,
                expectedVersion, tenantId, projectId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.ACCESS_CONFIG_CONFLICT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>三步在同一事务内：按设备取事务级 advisory 锁串行化同一设备的建立请求，关闭既有活跃会话并记接管
     * 原因，再以当前最大代次 +1 写入新会话。锁只按设备、不按项目，因此不同设备互不阻塞。</p>
     */
    @Override
    public boolean touchActivity(UUID projectId, UUID deviceId, Instant at) {
        return jdbcTemplate.update("""
                UPDATE dev_access_binding SET last_activity_at = ?
                 WHERE project_id = ? AND device_id = ?
                """, Timestamp.from(at), projectId, deviceId) > 0;
    }

    @Override
    public EstablishedSession establish(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol protocol,
                                        String sessionId, String ownerInstance, long configVersion, String clientIp,
                                        Instant at, Long heartbeatIntervalMillis) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))", Object.class,
                deviceId.toString());
        int replaced = jdbcTemplate.update("""
                UPDATE dev_connection
                   SET disconnected_at = ?, disconnect_reason = 'replaced_by_new_session'
                 WHERE project_id = ? AND device_id = ? AND protocol = ? AND disconnected_at IS NULL
                """, Timestamp.from(at), projectId, deviceId, protocol.name());
        long generation = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(generation), 0) + 1 FROM dev_connection WHERE device_id = ?
                """, Long.class, deviceId);
        List<EstablishedSession> inserted = jdbcTemplate.query("""
                INSERT INTO dev_connection
                    (id, tenant_id, project_id, device_id, session_id, protocol, broker_node, client_ip,
                     connected_at, config_version, generation, owner_instance, last_seen_at, heartbeat_interval_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, session_id, generation, config_version
                """, (rows, index) -> new EstablishedSession(rows.getObject("id", UUID.class),
                        rows.getString("session_id"), rows.getLong("generation"), rows.getLong("config_version"),
                        replaced > 0),
                UUID.randomUUID(), tenantId, projectId, deviceId, sessionId, protocol.name(), null, clientIp,
                Timestamp.from(at), configVersion, generation, ownerInstance, Timestamp.from(at), heartbeatIntervalMillis);
        if (inserted.isEmpty()) {
            throw new IllegalStateException("接入会话写入未返回事实");
        }
        return inserted.getFirst();
    }

    @Override
    public boolean touch(UUID projectId, UUID deviceId, UUID sessionRowId, String ownerInstance, Instant at) {
        return jdbcTemplate.update("""
                UPDATE dev_connection
                   SET last_seen_at = ?
                 WHERE project_id = ? AND device_id = ? AND id = ? AND disconnected_at IS NULL
                   AND owner_instance = ?
                """, Timestamp.from(at), projectId, deviceId, sessionRowId, ownerInstance) > 0;
    }

    @Override
    public boolean close(UUID projectId, UUID deviceId, UUID sessionRowId, String ownerInstance, String reason,
                         Instant at) {
        return jdbcTemplate.update("""
                UPDATE dev_connection
                   SET disconnected_at = ?, disconnect_reason = ?
                 WHERE project_id = ? AND device_id = ? AND id = ? AND disconnected_at IS NULL
                   AND owner_instance = ?
                """, Timestamp.from(at), reason, projectId, deviceId, sessionRowId, ownerInstance) > 0;
    }

    @Override
    public Optional<ActiveSession> findActive(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT %s FROM dev_connection
                 WHERE project_id = ? AND device_id = ? AND disconnected_at IS NULL
                   AND (protocol <> 'TCP' OR last_seen_at + heartbeat_interval_millis * interval '3 milliseconds' > clock_timestamp())
                 ORDER BY generation DESC LIMIT 1
                """.formatted(SESSION_COLUMNS), this::mapSession, projectId, deviceId).stream().findFirst();
    }

    @Override
    public Optional<LastDisconnect> findLastDisconnect(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT disconnect_reason, disconnected_at FROM dev_connection
                 WHERE project_id = ? AND device_id = ? AND disconnected_at IS NOT NULL
                 ORDER BY disconnected_at DESC LIMIT 1
                """, (rows, index) -> new LastDisconnect(rows.getString("disconnect_reason"),
                        instant(rows, "disconnected_at")), projectId, deviceId).stream().findFirst();
    }

    /**
     * 映射接入配置行。
     *
     * @param rows 查询结果
     * @param index 当前行号
     * @return 接入配置
     * @throws SQLException 列读取失败
     */
    private DeviceAccessBinding mapBinding(ResultSet rows, int index) throws SQLException {
        return new DeviceAccessBinding(rows.getObject("device_id", UUID.class),
                rows.getObject("tenant_id", UUID.class), rows.getObject("project_id", UUID.class),
                TransportProtocol.valueOf(rows.getString("protocol")), rows.getLong("config_version"),
                rows.getObject("heartbeat_seconds", Integer.class), rows.getBoolean("enabled"),
                instant(rows, "last_activity_at"), instant(rows, "created_at"), instant(rows, "updated_at"));
    }

    /**
     * 映射活跃会话行。
     *
     * @param rows 查询结果
     * @param index 当前行号
     * @return 活跃会话
     * @throws SQLException 列读取失败
     */
    private ActiveSession mapSession(ResultSet rows, int index) throws SQLException {
        return new ActiveSession(rows.getObject("id", UUID.class), rows.getObject("device_id", UUID.class),
                TransportProtocol.valueOf(rows.getString("protocol")), rows.getString("session_id"),
                rows.getString("owner_instance"), rows.getLong("generation"), rows.getLong("config_version"),
                instant(rows, "connected_at"), instant(rows, "last_seen_at"));
    }

    /** 可空时间列读取。 */
    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
