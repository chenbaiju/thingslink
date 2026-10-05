package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.WebhookEventRepository;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
/** Webhook 来源 JDBC 仓储，维护项目时间底线、来源去重与订阅投递意图。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcWebhookEventRepository implements WebhookEventRepository {
    private final JdbcTemplate jdbc;
    public JdbcWebhookEventRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    public void lockProject(UUID project){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('integ:webhook:project:'||?::text,0))",Object.class,project);}
    public Instant advanceFloor(UUID tenant,UUID project){return jdbc.queryForObject("INSERT INTO integ_webhook_ingress_state VALUES (?,?,clock_timestamp()-interval '7 days') ON CONFLICT(tenant_id,project_id) DO UPDATE SET reject_before=greatest(integ_webhook_ingress_state.reject_before,EXCLUDED.reject_before) RETURNING reject_before",Timestamp.class,tenant,project).toInstant();}
    public Optional<Existing> find(PublicWebhookEvent e){return jdbc.query("SELECT source_hash,result FROM integ_webhook_event WHERE tenant_id=? AND project_id=? AND event_type=? AND event_id=?",(r,n)->new Existing(r.getString(1),r.getString(2)),e.tenantId(),e.projectId(),e.eventType(),e.eventId()).stream().findFirst();}
    public void conflict(PublicWebhookEvent e,String hash,Instant now){jdbc.update("INSERT INTO integ_webhook_conflict VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",e.tenantId(),e.projectId(),e.eventType(),e.eventId(),hash,Timestamp.from(now));}
    public List<Plan> matching(PublicWebhookEvent e){return jdbc.query("SELECT s.id,s.current_revision FROM integ_webhook_subscription s JOIN integ_webhook_revision r ON (r.tenant_id,r.project_id,r.subscription_id,r.revision)=(s.tenant_id,s.project_id,s.id,s.current_revision) WHERE s.project_id=? AND s.project_generation=? AND s.status='ACTIVE' AND ?=ANY(r.event_types) AND (cardinality(r.device_ids)=0 OR ?=ANY(r.device_ids)) ORDER BY s.id LIMIT 20 FOR SHARE OF s",(r,n)->new Plan(r.getObject(1,UUID.class),r.getLong(2)),e.projectId(),e.projectGeneration(),e.eventType(),e.deviceId());}
    public int pending(UUID project){return jdbc.queryForObject("SELECT count(*) FROM integ_webhook_delivery WHERE project_id=? AND status IN ('READY','IN_FLIGHT')",Integer.class,project);}
    public void insert(PublicWebhookEvent e,String hash,String text,String result,Instant now){jdbc.update("INSERT INTO integ_webhook_event VALUES (?,?,?,?,?,?,?,?,?,?,?)",e.tenantId(),e.projectId(),e.eventType(),e.eventId(),e.projectGeneration(),hash,Timestamp.from(e.recordedAt()),Timestamp.from(e.occurredAt()),Timestamp.from(now),result,text);}
    public void enqueue(PublicWebhookEvent e,Plan p,Instant now){jdbc.update("INSERT INTO integ_webhook_delivery(id,tenant_id,project_id,event_type,event_id,subscription_id,subscription_revision,created_at,deadline_at,status,next_attempt_at) VALUES (?,?,?,?,?,?,?,?,?,'READY',?)",Uuid7.generate(),e.tenantId(),e.projectId(),e.eventType(),e.eventId(),p.subscription(),p.revision(),Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)),Timestamp.from(now));}
}
