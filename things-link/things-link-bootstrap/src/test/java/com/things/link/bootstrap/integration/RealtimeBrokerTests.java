package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.integration.infrastructure.EmqxRealtimePublisher;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RealtimeBrokerTests extends RealtimeBrokerFixture {
    @Test void mqtt3And5NegotiateZeroSessionAndDeliverExactNonRetainedData()throws Exception{
        for(int version:List.of(4,5)){
            var t=issue(120);String topic=RealtimeMqttPublisher.topic(t.ticketId());
            try(var w=wire(t,version,false)){
                connected(w);if(version==5)assertThat(java.util.HexFormat.of().formatHex(w.connack.body())).contains("1100000000");subscribed(w,topic);
                admission.accept(update(Uuid7.generate(),"1.12345678901234567890123456789"));dispatchAll();
                var payload=json.readTree(w.message(topic));assertThat(payload.path("properties").path("value").path("valueJson").asString()).isEqualTo("1.12345678901234567890123456789");
                assertThat(payload.path("properties").path("value").path("reportedRevision").asString()).isEqualTo("9007199254740993");
                w.disconnectWithLongExpiry();
            }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->brokerApi("/clients/tc-app-v1-"+t.ticketId()).statusCode()==404);
            try(var reconnected=wire(t,version,false)){connected(reconnected);assertThat(reconnected.connack.body()[0]).isZero();subscribed(reconnected,topic);reconnected.socket.setSoTimeout(300);assertThatThrownBy(()->reconnected.message(topic)).isInstanceOf(SocketTimeoutException.class);}
        }
    }
    @Test void wrongSecretAndDeviceIdentityCannotEnterApplicationBroker()throws Exception{
        var t=issue(120);
        try(var w=new PublicMqttWire(broker.getHost(),broker.getMappedPort(1883),4,"tc-app-v1-"+t.ticketId(),"tc-app-v1:"+t.ticketId(),"wrong",true)){assertThat(w.connack.body()[1]).isNotZero();}
        try(var w=new PublicMqttWire(broker.getHost(),broker.getMappedPort(1883),4,"device","project/device","wrong",true)){assertThat(w.connack.body()[1]).isNotZero();}
    }
    @Test void forbiddenTopicsQosAndClientPublishNeverDeliver()throws Exception{
        for(String denied:List.of("#","tc/app/v1/+/events","$share/g/tc/app/v1/"+UUID.randomUUID()+"/events","tc/device/secret")){
            var t=issue(120);try(var w=wire(t,4,true)){connected(w);try{var ack=w.subscribe(denied,1);assertThat(ack.body()[ack.body().length-1]).isEqualTo((byte)0x80);}catch(java.io.EOFException disconnected){/* deny_action disconnect */}}
            owner.update("UPDATE integ_realtime_ticket SET status='CLOSED',closed_reason='TEST' WHERE id=?",t.ticketId());
        }
        var t=issue(120);try(var w=wire(t,4,true)){connected(w);w.forbiddenPublish(RealtimeMqttPublisher.topic(t.ticketId()));assertThatThrownBy(w::read).isInstanceOf(java.io.EOFException.class);}
    }
    @Test void currentMembershipRevocationBlocksNewOutboundAndSubscription()throws Exception{
        var t=issue(120);String topic=RealtimeMqttPublisher.topic(t.ticketId());
        try(var w=wire(t,4,true)){
            connected(w);subscribed(w,topic);admission.accept(update(Uuid7.generate(),"2"));
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,account);dispatchAll();
            assertThat(owner.queryForObject("SELECT status FROM integ_realtime_delivery WHERE project_id=?",String.class,project)).isEqualTo("CANCELLED");
            w.socket.setSoTimeout(300);
            var silence=catchThrowable(()->w.message(topic));
            assertThat(silence).isInstanceOfAny(SocketTimeoutException.class,java.io.EOFException.class);
            if(silence instanceof SocketTimeoutException){
                w.socket.setSoTimeout(5000);
                try{var denied=w.subscribe(topic,1);assertThat(denied.header()).isEqualTo(0x90);assertThat(denied.body()[denied.body().length-1]).isEqualTo((byte)0x80);}catch(java.io.EOFException disconnected){/* Both SUBACK rejection and disconnect deny new subscription. */}
            }
        }
    }
    @Test void actualBrokerConfigurationHasNoCacheAndBoundedApplicationResources()throws Exception{
        var authorization=broker.execInContainer("/opt/emqx/bin/emqx","eval","emqx:get_config([authorization,cache,enable]).");assertThat(authorization.getExitCode()).isZero();assertThat(authorization.getStdout().trim()).isEqualTo("false");
        for(String field:List.of("[authorization,node_cache,enable]","[authentication_settings,node_cache,enable]")){
            var value=broker.execInContainer("/opt/emqx/bin/emqx","eval","emqx:get_config("+field+").");assertThat(value.getExitCode()).isZero();assertThat(value.getStdout().trim()).isEqualTo("false");
        }
        var config=broker.execInContainer("/opt/emqx/bin/emqx","eval","M=emqx:get_config([zones,tc_application,mqtt]),[maps:get(K,M) || K <- [session_expiry_interval,max_session_expiry_interval,max_mqueue_len,max_inflight,max_subscriptions,max_topic_alias,retain_available,wildcard_subscription,shared_subscription]].");
        assertThat(config.getExitCode()).isZero();assertThat(config.getStdout().replaceAll("\\s+","")).isEqualTo("[0,0,256,32,1,0,false,false,false]");
    }
    @Test void lowerAndHigherQosAndAnotherTicketTopicAreDenied()throws Exception{
        for(int qos:List.of(0,2)){
            var t=issue(120);try(var w=wire(t,4,true)){connected(w);try{var denied=w.subscribe(RealtimeMqttPublisher.topic(t.ticketId()),qos);assertThat(denied.body()[denied.body().length-1]).isEqualTo((byte)0x80);}catch(java.io.EOFException disconnected){}}
            owner.update("UPDATE integ_realtime_ticket SET status='CLOSED',closed_reason='TEST' WHERE id=?",t.ticketId());
        }
        var first=issue(120);var second=issue(120);try(var w=wire(first,4,true)){connected(w);try{var denied=w.subscribe(RealtimeMqttPublisher.topic(second.ticketId()),1);assertThat(denied.body()[denied.body().length-1]).isEqualTo((byte)0x80);}catch(java.io.EOFException disconnected){}}
    }
    @Test void mqtt5MaximumSubscriptionIdentifierAndAliasStayWithinPacketLimit()throws Exception{
        var t=issue(120);String topic=RealtimeMqttPublisher.topic(t.ticketId());
        try(var w=new PublicMqttWire(broker.getHost(),broker.getMappedPort(1883),5,"tc-app-v1-"+t.ticketId(),"tc-app-v1:"+t.ticketId(),t.credential(),false,true)){
            connected(w);subscribed(w,topic);UUID event=Uuid7.generate();admission.accept(update(event,"1"));
            String initial=owner.queryForObject("SELECT envelope FROM integ_realtime_delivery WHERE project_id=?",String.class,project);
            owner.update("DELETE FROM integ_realtime_delivery WHERE project_id=?",project);owner.update("DELETE FROM integ_realtime_event WHERE project_id=?",project);
            int maxEnvelope=32768-RealtimeMqttPublisher.packetBytes(t.ticketId(),new byte[32700])+32700;
            String value="1".repeat(maxEnvelope-initial.getBytes(StandardCharsets.UTF_8).length+1);admission.accept(update(event,value));dispatchAll();
            var received=json.readTree(w.message(topic));assertThat(received.path("properties").path("value").path("valueJson").asString()).isEqualTo(value);assertThat(w.lastPacketBytes).isLessThanOrEqualTo(32768);
        }
    }
    @Test void cachedAuthenticationCannotResurrectRemovedMember()throws Exception{
        var t=issue(120);try(var first=wire(t,4,true)){connected(first);}
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,account);
        try(var reconnect=wire(t,4,true)){assertThat(reconnect.connack.body()[1]).isNotZero();}
    }
    @Test void brokerDisconnectsAtTicketExpiry()throws Exception{
        var t=issue(8);try(var w=wire(t,4,true)){connected(w);subscribed(w,RealtimeMqttPublisher.topic(t.ticketId()));w.socket.setSoTimeout(12000);assertThatThrownBy(w::read).isInstanceOf(java.io.EOFException.class);}
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->brokerApi("/clients/tc-app-v1-"+t.ticketId()).statusCode()==404);
    }
}
