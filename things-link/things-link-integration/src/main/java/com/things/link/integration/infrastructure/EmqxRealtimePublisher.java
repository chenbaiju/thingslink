package com.things.link.integration.infrastructure;
import com.things.link.integration.application.RealtimeMqttPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
/** 独立应用Broker发布器，单次HTTP接管不等于客户端处理。 */
@Component
public class EmqxRealtimePublisher implements RealtimeMqttPublisher,AutoCloseable {
    private final RealtimeBrokerHttp http;private final ObjectMapper json=new ObjectMapper();
    public EmqxRealtimePublisher(@Value("${things-link.integration.realtime.mqtt-api.base-url:}")String base,
        @Value("${things-link.integration.realtime.mqtt-api.api-key:}")String key,@Value("${things-link.integration.realtime.mqtt-api.api-secret:}")String secret){http=new RealtimeBrokerHttp(base,key,secret);}
    public boolean configured(){return http.configured();}
    public Outcome publish(UUID ticket,byte[] envelope,Duration budget){
        if(ticket==null||envelope==null||RealtimeMqttPublisher.packetBytes(ticket,envelope)>32768)return Outcome.UNKNOWN;
        byte[] body=json.writeValueAsBytes(Map.of("topic",RealtimeMqttPublisher.topic(ticket),"qos",1,"retain",false,"payload",Base64.getEncoder().encodeToString(envelope),"payload_encoding","base64"));
        var reply=http.exchange("POST","/api/v5/publish",body,budget,65536);
        return reply.accepted()?Outcome.BROKER_ACCEPTED:reply.status()==0?Outcome.UNKNOWN:Outcome.REJECTED;
    }
    public void cancelActive(){http.cancelActive();}
    @PreDestroy public void close(){http.close();}
}
