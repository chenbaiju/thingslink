package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.RealtimeTicketRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true"})
class RealtimeOutboundAdmissionTests extends RealtimeEventFixture {
    @Autowired RealtimeOutboundAdmission outbound;
    UUID app;
    @AfterEach void removeApp(){if(app!=null){owner.update("DELETE FROM app_user_device WHERE app_user_id=?",app);owner.update("DELETE FROM app_user_role WHERE app_user_id=?",app);owner.update("DELETE FROM app_user WHERE id=?",app);}}
    RealtimeTicketRepository.Candidate candidate(RealtimeTicketService.Issued ticket){return new RealtimeTicketRepository.Candidate(tenant,project,ticket.ticketId());}
    int send(RealtimeTicketService.Issued ticket,AtomicInteger sent){return outbound.withAdmission(candidate(ticket),RealtimeTicketRequest.Protocol.MQTT,t->sent.incrementAndGet());}
    @Test void productionKeyRevocationWaitsForAdmittedFrameThenRejectsNewFrame()throws Exception{
        var ticket=connect();UUID key=authentication.authenticate(secret,"127.0.0.1").keyId();var sent=new AtomicInteger();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=workers.submit(()->outbound.withAdmission(candidate(ticket),RealtimeTicketRequest.Protocol.MQTT,t->{entered.countDown();await(release);return sent.incrementAndGet();}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=workers.submit(()->keys.revoke(tenant,project,account,Uuid7.generate(),key));
            try{waitLocks(1);assertThat(revoke.isDone()).isFalse();}finally{release.countDown();}
            first.get(10,TimeUnit.SECONDS);revoke.get(10,TimeUnit.SECONDS);
        }
        assertThatThrownBy(()->send(ticket,sent)).isInstanceOf(BusinessException.class);assertThat(sent).hasValue(1);
    }
    @Test void memberAndAccountRowsCannotChangeBetweenAuthorizationAndHandoff()throws Exception{
        var ticket=connect();heldMutations(ticket,List.of(()->owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,account),()->owner.update("UPDATE sys_account SET status='DISABLED' WHERE id=?",account)));
        assertThatThrownBy(()->send(ticket,new AtomicInteger())).isInstanceOf(BusinessException.class);
    }
    @Test void appUserRoleAndBindingAreHeldUntilFrameHandoff()throws Exception{
        app=Uuid7.generate();owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'unused')",app,tenant,"guard"+app);
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'OBSERVER')",Uuid7.generate(),tenant,project,app);
        owner.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role) VALUES (?,?,?,?,?,'READ_ONLY')",Uuid7.generate(),tenant,project,app,device);
        var scope=new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol","MQTT","eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value"))))));
        var ticket=tickets.issue(new RealtimeIdentity(RealtimeIdentity.Kind.APP,tenant,project,0,app,null,null,Instant.now().plusSeconds(120)),scope,"127.0.0.1");tickets.connectMqtt(ticket.credential(),"127.0.0.1");
        heldMutations(ticket,List.of(()->owner.update("UPDATE app_user SET status='LOCKED' WHERE id=?",app),()->owner.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?",app),()->owner.update("UPDATE app_user_device SET status='CLOSED' WHERE app_user_id=?",app)));
        assertThatThrownBy(()->send(ticket,new AtomicInteger())).isInstanceOf(BusinessException.class);
    }
    @Test void deviceModelAndTicketCloseCannotOvertakeAdmittedFrame()throws Exception{
        var ticket=connect();UUID next=Uuid7.generate();
        owner.update("""
            INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
            SELECT ?,tenant_id,project_id,device_type_id,'1.0.1',1,0,1,'PATCH',schema_profile,model_snapshot,schema_digest,digest_algorithm FROM dev_thing_model_version WHERE id=?
            """,next,model);
        heldMutations(ticket,List.of(()->owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",next,device),()->owner.update("UPDATE integ_realtime_ticket SET status='CLOSED',closed_reason='TEST' WHERE id=?",ticket.ticketId())));
        assertThatThrownBy(()->send(ticket,new AtomicInteger())).isInstanceOf(BusinessException.class);
    }
    @Test void revocationCommittedBeforeWaitingAdmissionNeverCallsSender()throws Exception{
        var ticket=connect();UUID key=authentication.authenticate(secret,"127.0.0.1").keyId();var sent=new AtomicInteger();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var workers=Executors.newVirtualThreadPerTaskExecutor()){
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("UPDATE integ_api_key SET status='REVOKED',revoked_at=clock_timestamp(),revision=revision+1 WHERE id=?")){sql.setObject(1,key);sql.executeUpdate();}
            var waiting=workers.submit(()->{try{send(ticket,sent);return (RuntimeException)null;}catch(RuntimeException failure){return failure;}});
            try{waitLocks(1);assertThat(waiting.isDone()).isFalse();}finally{connection.commit();}
            assertThat(waiting.get(10,TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);
        }
        assertThat(sent).hasValue(0);
    }
    @Test void databaseAndRedisFailureNeverInvokeSender()throws Exception{
        var ticket=connect();var sent=new AtomicInteger();
        owner.execute("REVOKE SELECT ON integ_realtime_ticket FROM thingslink_app");
        try{assertThatThrownBy(()->send(ticket,sent)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{owner.execute("GRANT SELECT ON integ_realtime_ticket TO thingslink_app");}
        redis.delete("quota:websocket:connections:{"+tenant+"}");
        assertThatThrownBy(()->send(ticket,sent)).isInstanceOf(BusinessException.class);assertThat(sent).hasValue(0);
    }
    @Test void protocolAndTenantMismatchNeverInvokeSender()throws Exception{
        var ticket=connect();var sent=new AtomicInteger();
        assertThatThrownBy(()->outbound.withAdmission(candidate(ticket),RealtimeTicketRequest.Protocol.WS,t->sent.incrementAndGet())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->outbound.withAdmission(new RealtimeTicketRepository.Candidate(UUID.randomUUID(),project,ticket.ticketId()),RealtimeTicketRequest.Protocol.MQTT,t->sent.incrementAndGet())).isInstanceOf(BusinessException.class);
        assertThat(sent).hasValue(0);
    }
    void heldMutations(RealtimeTicketService.Issued ticket,List<Runnable> mutations)throws Exception{
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var frame=workers.submit(()->outbound.withAdmission(candidate(ticket),RealtimeTicketRequest.Protocol.MQTT,t->{entered.countDown();await(release);return true;}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var changes=new ArrayList<Future<?>>();for(var mutation:mutations)changes.add(workers.submit(mutation));
            try{waitLocks(mutations.size());for(var change:changes)assertThat(change.isDone()).isFalse();}finally{release.countDown();}
            frame.get(10,TimeUnit.SECONDS);for(var change:changes)change.get(10,TimeUnit.SECONDS);
        }
    }
    void waitLocks(int number){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(()->owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>=number);}
    static void await(CountDownLatch release){try{if(!release.await(9,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}
}
