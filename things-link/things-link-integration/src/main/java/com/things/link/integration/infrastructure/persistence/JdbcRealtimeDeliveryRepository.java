package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.RealtimeDeliveryRepository;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.*;
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcRealtimeDeliveryRepository implements RealtimeDeliveryRepository {
    private final JdbcTemplate jdbc;
    public JdbcRealtimeDeliveryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final RowMapper<Delivery> ROW=(r,n)->new Delivery(r.getObject("id",UUID.class),r.getObject("ticket_id",UUID.class),r.getString("envelope"),r.getString("status"),r.getInt("attempts"),r.getTimestamp("next_attempt_at").toInstant(),r.getObject("lease_token",UUID.class),r.getTimestamp("lease_until")==null?null:r.getTimestamp("lease_until").toInstant());
    public List<Candidate> candidates(int limit){return jdbc.query("SELECT * FROM integ_realtime_delivery_candidates(?)",(r,n)->new Candidate(r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("id",UUID.class)),limit);}
    public List<Candidate> wsCandidates(UUID ticket,int limit){return jdbc.query("SELECT tenant_id,project_id,id FROM integ_realtime_delivery WHERE ticket_id=? AND status IN ('READY','IN_FLIGHT') AND (status='READY' OR lease_until<=clock_timestamp()) ORDER BY next_attempt_at,id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),ticket,Math.max(0,Math.min(256,limit)));}
    public Optional<Delivery> lock(UUID id){return jdbc.query("SELECT * FROM integ_realtime_delivery WHERE id=? FOR UPDATE",ROW,id).stream().findFirst();}
    public Delivery claim(UUID id,UUID token){return jdbc.queryForObject("UPDATE integ_realtime_delivery SET status='IN_FLIGHT',attempts=attempts+1,lease_token=?,lease_until=clock_timestamp()+interval '30 seconds',reason=NULL WHERE id=? RETURNING *",ROW,token,id);}
    public boolean finish(UUID id,UUID token,String status,String reason,int delay){return jdbc.update("UPDATE integ_realtime_delivery SET status=?,reason=?,lease_token=NULL,lease_until=NULL,finished_at=CASE WHEN ?='READY' THEN NULL ELSE clock_timestamp() END,next_attempt_at=clock_timestamp()+make_interval(secs=>?) WHERE id=? AND status='IN_FLIGHT' AND lease_token=? AND lease_until>clock_timestamp()",status,reason,status,delay,id,token)==1;}
    public void terminate(UUID id,String status,String reason){jdbc.update("UPDATE integ_realtime_delivery SET status=?,reason=?,lease_token=NULL,lease_until=NULL,finished_at=clock_timestamp() WHERE id=? AND status IN ('READY','IN_FLIGHT')",status,reason,id);}
}
