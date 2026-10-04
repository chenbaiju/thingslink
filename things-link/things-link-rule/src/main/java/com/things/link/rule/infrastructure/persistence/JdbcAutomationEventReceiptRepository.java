package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.AutomationEventReceipt;
import com.things.link.rule.domain.AutomationEventReceiptRepository;
import com.things.link.shared.message.AutomationPropertyAccepted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/** 仅自有rule事实；所有写方法加入调用者原事务，不静默auto-commit。 */
@Repository
public class JdbcAutomationEventReceiptRepository implements AutomationEventReceiptRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcAutomationEventReceiptRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    @Override public Instant databaseNow() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    /** 项目持续许可必须先由project公开端口取得；咨询锁只负责自动化串行，不负责授权。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void lockProject(UUID projectId) {
        String isolation=jdbc.queryForObject("SHOW transaction_isolation",String.class);
        if (!"read committed".equals(isolation)) throw new IllegalStateException("自动化受理要求READ COMMITTED");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('rule-automation:'||?::text,154))",Object.class,projectId);
    }
    @Override public Optional<AutomationEventReceipt> find(UUID projectId,UUID eventId) {
        return jdbc.query("SELECT * FROM rule_automation_event_receipt WHERE project_id=? AND source_event_id=?",
                this::read,projectId,eventId).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void insert(AutomationEventReceipt receipt) {
        jdbc.update("""
                INSERT INTO rule_automation_event_receipt(id,tenant_id,project_id,source_event_id,device_id,
                 accepted_at,admitted_at,source_digest,plan,result,reason_code,expires_at)
                VALUES(?,?,?,?,?,?,?, ?,?::jsonb,?,?,?)
                """, receipt.id(),receipt.tenantId(),receipt.projectId(),receipt.sourceEventId(),receipt.deviceId(),
                Timestamp.from(receipt.acceptedAt()),Timestamp.from(receipt.admittedAt()),receipt.sourceDigest(),
                json.writeValueAsString(receipt.plan()),receipt.result().name(),receipt.reasonCode(),Timestamp.from(receipt.expiresAt()));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public UUID rejectTransport(UUID id,int partition,long offset,String digest,String reason) {
        return jdbc.queryForObject("SELECT record_automation_ingress_rejection(?,?,?,?,?,?)",UUID.class,
                id,AutomationPropertyAccepted.TOPIC,partition,offset,digest,reason);
    }
    private AutomationEventReceipt read(ResultSet row,int index) throws SQLException {
        var plan=new ArrayList<UUID>();
        for(var id:json.readTree(row.getString("plan"))) plan.add(UUID.fromString(id.asString()));
        return new AutomationEventReceipt(row.getObject("id",UUID.class),row.getObject("tenant_id",UUID.class),
                row.getObject("project_id",UUID.class),row.getObject("source_event_id",UUID.class),row.getObject("device_id",UUID.class),
                row.getTimestamp("accepted_at").toInstant(),row.getTimestamp("admitted_at").toInstant(),row.getString("source_digest"),
                plan,AutomationEventReceipt.Result.valueOf(row.getString("result")),row.getString("reason_code"),
                row.getTimestamp("expires_at").toInstant());
    }
}
