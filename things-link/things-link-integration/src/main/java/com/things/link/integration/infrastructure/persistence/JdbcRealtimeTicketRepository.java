package com.things.link.integration.infrastructure.persistence;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.shared.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 实时票据 JDBC 仓储，维护数据库时间、身份摘要、连接绑定和清理范围。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcRealtimeTicketRepository implements RealtimeTicketRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public JdbcRealtimeTicketRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    @Override public void insert(RealtimeTicket t,String digest) {
        var i=t.identity();
        try {jdbc.update("""
            INSERT INTO integ_realtime_ticket(id,tenant_id,project_id,project_generation,issuer_kind,subject_id,
                account_id,key_id,identity_expires_at,protocol,scope_json,property_count,secret_hash,created_at,
                expires_at,status,lease_member,instance_id,peer_ip)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?::inet)
            """,t.id(),i.tenant(),i.project(),i.generation(),i.kind().name(),i.subject(),i.account(),i.key(),
            Timestamp.from(i.identityExpiresAt()),t.request().protocol().name(),json.writeValueAsString(t.request()),t.request().propertyCount(),digest,
            Timestamp.from(t.createdAt()),Timestamp.from(t.expiresAt()),t.status(),t.leaseMember(),t.instanceId(),t.peerIp());
        }catch(DataAccessException ex){for(Throwable c=ex;c!=null;c=c.getCause())if(c instanceof java.sql.SQLException sql&&"P0001".equals(sql.getSQLState())&&sql.getMessage().contains("INTEG_REALTIME_CAPACITY"))throw new BusinessException(IntegrationErrorCode.REALTIME_CAPACITY);throw ex;}
    }
    @Override public Optional<RealtimeTicket> prove(UUID id,String digest){return Optional.ofNullable(jdbc.queryForObject("SELECT integ_prove_realtime_ticket(?,?)::text",String.class,id,digest)).map(this::map);}
    @Override public Optional<RealtimeTicket> lockForDelivery(UUID id){return jdbc.query("SELECT (to_jsonb(t)-'secret_hash')::text FROM integ_realtime_ticket t WHERE id=? FOR SHARE",(r,n)->map(r.getString(1)),id).stream().findFirst();}
    @Override public Optional<RealtimeTicket> lockForTermination(UUID id){return jdbc.query("SELECT (to_jsonb(t)-'secret_hash')::text FROM integ_realtime_ticket t WHERE id=? FOR UPDATE",(r,n)->map(r.getString(1)),id).stream().findFirst();}
    @Override public Optional<RealtimeTicket> find(UUID id){return jdbc.query("SELECT (to_jsonb(t)-'secret_hash')::text FROM integ_realtime_ticket t WHERE id=?",(r,n)->map(r.getString(1)),id).stream().findFirst();}
    @Override public Optional<RealtimeTicket> connected(UUID id){return Optional.ofNullable(jdbc.queryForObject("SELECT integ_connected_realtime_ticket(?)::text",String.class,id)).map(this::map);}
    @Override public List<RealtimeTicket> lockConnected(UUID project){return jdbc.query("SELECT (to_jsonb(t)-'secret_hash')::text FROM integ_realtime_ticket t WHERE project_id=? AND status='CONNECTED' AND expires_at>clock_timestamp() ORDER BY id LIMIT 200 FOR UPDATE",(r,n)->map(r.getString(1)),project);}
    @Override public boolean bindWs(UUID id,String peerIp,String instance){try{return jdbc.update("UPDATE integ_realtime_ticket SET status='CONNECTED',peer_ip=?::inet,instance_id=? WHERE id=? AND protocol='WS' AND status='RESERVED' AND expires_at>clock_timestamp()",peerIp,instance,id)==1;}catch(DataAccessException ex){for(Throwable c=ex;c!=null;c=c.getCause())if(c instanceof java.sql.SQLException sql&&"P0001".equals(sql.getSQLState())&&sql.getMessage().contains("INTEG_REALTIME_CAPACITY"))throw new BusinessException(IntegrationErrorCode.REALTIME_CAPACITY);throw ex;}}
    @Override public String closedReason(UUID id){return jdbc.queryForObject("SELECT closed_reason FROM integ_realtime_ticket WHERE id=?",String.class,id);}
    @Override public boolean bindMqtt(UUID id,String peerIp){return jdbc.update("UPDATE integ_realtime_ticket SET status='CONNECTED',peer_ip=?::inet WHERE id=? AND protocol='MQTT' AND status<>'CLOSED' AND expires_at>clock_timestamp()",peerIp,id)==1;}
    @Override public boolean ipAllowed(String ip,List<String> cidrs){
        if(ip==null||ip.length()>64||!ip.matches("[0-9A-Fa-f:.]+"))return false;
        try{return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?::inet <<= ANY(?::cidr[])",Boolean.class,ip,"{"+String.join(",",cidrs)+"}"));}
        catch(DataAccessException ex){for(Throwable c=ex;c!=null;c=c.getCause())if(c instanceof java.sql.SQLException sql&&"22P02".equals(sql.getSQLState()))return false;throw ex;}
    }
    @Override public void close(UUID id,String reason){jdbc.update("UPDATE integ_realtime_ticket SET status='CLOSED',closed_reason=? WHERE id=? AND status<>'CLOSED'",reason,id);}
    @Override public List<Candidate> candidates(String instance,int limit){return jdbc.query("SELECT * FROM integ_realtime_candidates(?,?)",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),instance,limit);}
    @Override public int purgeExpired(int limit){return jdbc.queryForObject("SELECT integ_purge_realtime_tickets(?)",Integer.class,limit);}
    private RealtimeTicket map(String value){
        JsonNode n=json.readTree(value);
        var identity=new RealtimeIdentity(RealtimeIdentity.Kind.valueOf(n.path("issuer_kind").asString()),uuid(n,"tenant_id"),uuid(n,"project_id"),n.path("project_generation").asLong(),uuid(n,"subject_id"),uuid(n,"account_id"),uuid(n,"key_id"),instant(n,"identity_expires_at"));
        var scope=new RealtimeTicketParser().parse(json.writeValueAsBytes(n.path("scope_json")));
        return new RealtimeTicket(uuid(n,"id"),identity,scope,instant(n,"created_at"),instant(n,"expires_at"),n.path("status").asString(),text(n,"lease_member"),text(n,"instance_id"),text(n,"peer_ip"));
    }
    private static UUID uuid(JsonNode n,String name){String v=text(n,name);return v==null?null:UUID.fromString(v);}
    private static Instant instant(JsonNode n,String name){return java.time.OffsetDateTime.parse(n.path(name).asString()).toInstant();}
    private static String text(JsonNode n,String name){return n.path(name).isNull()?null:n.path(name).asString();}
}
