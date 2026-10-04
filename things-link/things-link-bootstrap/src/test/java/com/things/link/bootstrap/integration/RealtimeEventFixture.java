package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
abstract class RealtimeEventFixture extends OpenDeviceHttpFixture {
    @Autowired RealtimeTicketService tickets;
    @Autowired ApiKeyAuthenticationService authentication;
    @Autowired RealtimeEventAdmission admission;
    @Autowired PublicRealtimeIngress ingress;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @AfterEach void cleanupEvents(){
        for(String table:List.of("integ_realtime_delivery","integ_realtime_event","integ_realtime_ticket"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);
        redis.delete("quota:websocket:connections:{"+tenant+"}");
    }
    RealtimeTicketService.Issued connect()throws Exception{
        var scope=new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol","MQTT","eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value"))))));
        var issued=tickets.issue(RealtimeIdentity.fromKey(authentication.authenticate(secret,"127.0.0.1")),scope,"127.0.0.1");tickets.connectMqtt(issued.credential(),"127.0.0.1");return issued;
    }
    DeviceRealtimeUpdate update(UUID event,String value){return new DeviceRealtimeUpdate(event,tenant,project,device,model,"1.0.0",Instant.parse("2026-09-21T00:00:00Z"),0,"realtime-admission",Map.of("value",value,"empty","2"),Map.of("value","NUMBER","empty","NUMBER"),Map.of("value","9007199254740993"));}
    int count(String table){return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project);}
}
