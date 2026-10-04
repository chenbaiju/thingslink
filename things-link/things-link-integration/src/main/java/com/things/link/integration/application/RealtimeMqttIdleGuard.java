package com.things.link.integration.application;
import org.springframework.stereotype.Service;
import com.things.link.shared.error.BusinessException;
@Service
public class RealtimeMqttIdleGuard {
    private final RealtimeTicketService tickets;private final RealtimeMqttSessions sessions;
    public RealtimeMqttIdleGuard(RealtimeTicketService tickets,RealtimeMqttSessions sessions){this.tickets=tickets;this.sessions=sessions;}
    public void inspect(RealtimeMqttSessions.Session session){
        try{tickets.authorizeMqtt(session.ticket(),session.peerIp());return;}
        catch(BusinessException rejected){try{tickets.closeMqttById(session.ticket());}catch(RuntimeException unavailable){/* Physical close must still be attempted when the database cannot record it. */}}
        catch(RuntimeException unavailable){/* Authoritative uncertainty is fail-closed, never an allow. */}
        if(!sessions.disconnect(session.ticket()))throw new IllegalStateException("REALTIME_BROKER_DISCONNECT_UNCONFIRMED");
    }
}
