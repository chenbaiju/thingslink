package com.things.link.bootstrap.integration;

import com.things.link.integration.application.IntegrationProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 最新迁移、真实APP角色与固定租约的500行Key清理，不抹掉独立操作。 */
class ApiKeyCleanupIntegrationTests extends AbstractIntegrationTest {
    @Autowired IntegrationProjectCleanupContributor cleanup;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate appJdbc;
    JdbcTemplate owner;
    List<Fixture> fixtures=new ArrayList<>();
    @BeforeEach void setup(){owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));}
    @AfterEach void remove(){for(var f:fixtures){new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).execute(s->{for(String table:List.of("integ_webhook_attempt","integ_webhook_delivery","integ_webhook_conflict","integ_webhook_event","integ_webhook_ingress_state"))owner.update("DELETE FROM "+table+" WHERE project_id=?",f.project());owner.update("DELETE FROM integ_webhook_revision WHERE project_id=?",f.project());owner.update("DELETE FROM integ_webhook_subscription WHERE project_id=?",f.project());return null;});owner.update("DELETE FROM integ_realtime_delivery WHERE project_id=?",f.project());owner.update("DELETE FROM integ_realtime_event WHERE project_id=?",f.project());owner.update("DELETE FROM integ_realtime_ticket WHERE project_id=?",f.project());owner.update("DELETE FROM integ_command_receipt WHERE project_id=?",f.project());owner.update("DELETE FROM integ_api_key WHERE project_id=?",f.project());owner.update("DELETE FROM integ_webhook_recovery_operation WHERE project_id=?",f.project());owner.update("DELETE FROM sys_project WHERE id=?",f.project());owner.update("DELETE FROM sys_tenant WHERE id=?",f.tenant());}}
    Fixture seed(int size){
        Fixture f=new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());fixtures.add(f);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'key-cleanup')",f.tenant());
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,
                    deleted_at,cleanup_stage,cleanup_lease_token,cleanup_lease_until,cleanup_started_at,cleanup_next_attempt_at)
                VALUES (?,?,'key-cleanup',?,'PURGING',1,now()-interval '31 days','INTEGRATION',?,clock_timestamp()+interval '5 minutes',now(),now())
                """,f.project(),f.tenant(),"ik"+f.project().toString().replace("-",""),f.token());
        owner.update("""
                INSERT INTO integ_api_key(id,tenant_id,project_id,project_generation,issuer_account_id,name,secret_hash,
                    scopes,ip_cidrs,status,created_at,expires_at,revision)
                SELECT gen_random_uuid(),?,?,0,gen_random_uuid(),'cleanup',repeat('a',64),ARRAY['device:read'],ARRAY['127.0.0.1/32'::cidr],
                    'ACTIVE',now()-interval '40 days',now()-interval '35 days',0 FROM generate_series(1,?)
                """,f.tenant(),f.project(),size);
        owner.update("INSERT INTO integ_api_key_operation VALUES (?,?,?,?,'ISSUE',?,NULL,?,now())",f.tenant(),f.project(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),UUID.randomUUID());
        return f;
    }
    ProjectCleanupClaim claim(Fixture f){return new ProjectCleanupClaim(f.tenant(),f.project(),1,"INTEGRATION",f.token(),Instant.now().plusSeconds(300),false);}
    int count(Fixture f){return owner.queryForObject("SELECT count(*) FROM integ_api_key WHERE project_id=?",Integer.class,f.project());}
    @Test void deletesBoundedBatchesAndKeepsNeighborAndImmutableReceipt(){
        var a=seed(501);var b=seed(2);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(500);
        assertThat(count(a)).isEqualTo(1);assertThat(count(b)).isEqualTo(2);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).complete()).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM integ_api_key_operation WHERE project_id=?",Integer.class,a.project())).isEqualTo(1);
        assertThat(ProjectCleanupStage.OTA.next()).isEqualTo(ProjectCleanupStage.INTEGRATION);
        assertThat(ProjectCleanupStage.INTEGRATION.next()).isEqualTo(ProjectCleanupStage.ASSISTANT);
    }
    @Test void wrongTokenTenantGenerationStageAndExpiredLeaseCannotDelete(){
        var a=seed(1);var normal=claim(a);
        for(var bad:List.of(new ProjectCleanupClaim(a.tenant(),a.project(),1,"INTEGRATION",UUID.randomUUID(),normal.leaseUntil(),false),
                new ProjectCleanupClaim(UUID.randomUUID(),a.project(),1,"INTEGRATION",a.token(),normal.leaseUntil(),false),
                new ProjectCleanupClaim(a.tenant(),a.project(),0,"INTEGRATION",a.token(),normal.leaseUntil(),false))){
            assertThatThrownBy(()->tx.execute(s->cleanup.clean(bad))).isInstanceOf(DataAccessException.class);
        }
        owner.update("UPDATE sys_project SET cleanup_stage='OTA' WHERE id=?",a.project());
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(normal))).isInstanceOf(DataAccessException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='INTEGRATION',cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",a.project());
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(normal))).isInstanceOf(DataAccessException.class);
        assertThat(count(a)).isEqualTo(1);
    }
    @Test void outerFailureRestoresDeletedFactsAndFunctionIsNotPublic(){
        var a=seed(3);
        assertThatThrownBy(()->tx.execute(s->{cleanup.clean(claim(a));throw new IllegalStateException("fault");})).isInstanceOf(IllegalStateException.class);
        assertThat(count(a)).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.oid='integ_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0",Integer.class)).isZero();
    }
    @Test void leaseExpiringWhileKeyRowIsLockedRollsBackDeletion() throws Exception {
        var a=seed(1);
        try(var lock=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try(var st=lock.prepareStatement("SELECT id FROM integ_api_key WHERE project_id=? FOR UPDATE")) {
                st.setObject(1,a.project());st.executeQuery().close();
            }
            owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()+interval '3 seconds' WHERE id=?",a.project());
            var result=executor.submit(()->{try{tx.execute(s->cleanup.clean(claim(a)));return (RuntimeException)null;}catch(RuntimeException e){return e;}});
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->owner.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM public.integ_project_cleanup_batch%')",Boolean.class));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->owner.queryForObject(
                    "SELECT cleanup_lease_until<=clock_timestamp() FROM sys_project WHERE id=?",Boolean.class,a.project()));
            } finally {lock.commit();}
            assertThat(result.get(10,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(DataAccessException.class);
        }
        assertThat(count(a)).isEqualTo(1);
    }
    /** 新收据先删，500条批量上界不能因新增表翻倍。 */
    @Test void commandReceiptsAreBoundedBeforeKeysAndKeepNeighbor(){
        var a=seed(1);var b=seed(1);receipt(a,501);receipt(b,1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(500);
        assertThat(receiptCount(a)).isEqualTo(1);assertThat(count(a)).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(count(a)).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).complete()).isTrue();
        assertThat(receiptCount(b)).isEqualTo(1);assertThat(count(b)).isEqualTo(1);
    }
    void receipt(Fixture f,int size){owner.update("""
        INSERT INTO integ_command_receipt(tenant_id,project_id,project_generation,key_id,issuer_account_id,client_key_hash,command_id,device_id)
        SELECT ?,?,0,gen_random_uuid(),gen_random_uuid(),encode(digest(n::text,'sha256'),'hex'),gen_random_uuid(),gen_random_uuid()
        FROM generate_series(1,?) n
        """,f.tenant(),f.project(),size);}
    int receiptCount(Fixture f){return owner.queryForObject("SELECT count(*) FROM integ_command_receipt WHERE project_id=?",Integer.class,f.project());}
    /** 收据分支同样必须在等待后重新验证数据库租约。 */
    @Test void leaseExpiryWhileWaitingOnReceiptRollsBackDelete()throws Exception{
        var a=seed(1);receipt(a,1);
        try(var lock=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            lock.setAutoCommit(false);
            try(var st=lock.prepareStatement("SELECT key_id FROM integ_command_receipt WHERE project_id=? FOR UPDATE")){st.setObject(1,a.project());st.executeQuery().close();}
            owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()+interval '2 seconds' WHERE id=?",a.project());
            var pending=executor.submit(()->{try{tx.execute(s->cleanup.clean(claim(a)));return (RuntimeException)null;}catch(RuntimeException e){return e;}});
            try{
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM public.integ_project_cleanup_batch%')",Boolean.class));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->owner.queryForObject("SELECT cleanup_lease_until<=clock_timestamp() FROM sys_project WHERE id=?",Boolean.class,a.project()));
            }finally{lock.commit();}
            assertThat(pending.get(10,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(DataAccessException.class);
        }
        assertThat(receiptCount(a)).isEqualTo(1);assertThat(count(a)).isEqualTo(1);
    }
    @Test void realtimeTicketsAreCleanedBeforeReceiptsWithOneSharedBatchBound(){
        var a=seed(1);var b=seed(1);ticket(a,501,8);ticket(b,1,8);receipt(a,1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(500);
        assertThat(ticketCount(a)).isEqualTo(1);assertThat(ticketCount(b)).isEqualTo(1);
        assertThat(receiptCount(a)).isEqualTo(1);assertThat(count(a)).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).complete()).isTrue();
    }
    @Test void ticketRetentionIsSevenDaysBoundedAndFunctionsAreNotPublic(){
        var a=seed(0);ticket(a,501,8);ticket(a,2,6);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(10000)",Integer.class))).isEqualTo(500);
        assertThat(ticketCount(a)).isEqualTo(3);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(10000)",Integer.class))).isEqualTo(1);
        assertThat(ticketCount(a)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.proname IN ('integ_prove_realtime_ticket','integ_realtime_candidates','integ_purge_realtime_tickets') AND a.grantee=0",Integer.class)).isZero();
    }
    void ticket(Fixture f,int size,int ageDays){ticket(f,size,ageDays,"CLOSED");}
    void ticket(Fixture f,int size,int ageDays,String status){owner.update("""
        INSERT INTO integ_realtime_ticket(id,tenant_id,project_id,project_generation,issuer_kind,subject_id,
            identity_expires_at,protocol,scope_json,property_count,secret_hash,created_at,expires_at,status,instance_id)
        SELECT gen_random_uuid(),?,?,0,'APP',gen_random_uuid(),now()-make_interval(days=>?),
            'MQTT',jsonb_build_object('protocol','MQTT','eventTypes',jsonb_build_array('device.property.report'),
                'devices',jsonb_build_array(jsonb_build_object('deviceId',gen_random_uuid(),
                    'expectedModelVersionId',gen_random_uuid(),'propertyKeys',jsonb_build_array('value')))),
            1,repeat('b',64),now()-make_interval(days=>?)-interval '1 second',now()-make_interval(days=>?),
            ?,'cleanup' FROM generate_series(1,?)
        """,f.tenant(),f.project(),ageDays,ageDays,ageDays,status,size);}
    int ticketCount(Fixture f){return owner.queryForObject("SELECT count(*) FROM integ_realtime_ticket WHERE project_id=?",Integer.class,f.project());}
    @Test void deliveryCleanupPreservesReferencesAndSingleFiveHundredBudget(){
        var a=seed(1);var b=seed(0);ticket(a,1,8);ticket(b,1,8);delivery(a,501,8);delivery(b,1,6);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(500);
        assertThat(eventRows(a,"integ_realtime_delivery")).isEqualTo(1);assertThat(eventRows(a,"integ_realtime_event")).isEqualTo(501);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(500);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).complete()).isTrue();
        assertThat(eventRows(b,"integ_realtime_delivery")).isEqualTo(1);assertThat(ticketCount(b)).isEqualTo(1);
    }
    @Test void retentionKeepsReferencedEventsAndTicketsUntilDeliveryRetentionEnds(){
        var a=seed(0);ticket(a,1,8);delivery(a,1,6);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(500)",Integer.class))).isZero();
        assertThat(eventRows(a,"integ_realtime_event")).isEqualTo(1);assertThat(ticketCount(a)).isEqualTo(1);
        var b=seed(0);ticket(b,1,8);delivery(b,501,8);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(10000)",Integer.class))).isEqualTo(500);
        assertThat(eventRows(b,"integ_realtime_delivery")).isEqualTo(1);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(500)",Integer.class))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(500)",Integer.class))).isEqualTo(500);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(500)",Integer.class))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s->appJdbc.queryForObject("SELECT integ_purge_realtime_tickets(500)",Integer.class))).isEqualTo(1);
        assertThat(ticketCount(a)).isEqualTo(1);assertThat(eventRows(a,"integ_realtime_delivery")).isEqualTo(1);
    }
    @Test void webhookFactsCleanupIsBoundedOrderedAndRollbackSafe(){
        var a=seed(1);var b=seed(0);webhook(a,1);webhook(b,1);webhookFacts(a,501);webhookFacts(b,1);
        assertThatThrownBy(()->tx.execute(s->{cleanup.clean(claim(a));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(eventRows(a,"integ_webhook_attempt")).isEqualTo(501);
        for(int expected:List.of(500,1,500,1,500,1,500,1,1,2,1))assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(expected);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).complete()).isTrue();assertThat(eventRows(b,"integ_webhook_attempt")).isEqualTo(1);
        assertThatThrownBy(()->tx.execute(s->appJdbc.queryForList("SELECT * FROM integ_project_cleanup_subscriptions(?,?,1,?)",a.tenant(),a.project(),a.token()))).isInstanceOf(DataAccessException.class);
    }
    void webhookFacts(Fixture f,int count){
        owner.update("INSERT INTO integ_webhook_ingress_state VALUES (?,?,clock_timestamp()-interval '7 days')",f.tenant(),f.project());
        owner.update("INSERT INTO integ_webhook_event SELECT ?,?,'device.online',gen_random_uuid(),0,repeat('a',64),clock_timestamp(),clock_timestamp(),clock_timestamp(),'ACCEPTED','{}' FROM generate_series(1,?)",f.tenant(),f.project(),count);
        owner.update("INSERT INTO integ_webhook_conflict SELECT tenant_id,project_id,event_type,event_id,repeat('b',64),clock_timestamp() FROM integ_webhook_event WHERE project_id=?",f.project());
        owner.update("INSERT INTO integ_webhook_delivery(id,tenant_id,project_id,event_type,event_id,subscription_id,subscription_revision,created_at,deadline_at,status,next_attempt_at) SELECT gen_random_uuid(),e.tenant_id,e.project_id,e.event_type,e.event_id,s.id,1,clock_timestamp(),clock_timestamp()+interval '24 hours','READY',clock_timestamp() FROM integ_webhook_event e JOIN integ_webhook_subscription s ON s.project_id=e.project_id WHERE e.project_id=?",f.project());
        owner.update("UPDATE integ_webhook_delivery SET status='IN_FLIGHT',attempt_count=1,round_attempts=1,lease_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '30 seconds' WHERE project_id=?",f.project());
        owner.update("INSERT INTO integ_webhook_attempt(tenant_id,project_id,delivery_id,attempt_no,recovery_round,lease_token,started_at,result) SELECT tenant_id,project_id,id,1,1,lease_token,clock_timestamp(),'STARTED' FROM integ_webhook_delivery WHERE project_id=?",f.project());
    }
    void delivery(Fixture f,int size,int age){
        owner.update("INSERT INTO integ_realtime_event(tenant_id,project_id,event_id,device_id,source_hash,accepted_at) SELECT ?,?,gen_random_uuid(),gen_random_uuid(),repeat('c',64),now()-interval '8 days' FROM generate_series(1,?)",f.tenant(),f.project(),size);
        owner.update("""
            INSERT INTO integ_realtime_delivery(id,tenant_id,project_id,event_id,device_id,ticket_id,envelope,status,finished_at)
            SELECT gen_random_uuid(),e.tenant_id,e.project_id,e.event_id,e.device_id,t.id,'{}','DELIVERED',now()-make_interval(days=>?)
            FROM integ_realtime_event e JOIN integ_realtime_ticket t ON e.project_id=t.project_id WHERE e.project_id=?
            """,age,f.project());
    }
    int eventRows(Fixture f,String table){return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,f.project());}
    @org.springframework.beans.factory.annotation.Autowired com.things.link.integration.infrastructure.RealtimeRetentionMaintenance retentionMaintenance;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment environment;
    @Test void disabledAdmissionStillCancelsExpiredOrClosedDeliveryWithinOneBatchBudget(){
        assertThat(environment.getProperty("things-link.integration.realtime.enabled",Boolean.class,false)).isFalse();
        var a=seed(0);ticket(a,1,8,"CONNECTED");
        owner.update("INSERT INTO integ_realtime_event(tenant_id,project_id,event_id,device_id,source_hash,accepted_at) SELECT ?,?,gen_random_uuid(),gen_random_uuid(),repeat('c',64),now()-interval '8 days' FROM generate_series(1,502)",a.tenant(),a.project());
        owner.update("""
            INSERT INTO integ_realtime_delivery(id,tenant_id,project_id,event_id,device_id,ticket_id,envelope,status,attempts,lease_token,lease_until)
            SELECT gen_random_uuid(),e.tenant_id,e.project_id,e.event_id,e.device_id,t.id,'{}',
                CASE WHEN row_number() OVER()=1 THEN 'IN_FLIGHT' ELSE 'READY' END,
                CASE WHEN row_number() OVER()=1 THEN 1 ELSE 0 END,
                CASE WHEN row_number() OVER()=1 THEN gen_random_uuid() ELSE NULL END,
                CASE WHEN row_number() OVER()=1 THEN now()+interval '30 seconds' ELSE NULL END
            FROM integ_realtime_event e JOIN integ_realtime_ticket t ON e.project_id=t.project_id WHERE e.project_id=?
            """,a.project());
        retentionMaintenance.tick();assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_delivery WHERE project_id=? AND status='CANCELLED'",Integer.class,a.project())).isEqualTo(500);
        retentionMaintenance.tick();assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_delivery WHERE project_id=? AND status='CANCELLED' AND lease_token IS NULL AND finished_at>now()-interval '1 minute'",Integer.class,a.project())).isEqualTo(502);
        retentionMaintenance.tick();assertThat(eventRows(a,"integ_realtime_delivery")).isEqualTo(502);assertThat(eventRows(a,"integ_realtime_event")).isEqualTo(502);assertThat(ticketCount(a)).isEqualTo(1);
    }
    void webhook(Fixture f,int revisions){new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).execute(s->{
        UUID id=UUID.randomUUID();owner.update("INSERT INTO integ_webhook_subscription VALUES (?,?,?,0,?,now(),1,'ACTIVE',now())",id,f.tenant(),f.project(),f.token());
        owner.update("INSERT INTO integ_webhook_revision VALUES (?,?,?,1,'cleanup','https://receiver.example.com',ARRAY['device.online'],ARRAY[]::uuid[],?,now(),'a',gen_random_uuid())",f.tenant(),f.project(),id,f.token());
        for(int n=2;n<=revisions;n++){owner.update("UPDATE integ_webhook_subscription SET current_revision=? WHERE id=?",n,id);owner.update("INSERT INTO integ_webhook_revision SELECT tenant_id,project_id,subscription_id,?,name,target_url,event_types,device_ids,authorized_by,now(),signing_key_id,gen_random_uuid() FROM integ_webhook_revision WHERE subscription_id=? AND revision=1",n,id);}return null;});}
    @Test void webhookRecoveryReceiptSurvivesProjectBusinessCleanup(){var f=seed(0);webhook(f,1);
        owner.update("INSERT INTO integ_webhook_recovery_operation VALUES (?,?,?,?,?,?,2,clock_timestamp())",f.tenant(),f.project(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),UUID.randomUUID());
        for(int i=0;i<10;i++)if(tx.execute(s->cleanup.clean(claim(f))).complete())break;
        assertThat(eventRows(f,"integ_webhook_subscription")).isZero();assertThat(eventRows(f,"integ_webhook_recovery_operation")).isEqualTo(1);
    }
    @Test void webhookHistoryAndCircularCurrentPairStayWithin500ThenOriginalChain(){var f=seed(1);webhook(f,503);
        assertThat(tx.execute(s->cleanup.clean(claim(f))).deletedRows()).isEqualTo(500);assertThat(eventRows(f,"integ_webhook_revision")).isEqualTo(3);assertThat(count(f)).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(f))).deletedRows()).isEqualTo(2);assertThat(tx.execute(s->cleanup.clean(claim(f))).deletedRows()).isEqualTo(2);assertThat(eventRows(f,"integ_webhook_subscription")).isZero();assertThat(eventRows(f,"integ_webhook_revision")).isZero();
        assertThat(tx.execute(s->cleanup.clean(claim(f))).deletedRows()).isEqualTo(1);assertThat(tx.execute(s->cleanup.clean(claim(f))).complete()).isTrue();
    }
    @Test void webhookCleanupRollbackRestoresPairAndPrivateDelegateCannotBypassIt(){var f=seed(0);webhook(f,1);
        assertThatThrownBy(()->tx.execute(s->{cleanup.clean(claim(f));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);assertThat(eventRows(f,"integ_webhook_revision")).isEqualTo(1);
        assertThatThrownBy(()->tx.execute(s->appJdbc.queryForList("SELECT * FROM integ_project_cleanup_without_webhook(?,?,?,?)",f.tenant(),f.project(),1,f.token()))).isInstanceOf(DataAccessException.class);
        assertThat(tx.execute(s->cleanup.clean(claim(f))).deletedRows()).isEqualTo(2);
    }
    record Fixture(UUID tenant,UUID project,UUID token){}
}
