package com.things.link.alarm.infrastructure.persistence;

import com.things.link.alarm.application.AlarmExportSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

/** 只读取alarm域实例与事件表的流式导出适配器。 */
@Repository
public class JdbcAlarmExportSource implements AlarmExportSource {

    /** 服务端游标抓取批次。 */
    private static final int FETCH_SIZE = 1_024;
    /** 本域JDBC访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate JDBC访问器 */
    public JdbcAlarmExportSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public long streamInstances(UUID tenantId, UUID projectId, InstanceSink sink) {
        long[] count = {0L};
        jdbcTemplate.query(connection -> statement(connection, """
                SELECT id, rule_id, originator_type, originator_id, alarm_type, severity,
                       condition_state, ack_state, clear_reason, first_condition_at,
                       recovery_condition_at, activated_at, cleared_at, acknowledged_at,
                       acknowledged_by, last_received_at, last_occurred_at, last_value,
                       version, created_at, updated_at
                  FROM alarm_instance
                 WHERE tenant_id = ? AND project_id = ?
                 ORDER BY id
                """, tenantId, projectId), rs -> {
            sink.accept(new AlarmInstanceExportRow(
                    rs.getObject("id", UUID.class), rs.getObject("rule_id", UUID.class),
                    rs.getString("originator_type"), rs.getObject("originator_id", UUID.class),
                    rs.getString("alarm_type"), rs.getString("severity"), rs.getString("condition_state"),
                    rs.getString("ack_state"), rs.getString("clear_reason"),
                    instant(rs.getTimestamp("first_condition_at")), instant(rs.getTimestamp("recovery_condition_at")),
                    instant(rs.getTimestamp("activated_at")), instant(rs.getTimestamp("cleared_at")),
                    instant(rs.getTimestamp("acknowledged_at")), rs.getObject("acknowledged_by", UUID.class),
                    instant(rs.getTimestamp("last_received_at")), instant(rs.getTimestamp("last_occurred_at")),
                    rs.getDouble("last_value"), rs.getInt("version"), instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("updated_at"))));
            count[0]++;
        });
        return count[0];
    }

    /** {@inheritDoc} */
    @Override
    public long streamEvents(UUID tenantId, UUID projectId, EventSink sink) {
        long[] count = {0L};
        jdbcTemplate.query(connection -> statement(connection, """
                SELECT id, instance_id, event_type, source_message_id, trace_id, value,
                       occurred_at, received_at, actor_id, condition_state, ack_state, clear_reason
                  FROM alarm_event
                 WHERE tenant_id = ? AND project_id = ?
                 ORDER BY id
                """, tenantId, projectId), rs -> {
            sink.accept(new AlarmEventExportRow(
                    rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class),
                    rs.getString("event_type"), rs.getObject("source_message_id", UUID.class),
                    rs.getString("trace_id"), rs.getObject("value", Double.class),
                    instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("received_at")),
                    rs.getObject("actor_id", UUID.class), rs.getString("condition_state"),
                    rs.getString("ack_state"), rs.getString("clear_reason")));
            count[0]++;
        });
        return count[0];
    }

    /** 创建带可信项目二元组和服务端游标的语句。 */
    private static PreparedStatement statement(java.sql.Connection connection, String sql,
                                               UUID tenantId, UUID projectId) throws java.sql.SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setObject(1, tenantId);
        statement.setObject(2, projectId);
        statement.setFetchSize(FETCH_SIZE);
        return statement;
    }

    /** nullable JDBC时间到UTC。 */
    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
