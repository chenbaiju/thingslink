package com.things.link.bootstrap.rule;

import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.automation.*;
import com.things.link.rule.domain.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.TestPropertySource(properties={"things-link.automation.time.enabled=true","things-link.automation.time.scan-delay-millis=3600000","things-link.automation.execution.scan-delay-millis=3600000"})
class AutomationTimeAdmissionTests extends AbstractIntegrationTest {
    @Autowired AutomationManagementService management;
    @Autowired AutomationTimePolicy timePolicy;
    @Autowired AutomationScheduleRepository schedules;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    final JsonMapper json=JsonMapper.builder().build();
    UUID tenant,project,account,device,policy,collaborator,collaboratorTenant;
    int partition; final List<Long> kafkaOffsets=new ArrayList<>();
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
            q.executeUpdate("DELETE FROM rule_automation_schedule WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_schedule_state WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_tenant_work_slot WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_outbox_event WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_device_action_delivery WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM ts_device_command_claim WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM ts_device_command_attempt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM ts_device_command WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_notification_delivery WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_attempt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_execution WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_event_receipt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("UPDATE rule_automation SET active_version_id=NULL,status='DRAFT' WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_version WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_automation_quota_reservation WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_usage_counter_daily WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_access_binding WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_device WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_command_definition WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_property_definition WHERE tenant_id='"+tenant+"'");
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

    @Autowired AutomationTimeAdmissionService admission;
    @Autowired AutomationTimeScheduler scanner;
    @Autowired AutomationExecutionRunner executionRunner;
    @Autowired AutomationExecutionRepository executions;
    @Autowired com.things.link.support.scheduling.TenantWorkSlotRepository slots;
    private AutomationScheduleRepository.Candidate due(String name,String type)throws Exception{
        enable();JsonNode config=type.equals("CRON")?cron():once("2099-01-01T00:00:00Z");var d=create(name,config);UUID v=latest(d.id());asOwner(()->management.activate(project,d.id(),v,1));
        // 仅夹具让合法已发布未来点变为到期，生产受理仍使用真实数据库时钟/租约/版本。
        ownerUpdate("UPDATE rule_automation_schedule SET next_fire_at=clock_timestamp()-interval '2 hours' WHERE automation_id='"+d.id()+"'");
        return new AutomationScheduleRepository.Candidate(tenant,project,d.id());
    }
    private com.things.link.support.scheduling.TenantWorkSlotRepository.Lease lease(){return slots.tryAcquire(com.things.link.support.scheduling.TenantWorkSlotRepository.WorkType.AUTOMATION,tenant,Duration.ofSeconds(30)).orElseThrow();}
    @Test void overdueCronAdmitsOnlyOneThenAdvancesToFutureAndDispatchesOnce()throws Exception{
        var c=due("cron","CRON");scanner.run(c);scanner.run(c);
        assertThat(count("rule_automation_execution")).isEqualTo(1);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        var candidate=executions.candidates().stream().filter(e->e.projectId().equals(project)).findFirst().orElseThrow();executionRunner.run(candidate);executionRunner.run(candidate);
        tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(jdbc.queryForMap("SELECT trigger_type,status,source_event_id,occurred_at=scheduled_fire_at AS source_time,accepted_at=created_at AS acceptance FROM rule_automation_execution WHERE id=?",candidate.executionId()))
          .containsEntry("trigger_type","CRON").containsEntry("status","DISPATCHED").containsEntry("source_event_id",null).containsEntry("source_time",true).containsEntry("acceptance",true);});
        assertThat(count("rule_notification_delivery")).isEqualTo(1);assertThat(count("sys_outbox_event")).isEqualTo(1);
        assertThat(((Timestamp)schedule(c.automationId()).get("next_fire_at")).toInstant()).isAfter(Instant.now());
    }
    /** R4-3d：真实时间受理接通两种设备动作至持久派发；不冒充Broker或物理设备确认。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ONE_SHOT","CRON"})
    void timeTriggersPersistCommandAndMqttPropertySetExactlyOnce(String triggerType)throws Exception{
        enable();seedTimeActionDevice();
        JsonNode config=triggerType.equals("CRON")?cron():once("2099-01-01T00:00:00Z");
        var actions=List.of(
                new ActionSpec("device-command-action",json.createObjectNode().put("commandKey","reboot").set("input",json.createObjectNode())),
                new ActionSpec("device-property-set-action",json.createObjectNode().set("properties",json.createObjectNode().put("power",true))));
        var definition=asOwner(()->management.create(project,new AutomationManagementService.Edit(
                "time device actions",null,triggerType,config,List.of(),actions)));
        UUID version=latest(definition.id());
        asOwner(()->management.activate(project,definition.id(),version,definition.version()));
        // 只移动本次合法发布的发生点；这是受控到期组合，租约/受理/动作仍用生产服务与真实PG。
        ownerUpdate("UPDATE rule_automation_schedule SET next_fire_at=clock_timestamp()-interval '2 hours' WHERE automation_id='"+definition.id()+"'");
        Instant fireAt=((Timestamp)schedule(definition.id()).get("next_fire_at")).toInstant();
        var scheduleCandidate=new AutomationScheduleRepository.Candidate(tenant,project,definition.id());
        scanner.run(scheduleCandidate);scanner.run(scheduleCandidate);
        var candidate=executions.candidates().stream().filter(item->item.projectId().equals(project)).findFirst().orElseThrow();
        executionRunner.run(candidate);executionRunner.run(candidate);scanner.run(scheduleCandidate);
        assertThat(count("rule_automation_execution")).isEqualTo(1);
        assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        assertThat(count("rule_device_action_delivery")).isEqualTo(2);
        assertThat(count("ts_device_command")).isEqualTo(2);
        assertThat(count("ts_device_command_attempt")).isEqualTo(2);
        assertThat(count("rule_notification_delivery")).isZero();
        tx.executeWithoutResult(status->{
            rls.establish(tenant,project);
            assertThat(jdbc.queryForMap("SELECT trigger_type,status,automation_version_id,scheduled_fire_at,attempt_count FROM rule_automation_execution WHERE id=?",candidate.executionId()))
                    .containsEntry("trigger_type",triggerType).containsEntry("status","DISPATCHED")
                    .containsEntry("automation_version_id",version).containsEntry("scheduled_fire_at",Timestamp.from(fireAt)).containsEntry("attempt_count",1);
            assertThat(jdbc.queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=? AND metric='AUTOMATION_EXECUTION'",Long.class,project)).isEqualTo(1L);
            assertThat(jdbc.queryForList("""
                    SELECT command.operation_type
                    FROM rule_device_action_delivery delivery
                    JOIN ts_device_command command ON command.id=delivery.command_id
                    JOIN ts_device_command_attempt attempt ON attempt.command_id=command.id
                    JOIN sys_outbox_event outbox ON outbox.id=attempt.outbox_event_id
                    WHERE delivery.project_id=? AND delivery.automation_execution_id=?
                      AND command.target_device_id=? AND attempt.attempt_no=1 AND attempt.status='PENDING'
                      AND outbox.event_type='DEVICE_COMMAND_DISPATCH'
                    """,String.class,project,candidate.executionId(),device)).containsExactlyInAnyOrder("COMMAND","PROPERTY_SET");
            List<String> payloads=jdbc.queryForList("SELECT payload::text FROM sys_outbox_event WHERE project_id=? AND event_type='DEVICE_COMMAND_DISPATCH'",String.class,project);
            assertThat(payloads).hasSize(2);
            for(String payload:payloads){
                var dispatch=json.readValue(payload,com.things.link.shared.message.DeviceCommandDispatch.class);
                assertThat(dispatch.projectId()).isEqualTo(project);assertThat(dispatch.tenantId()).isEqualTo(tenant);
                assertThat(dispatch.targetDeviceId()).isEqualTo(device);assertThat(dispatch.attemptNo()).isEqualTo(1);
                if(dispatch.operationType()==com.things.link.shared.message.DeviceCommandDispatch.OperationType.PROPERTY_SET){
                    assertThat(dispatch.commandKey()).isNull();assertThat(json.readTree(dispatch.inputJson()).path("power").asBoolean()).isTrue();
                }else assertThat(dispatch.commandKey()).isEqualTo("reboot");
            }
        });
        if(triggerType.equals("ONE_SHOT"))assertThat(schedule(definition.id()).get("next_fire_at")).isNull();
        else assertThat(((Timestamp)schedule(definition.id()).get("next_fire_at")).toInstant()).isAfter(Instant.now());
    }
    private void seedTimeActionDevice()throws Exception{
        UUID type=Uuid7.generate();
        ownerUpdate("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES ('"+type+"','"+tenant+"','"+project+"','time-action','time-action','DIRECT','STANDARD','WIFI','PUBLISHED')");
        ownerUpdate("UPDATE dev_device SET device_type_id='"+type+"' WHERE id='"+device+"'");
        ownerUpdate("INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES ('"+Uuid7.generate()+"','"+tenant+"','"+project+"','"+type+"','reboot','reboot','{}','{}',30)");
        ownerUpdate("INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) VALUES ('"+Uuid7.generate()+"','"+tenant+"','"+project+"','"+type+"','power','power','SHARED','SWITCH')");
        ownerUpdate("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES ('"+device+"','"+tenant+"','"+project+"','MQTT')");
    }

    @Test void oneShotRejectionConsumesOccurrenceWithoutQuotaAndNeverRearms()throws Exception{
        var c=due("once","ONE_SHOT");ownerUpdate("UPDATE sys_project_member SET role='VIEWER' WHERE project_id='"+project+"'");scanner.run(c);scanner.run(c);
        assertThat(count("rule_automation_execution")).isEqualTo(1);assertThat(count("sys_automation_quota_reservation")).isZero();
        tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(jdbc.queryForMap("SELECT status,reason_code FROM rule_automation_execution WHERE project_id=?",project)).containsEntry("status","REJECTED").containsEntry("reason_code","AUTH_REVOKED");});
        assertThat(schedule(c.automationId()).get("next_fire_at")).isNull();
        ownerUpdate("UPDATE sys_project_member SET role='OWNER' WHERE project_id='"+project+"'");assertThat(state(latest(c.automationId())).consumed()).isTrue();
    }
    @Test void quotaAndArchivedProjectPersistDeterministicRejectionWithoutRetry()throws Exception{
        var c=due("quota","CRON");ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit=0 WHERE id='"+policy+"'");scanner.run(c);
        assertReason("QUOTA");assertThat(count("sys_automation_quota_reservation")).isZero();
        ownerUpdate("UPDATE sys_project SET status='ARCHIVED' WHERE id='"+project+"'");
        ownerUpdate("UPDATE rule_automation_schedule SET next_fire_at=clock_timestamp()-interval '1 second' WHERE automation_id='"+c.automationId()+"'");scanner.run(c);
        tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_execution WHERE project_id=? AND reason_code='PROJECT_READ_ONLY'",Integer.class,project)).isEqualTo(1);});
    }
    @Test void staleTokenAndNewPublicationRejectOldClaimWithoutTouchingNewCursor()throws Exception{
        var c=due("stale","CRON");var lease=lease();try{
            var claim=admission.claim(c,lease).orElseThrow();var d=asOwner(()->management.get(project,c.automationId()));
            asOwner(()->management.pause(project,c.automationId(),d.version()));var paused=schedule(c.automationId());
            assertThat(admission.accept(claim,lease)).isFalse();assertThat(schedule(c.automationId())).isEqualTo(paused);assertThat(count("rule_automation_execution")).isZero();
        }finally{slots.release(lease);}
    }
    @Test void lostLeaseAtFinalFenceRollsBackExecutionQuotaAndCursor()throws Exception{
        var c=due("fence","CRON");var lease=lease();String fn="rule_auto_time_fence_"+partition;
        try{
            var claim=admission.claim(c,lease).orElseThrow();var before=schedule(c.automationId());
            ownerUpdate("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid THEN UPDATE rule_automation_schedule SET lease_until=clock_timestamp()-interval '1 second' WHERE project_id=NEW.project_id; END IF; RETURN NEW; END $$");
            ownerUpdate("CREATE TRIGGER "+fn+" AFTER INSERT ON rule_automation_execution FOR EACH ROW EXECUTE FUNCTION "+fn+"()");
            assertThatThrownBy(()->admission.accept(claim,lease)).isInstanceOf(AutomationTimeAdmissionService.StaleScheduleLeaseException.class);
            assertThat(count("rule_automation_execution")).isZero();assertThat(count("sys_automation_quota_reservation")).isZero();assertThat(schedule(c.automationId())).isEqualTo(before);
        }finally{ownerUpdate("DROP TRIGGER IF EXISTS "+fn+" ON rule_automation_execution");ownerUpdate("DROP FUNCTION IF EXISTS "+fn+"()");slots.release(lease);}
    }
    @Test void transientDatabaseFailureKeepsOccurrenceForLaterClaim()throws Exception{
        var c=due("db","CRON");String fn="rule_auto_time_fault_"+partition;var before=schedule(c.automationId());
        try{
            ownerUpdate("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid THEN RAISE EXCEPTION 'owned time admission fault' USING ERRCODE='08006'; END IF; RETURN NEW; END $$");
            ownerUpdate("CREATE TRIGGER "+fn+" BEFORE INSERT ON rule_automation_execution FOR EACH ROW EXECUTE FUNCTION "+fn+"()");
            assertThatThrownBy(()->scanner.run(c)).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(schedule(c.automationId()).get("next_fire_at")).isEqualTo(before.get("next_fire_at"));assertThat(count("rule_automation_execution")).isZero();assertThat(count("sys_automation_quota_reservation")).isZero();
        }finally{ownerUpdate("DROP TRIGGER IF EXISTS "+fn+" ON rule_automation_execution");ownerUpdate("DROP FUNCTION IF EXISTS "+fn+"()");}
        ownerUpdate("UPDATE rule_automation_schedule SET lease_until=clock_timestamp()-interval '1 second' WHERE automation_id='"+c.automationId()+"'");scanner.run(c);assertThat(count("rule_automation_execution")).isEqualTo(1);
    }
    @Test void earlyClockAndTenantSlotPreventClaimAndCallerIdentityIsRestored()throws Exception{
        var c=due("early","CRON");var held=lease();try{scanner.run(c);assertThat(count("rule_automation_execution")).isZero();}finally{slots.release(held);}
        ownerUpdate("UPDATE rule_automation_schedule SET next_fire_at=clock_timestamp()+interval '1 hour' WHERE automation_id='"+c.automationId()+"'");
        var previous=new TenantScope(Uuid7.generate(),Uuid7.generate(),Uuid7.generate());TenantContext.set(previous);scanner.run(c);assertThat(TenantContext.current()).contains(previous);TenantContext.clear();
        assertThat(count("rule_automation_execution")).isZero();
    }
    @Test void scannerReturnsOnlyTenantHeadAndCursorExcludesAlreadyVisitedTenant()throws Exception{
        var c=due("head1","CRON");due("head2","CRON");
        assertThat(schedules.candidates(null).stream().filter(x->x.tenantId().equals(tenant))).hasSize(1);
        assertThat(schedules.candidates(tenant).stream().filter(x->x.tenantId().equals(tenant))).isEmpty();
        var held=lease();try{var claimed=admission.claim(c,held).orElseThrow();assertThat(admission.claim(c,held)).isEmpty();}finally{slots.release(held);}
    }
    @Test void twoThreadsCompeteForOneTenantAndOnlyOneOccurrenceIsCharged()throws Exception{
        var c=due("concurrent","CRON");var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();scanner.run(c);return null;});var b=pool.submit(()->{start.await();scanner.run(c);return null;});start.countDown();
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(count("rule_automation_execution")).isEqualTo(1);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
    }
    @Test void expiredClaimCannotAdvanceAfterAnotherTokenTakesOver()throws Exception{
        var c=due("takeover","CRON");var first=lease();var old=admission.claim(c,first).orElseThrow();slots.release(first);
        ownerUpdate("UPDATE rule_automation_schedule SET lease_until=clock_timestamp()-interval '1 second' WHERE automation_id='"+c.automationId()+"'");
        var second=lease();try{var current=admission.claim(c,second).orElseThrow();assertThat(current.token()).isGreaterThan(old.token());
            assertThat(admission.accept(old,second)).isFalse();assertThat(admission.accept(current,second)).isTrue();assertThat(admission.accept(current,second)).isFalse();
        }finally{slots.release(second);}
        assertThat(count("rule_automation_execution")).isEqualTo(1);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
    }
    private void assertReason(String reason){tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(jdbc.queryForObject("SELECT reason_code FROM rule_automation_execution WHERE project_id=?",String.class,project)).isEqualTo(reason);});}
    private Map<String,Object> cleanupBatch(UUID token){return tx.execute(s->jdbc.queryForMap("SELECT * FROM rule_project_cleanup_batch(?,?,(SELECT lifecycle_generation FROM sys_project WHERE id=?),?)",tenant,project,project,token));}
    private AutomationDefinition create(String name,JsonNode config){return asOwner(()->management.create(project,edit(name,config)));}
    private AutomationManagementService.Edit edit(String name,JsonNode config){return new AutomationManagementService.Edit(name,null,config.has("runAt")?"ONE_SHOT":"CRON",config,List.of(),actions());}
    private List<ActionSpec> actions(){return List.of(new ActionSpec("notification-action",json.createObjectNode().put("channel","email").put("recipient","test@example.com").put("subject","test").put("body","test")));}
    private tools.jackson.databind.node.ObjectNode cron(){return json.createObjectNode().put("deviceId",device.toString()).put("cronExpression","0 * * * * *");}
    private JsonNode once(String at){return json.createObjectNode().put("deviceId",device.toString()).put("runAt",at);}
    private AutomationScheduleRepository.State state(UUID v){return tx.execute(s->{rls.establish(tenant,project);return schedules.state(project,v).orElseThrow();});}
    private Map<String,Object> schedule(UUID id){return tx.execute(s->{rls.establish(tenant,project);return jdbc.queryForMap("SELECT * FROM rule_automation_schedule WHERE project_id=? AND automation_id=?",project,id);});}
    private UUID latest(UUID id){return asOwner(()->jdbc.queryForObject("SELECT id FROM rule_automation_version WHERE project_id=? AND automation_id=? ORDER BY version_number DESC LIMIT 1",UUID.class,project,id));}
    private void enable()throws Exception{ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit=10 WHERE id='"+policy+"'");}
    private int count(String table){return tx.execute(s->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);});}
    private <T>T asOwner(Supplier<T> body){var previous=TenantContext.current();TenantContext.set(new TenantScope(tenant,project,account));try{return body.get();}finally{TenantContext.clear();previous.ifPresent(TenantContext::set);}}
    private Connection owner()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private void ownerUpdate(String sql)throws Exception{try(var c=owner();var q=c.createStatement()){q.execute(sql);}}
}
