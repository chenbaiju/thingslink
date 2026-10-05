package com.things.link.integration.application;
import org.springframework.stereotype.Service;
import com.things.link.shared.error.BusinessException;
@Service
public class RealtimeMqttIdleGuard {
    private final RealtimeTicketService tickets;private final RealtimeMqttSessions sessions;
    public RealtimeMqttIdleGuard(RealtimeTicketService tickets,RealtimeMqttSessions sessions){this.tickets=tickets;this.sessions=sessions;}
    public void inspect(RealtimeMqttSessions.Session session){
        try{tickets.authorizeMqtt(session.ticket(),session.peerIp());return;}
        catch(BusinessException rejected){try{tickets.closeMqttById(session.ticket());}catch(RuntimeException unavailable){/* 即使数据库无法记录关闭事实，也必须尝试关闭物理连接。 */}}
        catch(RuntimeException unavailable){/* 权威状态不确定时拒绝放行，不能视为允许。 */}
        if(!sessions.disconnect(session.ticket()))throw new IllegalStateException("REALTIME_BROKER_DISCONNECT_UNCONFIRMED");
    }
}
