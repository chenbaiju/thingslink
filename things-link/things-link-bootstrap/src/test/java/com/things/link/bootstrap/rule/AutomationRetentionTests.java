package com.things.link.bootstrap.rule;

import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.automation.*;
import com.things.link.rule.domain.AutomationDefinition;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.shared.tenant.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

/** 真实受限APP与专用角色验证到期、引用、项目租约及历史重放；owner仅建有年龄的夹具。 */
class AutomationRetentionTests extends AbstractIntegrationTest {
    @Autowired AutomationManagementService management;
    @Autowired AutomationEventIngress ingress;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired AutomationExecutionRunner runner;
    @Autowired com.things.link.rule.application.RuleNotificationDeliveryStore notifications;
    @Autowired com.things.link.telemetry.application.DeviceCommandClaimPort commandClaims;
    @Autowired com.things.link.rule.domain.AutomationExecutionRepository executions;
    final JsonMapper json=JsonMapper.builder().build();
    UUID tenant,project,account,device,policy,collaborator,collaboratorTenant;
    int partition;
    final List<Long> kafkaOffsets=new ArrayList<>();
    @BeforeEach void seed()throws Exception{
        tenant=Uuid7.generate();project=Uuid7.generate();account=Uuid7.generate();device=Uuid7.generate();policy=Uuid7.generate();
        partition=ThreadLocalRandom.current().nextInt(10000,1000000000);
        try(var c=owner();var q=c.createStatement()){
            q.executeUpdate("INSERT INTO sys_quota_policy(id,code) VALUES ('"+policy+"','"+policy.toString().replace("-", "")+"')");
            q.executeUpdate("INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES ('"+tenant+"','automation-test','"+policy+"')");
            q.executeUpdate("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES ('"+account+"','"+account+"@example.com','{noop}unused','test')");
            q.executeUpdate("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES ('"+Uuid7.generate()+"','"+tenant+"','"+account+"')");
            q.executeUpdate("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES ('"+project+"','"+tenant+"','test','sh-1','"+project+"')");
            q.executeUpdate("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES ('"+Uuid7.generate()+"','"+project+"','"+account+"','OWNER')");
            q.executeUpdate("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name) VALUES ('"+device+"','"+tenant+"','"+project+"','test','test')");
        }
    }
    @AfterEach void cleanup()throws Exception{
        TenantContext.clear();
        try(var c=owner();var q=c.createStatement()){
            q.executeUpdate("DELETE FROM rule_automation_ingress_rejection WHERE partition_id="+partition);
            for(long offset:kafkaOffsets)q.executeUpdate("DELETE FROM rule_automation_ingress_rejection WHERE partition_id=0 AND record_offset="+offset);
            q.executeUpdate("DELETE FROM sys_tenant_work_slot WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_outbox_event WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_attempt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_device_action_delivery WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM ts_device_command_claim WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM ts_device_command_attempt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM ts_device_command WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_notification_delivery WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_execution WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_event_receipt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("UPDATE rule_automation SET active_version_id=NULL,status='DRAFT' WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_version WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_automation_quota_reservation WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_usage_counter_daily WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_access_binding WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_device WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_type WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_project_member WHERE project_id='"+project+"'");
            q.executeUpdate("DELETE FROM sys_project WHERE id='"+project+"'");
            q.executeUpdate("DELETE FROM sys_tenant_member WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_account WHERE id='"+account+"'");
            q.executeUpdate("DELETE FROM sys_tenant WHERE id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_quota_policy WHERE id='"+policy+"'");
            if(collaborator!=null){
                q.executeUpdate("DELETE FROM sys_tenant_member WHERE account_id='"+collaborator+"'");
                q.executeUpdate("DELETE FROM sys_account WHERE id='"+collaborator+"'");
                q.executeUpdate("DELETE FROM sys_tenant WHERE id='"+collaboratorTenant+"'");
            }
        }
    }
    @Autowired com.things.link.rule.domain.AutomationRetentionRepository retention;
    UUID execution,source,delivery; AutomationDefinition definition;
    private void history(int terminalHours)throws Exception{history(terminalHours,false);}
    private void history(int terminalHours,boolean running)throws Exception{
        enable(4);definition=active("retained");execution=Uuid7.generate();source=Uuid7.generate();
        String age="(clock_timestamp()-interval '"+terminalHours+" hours')";
        String completed=running?"NULL":age;
        ownerUpdate("INSERT INTO rule_automation_event_receipt(id,tenant_id,project_id,source_event_id,device_id,accepted_at,admitted_at,source_digest,plan,result,expires_at) VALUES ('"+Uuid7.generate()+"','"+tenant+"','"+project+"','"+source+"','"+device+"',"+age+","+age+",'"+"a".repeat(64)+"','[\""+definition.activeVersionId()+"\"]','ACCEPTED',"+age+"+interval '720 hours')");
        ownerUpdate("INSERT INTO rule_automation_execution(id,tenant_id,project_id,automation_id,automation_version_id,trigger_type,occurrence_key,source_event_id,device_id,input_snapshot,input_digest,responsible_account_id,trace_id,occurred_at,accepted_at,status,attempt_count,lease_token,created_at,completed_at,recovery_deadline) VALUES ('"+execution+"','"+tenant+"','"+project+"','"+definition.id()+"','"+definition.activeVersionId()+"','PROPERTY_REPORTED','event:"+source+"','"+source+"','"+device+"','{\"x\":1}','"+"a".repeat(64)+"','"+account+"','retention',"+age+","+age+",'"+(running?"RUNNING":"DISPATCHED")+"',1,1,"+age+","+completed+","+age+"+interval '24 hours')");
        ownerUpdate("INSERT INTO rule_automation_attempt(id,tenant_id,project_id,execution_id,attempt_number,lease_token,started_at,finished_at,outcome) VALUES ('"+Uuid7.generate()+"','"+tenant+"','"+project+"','"+execution+"',1,1,"+age+","+completed+",'"+(running?"STARTED":"SUCCEEDED")+"')");
        ownerUpdate("INSERT INTO sys_automation_quota_reservation(tenant_id,project_id,execution_id,usage_date,created_at) VALUES ('"+tenant+"','"+project+"','"+execution+"',("+age+" AT TIME ZONE 'UTC')::date,"+age+")");
    }
    private com.things.link.shared.message.RuleNotificationDeliveryRequest notification()throws Exception{
        delivery=Uuid7.generate();
        var request=com.things.link.shared.message.RuleNotificationDeliveryRequest.automation(delivery,tenant,project,definition.id(),definition.activeVersionId(),execution,device,"EMAIL","test@example.com","test","body","retention",1,Instant.now().minus(Duration.ofDays(31)));
        assertThat(notifications.accept(request)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.READY);
        ownerUpdate("UPDATE rule_notification_delivery SET status='DELIVERED',attempt_count=1,created_at=clock_timestamp()-interval '31 days',updated_at=clock_timestamp()-interval '31 days',terminal_at=clock_timestamp()-interval '31 days',delivered_at=clock_timestamp()-interval '31 days' WHERE id='"+delivery+"'");
        ownerUpdate("INSERT INTO sys_outbox_event(id,tenant_id,project_id,aggregate_type,aggregate_id,event_type,destination_topic,partition_key,payload,trace_id) VALUES ('"+delivery+"','"+tenant+"','"+project+"','RULE_NOTIFICATION','"+delivery+"','RULE_NOTIFICATION_DELIVERY_REQUEST','tc.rule.notification','"+delivery+"','{}','retention')");
        return request;
    }
    private int batch(){return retention.purgeHistory(new com.things.link.rule.domain.AutomationRetentionRepository.Scope(tenant,project));}
    private void drain(){for(int i=0;i<10&&batch()>0;i++){} }
    @Test void dedicatedRolePurgesOnlyAgedInputAndOrdinaryRoleCannotRewriteHistory()throws Exception{
        history(169);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{rls.establish(tenant,project);jdbc.update("UPDATE rule_automation_execution SET input_snapshot=NULL WHERE id=?",execution);}))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{rls.establish(tenant,project);jdbc.update("DELETE FROM rule_automation_execution WHERE id=?",execution);}))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(retention.purgeInputs()).isEqualTo(1);
        asOwner(()->{tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(jdbc.queryForObject("SELECT input_snapshot IS NULL FROM rule_automation_execution WHERE id=?",Boolean.class,execution)).isTrue();});return null;});
        assertThat(batch()).isZero();assertThat(count("rule_automation_execution")).isEqualTo(1);
        try(var c=owner();var q=c.createStatement();var r=q.executeQuery("SELECT rolcanlogin,rolbypassrls FROM pg_roles WHERE rolname='thingslink_automation_cleanup'")){
            r.next();assertThat(r.getBoolean(1)).isFalse();assertThat(r.getBoolean(2)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT pg_has_role(current_user,'thingslink_automation_cleanup','MEMBER')",Boolean.class)).isFalse();
    }
    @Test void recentInputAndFactsCannotBePurged()throws Exception{
        history(167);assertThat(retention.purgeInputs()).isZero();assertThat(batch()).isZero();
        assertThat(count("rule_automation_attempt")).isEqualTo(1);assertThat(count("rule_automation_event_receipt")).isEqualTo(1);
    }
    @Test void pendingOrRecentlyPublishedOutboxKeepsHistoryAndOldReplayCannotReexecute()throws Exception{
        history(745);var request=notification();
        drain();assertThat(count("rule_automation_execution")).isEqualTo(1);assertThat(count("rule_automation_attempt")).isEqualTo(1);
        ownerUpdate("UPDATE sys_outbox_event SET status='PUBLISHED',published_at=clock_timestamp(),lease_token=NULL,leased_until=NULL,last_error=NULL WHERE id='"+delivery+"'");
        drain();assertThat(count("rule_notification_delivery")).isEqualTo(1);
        ownerUpdate("UPDATE sys_outbox_event SET published_at=clock_timestamp()-interval '193 hours' WHERE id='"+delivery+"'");
        drain();assertThat(count("rule_notification_delivery")).isZero();assertThat(count("rule_automation_execution")).isZero();
        assertThat(count("rule_automation_event_receipt")).isZero();assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        assertThat(notifications.accept(request)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.REJECTED);
        Instant old=Instant.now().minus(Duration.ofDays(31));
        var oldEvent=new AutomationPropertyAccepted(1,source,tenant,project,device,"1.0.0",old,old,Map.of("x",1),"retention");
        accept(oldEvent,1);assertThat(count("rule_automation_execution")).isZero();
        tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(jdbc.queryForObject("SELECT reason_code FROM rule_automation_event_receipt WHERE project_id=?",String.class,project)).isEqualTo("EVENT_EXPIRED");});
    }
    @Test void nonterminalDeliveryAndLiveLeasePreventParentDeletion()throws Exception{
        history(745);notification();
        ownerUpdate("UPDATE sys_outbox_event SET status='PUBLISHED',published_at=clock_timestamp()-interval '193 hours',lease_token=NULL,leased_until=NULL,last_error=NULL WHERE id='"+delivery+"'");
        ownerUpdate("UPDATE rule_notification_delivery SET dispatch_lease_token='"+Uuid7.generate()+"',dispatch_leased_until=clock_timestamp()+interval '1 minute' WHERE id='"+delivery+"'");
        drain();assertThat(count("rule_notification_delivery")).isEqualTo(1);
        ownerUpdate("UPDATE rule_notification_delivery SET dispatch_lease_token=NULL,dispatch_leased_until=NULL,status='QUEUED',terminal_at=NULL,delivered_at=NULL WHERE id='"+delivery+"'");
        drain();assertThat(count("rule_automation_execution")).isEqualTo(1);
    }
    @Test void quotaWaitsNinetyUtcDaysAndAllExecutionReferences()throws Exception{
        history(2209);assertThat(jdbc.queryForObject("SELECT automation_purge_quota_reservations()",Integer.class)).isZero();
        drain();assertThat(count("rule_automation_execution")).isZero();
        assertThat(jdbc.queryForObject("SELECT automation_purge_quota_reservations()",Integer.class)).isEqualTo(1);
        assertThat(count("sys_automation_quota_reservation")).isZero();
    }
    @Test void youngerQuotaRemainsAfterBusinessFactsExpire()throws Exception{
        history(745);drain();assertThat(jdbc.queryForObject("SELECT automation_purge_quota_reservations()",Integer.class)).isZero();
        assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
    }
    @Test void projectCleanupRequiresRealTokenAndRetainsPendingOutboxThenClearsDefinitions()throws Exception{
        history(745);notification();UUID token=Uuid7.generate();
        ownerUpdate("UPDATE sys_project SET status='PURGING',deleted_at=clock_timestamp()-interval '31 days',cleanup_stage='RULE',cleanup_started_at=clock_timestamp(),cleanup_next_attempt_at=clock_timestamp(),cleanup_lease_token='"+token+"',cleanup_lease_until=clock_timestamp()+interval '5 minutes' WHERE id='"+project+"'");
        assertThat(batch()).isZero();
        assertThatThrownBy(()->cleanupBatch(Uuid7.generate())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(cleanupBatch(token).get("blocked_reason")).isEqualTo("AUTOMATION_RETENTION_OR_DELIVERY");
        assertThat(count("rule_automation")).isEqualTo(1);
        ownerUpdate("UPDATE sys_outbox_event SET status='PUBLISHED',published_at=clock_timestamp()-interval '193 hours',lease_token=NULL,leased_until=NULL,last_error=NULL WHERE id='"+delivery+"'");
        boolean complete=false;
        for(int i=0;i<20;i++){var result=cleanupBatch(token);if(Boolean.TRUE.equals(result.get("complete"))){complete=true;break;}}
        assertThat(complete).isTrue();assertThat(count("rule_automation")).isZero();assertThat(count("rule_automation_version")).isZero();
        assertThat(count("rule_automation_execution")).isZero();assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
    }
    @Test void cleanupTransactionRollbackPreservesDeletedChildAndReplayProof()throws Exception{
        history(745);notification();
        ownerUpdate("UPDATE sys_outbox_event SET status='PUBLISHED',published_at=clock_timestamp()-interval '193 hours',lease_token=NULL,leased_until=NULL,last_error=NULL WHERE id='"+delivery+"'");
        tx.executeWithoutResult(status->{assertThat(jdbc.queryForObject("SELECT automation_retention_batch(?,?)",Integer.class,tenant,project)).isEqualTo(1);status.setRollbackOnly();});
        assertThat(count("rule_notification_delivery")).isEqualTo(1);assertThat(count("rule_automation_execution")).isEqualTo(1);
        assertThat(batch()).isEqualTo(1);assertThat(count("rule_notification_delivery")).isZero();
    }
    @Test void purgingWaitsForExecutionLeaseThenRecordsReadOnlyWithoutShorteningRetention()throws Exception{
        enable(4);active("pending");accept(event(Instant.now(),Map.of("x",1)),1);
        var candidate=executions.candidates().stream().filter(c->c.projectId().equals(project)).findFirst().orElseThrow();
        tx.executeWithoutResult(s->{rls.establish(tenant,project);executions.lock(candidate);executions.claim(candidate);});
        UUID token=Uuid7.generate();
        ownerUpdate("UPDATE sys_project SET status='PURGING',deleted_at=clock_timestamp()-interval '31 days',cleanup_stage='RULE',cleanup_started_at=clock_timestamp(),cleanup_next_attempt_at=clock_timestamp(),cleanup_lease_token='"+token+"',cleanup_lease_until=clock_timestamp()+interval '5 minutes' WHERE id='"+project+"'");
        assertThat(cleanupBatch(token).get("blocked_reason")).isEqualTo("AUTOMATION_WORK_IN_FLIGHT");
        ownerUpdate("UPDATE rule_automation_execution SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+candidate.executionId()+"'");
        assertThat(cleanupBatch(token).get("blocked_reason")).isEqualTo("AUTOMATION_RETENTION_OR_DELIVERY");
        tx.executeWithoutResult(s->{rls.establish(tenant,project);var row=jdbc.queryForMap("SELECT status,reason_code,completed_at>clock_timestamp()-interval '1 minute' AS recent FROM rule_automation_execution WHERE id=?",candidate.executionId());
            assertThat(row).containsEntry("status","REJECTED").containsEntry("reason_code","PROJECT_READ_ONLY").containsEntry("recent",true);});
        assertThat(count("rule_automation_attempt")).isEqualTo(1);assertThat(count("sys_outbox_event")).isZero();
    }
    @Test void inputCleanupIsBoundedAndSchedulerKeepsCallerIdentity()throws Exception{
        history(169);
        ownerUpdate("INSERT INTO rule_automation_event_receipt SELECT (jsonb_populate_record(NULL::rule_automation_event_receipt,to_jsonb(r)||jsonb_build_object('id',gen_random_uuid(),'source_event_id',gen_random_uuid()))).* FROM rule_automation_event_receipt r CROSS JOIN generate_series(1,500) WHERE r.project_id='"+project+"'");
        ownerUpdate("INSERT INTO rule_automation_execution SELECT (jsonb_populate_record(NULL::rule_automation_execution,to_jsonb(e)||jsonb_build_object('id',gen_random_uuid(),'source_event_id',r.source_event_id,'occurrence_key','event:'||r.source_event_id::text))).* FROM rule_automation_execution e JOIN rule_automation_event_receipt r ON r.project_id=e.project_id WHERE e.id='"+execution+"' AND r.source_event_id<>'"+source+"'");
        assertThat(retention.purgeInputs()).isEqualTo(500);
        var caller=new TenantScope(Uuid7.generate(),Uuid7.generate(),Uuid7.generate());TenantContext.set(caller);
        try{new AutomationRetentionScheduler(retention).clean();assertThat(TenantContext.current()).contains(caller);}finally{TenantContext.clear();}
        assertThat(retention.purgeInputs()).isZero();assertThat(count("rule_automation_execution")).isEqualTo(501);
    }
    @Test void projectCleanupHonorsExpiredRecoveryDeadlineInsteadOfReclassifyingReadOnly()throws Exception{
        history(745,true);UUID token=Uuid7.generate();
        ownerUpdate("UPDATE sys_project SET status='PURGING',deleted_at=clock_timestamp()-interval '31 days',cleanup_stage='RULE',cleanup_started_at=clock_timestamp(),cleanup_next_attempt_at=clock_timestamp(),cleanup_lease_token='"+token+"',cleanup_lease_until=clock_timestamp()+interval '5 minutes' WHERE id='"+project+"'");
        assertThat(cleanupBatch(token).get("blocked_reason")).isEqualTo("AUTOMATION_RETENTION_OR_DELIVERY");
        tx.executeWithoutResult(s->{rls.establish(tenant,project);
            assertThat(jdbc.queryForMap("SELECT status,reason_code FROM rule_automation_execution WHERE id=?",execution)).containsEntry("status","FAILED").containsEntry("reason_code","RECOVERY_EXHAUSTED");
            assertThat(jdbc.queryForObject("SELECT outcome FROM rule_automation_attempt WHERE execution_id=?",String.class,execution)).isEqualTo("LEASE_EXPIRED");
        });
        assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_automation_execution")).isEqualTo(1);
    }
    private Map<String,Object> cleanupBatch(UUID token){return tx.execute(s->jdbc.queryForMap("SELECT * FROM rule_project_cleanup_batch(?,?,(SELECT lifecycle_generation FROM sys_project WHERE id=?),?)",tenant,project,project,token));}
    private AutomationManagementService.Edit edit(String name){return new AutomationManagementService.Edit(name,null,"PROPERTY_REPORTED",json.createObjectNode().put("deviceId",device.toString()),List.of(),List.of(new ActionSpec("notification-action",json.createObjectNode().put("channel","email").put("recipient","test@example.com").put("subject","test").put("body","test"))));}
    private AutomationDefinition active(String name){return asOwner(()->{var d=management.create(project,edit(name));return management.activate(project,d.id(),latest(d.id()),d.version());});}
    private UUID latest(UUID id){return asOwner(()->jdbc.queryForObject("SELECT id FROM rule_automation_version WHERE project_id=? AND automation_id=? ORDER BY version_number DESC LIMIT 1",UUID.class,project,id));}
    private void enable(long limit)throws Exception{ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit="+limit+" WHERE id='"+policy+"'");}
    private int count(String table){return tx.execute(status->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);});}
    private void accept(AutomationPropertyAccepted event,long offset){ingress.accept(event.deviceId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),json.writeValueAsBytes(event),partition,offset);}
    private AutomationPropertyAccepted event(Instant accepted,Map<String,Object> input){accepted=accepted.truncatedTo(java.time.temporal.ChronoUnit.MICROS);return new AutomationPropertyAccepted(1,Uuid7.generate(),tenant,project,device,"1.0.0",accepted,accepted,input,"test");}
    private <T>T asOwner(Supplier<T> body){var previous=TenantContext.current();TenantContext.set(new TenantScope(tenant,project,account));try{return body.get();}finally{TenantContext.clear();previous.ifPresent(TenantContext::set);}}
    private Connection owner()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private void ownerUpdate(String sql)throws Exception{try(var c=owner();var q=c.createStatement()){q.execute(sql);}}
}
