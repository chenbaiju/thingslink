package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.telemetry.domain.MessageLogQuery;
import com.things.link.telemetry.domain.MessageLogRepository;
import com.things.link.telemetry.domain.MessageLogStatistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** TimescaleDB 设备消息日志仓储。 */
@Repository
public class JdbcMessageLogRepository implements MessageLogRepository {
    /** JDBC 访问入口。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建仓储。
     *
     * @param jdbc JDBC 访问入口
     */
    public JdbcMessageLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public boolean tryAcquire(DeviceMessageLog entry) {
        return jdbc.update("""
                INSERT INTO sys_message_log_inbox (message_id, project_id, received_at)
                VALUES (?, ?, ?)
                ON CONFLICT (message_id) DO NOTHING
                """, entry.messageId(), entry.projectId(), Timestamp.from(entry.receivedAt())) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public void save(DeviceMessageLog entry) {
        jdbc.update("""
                INSERT INTO ts_device_message_log
                    (id, project_id, device_id, message_id, tenant_id, protocol, direction,
                     topic, payload_summary, raw_bytes, error_code, ts, received_at, trace_id,
                     message_type, accepted_at, parsed_at, processed_at, delivered_at, replied_at,
                     truncated, sampled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.id(), entry.projectId(), entry.deviceId(), entry.messageId(), entry.tenantId(),
                entry.protocol().name(), entry.direction().name(), entry.topic(), entry.payloadSummary(),
                entry.rawBytes(), entry.errorCode(), Timestamp.from(entry.ts()),
                Timestamp.from(entry.receivedAt()), entry.traceId(), entry.messageType(),
                time(entry.acceptedAt()), time(entry.parsedAt()), time(entry.processedAt()),
                time(entry.deliveredAt()), time(entry.repliedAt()), entry.truncated(), entry.sampled());
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<DeviceMessageLog> find(MessageLogQuery query) {
        CursorPosition position = decodeCursor(query.cursor());
        StringBuilder sql = new StringBuilder("""
                SELECT id, project_id, device_id, message_id, tenant_id, protocol, direction,
                       topic, payload_summary, raw_bytes, error_code, ts, received_at, trace_id, created_at,
                       message_type, accepted_at, parsed_at, processed_at, delivered_at, replied_at,
                       truncated, sampled
                  FROM ts_device_message_log
                 WHERE project_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(query.projectId()));
        if (query.deviceId() != null) {
            sql.append(" AND device_id = ?");
            parameters.add(query.deviceId());
        }
        if (query.direction() != null) {
            sql.append(" AND direction = ?");
            parameters.add(query.direction().name());
        }
        if (query.protocol() != null) {
            sql.append(" AND protocol = ?");
            parameters.add(query.protocol().name());
        }
        if (query.from() != null) {
            sql.append(" AND ts >= ?");
            parameters.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND ts < ?");
            parameters.add(Timestamp.from(query.to()));
        }
        if (query.traceId() != null) {
            sql.append(" AND trace_id = ?");
            parameters.add(query.traceId());
        }
        if (query.messageType() != null) {
            sql.append(" AND message_type = ?");
            parameters.add(query.messageType());
        }
        if (position != null) {
            sql.append(" AND (ts, id) < (?, ?)");
            parameters.add(Timestamp.from(position.ts()));
            parameters.add(position.id());
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
        parameters.add(query.limit() + 1);

        List<DeviceMessageLog> rows = jdbc.query(sql.toString(), this::map, parameters.toArray());
        if (rows.size() <= query.limit()) {
            return CursorPage.last(rows);
        }
        List<DeviceMessageLog> items = List.copyOf(rows.subList(0, query.limit()));
        DeviceMessageLog last = items.getLast();
        return CursorPage.of(items, encodeCursor(last));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, timeout = 3)
    public MessageLogStatistics summarize(UUID projectId, Instant from, Instant to) {
        return jdbc.queryForObject("""
                SELECT count(*) AS message_count, coalesce(sum(raw_bytes), 0) AS raw_bytes
                  FROM ts_device_message_log
                 WHERE project_id = ? AND received_at >= ? AND received_at < ?
                """, (resultSet, rowNumber) -> new MessageLogStatistics(
                        resultSet.getLong("message_count"), resultSet.getLong("raw_bytes")),
                projectId, Timestamp.from(from), Timestamp.from(to));
    }

    /** 把时刻和稳定日志 ID 编码成客户端不可解释的游标。 */
    private static String encodeCursor(DeviceMessageLog entry) {
        String raw = entry.ts() + "|" + entry.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 严格解码游标，损坏或伪造游标统一按参数错误处理。 */
    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("游标字段不完整");
            }
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "消息日志游标不合法");
        }
    }

    /** 映射 JDBC 行，枚举解析同时阻止数据库非法语义静默传播到 API。 */
    private DeviceMessageLog map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DeviceMessageLog(resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("device_id", UUID.class),
                resultSet.getObject("message_id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                TransportProtocol.valueOf(resultSet.getString("protocol")),
                DeviceMessageLog.Direction.valueOf(resultSet.getString("direction")),
                resultSet.getString("topic"), resultSet.getString("payload_summary"),
                resultSet.getInt("raw_bytes"), resultSet.getString("error_code"),
                resultSet.getTimestamp("ts").toInstant(), resultSet.getTimestamp("received_at").toInstant(),
                resultSet.getString("trace_id"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getString("message_type"), instant(resultSet, "accepted_at"),
                instant(resultSet, "parsed_at"), instant(resultSet, "processed_at"),
                instant(resultSet, "delivered_at"), instant(resultSet, "replied_at"),
                resultSet.getBoolean("truncated"), resultSet.getBoolean("sampled"));
    }

    /** {@inheritDoc} */
    @Override
    public java.util.Optional<DeviceMessageLog> findByLogId(java.util.UUID projectId, java.util.UUID deviceId,
                                                            java.util.UUID logId) {
        return jdbc.query("""
                SELECT id, project_id, device_id, message_id, tenant_id, protocol, direction,
                       topic, payload_summary, raw_bytes, error_code, ts, received_at, trace_id, created_at,
                       message_type, accepted_at, parsed_at, processed_at, delivered_at, replied_at,
                       truncated, sampled
                  FROM ts_device_message_log
                 WHERE project_id = ? AND device_id = ? AND id = ?
                """, this::map, projectId, deviceId, logId).stream().findFirst();
    }

    /** nullable Instant 到 JDBC。 */
    private static Timestamp time(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** nullable JDBC 时间到 Instant。 */
    private static java.time.Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 游标的内部键集位置。 */
    private record CursorPosition(Instant ts, UUID id) {
    }
}
