package com.things.link.integration.infrastructure.persistence;

import com.things.link.integration.domain.WebhookQueryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** ADR0211：每次查询均显式项目过滤并由原事务建立双轴RLS。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcWebhookQueryRepository implements WebhookQueryRepository {
    private final JdbcTemplate jdbc;
    private static final String DELIVERY = "id,subscription_id,subscription_revision,event_type,event_id,status,attempt_count,recovery_round,round_attempts,created_at,deadline_at,next_attempt_at,terminal_at,reason";
    public JdbcWebhookQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public List<DeliveryView> deliveries(UUID project, UUID subscription, String status, Instant time, UUID id, int limit) {
        bounded(limit);
        StringBuilder sql = new StringBuilder("SELECT " + DELIVERY + " FROM integ_webhook_delivery WHERE project_id=?");
        var args = new ArrayList<Object>(); args.add(project);
        if (subscription != null) { sql.append(" AND subscription_id=?"); args.add(subscription); }
        if (status != null) { sql.append(" AND status=?"); args.add(status); }
        if (time != null) { sql.append(" AND (created_at,id)<(?,?)"); args.add(Timestamp.from(time)); args.add(id); }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?"); args.add(limit);
        return jdbc.query(sql.toString(), JdbcWebhookQueryRepository::delivery, args.toArray());
    }
    @Override public Optional<DeliveryDetail> detail(UUID project, UUID id) {
        var rows = jdbc.query("SELECT " + DELIVERY + " FROM integ_webhook_delivery WHERE project_id=? AND id=?",
                JdbcWebhookQueryRepository::delivery, project, id);
        if (rows.isEmpty()) return Optional.empty();
        var value = rows.getFirst();
        return jdbc.query("SELECT name,target_url FROM integ_webhook_revision WHERE project_id=? AND subscription_id=? AND revision=?",
                (rs, row) -> new DeliveryDetail(value, rs.getString("name"), rs.getString("target_url"), attempts(project, id)),
                project, value.subscriptionId(), Long.parseLong(value.subscriptionRevision())).stream().findFirst();
    }
    private List<AttemptView> attempts(UUID project, UUID id) {
        return jdbc.query("SELECT attempt_no,recovery_round,started_at,finished_at,result,http_status,elapsed_millis,reason FROM integ_webhook_attempt WHERE project_id=? AND delivery_id=? ORDER BY attempt_no LIMIT 15",
                (rs, row) -> new AttemptView(rs.getInt("attempt_no"), rs.getInt("recovery_round"), instant(rs,"started_at"), instant(rs,"finished_at"),
                        rs.getString("result"), rs.getObject("http_status", Integer.class), rs.getString("elapsed_millis"), rs.getString("reason")), project, id);
    }
    @Override public List<EventView> events(UUID project, String type, String result, Instant time, UUID id, int limit) {
        bounded(limit);
        var sql = new StringBuilder("SELECT e.event_type,e.event_id,e.source_occurred_at,e.recorded_at,e.accepted_at,e.result,e.source_hash,EXISTS(SELECT 1 FROM integ_webhook_conflict c WHERE c.tenant_id=e.tenant_id AND c.project_id=e.project_id AND c.event_type=e.event_type AND c.event_id=e.event_id) AS has_conflict FROM integ_webhook_event e WHERE e.project_id=? AND e.event_type=?");
        var args = new ArrayList<Object>(); args.add(project); args.add(type);
        if (result != null) { sql.append(" AND e.result=?"); args.add(result); }
        if (time != null) { sql.append(" AND (e.accepted_at,e.event_id)<(?,?)"); args.add(Timestamp.from(time)); args.add(id); }
        sql.append(" ORDER BY e.accepted_at DESC,e.event_id DESC LIMIT ?"); args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> new EventView(rs.getString("event_type"), rs.getObject("event_id", UUID.class),
                instant(rs,"source_occurred_at"), instant(rs,"recorded_at"), instant(rs,"accepted_at"), rs.getString("result"), rs.getString("source_hash"), rs.getBoolean("has_conflict")), args.toArray());
    }
    private static DeliveryView delivery(ResultSet rs, int row) throws SQLException {
        return new DeliveryView(rs.getObject("id",UUID.class),rs.getObject("subscription_id",UUID.class),rs.getString("subscription_revision"),rs.getString("event_type"),rs.getObject("event_id",UUID.class),
                rs.getString("status"),rs.getInt("attempt_count"),rs.getInt("recovery_round"),rs.getInt("round_attempts"),instant(rs,"created_at"),instant(rs,"deadline_at"),instant(rs,"next_attempt_at"),instant(rs,"terminal_at"),rs.getString("reason"));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant(); }
    private static void bounded(int limit) { if(limit<1||limit>101) throw new IllegalArgumentException("invalid query bound"); }
}
