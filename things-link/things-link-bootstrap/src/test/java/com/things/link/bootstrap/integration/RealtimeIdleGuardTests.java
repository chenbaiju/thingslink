package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.infrastructure.RealtimeMqttWatchdog;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RealtimeIdleGuardTests extends RealtimeBrokerFixture {
    UUID appUser;
    @AfterEach void removeApp(){if(appUser!=null){owner.update("DELETE FROM app_user_device WHERE app_user_id=?",appUser);owner.update("DELETE FROM app_user_role WHERE app_user_id=?",appUser);owner.update("DELETE FROM app_user WHERE id=?",appUser);appUser=null;}}
    @Autowired RealtimeMqttIdleGuard guard;
    @Autowired RealtimeMqttWatchdog watchdog;
    @Test void realWatchdogClosesIdleRevokedMemberWithoutAnyPropertyReport()throws Exception{
        var t=issue(120);try(var w=wire(t,4,true)){
            connected(w);subscribed(w,RealtimeMqttPublisher.topic(t.ticketId()));
            assertThat(realSessions.active()).extracting(RealtimeMqttSessions.Session::ticket).contains(t.ticketId());
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,account);
            watchdog.tick();w.socket.setSoTimeout(10000);assertThatThrownBy(w::read).isInstanceOf(java.io.EOFException.class);
            assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,t.ticketId())).isEqualTo("CLOSED");assertThat(count("integ_realtime_event")).isZero();
        }
    }
    @Test void rejectedManagementCallRetainsActualBrokerRetryFact()throws Exception{
        var t=issue(120);try(var w=wire(t,4,true)){
            connected(w);var session=realSessions.active().stream().filter(v->v.ticket().equals(t.ticketId())).findFirst().orElseThrow();
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,account);
            try(var denied=new com.things.link.integration.infrastructure.EmqxRealtimeSessions(apiBase(),"tc-app-dev-publisher","dev-only-app-publisher-secret-do-not-use-in-production")){
                doAnswer(i->denied.disconnect(t.ticketId())).when(sessions).disconnect(t.ticketId());assertThatThrownBy(()->guard.inspect(session)).hasMessage("REALTIME_BROKER_DISCONNECT_UNCONFIRMED");
                assertThat(realSessions.active()).extracting(RealtimeMqttSessions.Session::ticket).contains(t.ticketId());
            }
            doAnswer(i->realSessions.disconnect(t.ticketId())).when(sessions).disconnect(t.ticketId());guard.inspect(session);w.socket.setSoTimeout(5000);assertThatThrownBy(w::read).isInstanceOf(java.io.EOFException.class);
            assertThat(realSessions.disconnect(t.ticketId())).isTrue();
        }
    }
    @Test void databaseProofFailureStillClosesPhysicalConnection()throws Exception{
        var t=issue(120);try(var w=wire(t,4,true)){
            connected(w);var session=realSessions.active().stream().filter(v->v.ticket().equals(t.ticketId())).findFirst().orElseThrow();
            owner.execute("REVOKE EXECUTE ON FUNCTION integ_connected_realtime_ticket(uuid) FROM thingslink_app");
            try{guard.inspect(session);assertThatThrownBy(w::read).isInstanceOf(java.io.EOFException.class);}finally{owner.execute("GRANT EXECUTE ON FUNCTION integ_connected_realtime_ticket(uuid) TO thingslink_app");}
            assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,t.ticketId())).isEqualTo("CONNECTED");
        }
    }
    @Test void lostRedisLeaseAndAlreadyClosedTicketAreDisconnected()throws Exception{
        for(boolean close:List.of(false,true)){
            var t=issue(120);try(var w=wire(t,4,true)){
                connected(w);var session=realSessions.active().stream().filter(v->v.ticket().equals(t.ticketId())).findFirst().orElseThrow();
                if(close)owner.update("UPDATE integ_realtime_ticket SET status='CLOSED',closed_reason='TEST' WHERE id=?",t.ticketId());else redis.delete("quota:websocket:connections:{"+tenant+"}");
                guard.inspect(session);assertThatThrownBy(w::read).isInstanceOf(java.io.EOFException.class);
            }
        }
    }
    @Test void idleAppBindingRemovalClosesActualBrokerConnection()throws Exception{
        appUser=Uuid7.generate();owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'unused')",appUser,tenant,"idle"+appUser);
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'OBSERVER')",Uuid7.generate(),tenant,project,appUser);
        owner.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role) VALUES (?,?,?,?,?,'READ_ONLY')",Uuid7.generate(),tenant,project,appUser,device);
        var original=issue(120);var scope=new RealtimeTicketParser().parse(owner.queryForObject("SELECT scope_json::text FROM integ_realtime_ticket WHERE id=?",String.class,original.ticketId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var t=tickets.issue(new RealtimeIdentity(RealtimeIdentity.Kind.APP,tenant,project,0,appUser,null,null,Instant.now().plusSeconds(120)),scope,"127.0.0.1");
        try(var w=wire(t,5,true)){connected(w);subscribed(w,RealtimeMqttPublisher.topic(t.ticketId()));owner.update("UPDATE app_user_device SET status='CLOSED' WHERE app_user_id=?",appUser);var session=realSessions.active().stream().filter(v->v.ticket().equals(t.ticketId())).findFirst().orElseThrow();guard.inspect(session);var disconnected=w.read();assertThat(disconnected.header()).isEqualTo(0xE0);}
    }
    @Test void currentKeyRevocationClosesActualBrokerConnection()throws Exception{
        String key=keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("broker",List.of("device:read"),List.of("0.0.0.0/0","::/0"),Instant.now().plusSeconds(120))).secret();
        var original=issue(120);var scope=tx.execute(s->{rls.establish(tenant,project);return new RealtimeTicketParser().parse(owner.queryForObject("SELECT scope_json::text FROM integ_realtime_ticket WHERE id=?",String.class,original.ticketId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));});
        var principal=authentication.authenticate(key,"127.0.0.1");var t=tickets.issue(RealtimeIdentity.fromKey(principal),scope,"127.0.0.1");
        try(var w=wire(t,4,true)){connected(w);subscribed(w,RealtimeMqttPublisher.topic(t.ticketId()));keys.revoke(tenant,project,account,Uuid7.generate(),principal.keyId());var session=realSessions.active().stream().filter(v->v.ticket().equals(t.ticketId())).findFirst().orElseThrow();guard.inspect(session);assertThatThrownBy(w::read).isInstanceOf(java.io.EOFException.class);}
    }
}
