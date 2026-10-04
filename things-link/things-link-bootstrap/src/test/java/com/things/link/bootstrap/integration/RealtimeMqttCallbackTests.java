package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** 真实HTTP/PG/Redis回调；Broker线协议资格由c-2c另行验证。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true",
    "things-link.security.broker-callback.secret=realtime-callback-test-only-secret"})
class RealtimeMqttCallbackTests extends OpenDeviceHttpFixture {
    @Autowired RealtimeTicketService tickets;
    @Autowired ApiKeyAuthenticationService authentication;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    static final Map<String,String> BROKER=Map.of("X-Broker-Callback-Token","realtime-callback-test-only-secret");
    @AfterEach void removeTickets(){owner.update("DELETE FROM integ_realtime_ticket WHERE project_id=?",project);redis.delete("quota:websocket:connections:{"+tenant+"}");}
    RealtimeTicketService.Issued issue(String protocol)throws Exception{
        var scope=new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol",protocol,"eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value"))))));
        return tickets.issue(RealtimeIdentity.fromKey(authentication.authenticate(secret,"127.0.0.1")),scope,"127.0.0.1");
    }
    Map<String,Object> auth(RealtimeTicketService.Issued issued){var body=new HashMap<String,Object>();body.put("username","tc-app-v1:"+issued.ticketId());body.put("password",issued.credential());body.put("clientid","tc-app-v1-"+issued.ticketId());body.put("peerhost","127.0.0.1");body.put("zone","tc_application");return body;}
    Map<String,Object> acl(RealtimeTicketService.Issued issued){var body=auth(issued);body.remove("password");body.put("topic","tc/app/v1/"+issued.ticketId()+"/events");body.put("access","subscribe");body.put("qos","1");return body;}
    java.net.http.HttpResponse<String> callback(String endpoint,Map<String,Object> body)throws Exception{return request("POST","/api/v1/emqx/"+endpoint,json.writeValueAsString(body),null,BROKER);}
    String outcome(String endpoint,Map<String,Object> body)throws Exception{var response=callback(endpoint,body);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()).path("result").asString();}
    @Test void callbackProtectionAndExactConnectedSubscription()throws Exception{
        var issued=issue("MQTT");var auth=auth(issued);var acl=acl(issued);
        assertThat(request("POST","/api/v1/emqx/auth",json.writeValueAsString(auth),null,Map.of()).statusCode()).isEqualTo(401);
        assertThat(outcome("acl",acl)).isEqualTo("deny");
        var response=callback("auth",auth);assertThat(response.statusCode()).isEqualTo(200);
        var data=json.readTree(response.body());assertThat(data.path("result").asString()).isEqualTo("allow");
        assertThat(data.path("expire_at").asLong()).isEqualTo(issued.expiresAt().getEpochSecond());assertThat(data.path("is_superuser").asBoolean()).isFalse();assertThat(response.body()).doesNotContain(issued.credential());
        assertThat(outcome("acl",acl)).isEqualTo("allow");
        for(var mutation:List.of(Map.entry("access","publish"),Map.entry("topic","tc/app/v1/+/events"),Map.entry("topic","$share/group/"+issued.topic()),Map.entry("topic","tc/v1/p/d/up/property/report"),Map.entry("qos","0"),Map.entry("qos","2"),Map.entry("clientid","wrong"),Map.entry("peerhost","192.0.2.1"),Map.entry("zone","default"))){
            var wrong=new HashMap<>(acl);wrong.put(mutation.getKey(),mutation.getValue());assertThat(outcome("acl",wrong)).as(mutation.toString()).isEqualTo("deny");
        }
        keys.revoke(tenant,project,account,Uuid7.generate(),authentication.authenticate(secret,"127.0.0.1").keyId());
        assertThat(outcome("acl",acl)).isEqualTo("deny");assertThat(outcome("auth",auth)).isEqualTo("deny");
    }
    @Test void wrongIdentityProtocolZoneAndMissingTrustedPeerNeverFallBackToDeviceAuth()throws Exception{
        var ws=issue("WS");assertThat(outcome("auth",auth(ws))).isEqualTo("deny");
        var mqtt=issue("MQTT");var valid=auth(mqtt);
        for(var mutation:List.of(Map.entry("username","p/d"),Map.entry("username","tc-app-v1:"+Uuid7.generate()),Map.entry("zone","default"),Map.entry("password",secret),Map.entry("clientid","other"),Map.entry("peerhost",""))){
            var wrong=new HashMap<>(valid);wrong.put(mutation.getKey(),mutation.getValue());assertThat(outcome("auth",wrong)).isEqualTo("deny");
        }
        valid.remove("zone");assertThat(outcome("auth",valid)).isEqualTo("deny");
        assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_ticket WHERE project_id=? AND status='CONNECTED'",Integer.class,project)).isZero();
    }
    @Test void databaseProofFailureNeverReturnsAllowAndPermissionIsRestored()throws Exception{
        var issued=issue("MQTT");
        owner.execute("REVOKE EXECUTE ON FUNCTION integ_prove_realtime_ticket(uuid,text) FROM thingslink_app");
        try{var response=callback("auth",auth(issued));assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);assertThat(response.body()).doesNotContain("\"allow\"",issued.credential());}
        finally{owner.execute("GRANT EXECUTE ON FUNCTION integ_prove_realtime_ticket(uuid,text) TO thingslink_app");}
        assertThat(outcome("auth",auth(issued))).isEqualTo("allow");
    }
}
