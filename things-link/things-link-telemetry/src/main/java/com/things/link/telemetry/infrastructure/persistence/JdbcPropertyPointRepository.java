package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.telemetry.domain.PropertyPoint;
import com.things.link.telemetry.domain.PropertyPointRepository;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyHistoryPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** JDBC 实现的 TimescaleDB 属性点仓储。 */
@Repository
public class JdbcPropertyPointRepository implements PropertyPointRepository {
    /** JDBC 访问入口。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建仓储。
     *
     * @param jdbc JDBC 访问入口
     */
    public JdbcPropertyPointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public void save(PropertyPoint point) {
        jdbc.update("""
                INSERT INTO ts_property_point
                    (project_id, device_id, property_key, ts, message_id,
                     data_type, thing_model_version_id, model_version,
                     value_double, value_text, value_bool, value_json, quality)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, point.projectId(), point.deviceId(), point.propertyKey(), Timestamp.from(point.ts()),
                point.messageId(), point.dataType(), point.thingModelVersionId(), point.modelVersion(),
                point.valueDouble(), point.valueText(), point.valueBool(),
                point.valueJson(), point.quality());
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<PropertyPoint> findByDevice(UUID projectId, UUID deviceId, String propertyKey,
                                                   Instant from, Instant to, String cursor, int limit) {
        CursorPosition position = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT project_id, device_id, property_key, ts, message_id,
                       data_type, thing_model_version_id, model_version,
                       value_double, value_text, value_bool, value_json, quality
                  FROM ts_property_point
                 WHERE project_id = ? AND device_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(projectId, deviceId));
        if (propertyKey != null) {
            sql.append(" AND property_key = ?");
            parameters.add(propertyKey);
        }
        if (from != null) {
            sql.append(" AND ts >= ?");
            parameters.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND ts < ?");
            parameters.add(Timestamp.from(to));
        }
        if (position != null) {
            sql.append(" AND (ts, message_id, property_key) < (?, ?, ?)");
            parameters.add(Timestamp.from(position.ts()));
            parameters.add(position.messageId());
            parameters.add(position.propertyKey());
        }
        sql.append(" ORDER BY ts DESC, message_id DESC, property_key DESC LIMIT ?");
        parameters.add(limit + 1);

        List<PropertyPoint> rows = jdbc.query(sql.toString(), (rs, rowNumber) -> new PropertyPoint(
                rs.getObject("project_id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getString("property_key"), rs.getTimestamp("ts").toInstant(),
                rs.getObject("message_id", UUID.class),
                inferDataType(rs.getString("data_type"), rs.getObject("value_double"),
                        rs.getString("value_text"), rs.getObject("value_bool"), rs.getString("value_json")),
                rs.getObject("thing_model_version_id", UUID.class),
                rs.getString("model_version") == null ? "LEGACY_UNVERSIONED" : rs.getString("model_version"),
                (Double) rs.getObject("value_double"), rs.getString("value_text"),
                (Boolean) rs.getObject("value_bool"), rs.getString("value_json"),
                rs.getShort("quality")), parameters.toArray());
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<PropertyPoint> items = List.copyOf(rows.subList(0, limit));
        PropertyPoint last = items.getLast();
        return CursorPage.of(items, encodeCursor(last));
    }

    /** {@inheritDoc} */
    @Override
    public List<PropertyHistoryPoint> findHistory(UUID projectId, UUID deviceId, String propertyKey,
                                                  Instant from, Instant to, HistoryGranularity granularity,
                                                  HistoryAggregation aggregation, int limit) {
        String relation = switch (granularity) {
            case RAW -> "ts_property_point";
            case ONE_MINUTE -> "ts_property_point_1m";
            case ONE_HOUR -> "ts_property_point_1h";
            case ONE_DAY -> "ts_property_point_1d";
        };
        String timeColumn = granularity == HistoryGranularity.RAW ? "ts" : "bucket";
        String valueExpression = granularity == HistoryGranularity.RAW
                ? "value_double"
                : aggregateExpression(aggregation);
        String countExpression = granularity == HistoryGranularity.RAW ? "1" : "sample_count";
        // 只返回完整落在可见窗口内的桶，禁止上界桶混入未来或无权读取的事实。
        String upperBound = granularity.aggregated()
                ? "bucket + interval '" + granularity.bucketWidth().toSeconds() + " seconds' <= ?"
                : "ts < ?";
        String sql = """
                SELECT %s AS point_ts, %s AS point_value, %s AS sample_count,
                       thing_model_version_id, model_version
                  FROM %s
                 WHERE project_id = ? AND device_id = ? AND property_key = ?
                   AND %s >= ? AND %s
                   AND %s IS NOT NULL
                 ORDER BY %s ASC
                 LIMIT ?
                """.formatted(timeColumn, valueExpression, countExpression, relation,
                timeColumn, upperBound, valueExpression, timeColumn);
        return jdbc.query(sql, (rs, rowNumber) -> new PropertyHistoryPoint(
                        rs.getTimestamp("point_ts").toInstant(), rs.getDouble("point_value"),
                        rs.getLong("sample_count"), rs.getObject("thing_model_version_id", UUID.class),
                        rs.getString("model_version") == null ? "LEGACY_UNVERSIONED" : rs.getString("model_version")),
                projectId, deviceId, propertyKey, Timestamp.from(from), Timestamp.from(to), limit);
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasNonNumericData(UUID projectId, UUID deviceId, String propertyKey, Instant from, Instant to) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM ts_property_point
                     WHERE project_id = ? AND device_id = ? AND property_key = ?
                       AND ts >= ? AND ts < ? AND value_double IS NULL
                )
                """, Boolean.class, projectId, deviceId, propertyKey, Timestamp.from(from), Timestamp.from(to));
        return Boolean.TRUE.equals(result);
    }

    /** 存量点没有 data_type，只能从唯一非空值列如实恢复，不伪造版本。 */
    private static String inferDataType(String dataType, Object number, String text, Object bool, String json) {
        if (dataType != null) {
            return dataType;
        }
        if (number != null) {
            return "NUMBER";
        }
        if (text != null) {
            return "TEXT";
        }
        if (bool != null) {
            return "SWITCH";
        }
        return json != null && json.stripLeading().startsWith("[") ? "LIST" : "OBJECT";
    }

    /** 把受控枚举映射为 SQL 表达式，禁止把客户端文本拼进查询。 */
    private static String aggregateExpression(HistoryAggregation aggregation) {
        return switch (aggregation) {
            case AVG -> "sum_value / NULLIF(sample_count, 0)";
            case MIN -> "min_value";
            case MAX -> "max_value";
            case SUM -> "sum_value";
            case COUNT -> "sample_count::double precision";
        };
    }

    /** 把末项位置编码为不透明 URL 安全游标。 */
    private static String encodeCursor(PropertyPoint point) {
        String raw = point.ts() + "|" + point.messageId() + "|" + point.propertyKey();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码并严格校验客户端回传的游标。 */
    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3 || parts[2].isBlank()) {
                throw new IllegalArgumentException("游标字段不完整");
            }
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]), parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "历史查询游标不合法");
        }
    }

    /** 游标内部位置，不作为 API 契约暴露。 */
    private record CursorPosition(Instant ts, UUID messageId, String propertyKey) {
    }
}
