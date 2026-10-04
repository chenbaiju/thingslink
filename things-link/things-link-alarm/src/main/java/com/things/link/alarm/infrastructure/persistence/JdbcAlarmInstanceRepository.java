package com.things.link.alarm.infrastructure.persistence;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmTimestampPrecision;
import com.things.link.shared.page.CursorPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC 实例仓储；所有状态更新都以 version 作为 Compare-And-Set 前置条件。 */
@Repository
public class JdbcAlarmInstanceRepository implements AlarmInstanceRepository {
    /** 数据库访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate 数据库访问器 */
    public JdbcAlarmInstanceRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override public Optional<AlarmInstance> findActive(UUID projectId, UUID ruleId, UUID originatorId, String alarmType) {
        return jdbcTemplate.query(instanceSelect() + """
                 WHERE project_id = ? AND rule_id = ? AND originator_id = ? AND alarm_type = ?
                   AND condition_state IN ('PENDING', 'ACTIVE')
                """, this::mapInstance, projectId, ruleId, originatorId, alarmType).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<AlarmInstance> findById(UUID projectId, UUID instanceId) {
        return jdbcTemplate.query(instanceSelect() + " WHERE project_id = ? AND id = ?", this::mapInstance,
                projectId, instanceId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public CursorPage<AlarmInstance> page(UUID projectId, String cursor, int limit) {
        Cursor position = Cursor.decode(cursor);
        List<AlarmInstance> rows = jdbcTemplate.query(instanceSelect() + """
                 WHERE project_id = ? AND (?::timestamptz IS NULL OR (updated_at, id) < (?::timestamptz, ?::uuid))
                 ORDER BY updated_at DESC, id DESC LIMIT ?
                """, this::mapInstance, projectId, time(position.time()), time(position.time()), position.id(), limit + 1);
        if (rows.size() <= limit) return CursorPage.last(rows);
        List<AlarmInstance> items = rows.subList(0, limit);
        AlarmInstance last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.updatedAt(), last.id()));
    }
    /** {@inheritDoc} */
    @Override
    public List<AlarmInstance> findByDevices(UUID tenantId, UUID projectId, List<UUID> deviceIds,
            Set<AlarmInstance.ConditionState> conditionStates, Set<AlarmInstance.AckState> ackStates,
            Set<AlarmRule.Severity> severities, Instant beforeUpdatedAt, UUID beforeId, int candidateLimit) {
        // 只拼接占位符数量，设备ID与枚举都绑定参数；不得先取全项目页再在Java裁剪。
        String sql = instanceSelect() + " WHERE tenant_id = ? AND project_id = ? AND originator_type = 'DEVICE'"
                + " AND originator_id IN (" + placeholders(deviceIds.size()) + ")"
                + " AND condition_state IN (" + placeholders(conditionStates.size()) + ")"
                + " AND ack_state IN (" + placeholders(ackStates.size()) + ")"
                + " AND severity IN (" + placeholders(severities.size()) + ")"
                + " AND (?::timestamptz IS NULL OR (updated_at,id) < (?::timestamptz,?::uuid))"
                + " ORDER BY updated_at DESC,id DESC LIMIT ?";
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.add(projectId);
        parameters.addAll(deviceIds);
        conditionStates.forEach(value -> parameters.add(value.name()));
        ackStates.forEach(value -> parameters.add(value.name()));
        severities.forEach(value -> parameters.add(value.name()));
        parameters.add(time(beforeUpdatedAt));
        parameters.add(time(beforeUpdatedAt));
        parameters.add(beforeId);
        parameters.add(candidateLimit);
        return jdbcTemplate.query(sql, this::mapInstance, parameters.toArray());
    }

    /** 受上层有界集合限制的参数占位符，不接受请求SQL片段。 */
    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** {@inheritDoc} */
    @Override public CursorPage<AlarmEvent> pageEvents(UUID projectId, UUID instanceId, String cursor, int limit) {
        Cursor position = Cursor.decode(cursor);
        List<AlarmEvent> rows = jdbcTemplate.query(eventSelect() + """
                 WHERE project_id = ? AND instance_id = ?
                   AND (?::timestamptz IS NULL OR (received_at, id) < (?::timestamptz, ?::uuid))
                 ORDER BY received_at DESC, id DESC LIMIT ?
                """, this::mapEvent, projectId, instanceId, time(position.time()), time(position.time()), position.id(), limit + 1);
        if (rows.size() <= limit) return CursorPage.last(rows);
        List<AlarmEvent> items = rows.subList(0, limit);
        AlarmEvent last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.receivedAt(), last.id()));
    }
    /** {@inheritDoc} */
    @Override public long countDistinctActiveDevices(UUID projectId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT originator_id) FROM alarm_instance
                 WHERE project_id = ? AND originator_type = 'DEVICE' AND condition_state = 'ACTIVE'
                """, Long.class, projectId);
        return count == null ? 0L : count;
    }
    @Override
    public Set<UUID> activeDeviceIds(UUID projectId, List<UUID> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty() || deviceIds.size() > 20)
            throw new IllegalArgumentException("告警设备摘要批次超限");
        var parameters = new java.util.ArrayList<Object>();
        parameters.addAll(deviceIds);
        parameters.add(projectId);
        String candidates = String.join(",", java.util.Collections.nCopies(deviceIds.size(), "(?::uuid)"));
        // 从有界请求集合做EXISTS，避免全量去重，也避免Timescale SkipScan对RLS子计划的限制。
        return Set.copyOf(jdbcTemplate.query("SELECT candidate.id FROM (VALUES " + candidates + ") AS candidate(id) "
                + "WHERE EXISTS (SELECT 1 FROM alarm_instance a WHERE a.project_id = ? "
                + "AND a.originator_type = 'DEVICE' AND a.condition_state = 'ACTIVE' AND a.originator_id = candidate.id)",
                (rs, row) -> rs.getObject("id", UUID.class), parameters.toArray()));
    }

    @Override
    public List<DeviceSeverity> activeDeviceSeverities(UUID projectId, UUID afterDeviceId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("告警统计批次超限");
        return jdbcTemplate.query("""
                SELECT originator_id, MAX(CASE severity
                    WHEN 'CRITICAL' THEN 5 WHEN 'MAJOR' THEN 4 WHEN 'MINOR' THEN 3
                    WHEN 'WARNING' THEN 2 WHEN 'INFO' THEN 1 ELSE 0 END) AS severity_rank
                  FROM alarm_instance
                 WHERE project_id = ? AND originator_type = 'DEVICE' AND condition_state = 'ACTIVE'
                   AND (?::uuid IS NULL OR originator_id > ?::uuid)
                 GROUP BY originator_id ORDER BY originator_id LIMIT ?
                """, (rs, row) -> new DeviceSeverity(rs.getObject("originator_id", UUID.class),
                        switch (rs.getInt("severity_rank")) {
                            case 5 -> AlarmRule.Severity.CRITICAL;
                            case 4 -> AlarmRule.Severity.MAJOR;
                            case 3 -> AlarmRule.Severity.MINOR;
                            case 2 -> AlarmRule.Severity.WARNING;
                            case 1 -> AlarmRule.Severity.INFO;
                            default -> throw new IllegalStateException("未知告警严重度");
                        }), projectId, afterDeviceId, afterDeviceId, limit);
    }

    /** {@inheritDoc} */
    @Override public boolean create(AlarmInstance value) {
        return jdbcTemplate.update("""
                INSERT INTO alarm_instance (id, tenant_id, project_id, rule_id, originator_type, originator_id,
                    alarm_type, severity, condition_state, ack_state, clear_reason, first_condition_at,
                    recovery_condition_at, activated_at, cleared_at, acknowledged_at, acknowledged_by,
                    last_received_at, last_occurred_at, last_value, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, originator_type, originator_id, rule_id, alarm_type)
                    WHERE condition_state IN ('PENDING', 'ACTIVE') DO NOTHING
                """, value.id(), value.tenantId(), value.projectId(), value.ruleId(), value.originatorType().name(),
                value.originatorId(), value.alarmType(), value.severity().name(), value.conditionState().name(),
                value.ackState().name(), name(value.clearReason()), time(value.firstConditionAt()),
                time(value.recoveryConditionAt()), time(value.activatedAt()), time(value.clearedAt()),
                time(value.acknowledgedAt()), value.acknowledgedBy(), time(value.lastReceivedAt()),
                time(value.lastOccurredAt()), value.lastValue(), value.version(), time(value.createdAt()),
                time(value.updatedAt())) == 1;
    }
    /** {@inheritDoc} */
    @Override public boolean update(AlarmInstance value) {
        return jdbcTemplate.update("""
                UPDATE alarm_instance SET severity = ?, condition_state = ?, ack_state = ?, clear_reason = ?,
                    first_condition_at = ?, recovery_condition_at = ?, activated_at = ?, cleared_at = ?,
                    acknowledged_at = ?, acknowledged_by = ?, last_received_at = ?, last_occurred_at = ?,
                    last_value = ?, version = version + 1, updated_at = ?
                 WHERE project_id = ? AND id = ? AND version = ?
                """, value.severity().name(), value.conditionState().name(), value.ackState().name(),
                name(value.clearReason()), time(value.firstConditionAt()), time(value.recoveryConditionAt()),
                time(value.activatedAt()), time(value.clearedAt()), time(value.acknowledgedAt()), value.acknowledgedBy(),
                time(value.lastReceivedAt()), time(value.lastOccurredAt()), value.lastValue(), time(value.updatedAt()),
                value.projectId(), value.id(), value.version()) == 1;
    }
    /** {@inheritDoc} */
    @Override public boolean appendEvent(AlarmEvent event) {
        return jdbcTemplate.update("""
                INSERT INTO alarm_event (id, tenant_id, project_id, instance_id, event_type, source_message_id,
                    trace_id, value, occurred_at, received_at, actor_id, condition_state, ack_state, clear_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instance_id, source_message_id, event_type) WHERE source_message_id IS NOT NULL DO NOTHING
                """, event.id(), event.tenantId(), event.projectId(), event.instanceId(), event.eventType().name(),
                event.sourceMessageId(), event.traceId(), event.value(), time(event.occurredAt()), time(event.receivedAt()),
                event.actorId(), event.conditionState().name(), event.ackState().name(), name(event.clearReason())) == 1;
    }

    /** @return 实例基础列 */
    private static String instanceSelect() { return """
            SELECT id, tenant_id, project_id, rule_id, originator_type, originator_id, alarm_type, severity,
                   condition_state, ack_state, clear_reason, first_condition_at, recovery_condition_at, activated_at,
                   cleared_at, acknowledged_at, acknowledged_by, last_received_at, last_occurred_at, last_value,
                   version, created_at, updated_at FROM alarm_instance
            """; }
    /** @return 事件基础列 */
    private static String eventSelect() { return """
            SELECT id, tenant_id, project_id, instance_id, event_type, source_message_id, trace_id, value,
                   occurred_at, received_at, actor_id, condition_state, ack_state, clear_reason FROM alarm_event
            """; }
    /** @param rs JDBC 行 @param row 行号 @return 实例 */
    private AlarmInstance mapInstance(ResultSet rs, int row) throws SQLException {
        return new AlarmInstance(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("rule_id", UUID.class),
                AlarmRule.OriginatorType.valueOf(rs.getString("originator_type")), rs.getObject("originator_id", UUID.class),
                rs.getString("alarm_type"), AlarmRule.Severity.valueOf(rs.getString("severity")),
                AlarmInstance.ConditionState.valueOf(rs.getString("condition_state")),
                AlarmInstance.AckState.valueOf(rs.getString("ack_state")), enumValue(rs.getString("clear_reason"), AlarmInstance.ClearReason.class),
                instant(rs, "first_condition_at"), instant(rs, "recovery_condition_at"), instant(rs, "activated_at"),
                instant(rs, "cleared_at"), instant(rs, "acknowledged_at"), rs.getObject("acknowledged_by", UUID.class),
                instant(rs, "last_received_at"), instant(rs, "last_occurred_at"), rs.getDouble("last_value"),
                rs.getInt("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
    /** @param rs JDBC 行 @param row 行号 @return 不可变事件 */
    private AlarmEvent mapEvent(ResultSet rs, int row) throws SQLException {
        return new AlarmEvent(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("instance_id", UUID.class),
                AlarmEvent.EventType.valueOf(rs.getString("event_type")), rs.getObject("source_message_id", UUID.class),
                rs.getString("trace_id"), rs.getObject("value", Double.class), instant(rs, "occurred_at"),
                instant(rs, "received_at"), rs.getObject("actor_id", UUID.class),
                AlarmInstance.ConditionState.valueOf(rs.getString("condition_state")),
                AlarmInstance.AckState.valueOf(rs.getString("ack_state")), enumValue(rs.getString("clear_reason"), AlarmInstance.ClearReason.class));
    }
    /** nullable enum 转数据库名称。 */ private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    /** nullable 数据库 enum 转 Java。 */ private static <T extends Enum<T>> T enumValue(String value, Class<T> type) { return value == null ? null : Enum.valueOf(type, value); }
    /** nullable Instant 到 JDBC；写前固定微秒精度，避免驱动与公开来源各自舍入。 */
    private static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(AlarmTimestampPrecision.toMicros(value));
    }
    /** nullable JDBC 时间到 UTC Instant。 */ private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    /** 不透明时间+ID 键集游标。 */
    private record Cursor(Instant time, UUID id) {
        static Cursor decode(String value) {
            if (value == null || value.isBlank()) return new Cursor(null, null);
            try {
                String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", 2);
                return new Cursor(Instant.parse(fields[0]), UUID.fromString(fields[1]));
            } catch (RuntimeException exception) { throw new IllegalArgumentException("告警游标不合法", exception); }
        }
        static String encode(Instant time, UUID id) { return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8)); }
    }
}
