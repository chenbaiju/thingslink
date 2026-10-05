package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.*;
/** Webhook 投递 JDBC 仓储，维护尝试回执、租约、轮次及终态条件更新。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcWebhookDeliveryRepository implements WebhookDeliveryRepository {
    private final JdbcTemplate jdbc;public JdbcWebhookDeliveryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final RowMapper<Delivery> ROW=(r,n)->new Delivery(r.getObject("id",UUID.class),r.getObject("subscription_id",UUID.class),r.getLong("subscription_revision"),r.getString("event_type"),r.getObject("event_id",UUID.class),r.getString("status"),r.getInt("attempt_count"),r.getInt("recovery_round"),r.getInt("round_attempts"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("deadline_at").toInstant(),r.getTimestamp("next_attempt_at").toInstant(),r.getObject("lease_token",UUID.class),r.getTimestamp("lease_until")==null?null:r.getTimestamp("lease_until").toInstant(),r.getTimestamp("terminal_at")==null?null:r.getTimestamp("terminal_at").toInstant());
    public List<Candidate> candidates(int limit){return jdbc.query("SELECT * FROM integ_webhook_delivery_candidates(?)",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),limit);}
    public Optional<Delivery> find(UUID id){return jdbc.query("SELECT * FROM integ_webhook_delivery WHERE id=?",ROW,id).stream().findFirst();}
    public Optional<Delivery> lock(UUID id){return jdbc.query("SELECT * FROM integ_webhook_delivery WHERE id=? FOR UPDATE",ROW,id).stream().findFirst();}
    public String eventText(UUID id){return jdbc.queryForObject("SELECT e.event_text FROM integ_webhook_delivery d JOIN integ_webhook_event e USING(tenant_id,project_id,event_type,event_id) WHERE d.id=?",String.class,id);}
    public Delivery claim(UUID id,UUID token){var result=jdbc.queryForObject("UPDATE integ_webhook_delivery SET status='IN_FLIGHT',attempt_count=attempt_count+1,round_attempts=round_attempts+1,lease_token=?,lease_until=clock_timestamp()+interval '30 seconds',reason=NULL WHERE id=? RETURNING *",ROW,token,id);jdbc.update("INSERT INTO integ_webhook_attempt(tenant_id,project_id,delivery_id,attempt_no,recovery_round,lease_token,started_at,result) SELECT tenant_id,project_id,id,attempt_count,recovery_round,lease_token,clock_timestamp(),'STARTED' FROM integ_webhook_delivery WHERE id=?",id);return result;}
    public void completeAttempt(UUID id,UUID token,String result,Integer status,Long elapsed,String reason){jdbc.update("UPDATE integ_webhook_attempt SET result=?,http_status=?,elapsed_millis=?,reason=?,finished_at=clock_timestamp() WHERE delivery_id=? AND lease_token=? AND result='STARTED'",result,status,elapsed,reason,id,token);}
    public boolean finish(UUID id,UUID token,String status,String reason,int delay){return jdbc.update("UPDATE integ_webhook_delivery SET status=?,reason=?,lease_token=NULL,lease_until=NULL,terminal_at=CASE WHEN ?='READY' THEN NULL ELSE clock_timestamp() END,next_attempt_at=clock_timestamp()+make_interval(secs=>?) WHERE id=? AND status='IN_FLIGHT' AND lease_token=? AND lease_until>clock_timestamp()",status,reason,status,delay,id,token)==1;}
    public boolean recover(UUID id,int expectedRound){return jdbc.update("UPDATE integ_webhook_delivery SET status='READY',recovery_round=recovery_round+1,round_attempts=0,deadline_at=clock_timestamp()+interval '24 hours',next_attempt_at=clock_timestamp(),terminal_at=NULL,reason=NULL WHERE id=? AND status='DEAD' AND recovery_round=? AND recovery_round<3 AND terminal_at>clock_timestamp()-interval '7 days'",id,expectedRound)==1;}
    public void terminate(UUID id,String status,String reason){jdbc.update("UPDATE integ_webhook_delivery SET status=?,reason=?,lease_token=NULL,lease_until=NULL,terminal_at=clock_timestamp() WHERE id=? AND status IN ('READY','IN_FLIGHT')",status,reason,id);}
}
