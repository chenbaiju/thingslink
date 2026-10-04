package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.infrastructure.websocket.PublicRealtimeWsHandler;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true"})
class PublicRealtimeWsBoundaryTests extends RealtimeEventFixture {
    @Autowired PublicRealtimeWsHandler handler;
    final List<PublicRealtimeWsTests.Socket> sockets=new ArrayList<>();UUID app;
    @AfterEach void cleanup()throws Exception{
        for(var socket:sockets)try{socket.close();}catch(RuntimeException ignored){}
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(12)).until(()->{handler.tick();return owner.queryForObject("SELECT count(*) FROM integ_realtime_ticket WHERE project_id=? AND status='CONNECTED'",Integer.class,project)==0;});
        if(app!=null){owner.update("DELETE FROM app_user_device WHERE app_user_id=?",app);owner.update("DELETE FROM app_user_role WHERE app_user_id=?",app);owner.update("DELETE FROM app_user WHERE id=?",app);}
    }
    RealtimeIdentity console(int seconds){return new RealtimeIdentity(RealtimeIdentity.Kind.CONSOLE,tenant,project,0,account,account,null,Instant.now().plusSeconds(seconds));}
    RealtimeTicketService.Issued issue(RealtimeIdentity identity)throws Exception{return tickets.issue(identity,new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol","WS","eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value")))))),"127.0.0.1");}
    PublicRealtimeWsTests.Socket open(RealtimeTicketService.Issued ticket)throws Exception{var socket=new PublicRealtimeWsTests.Socket();sockets.add(socket);client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).subprotocols("tc-realtime-v1",ticket.credential()).buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/open/v1/realtime/ws"),socket).get(15,TimeUnit.SECONDS);assertThat(socket.next()).contains("READY");return socket;}
    @Test void actualExpiryClosesWithoutIncomingEvents()throws Exception{var s=open(issue(console(8)));assertThat(s.closed.get(16,TimeUnit.SECONDS)).isEqualTo(1008);}
    @Test void keyRevocationClosesExistingWs()throws Exception{var identity=RealtimeIdentity.fromKey(authentication.authenticate(secret,"127.0.0.1"));var s=open(issue(identity));keys.revoke(tenant,project,account,Uuid7.generate(),identity.key());assertThat(s.closed.get(12,TimeUnit.SECONDS)).isEqualTo(1008);}
    @Test void appBindingRevocationClosesExistingWs()throws Exception{
        app=Uuid7.generate();owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'unused')",app,tenant,"ws"+app);
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'OBSERVER')",Uuid7.generate(),tenant,project,app);
        owner.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role) VALUES (?,?,?,?,?,'READ_ONLY')",Uuid7.generate(),tenant,project,app,device);
        var s=open(issue(new RealtimeIdentity(RealtimeIdentity.Kind.APP,tenant,project,0,app,null,null,Instant.now().plusSeconds(120))));owner.update("UPDATE app_user_device SET status='CLOSED' WHERE app_user_id=?",app);assertThat(s.closed.get(12,TimeUnit.SECONDS)).isEqualTo(1008);
    }
    @Test void databasePrivilegeFailureCloses1011AndCleanupRecovers()throws Exception{
        var ticket=issue(console(120));var s=open(ticket);owner.execute("REVOKE SELECT ON integ_realtime_ticket FROM thingslink_app");try{assertThat(s.closed.get(12,TimeUnit.SECONDS)).isEqualTo(1011);}finally{owner.execute("GRANT SELECT ON integ_realtime_ticket TO thingslink_app");}
    }
    @Test void emptyOriginAllowlistRejectsEvenSameOriginAndWrongProtocolOrder()throws Exception{
        var ticket=issue(console(120));String uri="ws://127.0.0.1:"+port+"/api/open/v1/realtime/ws";
        assertThatThrownBy(()->client.newWebSocketBuilder().header("Origin","http://127.0.0.1:"+port).subprotocols("tc-realtime-v1",ticket.credential()).buildAsync(URI.create(uri),new PublicRealtimeWsTests.Socket()).get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(WebSocketHandshakeException.class);
        assertThatThrownBy(()->client.newWebSocketBuilder().subprotocols(ticket.credential(),"tc-realtime-v1").buildAsync(URI.create(uri),new PublicRealtimeWsTests.Socket()).get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(WebSocketHandshakeException.class);
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,ticket.ticketId())).isEqualTo("RESERVED");open(ticket);
    }
}
