package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcRealtimeEventRepository implements RealtimeEventRepository {
    private final JdbcTemplate jdbc;
    public JdbcRealtimeEventRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Admission admit(DeviceRealtimeUpdate u,String hash){
        int inserted=jdbc.update("INSERT INTO integ_realtime_event(tenant_id,project_id,event_id,device_id,source_hash) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",u.tenantId(),u.projectId(),u.messageId(),u.deviceId(),hash);
        if(inserted==1)return Admission.NEW;
        String original=jdbc.queryForObject("SELECT source_hash FROM integ_realtime_event WHERE project_id=? AND event_id=? AND device_id=?",String.class,u.projectId(),u.messageId(),u.deviceId());
        return hash.equals(original)?Admission.DUPLICATE:Admission.CONFLICT;
    }
    @Override public int pending(UUID ticket){return jdbc.queryForObject("SELECT count(*) FROM integ_realtime_delivery WHERE ticket_id=? AND status IN ('READY','IN_FLIGHT')",Integer.class,ticket);}
    @Override public void enqueue(RealtimeTicket ticket,DeviceRealtimeUpdate u,String envelope){
        jdbc.update("INSERT INTO integ_realtime_delivery(id,tenant_id,project_id,event_id,device_id,ticket_id,envelope) VALUES(?,?,?,?,?,?,?)",Uuid7.generate(),u.tenantId(),u.projectId(),u.messageId(),u.deviceId(),ticket.id(),envelope);
    }
}
