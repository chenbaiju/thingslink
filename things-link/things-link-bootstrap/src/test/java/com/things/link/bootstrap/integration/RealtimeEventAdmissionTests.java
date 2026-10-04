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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true"})
class RealtimeEventAdmissionTests extends RealtimeEventFixture {
    @Test void exactEnvelopeAndDuplicateNeverBackfillsLaterTicket()throws Exception{
        connect();var update=update(Uuid7.generate(),"1.12345678901234567890123456789");admission.accept(update);connect();admission.accept(update);
        assertThat(count("integ_realtime_event")).isEqualTo(1);assertThat(count("integ_realtime_delivery")).isEqualTo(1);
        var envelope=json.readTree(owner.queryForObject("SELECT envelope FROM integ_realtime_delivery WHERE project_id=?",String.class,project));
        assertThat(envelope.path("properties").propertyNames()).containsExactly("value");
        assertThat(envelope.path("properties").path("value").path("valueJson").asString()).isEqualTo("1.12345678901234567890123456789");
        assertThat(envelope.path("properties").path("value").path("reportedRevision").asString()).isEqualTo("9007199254740993");
    }
    @Test void rollbackAndConflictingIdentityPreserveOriginalPlan()throws Exception{
        connect();UUID event=Uuid7.generate();
        assertThatThrownBy(()->tx.execute(s->{admission.accept(update(event,"1"));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(count("integ_realtime_event")).isZero();assertThat(count("integ_realtime_delivery")).isZero();
        admission.accept(update(event,"1"));assertThatThrownBy(()->ingress.accept(update(event,"2"))).isInstanceOf(IllegalArgumentException.class).hasMessage("REALTIME_EVENT_ID_CONFLICT");
        assertThat(count("integ_realtime_event")).isEqualTo(1);assertThat(count("integ_realtime_delivery")).isEqualTo(1);
    }
    @Test void twelveConcurrentAdmissionsCommitOneFrozenPlan()throws Exception{
        connect();var update=update(Uuid7.generate(),"9007199254740993");
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<12;i++)futures.add(workers.submit(()->{start.await();admission.accept(update);return null;}));
            start.countDown();for(var future:futures)future.get(20,TimeUnit.SECONDS);
        }
        assertThat(count("integ_realtime_event")).isEqualTo(1);assertThat(count("integ_realtime_delivery")).isEqualTo(1);
    }
    @Test void payloadAndQueueBoundsCloseWithoutTruncatingFacts()throws Exception{
        var large=connect();admission.accept(update(Uuid7.generate(),"1".repeat(32768)));
        assertThat(count("integ_realtime_delivery")).isZero();assertThat(owner.queryForObject("SELECT closed_reason FROM integ_realtime_ticket WHERE id=?",String.class,large.ticketId())).isEqualTo("RESYNC_REQUIRED");
        var bounded=connect();for(int i=0;i<257;i++)admission.accept(update(Uuid7.generate(),Integer.toString(i)));
        assertThat(count("integ_realtime_delivery")).isEqualTo(256);
        assertThat(owner.queryForObject("SELECT closed_reason FROM integ_realtime_ticket WHERE id=?",String.class,bounded.ticketId())).isEqualTo("RESYNC_REQUIRED");
    }
    @Test void rlsAndImmutablePayloadAndWrongOwnerStayIsolated()throws Exception{
        connect();var original=update(Uuid7.generate(),"1");admission.accept(original);
        assertThat(tx.<Integer>execute(s->{rls.establish(tenant,UUID.randomUUID());return jdbc.queryForObject("SELECT count(*) FROM integ_realtime_delivery",Integer.class);})).isZero();
        assertThat(tx.<Integer>execute(s->{rls.establish(UUID.randomUUID(),project);return jdbc.queryForObject("SELECT count(*) FROM integ_realtime_event",Integer.class);})).isZero();
        assertThatThrownBy(()->owner.update("UPDATE integ_realtime_delivery SET envelope='{}' WHERE project_id=?",project)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->tx.execute(s->{rls.establish(tenant,project);jdbc.update("DELETE FROM integ_realtime_delivery WHERE project_id=?",project);return null;})).isInstanceOf(DataAccessException.class);
        var wrong=new DeviceRealtimeUpdate(Uuid7.generate(),UUID.randomUUID(),project,device,model,"1.0.0",original.occurredAt(),0,original.traceId(),original.propertiesJson(),original.propertyDataTypes());
        admission.accept(wrong);assertThat(count("integ_realtime_event")).isEqualTo(1);
    }
    @Test void readableProjectLockBlocksLifecycleChangeUntilAdmissionCommits()throws Exception{
        var ticket=connect();var admitted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var first=workers.submit(()->tx.execute(status->{admission.accept(update(Uuid7.generate(),"1"));admitted.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var change=workers.submit(()->owner.update("UPDATE sys_project SET status='ARCHIVED',lifecycle_generation=lifecycle_generation+1 WHERE id=?",project));
            try{org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'UPDATE sys_project SET status=%lifecycle_generation%')",Boolean.class));assertThat(change.isDone()).isFalse();}
            finally{release.countDown();}
            first.get(10,TimeUnit.SECONDS);change.get(10,TimeUnit.SECONDS);
        }
        admission.accept(update(Uuid7.generate(),"2"));
        assertThat(count("integ_realtime_delivery")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,ticket.ticketId())).isEqualTo("CLOSED");
    }
}
