package com.things.link.integration.application;
import java.time.Duration;
import java.util.UUID;
public interface RealtimeMqttPublisher {
    boolean configured();
    Outcome publish(UUID ticket,byte[] envelope,Duration budget);
    void cancelActive();
    enum Outcome { BROKER_ACCEPTED, REJECTED, UNKNOWN }
    static String topic(UUID ticket){return "tc/app/v1/"+ticket+"/events";}
    static int packetBytes(UUID ticket,byte[] envelope){
        int remaining=2+topic(ticket).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+2+envelope.length;
        int header=1;for(int n=remaining;;n/=128){header++;if(n<128)break;}return header+remaining+16; // ADR0179: MQTT5 property length, maximum subscription identifier and outbound topic alias.
    }
}
