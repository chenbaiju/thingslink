package com.things.link.task.infrastructure.persistence;

import com.things.link.task.domain.DeviceTaskRepository;
import com.things.link.task.domain.DeviceTaskJobItem;
import com.things.link.task.domain.DeviceTaskExecutionItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 任务域只读投影；历史从冻结目标出发，不用当前配置倒推原目标。 */
@Repository
public class JdbcDeviceTaskRepository implements DeviceTaskRepository {
    private final JdbcTemplate jdbc;
    public JdbcDeviceTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<DeviceTaskJobItem> candidates(UUID projectId, Instant beforeTime, UUID beforeId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("任务候选批次不合法");
        String sql = "SELECT id,name,status,version,target_type,target_group_id,command_key,created_at FROM task_job WHERE project_id=? AND deleted_at IS NULL";
        List<Object> args = new ArrayList<>(List.of(projectId));
        if (beforeTime != null) {
            sql += " AND (created_at,id) < (?,?)";
            args.add(Timestamp.from(beforeTime)); args.add(beforeId);
        }
        sql += " ORDER BY created_at DESC,id DESC LIMIT ?"; args.add(limit);
        return jdbc.query(sql, (r, n) -> new DeviceTaskJobItem(r.getObject("id", UUID.class), r.getString("name"),
                r.getString("status"), r.getLong("version"), r.getString("target_type"), r.getObject("target_group_id", UUID.class),
                r.getString("command_key"), r.getTimestamp("created_at").toInstant()), args.toArray());
    }

    @Override public List<DeviceTaskExecutionItem> history(UUID projectId, UUID deviceId, Instant beforeTime, UUID beforeId, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException("任务历史页大小不合法");
        String sql = """
                SELECT e.id,e.job_id,e.trigger_type,e.status AS execution_status,t.status AS target_status,
                       e.command_key,t.command_id,e.started_at,t.accepted_at,t.completed_at
                  FROM task_target t JOIN task_execution e
                    ON e.id=t.execution_id AND e.project_id=t.project_id AND e.tenant_id=t.tenant_id
                 WHERE t.project_id=? AND t.device_id=?
                """;
        List<Object> args = new ArrayList<>(List.of(projectId, deviceId));
        if (beforeTime != null) {
            sql += " AND (e.started_at,e.id) < (?,?)";
            args.add(Timestamp.from(beforeTime)); args.add(beforeId);
        }
        sql += " ORDER BY e.started_at DESC,e.id DESC LIMIT ?"; args.add(limit);
        return jdbc.query(sql, (r, n) -> new DeviceTaskExecutionItem(r.getObject("id", UUID.class),
                r.getObject("job_id", UUID.class), r.getString("trigger_type"), r.getString("execution_status"),
                r.getString("target_status"), r.getString("command_key"), r.getObject("command_id", UUID.class),
                r.getTimestamp("started_at").toInstant(), r.getTimestamp("accepted_at") == null ? null : r.getTimestamp("accepted_at").toInstant(),
                r.getTimestamp("completed_at") == null ? null : r.getTimestamp("completed_at").toInstant()), args.toArray());
    }
}
