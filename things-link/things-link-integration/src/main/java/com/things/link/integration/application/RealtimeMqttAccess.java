package com.things.link.integration.application;
import com.things.link.device.application.ApplicationMqttAccess;
import com.things.link.integration.domain.RealtimeTicketCredential;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import java.util.UUID;
/** Broker回调保护在入口认证链；本端口只接受独立zone、规范身份和精确QoS1订阅。 */
@Service
public class RealtimeMqttAccess implements ApplicationMqttAccess {
    private final RealtimeTicketService tickets;
    public RealtimeMqttAccess(RealtimeTicketService tickets){this.tickets=tickets;}
    @Override public Authentication authenticate(String username,String password,String clientId,String peerIp,String zone){
        UUID id=identity(username,clientId,peerIp,zone);if(id==null)return Authentication.deny();
        var credential=RealtimeTicketCredential.parse(password);if(credential.isEmpty()||!id.equals(credential.get().id()))return Authentication.deny();
        try{return new Authentication(true,tickets.connectMqtt(password,peerIp).expiresAt());}catch(BusinessException denied){return Authentication.deny();}
    }
    @Override public boolean authorize(String username,String clientId,String peerIp,String zone,String topic,String action,String qos){
        UUID id=identity(username,clientId,peerIp,zone);
        if(id==null||!("subscribe".equals(action)||"1".equals(action))||!"1".equals(qos)||!("tc/app/v1/"+id+"/events").equals(topic))return false;
        try{tickets.authorizeMqtt(id,peerIp);return true;}catch(BusinessException denied){return false;}
    }
    private static UUID identity(String username,String clientId,String peerIp,String zone){
        if(!ZONE.equals(zone)||!ApplicationMqttAccess.applicationIdentity(username)||peerIp==null||!peerIp.matches("[0-9A-Fa-f:.]{1,64}"))return null;
        String raw=username.substring("tc-app-v1:".length());
        try{UUID id=UUID.fromString(raw);return id.toString().equals(raw)&&("tc-app-v1-"+id).equals(clientId)?id:null;}catch(IllegalArgumentException e){return null;}
    }
}
