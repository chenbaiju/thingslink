package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppAlarm;
import com.things.link.enduser.domain.AppAlarmQuery;
import com.things.link.enduser.domain.AppAlarmRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 仅组合精确许可的设备/告警公开视图，权限与筛选在数据库中先于分页。 */
@Repository
public class JdbcAppAlarmRepository implements AppAlarmRepository {
    private static final String SELECT = """
            SELECT a.id,a.device_id,d.name AS device_name,d.device_key,a.alarm_type,a.severity,
                   a.condition_state,a.ack_state,a.first_condition_at,a.activated_at,a.cleared_at,
                   a.acknowledged_at,a.last_received_at
              FROM app_user_device b
              JOIN dev_device_app_v1 d ON d.tenant_id=b.tenant_id AND d.project_id=b.project_id AND d.id=b.device_id
              JOIN alarm_app_history_v1 a ON a.tenant_id=d.tenant_id AND a.project_id=d.project_id AND a.device_id=d.id
             WHERE b.tenant_id=? AND b.project_id=? AND b.app_user_id=? AND b.status='ACTIVE'
            """;
    private static final RowMapper<AppAlarm> MAPPER = (rs, row) -> new AppAlarm(
            rs.getObject("id",UUID.class),rs.getObject("device_id",UUID.class),rs.getString("device_name"),
            rs.getString("device_key"),rs.getString("alarm_type"),rs.getString("severity"),
            rs.getString("condition_state"),rs.getString("ack_state"),time(rs,"first_condition_at"),
            time(rs,"activated_at"),time(rs,"cleared_at"),time(rs,"acknowledged_at"),time(rs,"last_received_at"));
    private final JdbcTemplate jdbc;
    /** @param jdbc 当前事务访问器 */
    public JdbcAppAlarmRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 沿用接口定义的当前授权边界。{@inheritDoc} */
    @Override
    public List<AppAlarm> list(UUID tenantId, UUID projectId, UUID userId, AppAlarmQuery query,
                               Instant beforeTime, UUID beforeId, int count) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>(List.of(tenantId, projectId, userId));
        if (query.deviceId()!=null) { sql.append(" AND a.device_id=?"); args.add(query.deviceId()); }
        if (query.severity()!=null) { sql.append(" AND a.severity=?"); args.add(query.severity()); }
        if (query.conditionState()!=null) { sql.append(" AND a.condition_state=?"); args.add(query.conditionState()); }
        if (query.from()!=null) {
            sql.append(" AND a.first_condition_at>=? AND a.first_condition_at<?");
            args.add(Timestamp.from(query.from())); args.add(Timestamp.from(query.to()));
        }
        if (beforeTime!=null) {
            sql.append(" AND (a.first_condition_at,a.id)<(?,?)");
            args.add(Timestamp.from(beforeTime)); args.add(beforeId);
        }
        sql.append(" ORDER BY a.first_condition_at DESC,a.id DESC LIMIT ?"); args.add(count);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
    /** 沿用接口定义的当前授权边界。{@inheritDoc} */
    @Override
    public Optional<AppAlarm> detail(UUID tenantId, UUID projectId, UUID userId, UUID id) {
        return jdbc.query(SELECT+" AND a.id=?", MAPPER, tenantId, projectId, userId, id).stream().findFirst();
    }
    /** 保留历史未知时刻为空，不用别的时间补造。 */
    private static Instant time(ResultSet row, String column) throws SQLException {
        Timestamp time = row.getTimestamp(column); return time==null ? null : time.toInstant();
    }
}
