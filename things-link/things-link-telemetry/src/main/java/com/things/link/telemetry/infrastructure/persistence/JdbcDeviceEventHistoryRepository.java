package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.telemetry.domain.DeviceEventHistoryItem;
import com.things.link.telemetry.domain.DeviceEventHistoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 只读原发生事实；不查询当前事件定义、绑定、私有摘要或计量正文。 */
@Repository
public class JdbcDeviceEventHistoryRepository implements DeviceEventHistoryRepository {
    /** 显式低敏字段，JSON文本避免JDBC预先浮点化。 */
    private static final String SELECT = """
            SELECT message_id,device_id,device_type_id,event_key,level,thing_model_version_id,
                   model_version,eligibility,occurred_at,received_at,accepted_at,params::text AS params,params_redacted
              FROM ts_device_event WHERE project_id=? AND device_id=? AND occurred_at>=? AND occurred_at<?
            """;
    private final JdbcTemplate jdbc;
    /** @param jdbc 当前事务及RLS数据库连接 */
    public JdbcDeviceEventHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override public List<DeviceEventHistoryItem> find(UUID project, UUID device, String key, String level, UUID version,
            Instant from, Instant to, Instant beforeTime, UUID beforeId, int fetchLimit) {
        String sql = SELECT;
        List<Object> args = bounds(project, device, from, to);
        if (key != null) { sql += " AND event_key=?"; args.add(key); }
        if (level != null) { sql += " AND level=?"; args.add(level); }
        if (version != null) { sql += " AND thing_model_version_id=?"; args.add(version); }
        if (beforeTime != null) { sql += " AND (occurred_at,message_id)<(?,?)"; args.add(Timestamp.from(beforeTime)); args.add(beforeId); }
        sql += " ORDER BY occurred_at DESC,message_id DESC LIMIT ?"; args.add(fetchLimit);
        return jdbc.query(sql, this::map, args.toArray());
    }
    /** {@inheritDoc} */
    @Override public Optional<DeviceEventHistoryItem> findOne(UUID project, UUID device, UUID messageId, Instant from, Instant to) {
        List<Object> args = bounds(project, device, from, to); args.add(messageId);
        return jdbc.query(SELECT + " AND message_id=?", this::map, args.toArray()).stream().findFirst();
    }
    /** 微秒持久值与纳秒边界比较等价于向上取整；避免PG四舍五入扩大排他上界。 */
    private static Instant ceiling(Instant value) { int remainder = value.getNano() % 1000; return remainder == 0 ? value : value.plusNanos(1000 - remainder); }
    /** 当前设备范围和时间窗始终是SQL过滤条件。 */
    private static List<Object> bounds(UUID project, UUID device, Instant from, Instant to) {
        return new ArrayList<>(List.of(project, device, Timestamp.from(ceiling(from)), Timestamp.from(ceiling(to))));
    }
    /** 参数作为JSON文本交给应用层显式高精度解析。 */
    private DeviceEventHistoryItem map(ResultSet row, int index) throws SQLException {
        return new DeviceEventHistoryItem(row.getObject("message_id", UUID.class), row.getObject("device_id", UUID.class),
                row.getObject("device_type_id", UUID.class), row.getString("event_key"), row.getString("level"),
                row.getObject("thing_model_version_id", UUID.class), row.getString("model_version"), row.getString("eligibility"),
                row.getTimestamp("occurred_at").toInstant(), row.getTimestamp("received_at").toInstant(),
                row.getTimestamp("accepted_at").toInstant(), row.getString("params"), row.getBoolean("params_redacted"));
    }
}
