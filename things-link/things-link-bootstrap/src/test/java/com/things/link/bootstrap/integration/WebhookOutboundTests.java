package com.things.link.bootstrap.integration;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.notification.delivery.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** True PostgreSQL authority locks and true pinned TLS, including commit uncertainty after remote acceptance. */
@Import(WebhookOutboundTests.ReceiverConfiguration.class)
class WebhookOutboundTests extends WebhookFixture {
    @TestConfiguration static class ReceiverConfiguration {
        @Bean(destroyMethod="close") WebhookTlsReceiver webhookReceiver()throws Exception{return new WebhookTlsReceiver();}
        @Bean @Primary PinnedWebhookTransport testWebhookTransport(WebhookTlsReceiver receiver){return receiver.transport();}
    }
    @Autowired WebhookTlsReceiver receiver;
    @Autowired WebhookEventAdmission admission;
    @Autowired WebhookDeliveryState state;
    @Autowired WebhookOutboundDelivery outbound;
    @Autowired WebhookDeliveryDispatcher dispatcher;
    @Autowired WebhookDeliveryRepository deliveries;
    UUID neighbor;
    @BeforeEach void receiverReset(){receiver.reset();}
    @AfterEach void extraCleanup(){receiver.release.countDown();owner.execute("DROP TRIGGER IF EXISTS test_webhook_commit_failure ON integ_webhook_delivery");owner.execute("DROP FUNCTION IF EXISTS test_webhook_commit_failure()");
        if(neighbor!=null)new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).execute(s->{for(String table:List.of("integ_webhook_attempt","integ_webhook_delivery","integ_webhook_conflict","integ_webhook_event","integ_webhook_ingress_state","integ_webhook_revision","integ_webhook_subscription","integ_webhook_operation"))owner.update("DELETE FROM "+table+" WHERE project_id=?",neighbor);owner.update("DELETE FROM sys_project_member WHERE project_id=?",neighbor);owner.update("DELETE FROM sys_project WHERE id=?",neighbor);return null;});
    }
    @Override WebhookSubscriptionService.Spec spec(){return new WebhookSubscriptionService.Spec("receiver",receiver.target(),List.of("device.online"),List.of());}
    Instant now(){return owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();}
    PublicWebhookEvent event(UUID scope){var time=now();return new PublicWebhookEvent(Uuid7.generate(),"device.online",tenant,scope,0,"device",device,device,time,time,null,"{\"valueJson\":\"9007199254740993\",\"text\":\"中文\"}");}
    WebhookDeliveryRepository.Candidate enqueue(){admission.accept(event(project));return new WebhookDeliveryRepository.Candidate(tenant,project,owner.queryForObject("SELECT id FROM integ_webhook_delivery WHERE project_id=? ORDER BY created_at DESC LIMIT 1",UUID.class,project));}
    WebhookDeliveryRepository.Delivery read(WebhookDeliveryRepository.Candidate c){return tx.execute(s->{rls.establish(c.tenant(),c.project());return deliveries.find(c.id()).orElseThrow();});}
    void expire(WebhookDeliveryRepository.Candidate c){owner.update("UPDATE integ_webhook_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",c.id());}
    void waitLocks(int n){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>=n);}
    @Test void committedSuccessfulDeliveryHasExactSignedBodyAndAttemptReason()throws Exception{
        var issued=create(Uuid7.generate());var sub=issued.subscription();var c=enqueue();dispatcher.dispatch(c);assertThat(read(c).status()).isEqualTo("SUCCEEDED");assertThat(receiver.received).hasSize(1);
        var wire=receiver.received.getFirst();String body=new String(wire.body(),java.nio.charset.StandardCharsets.UTF_8);assertThat(body).contains("9007199254740993","中文",c.id().toString());
        byte[] secret=Base64.getDecoder().decode(issued.signingSecret());var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"));String canonical=wire.headers().getFirst("X-ThingsLink-Timestamp")+"\n"+wire.headers().getFirst("X-ThingsLink-Nonce")+"\n"+c.id()+"\n"+body;
        assertThat(wire.headers().getFirst("X-ThingsLink-Signature")).isEqualTo("v1="+HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(owner.queryForMap("SELECT result,reason,http_status FROM integ_webhook_attempt WHERE delivery_id=?",c.id())).containsEntry("result","SUCCEEDED").containsEntry("reason","HTTP_200").containsEntry("http_status",200);dispatcher.dispatch(c);assertThat(receiver.received).hasSize(1);
    }
    @Test void inactiveAuthorBeforeClaimCancelsWithoutAttempt(){create(Uuid7.generate());var c=enqueue();owner.update("UPDATE sys_account SET status='DISABLED' WHERE id=?",account);dispatcher.dispatch(c);assertThat(read(c).status()).isEqualTo("CANCELLED");assertThat(rows("integ_webhook_attempt")).isZero();assertThat(receiver.received).isEmpty();}
    @Test void demotedAuthorAfterClaimCancelsStartedAttempt(){create(Uuid7.generate());var c=enqueue();var claim=state.claim(c,true).orElseThrow();owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",project,account);outbound.send(c,claim.token());assertThat(read(c).status()).isEqualTo("CANCELLED");assertThat(owner.queryForObject("SELECT result FROM integ_webhook_attempt WHERE delivery_id=?",String.class,c.id())).isEqualTo("CANCELLED");assertThat(receiver.received).isEmpty();}
    @Test void pauseRevisionAfterClaimCancelsWithoutNetwork(){var sub=create(Uuid7.generate()).subscription();var c=enqueue();var claim=state.claim(c,true).orElseThrow();change(sub.id(),1,"PAUSE");outbound.send(c,claim.token());assertThat(read(c).status()).isEqualTo("CANCELLED");assertThat(receiver.received).isEmpty();}
    @Test void leaseTokenAndRemainingBudgetFenceEverySocket(){create(Uuid7.generate());var c=enqueue();var claim=state.claim(c,true).orElseThrow();assertThat(outbound.send(new WebhookDeliveryRepository.Candidate(UUID.randomUUID(),project,c.id()),claim.token())).isFalse();assertThat(outbound.send(c,UUID.randomUUID())).isFalse();owner.update("UPDATE integ_webhook_delivery SET lease_until=clock_timestamp()+interval '2 seconds' WHERE id=?",c.id());assertThat(outbound.send(c,claim.token())).isFalse();expire(c);assertThat(outbound.send(c,claim.token())).isFalse();var next=state.claim(c,true).orElseThrow();assertThat(outbound.send(c,claim.token())).isFalse();assertThat(receiver.received).isEmpty();assertThat(outbound.send(c,next.token())).isTrue();assertThat(receiver.received).hasSize(1);}
    @Test void archivedProjectRejectsBeforeSending(){create(Uuid7.generate());var c=enqueue();var claim=state.claim(c,true).orElseThrow();owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);outbound.send(c,claim.token());assertThat(read(c).status()).isEqualTo("CANCELLED");assertThat(receiver.received).isEmpty();}
    @Test void changedProjectGenerationCancelsOldAcceptedPlan(){create(Uuid7.generate());var c=enqueue();var claim=state.claim(c,true).orElseThrow();owner.update("UPDATE sys_project SET lifecycle_generation=lifecycle_generation+1 WHERE id=?",project);outbound.send(c,claim.token());assertThat(read(c).status()).isEqualTo("CANCELLED");assertThat(receiver.received).isEmpty();}
    @Test void productionPauseCannotOvertakeAdmittedHttpAndCommit()throws Exception{
        var sub=create(Uuid7.generate()).subscription();var c=enqueue();receiver.release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var send=pool.submit(()->dispatcher.dispatch(c));assertThat(receiver.entered.await(4,TimeUnit.SECONDS)).isTrue();var pause=pool.submit(()->change(sub.id(),1,"PAUSE"));try{waitLocks(1);assertThat(pause.isDone()).isFalse();}finally{receiver.release.countDown();}send.get(10,TimeUnit.SECONDS);pause.get(10,TimeUnit.SECONDS);}
        assertThat(read(c).status()).isEqualTo("SUCCEEDED");assertThat(receiver.received).hasSize(1);
    }
    @Test void authorAccountAndMemberLocksCoverActualHttp()throws Exception{
        create(Uuid7.generate());var c=enqueue();receiver.release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var send=pool.submit(()->dispatcher.dispatch(c));assertThat(receiver.entered.await(4,TimeUnit.SECONDS)).isTrue();var disable=pool.submit(()->owner.update("UPDATE sys_account SET status='DISABLED' WHERE id=?",account));var demote=pool.submit(()->owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",project,account));try{waitLocks(2);assertThat(disable.isDone()).isFalse();assertThat(demote.isDone()).isFalse();}finally{receiver.release.countDown();}send.get(10,TimeUnit.SECONDS);disable.get(10,TimeUnit.SECONDS);demote.get(10,TimeUnit.SECONDS);}
        assertThat(read(c).status()).isEqualTo("SUCCEEDED");assertThat(receiver.received).hasSize(1);
    }
    @Test void authorDisabledBeforeWaitingAdmissionNeverSends()throws Exception{
        create(Uuid7.generate());var c=enqueue();var claim=state.claim(c,true).orElseThrow();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var pool=Executors.newVirtualThreadPerTaskExecutor()){
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("UPDATE sys_account SET status='DISABLED' WHERE id=?")){sql.setObject(1,account);sql.executeUpdate();}var send=pool.submit(()->outbound.send(c,claim.token()));try{waitLocks(1);assertThat(send.isDone()).isFalse();}finally{connection.commit();}send.get(10,TimeUnit.SECONDS);
        }assertThat(read(c).status()).isEqualTo("CANCELLED");assertThat(receiver.received).isEmpty();
    }
    @Test void databaseFailurePreservesCommittedStartAndNeverClaimsSuccess(){create(Uuid7.generate());var c=enqueue();var claim=state.claim(c,true).orElseThrow();owner.execute("REVOKE SELECT ON integ_webhook_subscription FROM thingslink_app");try{assertThatThrownBy(()->outbound.send(c,claim.token())).isInstanceOf(DataAccessException.class);}finally{owner.execute("GRANT SELECT ON integ_webhook_subscription TO thingslink_app");}assertThat(read(c).status()).isEqualTo("IN_FLIGHT");assertThat(owner.queryForObject("SELECT result FROM integ_webhook_attempt WHERE delivery_id=?",String.class,c.id())).isEqualTo("STARTED");assertThat(receiver.received).isEmpty();}
    @Test void remoteAcceptedButCommitFailedRecoversSameIdentityAndExactBody()throws Exception{
        create(Uuid7.generate());var c=enqueue();
        owner.execute("CREATE FUNCTION test_webhook_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'test commit failure'; END IF; RETURN NEW; END $$");owner.execute("CREATE CONSTRAINT TRIGGER test_webhook_commit_failure AFTER UPDATE ON integ_webhook_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_webhook_commit_failure()");
        try{assertThatThrownBy(()->dispatcher.dispatch(c)).isInstanceOf(RuntimeException.class);}finally{owner.execute("DROP TRIGGER test_webhook_commit_failure ON integ_webhook_delivery");owner.execute("DROP FUNCTION test_webhook_commit_failure()");}
        assertThat(receiver.received).hasSize(1);assertThat(read(c).status()).isEqualTo("IN_FLIGHT");assertThat(owner.queryForObject("SELECT result FROM integ_webhook_attempt WHERE delivery_id=?",String.class,c.id())).isEqualTo("STARTED");expire(c);dispatcher.dispatch(c);assertThat(read(c).status()).isEqualTo("SUCCEEDED");assertThat(receiver.received).hasSize(2);assertThat(receiver.acceptedEffects()).isEqualTo(1);assertThat(receiver.received.get(1).body()).isEqualTo(receiver.received.getFirst().body());assertThat(receiver.received.get(1).headers().getFirst("X-ThingsLink-Nonce")).isNotEqualTo(receiver.received.getFirst().headers().getFirst("X-ThingsLink-Nonce"));
        assertThat(owner.queryForList("SELECT result FROM integ_webhook_attempt WHERE delivery_id=? ORDER BY attempt_no",String.class,c.id())).containsExactly("UNKNOWN","SUCCEEDED");
    }
    @Test void oneProjectsBacklogCannotFillEntireCandidateBatch(){
        var sub=create(Uuid7.generate()).subscription();owner.update("INSERT INTO integ_webhook_event SELECT ?,?,'device.online',gen_random_uuid(),0,repeat('a',64),clock_timestamp(),clock_timestamp(),clock_timestamp(),'ACCEPTED','{}' FROM generate_series(1,120)",tenant,project);owner.update("INSERT INTO integ_webhook_delivery(id,tenant_id,project_id,event_type,event_id,subscription_id,subscription_revision,created_at,deadline_at,status,next_attempt_at) SELECT gen_random_uuid(),tenant_id,project_id,event_type,event_id,?,1,clock_timestamp(),clock_timestamp()+interval '24 hours','READY',clock_timestamp()-interval '1 hour' FROM integ_webhook_event WHERE project_id=?",sub.id(),project);
        neighbor=Uuid7.generate();owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'neighbor',?)",neighbor,tenant,"wh"+neighbor.toString().replace("-",""));owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),neighbor,account);service.create(tenant,neighbor,account,Uuid7.generate(),spec());admission.accept(event(neighbor));var candidates=state.candidates();assertThat(candidates).hasSize(100);assertThat(candidates.get(1).project()).isEqualTo(neighbor);
    }
}
