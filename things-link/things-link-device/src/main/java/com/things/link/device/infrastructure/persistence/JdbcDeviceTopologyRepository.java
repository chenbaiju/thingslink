package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceTopology;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 持久化拓扑绑定，保持与设备域其他仓储一致的 JDBC 风格。 */
@Repository
public class JdbcDeviceTopologyRepository implements DeviceTopologyRepository {
    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcDeviceTopologyRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Override public void create(DeviceTopology topology) {
        DeviceTopologyConstraintTranslator.execute(() -> jdbcTemplate.update("""
                INSERT INTO dev_topo
                    (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source,
                     online_status, last_online_at, status_changed_at, bound_by, bound_at,
                     unbound_by, unbound_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, topology.id(), topology.tenantId(), topology.projectId(), topology.gatewayDeviceId(),
                topology.subDeviceId(), topology.bindSource().name(), topology.onlineStatus().name(),
                null, null, topology.boundBy(), Timestamp.from(topology.boundAt()),
                null, null, topology.version(), Timestamp.from(topology.createdAt())));
    }

    @Override public Optional<DeviceTopology> findActiveBySubDevice(UUID projectId, UUID subDeviceId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source,
                       online_status, last_online_at, status_changed_at, bound_by, bound_at,
                       unbound_by, unbound_at, version, created_at
                  FROM dev_topo
                 WHERE project_id = ? AND sub_device_id = ? AND unbound_at IS NULL
                """, this::map, projectId, subDeviceId).stream().findFirst();
    }

    @Override public List<DeviceTopology> findActiveByGateway(UUID projectId, UUID gatewayId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source,
                       online_status, last_online_at, status_changed_at, bound_by, bound_at,
                       unbound_by, unbound_at, version, created_at
                  FROM dev_topo
                 WHERE project_id = ? AND gateway_device_id = ? AND unbound_at IS NULL
                 ORDER BY bound_at DESC, id DESC
                """, this::map, projectId, gatewayId);
    }

    @Override public List<DeviceTopology> findActiveByProject(UUID projectId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source,
                       online_status, last_online_at, status_changed_at, bound_by, bound_at,
                       unbound_by, unbound_at, version, created_at
                  FROM dev_topo
                 WHERE project_id = ? AND unbound_at IS NULL
                 ORDER BY gateway_device_id, bound_at DESC, id DESC
                """, this::map, projectId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasIncompatibleRoleForDevice(UUID projectId, UUID deviceId, DeviceType.DeviceKind candidateKind) {
        String candidate = candidateKind == null ? null : candidateKind.name();
        // ADR0057：NULL 不是可接受分类；显式 text 转换与 IS DISTINCT FROM 避免空参数推断或三值逻辑漏判。
        Boolean incompatible = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM dev_topo t
                     WHERE t.project_id = ? AND t.unbound_at IS NULL
                       AND ((t.gateway_device_id = ? AND CAST(? AS text) IS DISTINCT FROM 'GATEWAY')
                         OR (t.sub_device_id = ? AND CAST(? AS text) IS DISTINCT FROM 'SUB_DEVICE'))
                )
                """, Boolean.class, projectId, deviceId, candidate, deviceId, candidate);
        return Boolean.TRUE.equals(incompatible);
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasIncompatibleRoleForType(UUID projectId, UUID deviceTypeId, DeviceType.DeviceKind candidateKind) {
        String candidate = candidateKind == null ? null : candidateKind.name();
        // ADR0057：只观察已持类型锁保护的关联集合；过滤 deleted_at 或锁住设备都会破坏本查询的保护边界。
        Boolean incompatible = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM dev_topo t
                    JOIN dev_device d ON d.project_id = t.project_id
                     AND (d.id = t.gateway_device_id OR d.id = t.sub_device_id)
                     WHERE t.project_id = ? AND d.device_type_id = ? AND t.unbound_at IS NULL
                       AND ((d.id = t.gateway_device_id AND CAST(? AS text) IS DISTINCT FROM 'GATEWAY')
                         OR (d.id = t.sub_device_id AND CAST(? AS text) IS DISTINCT FROM 'SUB_DEVICE'))
                )
                """, Boolean.class, projectId, deviceTypeId, candidate, candidate);
        return Boolean.TRUE.equals(incompatible);
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasInvalidRolesInGatewayComponent(UUID projectId, UUID gatewayId) {
        // ADR0057：必须先只读验证全部相关角色，再进入多设备锁；LEFT JOIN 让缺失类型显式失败。
        // 直接子设备若又成为另一条有效关系的网关，也属于本操作会触及的异常角色，不能只验一层边。
        Boolean invalid = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM dev_topo t
                    LEFT JOIN dev_device g ON g.project_id = t.project_id AND g.id = t.gateway_device_id
                    LEFT JOIN dev_device s ON s.project_id = t.project_id AND s.id = t.sub_device_id
                    LEFT JOIN dev_type gt ON gt.project_id = t.project_id AND gt.id = g.device_type_id
                    LEFT JOIN dev_type st ON st.project_id = t.project_id AND st.id = s.device_type_id
                     WHERE t.project_id = ? AND t.unbound_at IS NULL
                       AND (t.gateway_device_id = ? OR t.sub_device_id = ? OR EXISTS (
                           SELECT 1 FROM dev_topo parent
                            WHERE parent.project_id = t.project_id AND parent.unbound_at IS NULL
                              AND parent.gateway_device_id = ? AND parent.sub_device_id = t.gateway_device_id
                       ))
                       AND (g.id IS NULL OR s.id IS NULL OR g.deleted_at IS NOT NULL OR s.deleted_at IS NOT NULL
                         OR gt.deleted_at IS NOT NULL OR st.deleted_at IS NOT NULL
                         OR gt.device_kind IS DISTINCT FROM 'GATEWAY'
                         OR st.device_kind IS DISTINCT FROM 'SUB_DEVICE')
                )
                """, Boolean.class, projectId, gatewayId, gatewayId, gatewayId);
        return Boolean.TRUE.equals(invalid);
    }

    @Override public boolean closeActive(UUID projectId, UUID subDeviceId, UUID unboundBy) {
        return jdbcTemplate.update("""
                UPDATE dev_topo SET unbound_at = now(), unbound_by = ?
                 WHERE project_id = ? AND sub_device_id = ? AND unbound_at IS NULL
                """, unboundBy, projectId, subDeviceId) == 1;
    }

    @Override public boolean updateOnlineStatus(UUID projectId, UUID subDeviceId,
                                                DeviceTopology.OnlineStatus status, Instant receivedAt) {
        return jdbcTemplate.update("""
                UPDATE dev_topo
                   SET online_status = ?,
                       last_online_at = CASE WHEN ? = 'ONLINE' THEN ? ELSE last_online_at END,
                       status_changed_at = ?,
                       version = version + 1
                 WHERE project_id = ? AND sub_device_id = ? AND unbound_at IS NULL
                   AND (status_changed_at IS NULL OR status_changed_at <= ?)
                """, status.name(), status.name(), Timestamp.from(receivedAt), Timestamp.from(receivedAt),
                projectId, subDeviceId, Timestamp.from(receivedAt)) == 1;
    }

    /** @param rs 结果集 @param rowNum 行号 @return 拓扑领域对象 */
    private DeviceTopology map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastOnline = rs.getTimestamp("last_online_at");
        Timestamp statusChanged = rs.getTimestamp("status_changed_at");
        Timestamp unboundAt = rs.getTimestamp("unbound_at");
        return new DeviceTopology(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("gateway_device_id", UUID.class),
                rs.getObject("sub_device_id", UUID.class),
                DeviceTopology.BindSource.valueOf(rs.getString("bind_source")),
                DeviceTopology.OnlineStatus.valueOf(rs.getString("online_status")),
                lastOnline == null ? null : lastOnline.toInstant(),
                statusChanged == null ? null : statusChanged.toInstant(),
                rs.getObject("bound_by", UUID.class), rs.getTimestamp("bound_at").toInstant(),
                rs.getObject("unbound_by", UUID.class), unboundAt == null ? null : unboundAt.toInstant(),
                rs.getInt("version"), rs.getTimestamp("created_at").toInstant());
    }
}
