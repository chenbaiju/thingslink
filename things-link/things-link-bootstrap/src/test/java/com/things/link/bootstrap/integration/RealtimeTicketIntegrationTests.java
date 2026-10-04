package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.enduser.application.*;
import com.things.link.project.application.RealtimeConnectionLease;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true"})
class RealtimeTicketIntegrationTests extends OpenDeviceHttpFixture {
    @Autowired RealtimeTicketService tickets;
    @Autowired RealtimeTicketMaintenanceService maintenance;
    @Autowired ApiKeyAuthenticationService authentication;
    @Autowired AppTokenIssuer appTokens;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired StringRedisTemplate redis;
    @Autowired RealtimeConnectionLease leases;
    UUID app;
    @AfterEach void cleanupTickets(){
        redis.delete("quota:websocket:connections:{"+tenant+"}");
        owner.update("DELETE FROM integ_realtime_ticket WHERE project_id=?",project);
        owner.update("DELETE FROM app_user_device WHERE project_id=?",project);
        owner.update("DELETE FROM app_user_role WHERE project_id=?",project);
        if(app!=null)owner.update("DELETE FROM app_user WHERE id=?",app);
    }
    String body(String protocol)throws Exception{return json.writeValueAsString(Map.of("protocol",protocol,"eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value")))));}
    RealtimeTicketRequest scope()throws Exception{return new RealtimeTicketParser().parse(body("MQTT").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    RealtimeIdentity keyIdentity(){return RealtimeIdentity.fromKey(authentication.authenticate(secret,"127.0.0.1"));}
    RealtimeIdentity console(){return new RealtimeIdentity(RealtimeIdentity.Kind.CONSOLE,tenant,project,0,account,account,null,Instant.now().plusSeconds(120));}
    @Test void trueHttpThreeAudiencesAndOneTimeSecret()throws Exception {
        String path="/api/open/v1/realtime/tickets",key=Uuid7.generate().toString();
        var first=request("POST",path,body("MQTT"),null,Map.of("X-Api-Key",secret,"Idempotency-Key",key));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var data=json.readTree(first.body());String credential=data.path("credential").asString();
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(tickets.authenticate(credential,RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1").identity().kind()).isEqualTo(RealtimeIdentity.Kind.API_KEY);
        assertThat(owner.queryForObject("SELECT secret_hash FROM integ_realtime_ticket WHERE id=?",String.class,UUID.fromString(data.path("ticketId").asString()))).isEqualTo(RealtimeTicketCredential.parse(credential).orElseThrow().digest());
        var repeat=request("POST",path,body("MQTT"),null,Map.of("X-Api-Key",secret,"Idempotency-Key",key));assertThat(repeat.statusCode()).isEqualTo(409);assertThat(repeat.body()).doesNotContain(credential);
        assertThat(request("POST",path,body("MQTT"),token(),Map.of("Idempotency-Key",Uuid7.generate().toString())).statusCode()).isEqualTo(401);
        String cp="/api/v1/projects/"+project+"/realtime-tickets";
        assertThat(request("POST",cp,body("WS"),token(),Map.of("Idempotency-Key",Uuid7.generate().toString())).statusCode()).isEqualTo(201);
        assertThat(request("POST",cp,body("WS"),token(),Map.of("X-Api-Key",secret,"Idempotency-Key",Uuid7.generate().toString())).statusCode()).isEqualTo(401);
        String appToken=appToken();
        var response=request("POST","/api/v1/app/realtime-tickets",body("WS"),appToken,Map.of("Idempotency-Key",Uuid7.generate().toString()));assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(request("POST",cp,body("WS"),appToken,Map.of("Idempotency-Key",Uuid7.generate().toString())).statusCode()).isEqualTo(401);
    }
    String appToken(){app=Uuid7.generate();
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'unused')",app,tenant,"rt"+app);
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'OBSERVER')",Uuid7.generate(),tenant,project,app);
        owner.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role) VALUES (?,?,?,?,?,'READ_ONLY')",Uuid7.generate(),tenant,project,app,device);
        return appTokens.issue(new AppAuthenticatedPrincipal(tenant,project,app)).value();
    }
    @Test void currentKeyMemberAccountAndProtocolAreRechecked()throws Exception {
        var issued=tickets.issue(keyIdentity(),scope(),"127.0.0.1");
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.WS,"127.0.0.1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"192.0.2.1")).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_account SET status='DISABLED' WHERE id=?",account);
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1")).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_account SET status='ACTIVE' WHERE id=?",account);
        keys.revoke(tenant,project,account,Uuid7.generate(),keyIdentity().key());
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1")).isInstanceOf(BusinessException.class);
        var c=tickets.issue(console(),scope(),"127.0.0.1");owner.update("DELETE FROM sys_project_member WHERE project_id=?",project);
        assertThatThrownBy(()->tickets.authenticate(c.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1")).isInstanceOf(BusinessException.class);
    }
    @Test void appBindingAndUserStatusCannotBeInheritedFromTicket()throws Exception {
        appToken();var identity=new RealtimeIdentity(RealtimeIdentity.Kind.APP,tenant,project,0,app,null,null,Instant.now().plusSeconds(120));
        var issued=tickets.issue(identity,scope(),"127.0.0.1");
        owner.update("UPDATE app_user_device SET status='CLOSED' WHERE app_user_id=?",app);
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1")).isInstanceOf(BusinessException.class);
        owner.update("UPDATE app_user_device SET status='ACTIVE' WHERE app_user_id=?",app);
        owner.update("UPDATE app_user SET status='LOCKED' WHERE id=?",app);
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1")).isInstanceOf(BusinessException.class);
    }
    @Test void keyAndConsoleTwelveConcurrentIssuersShareFiveSlots()throws Exception {
        var request=scope();var key=keyIdentity();var console=console();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int n=0;n<12;n++){var identity=n%2==0?key:console;futures.add(workers.submit(()->{start.await();try{tickets.issue(identity,request,"127.0.0.1");return true;}catch(BusinessException ex){assertThat(ex.errorCode().code()).isEqualTo(80005);return false;}}));}
            start.countDown();int admitted=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS))admitted++;
            assertThat(admitted).isEqualTo(5);
            assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_ticket WHERE project_id=?",Integer.class,project)).isEqualTo(5);
            assertThat(redis.opsForZSet().zCard("quota:websocket:connections:{"+tenant+"}")).isEqualTo(5);
        }
    }
    @Test void rlsImmutableScopeAndLeaseLossFailClosed()throws Exception {
        var issued=tickets.issue(console(),scope(),"127.0.0.1");
        assertThat(tx.<Integer>execute(s->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT count(*) FROM integ_realtime_ticket",Integer.class);})).isEqualTo(1);
        assertThat(tx.<Integer>execute(s->{rls.establish(UUID.randomUUID(),project);return jdbc.queryForObject("SELECT count(*) FROM integ_realtime_ticket",Integer.class);})).isZero();
        assertThat(tx.<Integer>execute(s->{rls.establish(tenant,UUID.randomUUID());return jdbc.queryForObject("SELECT count(*) FROM integ_realtime_ticket",Integer.class);})).isZero();
        assertThatThrownBy(()->owner.update("UPDATE integ_realtime_ticket SET protocol='WS' WHERE id=?",issued.ticketId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->tx.execute(s->{rls.establish(tenant,project);jdbc.update("DELETE FROM integ_realtime_ticket WHERE id=?",issued.ticketId());return null;})).isInstanceOf(DataAccessException.class);
        redis.delete("quota:websocket:connections:{"+tenant+"}");
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode().code()).isEqualTo(80007));
        for(var c:tickets.candidates())maintenance.maintain(c);
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,issued.ticketId())).isEqualTo("CLOSED");
    }
    @Test void revokedKeyMaintenanceCommitsClosureAfterAuthorizationTransactionRollsBack()throws Exception {
        var identity=keyIdentity();var issued=tickets.issue(identity,scope(),"127.0.0.1");
        keys.revoke(tenant,project,account,Uuid7.generate(),identity.key());
        for(var c:tickets.candidates())maintenance.maintain(c);
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,issued.ticketId())).isEqualTo("CLOSED");
        assertThat(redis.opsForZSet().zCard("quota:websocket:connections:{"+tenant+"}")).isZero();
    }
    @Test void actualDatabaseExpiryRejectsPreviouslyUsableTicket()throws Exception {
        var id=console();Instant end=owner.queryForObject("SELECT clock_timestamp()+interval '7 seconds'",java.sql.Timestamp.class).toInstant();
        var limited=new RealtimeIdentity(id.kind(),id.tenant(),id.project(),id.generation(),id.subject(),id.account(),null,end);
        var issued=tickets.issue(limited,scope(),"127.0.0.1");
        assertThat(issued.expiresAt()).isEqualTo(end);
        tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(12)).until(()->owner.queryForObject("SELECT clock_timestamp()>=?",Boolean.class,java.sql.Timestamp.from(end)));
        assertThatThrownBy(()->tickets.authenticate(issued.credential(),RealtimeTicketRequest.Protocol.MQTT,"127.0.0.1")).isInstanceOf(BusinessException.class);
        for(var c:tickets.candidates())maintenance.maintain(c);
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,issued.ticketId())).isEqualTo("CLOSED");
    }
    @Test void expiryUsesDatabaseClockAndWrongModelNeverConsumesTicket()throws Exception {
        var request=scope();var id=console();var expiring=new RealtimeIdentity(id.kind(),id.tenant(),id.project(),id.generation(),id.subject(),id.account(),null,Instant.now().plusSeconds(1));
        assertThatThrownBy(()->tickets.issue(expiring,request,"127.0.0.1")).isInstanceOf(BusinessException.class);
        var wrong=new RealtimeTicketRequest(request.protocol(),request.eventTypes(),List.of(new com.things.link.device.application.RuntimeDeviceQuery(device,Uuid7.generate(),List.of("value"))));
        assertThatThrownBy(()->tickets.issue(id,wrong,"127.0.0.1")).isInstanceOf(BusinessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_ticket WHERE project_id=?",Integer.class,project)).isZero();
    }
}
