package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.telemetry.domain.DeviceCommandHistoryItem;
import com.things.link.telemetry.domain.DeviceCommandHistoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 显式低敏投影；不把网关连接身份误认为命令目标，不逐行读取尝试表。 */
@Repository
public class JdbcDeviceCommandHistoryRepository implements DeviceCommandHistoryRepository {
    private final JdbcTemplate jdbc;
    public JdbcDeviceCommandHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<DeviceCommandHistoryItem> find(UUID projectId, UUID deviceId,
            Instant beforeTime, UUID beforeId, int fetchLimit) {
        String sql = """
                SELECT id, target_device_id, operation_type, command_key, status,
                       attempt_count, max_attempts, accepted_at, completed_at
                  FROM ts_device_command
                 WHERE project_id = ? AND target_device_id = ?
                """;
        List<Object> params = new ArrayList<>(List.of(projectId, deviceId));
        if (beforeTime != null) {
            sql += " AND (accepted_at,id) < (?,?)";
            params.add(Timestamp.from(beforeTime)); params.add(beforeId);
        }
        sql += " ORDER BY accepted_at DESC,id DESC LIMIT ?";
        params.add(fetchLimit);
        return jdbc.query(sql, (r, row) -> new DeviceCommandHistoryItem(
                r.getObject("id", UUID.class), r.getObject("target_device_id", UUID.class),
                r.getString("operation_type"), r.getString("command_key"), r.getString("status"),
                r.getInt("attempt_count"), r.getInt("max_attempts"), r.getTimestamp("accepted_at").toInstant(),
                r.getTimestamp("completed_at") == null ? null : r.getTimestamp("completed_at").toInstant()), params.toArray());
    }
}
