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
class AutomationTimeLifecycleTests extends AbstractIntegrationTest {
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
            q.executeUpdate("DELETE FROM rule_automation_execution WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_event_receipt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("UPDATE rule_automation SET active_version_id=NULL,status='DRAFT' WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_version WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_automation_quota_reservation WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_usage_counter_daily WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_device WHERE tenant_id='"+tenant+"'");
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

    @Test void cronSnapshotsTimezoneAndPauseKeepsVersionFloor()throws Exception{
        enable();ownerUpdate("UPDATE sys_project SET timezone='America/New_York' WHERE id='"+project+"'");
        var d=create("cron",cron());UUID v=latest(d.id());
        assertThat(asOwner(()->management.getVersion(project,d.id(),v)).triggerConfig().path("timezone").asString()).isEqualTo("America/New_York");
        var active=asOwner(()->management.activate(project,d.id(),v,d.version()));Instant floor=state(v).floor();
        var paused=asOwner(()->management.pause(project,d.id(),active.version()));
        assertThat(schedule(d.id()).get("next_fire_at")).isNull();assertThat(state(v).floor()).isEqualTo(floor);
        ownerUpdate("UPDATE sys_project SET timezone='Asia/Shanghai' WHERE id='"+project+"'");
        asOwner(()->management.activate(project,d.id(),v,paused.version()));
        assertThat(asOwner(()->management.getVersion(project,d.id(),v)).triggerConfig().path("timezone").asString()).isEqualTo("America/New_York");
    }
    @Test void oldVersionRetainsFloorAcrossSwitchAndHistoryCleanup()throws Exception{
        enable();var d=create("switch",cron());UUID old=latest(d.id());var active=asOwner(()->management.activate(project,d.id(),old,1));
        Instant future=Instant.parse("2099-01-01T00:00:00Z");
        ownerUpdate("UPDATE rule_automation_schedule_state SET next_floor_at='2099-01-01T00:00:00Z' WHERE automation_version_id='"+old+"'");
        var revised=asOwner(()->management.revise(project,d.id(),active.version(),edit("switch",cron())));UUID next=latest(d.id());
        var newer=asOwner(()->management.activate(project,d.id(),next,revised.version()));
        assertThat(state(next).floor()).isBefore(future);
        tx.executeWithoutResult(s->{rls.establish(tenant,project);jdbc.queryForObject("SELECT automation_retention_batch(?,?)",Integer.class,tenant,project);});
        asOwner(()->management.activate(project,d.id(),old,newer.version()));
        assertThat(((Timestamp)schedule(d.id()).get("next_fire_at")).toInstant()).isEqualTo(future);
        assertThat(state(old).floor()).isEqualTo(future);
    }
    @Test void oneShotExpiredOrConsumedCannotRepublishEvenAfterClockRollback()throws Exception{
        enable();var d=create("once",once("2099-01-01T00:00:00Z"));UUID v=latest(d.id());var active=asOwner(()->management.activate(project,d.id(),v,1));
        ownerUpdate("UPDATE rule_automation_schedule_state SET one_shot_consumed=true WHERE automation_version_id='"+v+"'");
        assertThatThrownBy(()->asOwner(()->management.activate(project,d.id(),v,active.version()))).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThat(asOwner(()->management.get(project,d.id())).version()).isEqualTo(active.version());
        var expired=create("expired",once("2000-01-01T00:00:00Z"));
        assertThatThrownBy(()->asOwner(()->management.activate(project,expired.id(),latest(expired.id()),1))).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThat(count("rule_automation_schedule")).isEqualTo(1);
    }
    @Test void rollbackRemovesScheduleAndFloorAndPauseRollbackRestoresLease()throws Exception{
        enable();var d=create("rollback",cron());UUID v=latest(d.id());
        asOwner(()->{tx.executeWithoutResult(s->{management.activate(project,d.id(),v,1);s.setRollbackOnly();});return null;});
        assertThat(count("rule_automation_schedule")).isZero();assertThat(count("rule_automation_schedule_state")).isZero();
        var active=asOwner(()->management.activate(project,d.id(),v,1));var before=schedule(d.id());
        asOwner(()->{tx.executeWithoutResult(s->{management.pause(project,d.id(),active.version());s.setRollbackOnly();});return null;});
        assertThat(schedule(d.id())).isEqualTo(before);
    }
    @Test void ordinaryRoleCannotEraseOrRewindStateAndWrongScopeCannotRead()throws Exception{
        enable();var d=create("guards",cron());UUID v=latest(d.id());asOwner(()->management.activate(project,d.id(),v,1));
        for(String sql:List.of("DELETE FROM rule_automation_schedule_state WHERE project_id=?","UPDATE rule_automation_schedule_state SET next_floor_at=next_floor_at-interval '1 hour' WHERE project_id=?"))
            assertThatThrownBy(()->tx.executeWithoutResult(s->{rls.establish(tenant,project);jdbc.update(sql,project);})).isInstanceOf(org.springframework.dao.DataAccessException.class);
        ownerUpdate("UPDATE rule_automation_schedule_state SET one_shot_consumed=true WHERE automation_version_id='"+v+"'");
        assertThatThrownBy(()->tx.executeWithoutResult(s->{rls.establish(tenant,project);jdbc.update("UPDATE rule_automation_schedule_state SET one_shot_consumed=false WHERE project_id=?",project);})).isInstanceOf(org.springframework.dao.DataAccessException.class);
        tx.executeWithoutResult(s->{rls.establish(tenant,Uuid7.generate());assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_schedule_state WHERE project_id=?",Integer.class,project)).isZero();});
    }
    @Test void managementChangesInvalidateOldTokensAndPropertyVersionClearsOnlyFutureCursor()throws Exception{
        enable();var d=create("switch-property",cron());UUID v=latest(d.id());var active=asOwner(()->management.activate(project,d.id(),v,1));long token=((Number)schedule(d.id()).get("lease_token")).longValue();
        var property=new AutomationManagementService.Edit("switch-property",null,"PROPERTY_REPORTED",json.createObjectNode().put("deviceId",device.toString()),List.of(),actions());
        var revised=asOwner(()->management.revise(project,d.id(),active.version(),property));UUID pv=latest(d.id());
        assertThat(schedule(d.id()).get("next_fire_at")).isNotNull();
        var changed=asOwner(()->management.activate(project,d.id(),pv,revised.version()));
        assertThat(schedule(d.id()).get("next_fire_at")).isNull();assertThat(((Number)schedule(d.id()).get("lease_token")).longValue()).isGreaterThan(token);
        assertThat(state(v)).isNotNull();asOwner(()->{management.delete(project,d.id(),changed.version());return null;});assertThat(state(v)).isNotNull();
    }
    @Test void realProjectCleanupRemovesStateOnlyAtAuthorizedFinalDomainBoundary()throws Exception{
        enable();var d=create("cleanup",cron());asOwner(()->management.activate(project,d.id(),latest(d.id()),1));UUID token=Uuid7.generate();
        ownerUpdate("UPDATE sys_project SET status='PURGING',deleted_at=clock_timestamp()-interval '31 days',cleanup_stage='RULE',cleanup_started_at=clock_timestamp(),cleanup_next_attempt_at=clock_timestamp(),cleanup_lease_token='"+token+"',cleanup_lease_until=clock_timestamp()+interval '5 minutes' WHERE id='"+project+"'");
        assertThatThrownBy(()->cleanupBatch(Uuid7.generate())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(int i=0;i<10;i++)if(Boolean.TRUE.equals(cleanupBatch(token).get("complete")))break;
        assertThat(count("rule_automation_schedule_state")).isZero();assertThat(count("rule_automation_schedule")).isZero();assertThat(count("rule_automation_version")).isZero();
    }
    @Test void dstSkipsNonexistentLocalTimeAndExecutesBothRepeatedUtcPoints(){
        var c=timePolicy.normalize("CRON",cron().put("cronExpression","0 30 2 * * *").put("timezone","America/New_York"),"UTC");
        assertThat(timePolicy.next(c,Instant.parse("2026-03-08T06:59:00Z"))).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        c=timePolicy.normalize("CRON",cron().put("cronExpression","0 30 1 * * *").put("timezone","America/New_York"),"UTC");
        var first=timePolicy.next(c,Instant.parse("2026-11-01T04:00:00Z"));assertThat(first).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(timePolicy.next(c,first)).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
    }
    @Test void rejectsHighFrequencyMacrosUnknownFieldsAndOversizedOrImplicitInput(){
        for(String expression:List.of("* * * * * *","0,30 * * * * *","@hourly","0 0 * * *","0 0 0 * * * *"))
            assertThatThrownBy(()->timePolicy.normalize("CRON",cron().put("cronExpression",expression),"UTC")).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThatThrownBy(()->timePolicy.normalize("CRON",cron().put("timezone","+08:00"),"UTC")).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThatThrownBy(()->timePolicy.normalize("CRON",cron().put("shadow",true),"UTC")).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        var large=cron();large.set("payload",json.createObjectNode().put("value","x".repeat(16384)));
        assertThatThrownBy(()->timePolicy.normalize("CRON",large,"UTC")).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThat(timePolicy.normalize("CRON",cron(),"UTC").path("payload").isObject()).isTrue();
    }
    @Test void realExpiredExecutionCleanupCannotEraseCronFloorOrOneShotConsumption()throws Exception{
        enable();
        for(String type:List.of("CRON","ONE_SHOT")){
            var d=create("retained-"+type,type.equals("CRON")?cron():once("2099-01-01T00:00:00Z"));UUID v=latest(d.id());
            var active=asOwner(()->management.activate(project,d.id(),v,1));var paused=asOwner(()->management.pause(project,d.id(),active.version()));
            ownerUpdate("UPDATE rule_automation_schedule_state SET next_floor_at='2099-01-01T00:00:00Z',one_shot_consumed="+type.equals("ONE_SHOT")+" WHERE automation_version_id='"+v+"'");
            UUID execution=Uuid7.generate();
            try(var c=owner();var q=c.prepareStatement("""
                INSERT INTO rule_automation_execution(id,tenant_id,project_id,automation_id,automation_version_id,trigger_type,
                  occurrence_key,scheduled_fire_at,device_id,input_snapshot,input_digest,responsible_account_id,trace_id,status,reason_code,
                  created_at,completed_at,recovery_deadline,occurred_at,accepted_at)
                VALUES(?,?,?,?,?,?,?,clock_timestamp()-interval '800 hours',?,'{}',repeat('0',64),?,'retention-test','SKIPPED','CONDITION_FALSE',
                  clock_timestamp()-interval '800 hours',clock_timestamp()-interval '799 hours',clock_timestamp()-interval '776 hours',
                  clock_timestamp()-interval '800 hours',clock_timestamp()-interval '800 hours')
                """)){
                q.setObject(1,execution);q.setObject(2,tenant);q.setObject(3,project);q.setObject(4,d.id());q.setObject(5,v);q.setString(6,type);
                q.setString(7,"time:"+v+":retention-owned-fixture");q.setObject(8,device);q.setObject(9,account);q.executeUpdate();
            }
            assertThat(count("rule_automation_execution")).isEqualTo(1);
            tx.executeWithoutResult(t->{rls.establish(tenant,project);assertThat(jdbc.queryForObject("SELECT automation_retention_batch(?,?)",Integer.class,tenant,project)).isEqualTo(1);});
            assertThat(count("rule_automation_execution")).isZero();assertThat(state(v).floor()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
            if(type.equals("ONE_SHOT"))assertThatThrownBy(()->asOwner(()->management.activate(project,d.id(),v,paused.version()))).isInstanceOf(com.things.link.shared.error.BusinessException.class);
            else {asOwner(()->management.activate(project,d.id(),v,paused.version()));assertThat(((Timestamp)schedule(d.id()).get("next_fire_at")).toInstant()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));}
        }
    }
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
