package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true"})
class RealtimeDeliveryTests extends RealtimeEventFixture {
    @Autowired RealtimeDeliveryState state;
    @Autowired RealtimeDeliveryDispatcher dispatcher;
    @Autowired RealtimeDeliveryRepository deliveries;
    @MockitoBean RealtimeMqttPublisher publisher;
    @BeforeEach void publisherReady(){when(publisher.configured()).thenReturn(true);when(publisher.publish(any(),any(),any())).thenReturn(RealtimeMqttPublisher.Outcome.BROKER_ACCEPTED);}
    RealtimeDeliveryRepository.Candidate prepare()throws Exception{connect();admission.accept(update(Uuid7.generate(),"1.123456789012345678901"));return candidate();}
    RealtimeDeliveryRepository.Candidate candidate(){return new RealtimeDeliveryRepository.Candidate(tenant,project,owner.queryForObject("SELECT id FROM integ_realtime_delivery WHERE project_id=?",UUID.class,project));}
    String status(){return owner.queryForObject("SELECT status FROM integ_realtime_delivery WHERE project_id=?",String.class,project);}
    int attempts(){return owner.queryForObject("SELECT attempts FROM integ_realtime_delivery WHERE project_id=?",Integer.class,project);}
    void due(){owner.update("UPDATE integ_realtime_delivery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE project_id=?",project);}
    void expire(){owner.update("UPDATE integ_realtime_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE project_id=?",project);}
    @Test void acceptedExactlyOnceAndTerminalImmutable()throws Exception{
        var c=prepare();dispatcher.dispatch(c);dispatcher.dispatch(c);
        assertThat(status()).isEqualTo("DELIVERED");assertThat(attempts()).isEqualTo(1);verify(publisher,times(1)).publish(any(),any(),eq(Duration.ofSeconds(5)));
        assertThatThrownBy(()->owner.update("UPDATE integ_realtime_delivery SET status='READY',finished_at=NULL WHERE project_id=?",project)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void twentyClaimsProduceOnlyOneLiveToken()throws Exception{
        var c=prepare();var ready=new CountDownLatch(1);int claims=0;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<20;i++)futures.add(pool.submit(()->{ready.await();return state.claim(c,true).isPresent();}));
            ready.countDown();for(var f:futures)if(f.get(15,TimeUnit.SECONDS))claims++;
        }
        assertThat(claims).isEqualTo(1);assertThat(attempts()).isEqualTo(1);assertThat(state.candidates()).doesNotContain(c);
    }
    @Test void fiveAttemptsUseBoundedBackoffAndStop()throws Exception{
        var c=prepare();when(publisher.publish(any(),any(),any())).thenReturn(RealtimeMqttPublisher.Outcome.REJECTED);
        for(int i=1;i<=5;i++){
            dispatcher.dispatch(c);assertThat(attempts()).isEqualTo(i);
            if(i<5){assertThat(status()).isEqualTo("READY");double remaining=owner.queryForObject("SELECT extract(epoch from next_attempt_at-clock_timestamp()) FROM integ_realtime_delivery WHERE project_id=?",Double.class,project);assertThat(remaining).isBetween((double)(1<<(i-1))-.8,(double)(1<<(i-1)));assertThat(state.claim(c,true)).isEmpty();due();}
        }
        assertThat(status()).isEqualTo("FAILED");dispatcher.dispatch(c);verify(publisher,times(5)).publish(any(),any(),any());
    }
    @Test void expiredLeaseCanRecoverButOldTokenCannotSendOrCloseTicket()throws Exception{
        var c=prepare();var old=state.claim(c,true).orElseThrow();expire();var fresh=state.claim(c,true).orElseThrow();
        assertThat(fresh.token()).isNotEqualTo(old.token());dispatcher.send(c,old);state.cancel(c,old,"RESYNC_REQUIRED");
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,old.ticket())).isEqualTo("CONNECTED");
        verify(publisher,never()).publish(any(),any(),any());dispatcher.send(c,fresh);assertThat(status()).isEqualTo("DELIVERED");assertThat(attempts()).isEqualTo(2);
    }
    @Test void fifthCrashedAttemptTerminatesWithoutSixthSend()throws Exception{
        var c=prepare();for(int i=0;i<5;i++){assertThat(state.claim(c,true)).isPresent();expire();}
        assertThat(state.claim(c,true)).isEmpty();assertThat(status()).isEqualTo("FAILED");assertThat(attempts()).isEqualTo(5);verify(publisher,never()).publish(any(),any(),any());
    }
    @Test void revokedIdentityCancelsAndInfrastructureFaultKeepsRecoverableLease()throws Exception{
        var c=prepare();var claim=state.claim(c,true).orElseThrow();
        owner.execute("REVOKE SELECT ON integ_api_key FROM thingslink_app");
        try{assertThatThrownBy(()->dispatcher.send(c,claim)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{owner.execute("GRANT SELECT ON integ_api_key TO thingslink_app");}
        assertThat(status()).isEqualTo("IN_FLIGHT");verify(publisher,never()).publish(any(),any(),any());
        keys.revoke(tenant,project,account,Uuid7.generate(),authentication.authenticate(secret,"127.0.0.1").keyId());
        dispatcher.send(c,claim);assertThat(status()).isEqualTo("CANCELLED");verify(publisher,never()).publish(any(),any(),any());
    }
    @Test void unknownLocalCommitAfterBrokerHandoffReplaysSameEnvelope()throws Exception{
        var c=prepare();var sent=new ArrayList<byte[]>();
        when(publisher.publish(any(),any(),any())).thenAnswer(invocation->{sent.add(invocation.getArgument(1));if(sent.size()==1)throw new org.springframework.transaction.TransactionSystemException("lost commit acknowledgement");return RealtimeMqttPublisher.Outcome.BROKER_ACCEPTED;});
        assertThatThrownBy(()->dispatcher.dispatch(c)).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(status()).isEqualTo("IN_FLIGHT");expire();dispatcher.dispatch(c);
        assertThat(status()).isEqualTo("DELIVERED");assertThat(sent).hasSize(2);assertThat(sent.get(1)).containsExactly(sent.getFirst());
    }
    @Test void missingPublisherConfigurationDoesNotBurnAttemptsAndClosedTicketsStillDrain()throws Exception{
        var c=prepare();when(publisher.configured()).thenReturn(false);dispatcher.dispatch(c);assertThat(attempts()).isZero();
        owner.update("UPDATE integ_realtime_ticket SET status='CLOSED',closed_reason='TEST' WHERE project_id=?",project);
        dispatcher.dispatch(c);assertThat(status()).isEqualTo("CANCELLED");assertThat(attempts()).isZero();
    }
    @Test void wholeMqttPacketLimitIncludesTopicAndHeaders()throws Exception{
        var t=connect();String value="1";
        // Freeze an envelope just below the JSON limit but above the whole packet limit.
        var event=update(Uuid7.generate(),value);admission.accept(event);
        String envelope=owner.queryForObject("SELECT envelope FROM integ_realtime_delivery WHERE project_id=?",String.class,project);
        owner.update("DELETE FROM integ_realtime_delivery WHERE project_id=?",project);owner.update("DELETE FROM integ_realtime_event WHERE project_id=?",project);
        admission.accept(update(event.messageId(),"1".repeat(32760-envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1)));
        assertThat(count("integ_realtime_delivery")).isEqualTo(1);dispatcher.dispatch(candidate());assertThat(status()).isEqualTo("CANCELLED");
        assertThat(owner.queryForObject("SELECT closed_reason FROM integ_realtime_ticket WHERE id=?",String.class,t.ticketId())).isEqualTo("RESYNC_REQUIRED");verify(publisher,never()).publish(any(),any(),any());
    }
}
