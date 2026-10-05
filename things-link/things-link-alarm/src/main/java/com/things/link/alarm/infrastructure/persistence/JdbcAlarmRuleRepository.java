package com.things.link.alarm.infrastructure.persistence;

import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.shared.page.CursorPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC 规则仓储；游标固定按创建时间和 UUID 倒序，避免深 offset。 */
@Repository
public class JdbcAlarmRuleRepository implements AlarmRuleRepository {
    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcAlarmRuleRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean create(AlarmRule rule) {
        return jdbcTemplate.update("""
                INSERT INTO alarm_rule (id, tenant_id, project_id, name, alarm_type, originator_type, originator_id,
                    property_key, trigger_operator, trigger_threshold, trigger_duration_seconds, clear_operator,
                    clear_threshold, clear_duration_seconds, severity, enabled, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rule.id(), rule.tenantId(), rule.projectId(), rule.name(), rule.alarmType(),
                rule.originatorType().name(), rule.originatorId(), rule.propertyKey(), rule.triggerOperator().name(),
                rule.triggerThreshold(), rule.triggerDurationSeconds(), rule.clearOperator().name(), rule.clearThreshold(),
                rule.clearDurationSeconds(), rule.severity().name(), rule.enabled(), rule.version(),
                Timestamp.from(rule.createdAt()), Timestamp.from(rule.updatedAt())) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<AlarmRule> findById(UUID projectId, UUID ruleId) {
        return jdbcTemplate.query(select() + " WHERE project_id = ? AND id = ? AND deleted_at IS NULL", this::map,
                projectId, ruleId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public CursorPage<AlarmRule> page(UUID projectId, String cursor, int limit) {
        Cursor position = Cursor.decode(cursor);
        List<AlarmRule> rows = jdbcTemplate.query(select() + """
                 WHERE project_id = ? AND deleted_at IS NULL
                   AND (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                 ORDER BY created_at DESC, id DESC LIMIT ?
                """, this::map, projectId, time(position.createdAt()), time(position.createdAt()), position.id(), limit + 1);
        return page(rows, limit, AlarmRule::createdAt, AlarmRule::id);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean update(AlarmRule rule) {
        return jdbcTemplate.update("""
                UPDATE alarm_rule SET name = ?, alarm_type = ?, originator_id = ?, property_key = ?,
                    trigger_operator = ?, trigger_threshold = ?, trigger_duration_seconds = ?, clear_operator = ?,
                    clear_threshold = ?, clear_duration_seconds = ?, severity = ?, enabled = ?, version = version + 1,
                    updated_at = ?
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, rule.name(), rule.alarmType(), rule.originatorId(), rule.propertyKey(), rule.triggerOperator().name(),
                rule.triggerThreshold(), rule.triggerDurationSeconds(), rule.clearOperator().name(), rule.clearThreshold(),
                rule.clearDurationSeconds(), rule.severity().name(), rule.enabled(), Timestamp.from(rule.updatedAt()),
                rule.projectId(), rule.id(), rule.version()) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean softDelete(UUID projectId, UUID ruleId, int version) {
        return jdbcTemplate.update("""
                UPDATE alarm_rule SET deleted_at = now(), updated_at = now(), version = version + 1
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, projectId, ruleId, version) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<AlarmRule> findEnabledByProperty(UUID projectId, UUID deviceId, String propertyKey) {
        return jdbcTemplate.query(select() + """
                 WHERE project_id = ? AND originator_id = ? AND property_key = ? AND enabled AND deleted_at IS NULL
                """, this::map, projectId, deviceId, propertyKey);
    }

    /** @return 显式列而非 SELECT *，字段演进不会悄悄改变行映射。 */
    private static String select() { return """
            SELECT id, tenant_id, project_id, name, alarm_type, originator_type, originator_id, property_key,
                   trigger_operator, trigger_threshold, trigger_duration_seconds, clear_operator, clear_threshold,
                   clear_duration_seconds, severity, enabled, version, created_at, updated_at, deleted_at
              FROM alarm_rule
            """; }

    /** @param rs 数据库行 @param row 行号 @return 领域规则 */
    private AlarmRule map(ResultSet rs, int row) throws SQLException {
        return new AlarmRule(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("name"), rs.getString("alarm_type"),
                AlarmRule.OriginatorType.valueOf(rs.getString("originator_type")), rs.getObject("originator_id", UUID.class),
                rs.getString("property_key"), AlarmRule.ComparisonOperator.valueOf(rs.getString("trigger_operator")),
                rs.getDouble("trigger_threshold"), rs.getInt("trigger_duration_seconds"),
                AlarmRule.ComparisonOperator.valueOf(rs.getString("clear_operator")), rs.getDouble("clear_threshold"),
                rs.getInt("clear_duration_seconds"), AlarmRule.Severity.valueOf(rs.getString("severity")),
                rs.getBoolean("enabled"), rs.getInt("version"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "deleted_at"));
    }

    /** 删除多取的一行并生成下一页游标。 */
    private static <T> CursorPage<T> page(List<T> rows, int limit,
                                           java.util.function.Function<T, Instant> time,
                                           java.util.function.Function<T, UUID> id) {
        if (rows.size() <= limit) return CursorPage.last(rows);
        List<T> items = rows.subList(0, limit);
        T last = items.getLast();
        return CursorPage.of(items, Cursor.encode(time.apply(last), id.apply(last)));
    }
    /** nullable Instant 到 JDBC。 */ private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    /** nullable JDBC 时间到 UTC Instant。 */ private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    /** 最小不透明键集游标；错误游标按空页之外的参数错误处理留给 API 层校验。 */
    private record Cursor(Instant createdAt, UUID id) {
        static Cursor decode(String value) {
            if (value == null || value.isBlank()) return new Cursor(null, null);
            try {
                String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", 2);
                return new Cursor(Instant.parse(fields[0]), UUID.fromString(fields[1]));
            } catch (RuntimeException exception) { throw new IllegalArgumentException("告警规则游标不合法", exception); }
        }
        static String encode(Instant time, UUID id) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
        }
    }
}
