package com.things.link.integration.infrastructure;
import com.things.link.integration.application.RealtimeMqttSessions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
/** 仅查询隔离的应用 Broker；禁止删除不属于本应用的客户端标识。 */
@Component
public class EmqxRealtimeSessions implements RealtimeMqttSessions,AutoCloseable {
    private final RealtimeBrokerHttp http;private final ObjectMapper json=new ObjectMapper();
    public EmqxRealtimeSessions(@Value("${things-link.integration.realtime.mqtt-api.base-url:}")String base,
        @Value("${things-link.integration.realtime.mqtt-api.session-api-key:}")String key,@Value("${things-link.integration.realtime.mqtt-api.session-api-secret:}")String secret){http=new RealtimeBrokerHttp(base,key,secret);}
    public boolean configured(){return http.configured();}
    public List<Session> active(){
        var reply=http.exchange("GET","/api/v5/clients?page=1&limit=1000",null,Duration.ofSeconds(5),4*1024*1024);
        if(!reply.accepted())throw unavailable();
        try{
            var root=json.readTree(reply.body());var rows=root.path("data");
            if(!rows.isArray()||rows.size()>1000||root.path("meta").path("hasnext").asBoolean())throw unavailable();
            var sessions=new ArrayList<Session>();
            for(var row:rows){String client=row.path("clientid").asString(),user=row.path("username").asString();
                if(!client.startsWith("tc-app-v1-")||!user.startsWith("tc-app-v1:"))continue;
                try{UUID id=UUID.fromString(client.substring(10));if(!client.equals("tc-app-v1-"+id)||!user.equals("tc-app-v1:"+id))continue;
                    String ip=row.path("ip_address").asString();if(!ip.matches("[0-9A-Fa-f:.]{1,64}"))throw unavailable();sessions.add(new Session(id,ip));
                }catch(IllegalArgumentException foreign){/* 禁止从格式错误的 Broker 元数据中猜测规范身份。 */}
            }
            return List.copyOf(sessions);
        }catch(RuntimeException malformed){throw unavailable();}
    }
    public boolean disconnect(UUID ticket){if(ticket==null)return false;var reply=http.exchange("DELETE","/api/v5/clients/tc-app-v1-"+ticket,null,Duration.ofSeconds(5),65536);return reply.accepted()||reply.status()==404;}
    public void cancelActive(){http.cancelActive();}
    @PreDestroy public void close(){http.close();}
    private static IllegalStateException unavailable(){return new IllegalStateException("REALTIME_BROKER_SESSION_UNAVAILABLE");}
}
