package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceConnection;
import com.things.link.device.domain.DeviceConnectionRepository;
import com.things.link.shared.message.TransportProtocol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** 使用显式 SQL 保存和查询设备连接会话。 */
@Repository
public class JdbcDeviceConnectionRepository implements DeviceConnectionRepository {
    /** JDBC 访问入口。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问入口 */
    public JdbcDeviceConnectionRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public void create(DeviceConnection c) {
        jdbcTemplate.update("""
                INSERT INTO dev_connection
                    (id, tenant_id, project_id, device_id, session_id, protocol, broker_node, client_ip, connected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.tenantId(), c.projectId(), c.deviceId(), c.sessionId(),
                c.protocol().name(), c.brokerNode(), c.clientIp(), c.connectedAt());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public List<DeviceConnection> findByDevice(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, session_id, protocol, broker_node,
                       client_ip, connected_at, disconnected_at, disconnect_reason, created_at
                  FROM dev_connection WHERE project_id = ? AND device_id = ?
                 ORDER BY connected_at DESC LIMIT 100
                """, this::map, projectId, deviceId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<String> findActiveMqttSessionIds(UUID projectId, UUID deviceId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT session_id
                  FROM dev_connection
                 WHERE project_id = ? AND device_id = ?
                   AND protocol = 'MQTT' AND disconnected_at IS NULL
                   AND session_id IS NOT NULL AND session_id <> ''
                 ORDER BY session_id
                """, String.class, projectId, deviceId);
    }

    /** @return 数据库行对应的连接领域对象 */
    private DeviceConnection map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp disc = rs.getTimestamp("disconnected_at");
        return new DeviceConnection(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getString("session_id"),
                TransportProtocol.valueOf(rs.getString("protocol")),
                rs.getString("broker_node"), rs.getString("client_ip"),
                rs.getTimestamp("connected_at").toInstant(),
                disc == null ? null : disc.toInstant(), rs.getString("disconnect_reason"),
                rs.getTimestamp("created_at").toInstant());
    }
}
