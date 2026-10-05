package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceMqttConnectionRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** 原连接账本与单调下界；设备锁覆盖所有并发签发/受理，不能脱离来源业务事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcDeviceMqttConnectionRepository implements DeviceMqttConnectionRepository {
    /** 同一事务的数据访问入口。 */
    private final JdbcTemplate jdbc;

    /** 集中建立完整事务局部范围，禁止中途切换租户。 */
    private final TransactionLocalRlsScope rls;

    /** 装配实际数据源与统一RLS入口。 */
    public JdbcDeviceMqttConnectionRepository(JdbcTemplate jdbc, TransactionLocalRlsScope rls) {
        this.jdbc = jdbc; this.rls = rls;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<Ticket> issue(Scope scope) {
        if (!lock(scope, true)) return Optional.empty();
        // 仅清理本设备限量过期非活跃票据；游标保持，完整跨设备维护由独立片接线。
        jdbc.update("""
                DELETE FROM dev_mqtt_connection_ticket t WHERE t.id IN (
                  SELECT x.id FROM dev_mqtt_connection_ticket x
                   WHERE x.tenant_id=? AND x.project_id=? AND x.device_id=? AND x.expires_at<=clock_timestamp()
                     AND NOT EXISTS(SELECT 1 FROM dev_connection c WHERE c.mqtt_connection_id=x.id AND c.disconnected_at IS NULL)
                   ORDER BY x.expires_at,x.id LIMIT 500)
                """, scope.tenantId(), scope.projectId(), scope.deviceId());
        Long waiting = jdbc.queryForObject("""
                SELECT count(*) FROM dev_mqtt_connection_ticket t
                 WHERE t.tenant_id=? AND t.project_id=? AND t.device_id=?
                   AND NOT EXISTS(SELECT 1 FROM dev_connection c WHERE c.mqtt_connection_id=t.id AND c.disconnected_at IS NULL)
                """, Long.class, scope.tenantId(), scope.projectId(), scope.deviceId());
        if (waiting == null || waiting >= 1024) return Optional.empty();
        return jdbc.query("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id)
                VALUES(?,?,?,?,?,?,?) RETURNING id,auth_order
                """, (rs, n) -> new Ticket(rs.getObject("id", UUID.class), rs.getLong("auth_order")),
                UUID.randomUUID(), scope.tenantId(), scope.projectId(), scope.deviceId(), scope.credentialVersion(),
                scope.configVersion(), scope.sessionId()).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Transition connected(Scope scope, UUID ticketId, String clientIp, String node) {
        if (ticketId == null || !lock(scope, true)) return Transition.rejected();
        Entry ticket = find(scope, ticketId);
        if (ticket == null || ticket.order() < cursor(scope)) return Transition.rejected();
        if (ticket.state().equals("ACTIVE"))
            return active(ticketId) ? new Transition(true, false, 0) : Transition.rejected();
        if (!ticket.state().equals("PENDING") || ticket.expired()) return Transition.rejected();
        advance(scope, ticket.order());
        int closed = closeEarlier(scope, ticket.order());
        jdbc.update("UPDATE dev_mqtt_connection_ticket SET state='ACTIVE' WHERE id=?", ticketId);
        jdbc.update("""
                INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,broker_node,client_ip,
                    connected_at,config_version,mqtt_connection_id)
                VALUES(?,?,?,?,?,'MQTT',?,?,clock_timestamp(),?,?)
                """, Uuid7.generate(), scope.tenantId(), scope.projectId(), scope.deviceId(), scope.sessionId(),
                blank(node), blank(clientIp), scope.configVersion(), ticketId);
        return new Transition(true, true, closed);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Transition disconnected(Scope scope, UUID ticketId, String reason) {
        if (ticketId == null || !lock(scope, false)) return Transition.rejected();
        Entry ticket = find(scope, ticketId);
        if (ticket == null) return Transition.rejected();
        if (ticket.state().equals("CLOSED")) return new Transition(true, false, 0);
        if (ticket.state().equals("PENDING") && ticket.expired()) return Transition.rejected();
        int closed = 0;
        if (ticket.order() >= cursor(scope)) {
            advance(scope, ticket.order());
            closed = closeEarlier(scope, ticket.order());
        }
        jdbc.update("UPDATE dev_mqtt_connection_ticket SET state='CLOSED' WHERE id=?", ticketId);
        closed += jdbc.update("""
                UPDATE dev_connection SET disconnected_at=clock_timestamp(),disconnect_reason=?
                 WHERE tenant_id=? AND project_id=? AND device_id=? AND protocol='MQTT'
                   AND mqtt_connection_id=? AND disconnected_at IS NULL
                """, blank(reason), scope.tenantId(), scope.projectId(), scope.deviceId(), ticketId);
        return new Transition(true, true, closed);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean permits(Scope scope, UUID ticketId) {
        if (ticketId == null || !lock(scope, true)) return false;
        Entry ticket = find(scope, ticketId);
        return ticket != null && ticket.order() >= cursor(scope)
                && (ticket.state().equals("PENDING") && !ticket.expired()
                    || ticket.state().equals("ACTIVE") && active(ticketId));
    }

    /** 先项目后设备，在事务局部RLS内复核原身份，归档只可关闭历史连接。 */
    private boolean lock(Scope scope, boolean requireActive) {
        var projects = jdbc.queryForList("""
                SELECT status FROM sys_project WHERE tenant_id=? AND id=? AND deleted_at IS NULL FOR SHARE
                """, String.class, scope.tenantId(), scope.projectId());
        if (projects.size() != 1 || requireActive && !projects.getFirst().equals("ACTIVE")) return false;
        rls.establish(scope.tenantId(), scope.projectId());
        var devices = jdbc.queryForList("""
                SELECT d.credential_version FROM dev_device d
                 LEFT JOIN dev_type t ON t.id=d.device_type_id AND t.deleted_at IS NULL
                 WHERE d.tenant_id=? AND d.project_id=? AND d.id=? AND d.deleted_at IS NULL
                   AND (t.device_kind IS NULL OR t.device_kind<>'SUB_DEVICE') FOR NO KEY UPDATE OF d
                """, Long.class, scope.tenantId(), scope.projectId(), scope.deviceId());
        if (devices.size() != 1 || devices.getFirst() != scope.credentialVersion()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE((SELECT config_version=? AND protocol='MQTT' AND enabled
                  FROM dev_access_binding WHERE tenant_id=? AND project_id=? AND device_id=?),?=0)
                """, Boolean.class, scope.configVersion(), scope.tenantId(), scope.projectId(), scope.deviceId(), scope.configVersion()));
    }

    /** 查询必须逐字段匹配原身份，UUID本身不代替归属校验。 */
    private Entry find(Scope scope, UUID ticketId) {
        return jdbc.query("""
                SELECT auth_order,state,expires_at<=clock_timestamp() AS expired FROM dev_mqtt_connection_ticket
                 WHERE id=? AND tenant_id=? AND project_id=? AND device_id=? AND credential_version=?
                   AND config_version=? AND session_id=? FOR UPDATE
                """, this::entry, ticketId, scope.tenantId(), scope.projectId(), scope.deviceId(),
                scope.credentialVersion(), scope.configVersion(), scope.sessionId()).stream().findFirst().orElse(null);
    }

    /** 持久下界缺失仅表示尚未观察，不从Broker时间推导。 */
    private long cursor(Scope scope) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT max_observed_order FROM dev_mqtt_session_cursor
                  WHERE tenant_id=? AND project_id=? AND device_id=? AND config_version=? AND session_id=?),0)
                """, Long.class, scope.tenantId(), scope.projectId(), scope.deviceId(), scope.configVersion(), scope.sessionId());
    }

    /** 设备锁串行，数据库触发器同时阻止任何写者倒退。 */
    private void advance(Scope scope, long order) {
        jdbc.update("""
                INSERT INTO dev_mqtt_session_cursor(tenant_id,project_id,device_id,config_version,session_id,max_observed_order)
                VALUES(?,?,?,?,?,?) ON CONFLICT(device_id,config_version,session_id)
                DO UPDATE SET max_observed_order=GREATEST(dev_mqtt_session_cursor.max_observed_order,EXCLUDED.max_observed_order)
                """, scope.tenantId(), scope.projectId(), scope.deviceId(), scope.configVersion(), scope.sessionId(), order);
    }

    /** 接管只关闭同配置同会话较早身份，其他Client ID不受影响。 */
    private int closeEarlier(Scope scope, long order) {
        int closed = jdbc.update("""
                UPDATE dev_connection c SET disconnected_at=clock_timestamp(),disconnect_reason='mqtt_connection_superseded'
                 FROM dev_mqtt_connection_ticket t WHERE c.mqtt_connection_id=t.id AND c.disconnected_at IS NULL
                   AND t.tenant_id=? AND t.project_id=? AND t.device_id=? AND t.config_version=? AND t.session_id=? AND t.auth_order<?
                """, scope.tenantId(), scope.projectId(), scope.deviceId(), scope.configVersion(), scope.sessionId(), order);
        jdbc.update("""
                UPDATE dev_mqtt_connection_ticket SET state='CLOSED'
                 WHERE tenant_id=? AND project_id=? AND device_id=? AND config_version=? AND session_id=? AND auth_order<? AND state<>'CLOSED'
                """, scope.tenantId(), scope.projectId(), scope.deviceId(), scope.configVersion(), scope.sessionId(), order);
        return closed;
    }

    /** ACTIVE账本仍需对应未关闭连接，控制事务关闭后不能留下ACL许可。 */
    private boolean active(UUID ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dev_connection WHERE mqtt_connection_id=? AND disconnected_at IS NULL)", Boolean.class, ticketId));
    }
    /** 小型内部行映射，不对外暴露过期时钟。 */
    private Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getLong("auth_order"), rs.getString("state"), rs.getBoolean("expired"));
    }
    /** 内部不可变查询快照。 */
    private record Entry(long order, String state, boolean expired) { }
    /** 保留既有可空节点/IP语义。 */
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
