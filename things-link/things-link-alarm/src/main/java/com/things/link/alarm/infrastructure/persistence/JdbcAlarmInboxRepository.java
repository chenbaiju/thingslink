package com.things.link.alarm.infrastructure.persistence;

import com.things.link.alarm.domain.AlarmInboxEvent;
import com.things.link.alarm.domain.AlarmInboxItem;
import com.things.link.alarm.domain.AlarmInboxRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** ADR0093：项目RLS叠加显式账号过滤；所有已读副作用加入调用方原事务。 */
@Repository
public class JdbcAlarmInboxRepository implements AlarmInboxRepository {
    /** 复用受项目上下文保护的连接，不引入全局账号RLS假设。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 当前事务的项目数据库访问器 */
    public JdbcAlarmInboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Instant databaseTime() {
        return jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /** {@inheritDoc} */
    @Override
    public List<AlarmInboxItem> page(UUID tenantId, UUID projectId, UUID accountId, Instant windowStart, Instant windowEnd,
                                    Instant positionTime, UUID positionId, int fetchLimit) {
        return jdbcTemplate.query("""
                SELECT e.id, e.instance_id, e.received_at, i.severity, i.alarm_type,
                       EXISTS (SELECT 1 FROM alarm_notification_read r
                                WHERE r.tenant_id=e.tenant_id AND r.project_id=e.project_id AND r.account_id=?
                                  AND r.alarm_event_id=e.id) AS is_read
                  FROM alarm_event e JOIN alarm_instance i
                    ON i.tenant_id=e.tenant_id AND i.project_id=e.project_id AND i.id=e.instance_id
                 WHERE e.tenant_id=? AND e.project_id=? AND e.event_type='ACTIVATED'
                   AND e.received_at BETWEEN ? AND ?
                   AND (?::timestamptz IS NULL OR (e.received_at,e.id) < (?::timestamptz,?::uuid))
                 ORDER BY e.received_at DESC,e.id DESC LIMIT ?
                """, (rs, row) -> new AlarmInboxItem(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getTimestamp("received_at").toInstant(),
                AlarmRule.Severity.valueOf(rs.getString("severity")), rs.getString("alarm_type"),
                rs.getBoolean("is_read")), accountId, tenantId, projectId, time(windowStart), time(windowEnd),
                time(positionTime), time(positionTime), positionId, fetchLimit);
    }

    /** {@inheritDoc} */
    @Override
    public int countUnread(UUID tenantId, UUID projectId, UUID accountId, Instant windowStart, Instant windowEnd) {
        // ADR0093只承诺99+，先LIMIT再COUNT避免为角标扫描全部匹配事实。
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (
                    SELECT 1 FROM alarm_event e
                     WHERE e.tenant_id=? AND e.project_id=? AND e.event_type='ACTIVATED'
                       AND e.received_at BETWEEN ? AND ?
                       AND NOT EXISTS (SELECT 1 FROM alarm_notification_read r
                                        WHERE r.tenant_id=e.tenant_id AND r.project_id=e.project_id AND r.account_id=?
                                          AND r.alarm_event_id=e.id)
                     LIMIT 100
                ) unread
                """, Integer.class, tenantId, projectId, time(windowStart), time(windowEnd), accountId);
        return count == null ? 0 : count;
    }

    /** {@inheritDoc} */
    @Override
    public List<AlarmInboxEvent> findActivatedEvents(UUID tenantId, UUID projectId, List<UUID> eventIds) {
        if (eventIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.add(projectId);
        parameters.addAll(eventIds);
        // 只有占位符数量进入SQL；UUID始终绑定参数，不把客户端内容拼接到语句。
        return jdbcTemplate.query("""
                SELECT id,received_at FROM alarm_event
                 WHERE tenant_id=? AND project_id=? AND event_type='ACTIVATED' AND id IN (
                """ + placeholders + ") ORDER BY id FOR KEY SHARE",
                (rs, row) -> new AlarmInboxEvent(rs.getObject("id", UUID.class),
                        rs.getTimestamp("received_at").toInstant()), parameters.toArray());
    }

    /** {@inheritDoc} */
    @Override
    public int insertReads(UUID tenantId, UUID projectId, UUID accountId, List<UUID> eventIds, Instant readAt) {
        int inserted = 0;
        // 至多100行按固定UUID序写入，降低两次重叠批次以相反顺序等待唯一键造成的死锁风险。
        for (UUID eventId : eventIds.stream().sorted().toList()) {
            inserted += jdbcTemplate.update("""
                    INSERT INTO alarm_notification_read
                        (id,tenant_id,project_id,account_id,alarm_event_id,read_at)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT (project_id,account_id,alarm_event_id) DO NOTHING
                    """, Uuid7.generate(), tenantId, projectId, accountId, eventId, time(readAt));
        }
        return inserted;
    }

    /** @param value 可空UTC时间 @return JDBC绑定值，首页游标时间允许为空 */
    private static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
