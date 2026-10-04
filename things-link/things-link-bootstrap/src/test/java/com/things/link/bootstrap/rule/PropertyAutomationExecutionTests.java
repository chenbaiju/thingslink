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

/** 真实PG租约、原事务意图及永久拒绝；不宣称外部通知送达。 */
@org.springframework.context.annotation.Import(PropertyAutomationExecutionTests.IsolatedDatabase.class)
@com.things.link.testing.OwnedTestContainers({"AUTOMATION_POSTGRES"})
class PropertyAutomationExecutionTests extends AbstractIntegrationTest {
    // 全局通知领取不能依靠随机tenant隔离，专库同时隔离其他缓存上下文的worker。
    private static final org.testcontainers.containers.PostgreSQLContainer<?> AUTOMATION_POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
                    .parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("automation_execution").withUsername(POSTGRES.getUsername())
                    .withPassword(POSTGRES.getPassword());
    private static final String DATABASE_URL = startDatabase();
    private static String startDatabase() { AUTOMATION_POSTGRES.start(); return AUTOMATION_POSTGRES.getJdbcUrl(); }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class IsolatedDatabase {
        @org.springframework.context.annotation.Bean
        org.springframework.test.context.DynamicPropertyRegistrar databaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
            };
        }
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.things.link.support.scheduling.NotificationWorkCoordinator notificationCoordinator;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.things.link.rule.application.RuleNotificationRetryScheduler notificationRetryScheduler;
    @Override protected Connection fixtureOwnerConnection() throws SQLException { return owner(); }

    @Autowired AutomationManagementService management;
    @Autowired AutomationEventIngress ingress;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired AutomationExecutionRunner runner;
    @Autowired com.things.link.rule.application.RuleNotificationDeliveryStore notifications;
    @Autowired com.things.link.telemetry.application.DeviceCommandClaimPort commandClaims;
    @Autowired com.things.link.rule.domain.AutomationExecutionRepository executions;
    /** 原属性动作命令状态机，测试不以手造attempt替代受理。 */
    @Autowired com.things.link.telemetry.application.DeviceCommandService commands;
    /** 真实持久重试领取。 */
    @Autowired com.things.link.telemetry.domain.DeviceCommandRepository commandRepository;
    /** 真实控制面拓扑变更，不用直接SQL伪装绑定动作。 */
    @Autowired com.things.link.device.application.DeviceTopologyService topology;
    /** 真实软删和拓扑级联入口。 */
    @Autowired com.things.link.device.application.DeviceService deviceManagement;
    final JsonMapper json=JsonMapper.builder().build();
    UUID tenant,project,account,device,policy,collaborator,collaboratorTenant;
    int partition;
    final List<Long> kafkaOffsets=new ArrayList<>();
    @BeforeEach void seed()throws Exception{
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(AUTOMATION_POSTGRES.getDatabaseName());
        assertThat(jdbc.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
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
            q.executeUpdate("DELETE FROM alarm_event WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM alarm_instance WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM alarm_rule WHERE tenant_id='"+tenant+"'");
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
    @Test void executesFrozenVersionOnceAndKeepsQuotaAndProvenance()throws Exception{
        enable(4);var definition=active("one");accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();
        var revised=asOwner(()->management.revise(project,definition.id(),definition.version(),edit("new")));
        asOwner(()->management.activate(project,definition.id(),latest(definition.id()),revised.version()));
        runner.run(c);runner.run(c);
        assertThat(state()).isEqualTo("DISPATCHED");assertThat(count("rule_automation_attempt")).isEqualTo(1);
        assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);assertThat(count("sys_outbox_event")).isEqualTo(1);
        asOwner(()->{var payload=jdbc.queryForObject("SELECT payload::text FROM sys_outbox_event WHERE tenant_id=?",String.class,tenant);
            var request=json.readValue(payload,com.things.link.shared.message.RuleNotificationDeliveryRequest.class);
            assertThat(request.automationExecutionId()).isEqualTo(c.executionId());
            assertThat(request.automationVersionId()).isEqualTo(definition.activeVersionId());
            assertThat(count("rule_notification_delivery")).isEqualTo(1);
            tx.executeWithoutResult(status->{rls.establish(tenant,project);
                assertThat(notifications.accept(request)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.READY);
                assertThat(notifications.accept(request)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.READY);});
            assertThat(count("rule_notification_delivery")).isEqualTo(1);
            var claim=notifications.claimDispatches(10,Duration.ofSeconds(30));
            var picked=claim.deliveries().stream().filter(v->v.projectId().equals(project)).findFirst().orElseThrow();
            Instant now=Instant.now();
            assertThat(notifications.startClaimed(project,picked.id(),1,claim.leaseToken(),now,now.plusSeconds(30))).isTrue();
            assertThat(notifications.startClaimed(project,picked.id(),1,claim.leaseToken(),now,now.plusSeconds(30))).isFalse();
            assertThat(notifications.accept(request)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.IDEMPOTENT_REPLAY);
            return null;});
    }
    @Test void permanentDeviceRejectionRollsBackEarlierNotificationAndDoesNotRetry()throws Exception{
        enable(4);var edit=edit("reject");var actions=new ArrayList<>(edit.actions());
        actions.add(new ActionSpec("device-command-action",json.createObjectNode().put("commandKey","missing-command").set("input",json.createObjectNode())));
        var d=asOwner(()->management.create(project,new AutomationManagementService.Edit(edit.name(),null,edit.triggerType(),edit.triggerConfig(),edit.conditions(),actions)));
        asOwner(()->management.activate(project,d.id(),latest(d.id()),1));accept(event(Instant.now(),Map.of("x",1)),1);
        var c=candidate();runner.run(c);runner.run(c);
        assertThat(state()).isEqualTo("FAILED");assertThat(reason()).isEqualTo("ACTION_REJECTED");
        assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();assertThat(count("rule_device_action_delivery")).isZero();
        assertThat(count("rule_automation_attempt")).isEqualTo(1);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
    }
    @Test void pausedAndRevokedExecutionsRejectBeforeAnyAction()throws Exception{
        enable(4);var d=active("paused");accept(event(Instant.now(),Map.of("x",1)),1);
        asOwner(()->management.pause(project,d.id(),d.version()));runner.run(candidate());
        assertThat(state()).isEqualTo("REJECTED");assertThat(reason()).isEqualTo("DEFINITION_DISABLED");assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();
    }
    @Test void revokedAuthorRejectsFrozenExecution()throws Exception{
        enable(4);active("revoked");accept(event(Instant.now(),Map.of("x",1)),1);
        ownerUpdate("UPDATE sys_account SET status='DISABLED' WHERE id='"+account+"'");runner.run(candidate());
        assertThat(state()).isEqualTo("REJECTED");assertThat(reason()).isEqualTo("AUTH_REVOKED");assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();
    }
    @Test void transientDatabaseFailureRetriesOnlyThreeTimesWithoutChargingAgain()throws Exception{
        enable(4);active("transient");accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();
        String trigger="auto_fault_"+partition;
        fault(trigger,"RAISE EXCEPTION 'owned transient fixture' USING ERRCODE='40001';");
        try{
            runner.run(c);assertThat(state()).isEqualTo("RETRY_WAIT");assertDelay(60);
            due();runner.run(c);assertThat(state()).isEqualTo("RETRY_WAIT");assertDelay(300);
            due();runner.run(c);assertThat(state()).isEqualTo("FAILED");assertThat(reason()).isEqualTo("RECOVERY_EXHAUSTED");
            assertThat(count("rule_automation_attempt")).isEqualTo(3);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
            assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();
        }finally{dropFault(trigger);}
    }
    @Test void finalLeaseCheckRollsBackEarlierIntentsAndCrashRecoveryKeepsIdentity()throws Exception{
        enable(4);active("lease");accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();
        String trigger="auto_expire_"+partition;
        fault(trigger,"UPDATE public.rule_automation_execution SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+c.executionId()+"';");
        try{runner.run(c);assertThat(state()).isEqualTo("RUNNING");assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();}
        finally{dropFault(trigger);}
        ownerUpdate("UPDATE rule_automation_execution SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+c.executionId()+"'");
        runner.run(c);assertThat(state()).isEqualTo("RETRY_WAIT");
        asOwner(()->{assertThat(jdbc.queryForObject("SELECT outcome FROM rule_automation_attempt WHERE execution_id=?",String.class,c.executionId())).isEqualTo("LEASE_EXPIRED");return null;});
        due();runner.run(c);assertThat(state()).isEqualTo("DISPATCHED");assertThat(count("sys_outbox_event")).isEqualTo(1);
        assertThat(count("rule_automation_attempt")).isEqualTo(2);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
    }
    @Test void archivedProjectClosesExistingExecutionWithoutBorrowingThreadIdentity()throws Exception{
        enable(4);active("archived");accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();
        ownerUpdate("UPDATE sys_project SET status='ARCHIVED' WHERE id='"+project+"'");
        var foreign=new TenantScope(Uuid7.generate(),Uuid7.generate(),Uuid7.generate());TenantContext.set(foreign);
        try{runner.run(c);assertThat(TenantContext.current()).contains(foreign);}finally{TenantContext.clear();}
        assertThat(state()).isEqualTo("REJECTED");assertThat(reason()).isEqualTo("PROJECT_READ_ONLY");assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();
    }
    @Test void alarmPermanentRejectionAlsoRollsBackNotification()throws Exception{
        enable(4);var edit=edit("bad alarm");var actions=new ArrayList<>(edit.actions());
        actions.add(new ActionSpec("alarm-create-action",json.createObjectNode().put("alarmRuleId",Uuid7.generate().toString())));
        var d=asOwner(()->management.create(project,new AutomationManagementService.Edit(edit.name(),null,edit.triggerType(),edit.triggerConfig(),edit.conditions(),actions)));
        asOwner(()->management.activate(project,d.id(),latest(d.id()),1));accept(event(Instant.now(),Map.of("x",1)),1);
        runner.run(candidate());assertThat(state()).isEqualTo("FAILED");assertThat(reason()).isEqualTo("ACTION_REJECTED");
        assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();
    }
    @Test void falseConditionConsumesOneAdmissionButNoAction()throws Exception{
        enable(4);var edit=edit("false");
        var condition=new com.things.link.rule.application.ConditionSpec("payload-property-compare",
                json.createObjectNode().put("pointer","/x").put("operator","GT").put("value",100));
        var d=asOwner(()->management.create(project,new AutomationManagementService.Edit(edit.name(),null,edit.triggerType(),edit.triggerConfig(),List.of(condition),edit.actions())));
        asOwner(()->management.activate(project,d.id(),latest(d.id()),1));accept(event(Instant.now(),Map.of("x",1)),1);
        runner.run(candidate());assertThat(state()).isEqualTo("SKIPPED");assertThat(reason()).isEqualTo("CONDITION_FALSE");
        assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
    }
    @Test void terminalAndAttemptHistoryCannotBeRewritten()throws Exception{
        enable(4);active("immutable");accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();runner.run(c);
        asOwner(()->{
            assertThatThrownBy(()->jdbc.update("UPDATE rule_automation_execution SET status='QUEUED',completed_at=NULL WHERE id=?",c.executionId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE rule_automation_execution SET input_snapshot='{}' WHERE id=?",c.executionId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE rule_automation_attempt SET reason_code='CONFIG_INVALID' WHERE execution_id=?",c.executionId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return null;
        });
        assertThat(state()).isEqualTo("DISPATCHED");
    }
    @Test void concurrentWorkersCannotClaimTheSameExecutionTwice()throws Exception{
        enable(4);active("concurrent");accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();runner.run(c);return null;});
            var b=pool.submit(()->{start.await();runner.run(c);return null;});start.countDown();
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertThat(state()).isEqualTo("DISPATCHED");assertThat(count("rule_automation_attempt")).isEqualTo(1);
        assertThat(count("sys_outbox_event")).isEqualTo(1);
    }
    @Test void expiredTenantSlotCannotCommitEvenWithValidExecutionLease()throws Exception{
        enable(4);active("slot fence");accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();String name="auto_slot_"+partition;
        ownerUpdate("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid THEN UPDATE public.sys_tenant_work_slot SET leased_until=clock_timestamp()-interval '1 second' WHERE work_type='AUTOMATION' AND tenant_id='"+tenant+"'; END IF; RETURN NEW; END $$");
        ownerUpdate("CREATE TRIGGER "+name+" BEFORE INSERT ON sys_outbox_event FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        try{runner.run(c);assertThat(state()).isEqualTo("RUNNING");assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();}
        finally{dropFault(name);}
    }
    @Test void tenantSlotTakeoverAcrossPhysicalConnectionsRejectsOldToken()throws Exception{
        var type=com.things.link.support.scheduling.TenantWorkSlotRepository.WorkType.AUTOMATION;
        try(var first=DriverManager.getConnection(DATABASE_URL,APP_ROLE,APP_ROLE_PASSWORD);
            var second=DriverManager.getConnection(DATABASE_URL,APP_ROLE,APP_ROLE_PASSWORD)){
            var a=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(first,true));
            var b=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(second,true));
            assertThat(a.queryForObject("SELECT pg_backend_pid()",Integer.class)).isNotEqualTo(b.queryForObject("SELECT pg_backend_pid()",Integer.class));
            var one=new com.things.link.support.scheduling.JdbcTenantWorkSlotRepository(a);
            var two=new com.things.link.support.scheduling.JdbcTenantWorkSlotRepository(b);
            var old=one.tryAcquire(type,tenant,Duration.ofSeconds(30)).orElseThrow();
            assertThat(two.tryAcquire(type,tenant,Duration.ofSeconds(30))).isEmpty();
            ownerUpdate("UPDATE sys_tenant_work_slot SET leased_until=clock_timestamp()-interval '1 second' WHERE work_type='AUTOMATION' AND tenant_id='"+tenant+"'");
            var current=two.tryAcquire(type,tenant,Duration.ofSeconds(30)).orElseThrow();
            first.setAutoCommit(false);assertThat(one.fence(old)).isFalse();first.commit();
            assertThat(one.release(old)).isFalse();second.setAutoCommit(false);assertThat(two.fence(current)).isTrue();second.commit();second.setAutoCommit(true);
            assertThat(two.release(current)).isTrue();
        }
    }
    @Test void persistedInputAndConditionKeepDecimalPrecision()throws Exception{
        enable(4);var edit=edit("precision");
        var condition=new com.things.link.rule.application.ConditionSpec("payload-property-compare",json.createObjectNode()
                .put("pointer","/x").put("operator","GT").put("value",new java.math.BigDecimal("0.10000000000000000001")));
        var d=asOwner(()->management.create(project,new AutomationManagementService.Edit(edit.name(),null,edit.triggerType(),edit.triggerConfig(),List.of(condition),edit.actions())));
        asOwner(()->management.activate(project,d.id(),latest(d.id()),1));
        accept(event(Instant.now(),Map.of("x",new java.math.BigDecimal("0.10000000000000000002"))),1);
        runner.run(candidate());assertThat(state()).isEqualTo("DISPATCHED");
        var stored=asOwner(()->management.getVersion(project,d.id(),latest(d.id())));
        assertThat(stored.conditions().get(0).path("config").path("value").decimalValue()).isEqualByComparingTo("0.10000000000000000001");
    }
    @Test void mqttCommandAndPropertyActionsPersistRealDeliveryAndDownlinkTogether()throws Exception{
        enable(4);seedActionDevice();
        var edit=edit("mqtt");var actions=List.of(
                new ActionSpec("device-command-action",json.createObjectNode().put("commandKey","reboot").set("input",json.createObjectNode())),
                new ActionSpec("device-property-set-action",json.createObjectNode().set("properties",json.createObjectNode().put("power",true))));
        var d=asOwner(()->management.create(project,new AutomationManagementService.Edit(edit.name(),null,edit.triggerType(),edit.triggerConfig(),List.of(),actions)));
        asOwner(()->management.activate(project,d.id(),latest(d.id()),1));accept(event(Instant.now(),Map.of("x",1)),1);var c=candidate();
        runner.run(c);runner.run(c);assertThat(state()).isEqualTo("DISPATCHED");
        assertThat(count("rule_device_action_delivery")).isEqualTo(2);assertThat(count("ts_device_command")).isEqualTo(2);
        assertThat(count("ts_device_command_attempt")).isEqualTo(2);assertThat(count("sys_outbox_event")).isEqualTo(2);
        asOwner(()->{assertThat(jdbc.queryForList("SELECT automation_execution_id FROM rule_device_action_delivery WHERE project_id=?",UUID.class,project))
                .containsExactly(c.executionId(),c.executionId());return null;});
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"HTTP","COAP","TCP"})
    void commandActionsUseRealTransportShape(String protocol)throws Exception{
        enable(4);seedActionDevice();bind(protocol);
        var edit=edit("command "+protocol);var actions=List.of(new ActionSpec("device-command-action",
                json.createObjectNode().put("commandKey","reboot").set("input",json.createObjectNode())));
        publishActions(edit,actions);accept(event(Instant.now(),Map.of("x",1)),1);runner.run(candidate());
        assertThat(state()).isEqualTo("DISPATCHED");assertThat(count("ts_device_command")).isEqualTo(1);
        assertThat(count("rule_device_action_delivery")).isEqualTo(1);
        if(protocol.equals("TCP")){
            assertThat(count("sys_outbox_event")).isEqualTo(1);assertThat(count("ts_device_command_attempt")).isEqualTo(1);
        }else{
            assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();assertThat(count("ts_device_command_attempt")).isZero();
            var claimed=commandClaims.claim(new com.things.link.telemetry.application.DeviceCommandClaimPort.ClaimRequest(
                    tenant,project,device,Duration.ofSeconds(30),1));
            assertThat(claimed).singleElement().satisfies(command->{assertThat(command.commandKey()).isEqualTo("reboot");assertThat(command.attempt()).isEqualTo(1);});
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"HTTP","COAP","TCP","MQTT"})
    void nonMqttPropertySetRejectsWholeAutomation(String protocol)throws Exception{
        enable(4);seedActionDevice();bind(protocol);
        if (protocol.equals("MQTT")) ownerUpdate("UPDATE dev_access_binding SET enabled=false,config_version=config_version+1 WHERE device_id='"+device+"'");var edit=edit("property "+protocol);var actions=new ArrayList<>(edit.actions());
        actions.add(new ActionSpec("device-property-set-action",json.createObjectNode().set("properties",json.createObjectNode().put("power",true))));
        publishActions(edit,actions);accept(event(Instant.now(),Map.of("x",1)),1);runner.run(candidate());
        assertThat(state()).isEqualTo("FAILED");assertThat(reason()).isEqualTo("ACTION_REJECTED");
        assertThat(count("ts_device_command")).isZero();assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();
    }
    /** 已受理原信封遇配置失效，失败终态只能提交一次。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"HTTP","COAP","TCP","MQTT"})
    void pendingPropertyStopsWhenMqttCapabilityDisappears(String protocol)throws Exception{
        var dispatch = preparePropertyDispatch();
        bind(protocol);
        if (protocol.equals("MQTT")) ownerUpdate("UPDATE dev_access_binding SET enabled=false,config_version=config_version+1 WHERE device_id='"+device+"'");
        assertThat(commands.admitDispatch(dispatch)).isFalse();
        assertPropertyStopped(dispatch.commandId());
        assertThat(commands.admitDispatch(dispatch)).isFalse();
        assertPropertyStopped(dispatch.commandId());
    }

    /** 有效原重试领取不能把属性设置重新投到TCP。 */
    @Test void propertyDispatchRetryStopsWithoutAnotherAttempt()throws Exception{
        var dispatch = preparePropertyDispatch();
        commands.recordDispatchFailure(dispatch,com.things.link.shared.message.DeviceCommandDispatchFailure.DISPATCH_FAILED,Instant.now());
        ownerUpdate("UPDATE ts_device_command SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        bind("TCP");
        var due = commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        commands.processDue(due);
        assertPropertyStopped(dispatch.commandId());
        commands.processDue(due);
        assertPropertyStopped(dispatch.commandId());
        assertThat(count("ts_device_command_attempt")).isEqualTo(1);
    }

    /** 故障发生在终态Outbox写入时，父状态、attempt及拒绝事实全部回滚。 */
    @Test void propertyRejectionOutboxFailureRollsBack()throws Exception{
        var dispatch = preparePropertyDispatch(); bind("HTTP");
        String trigger = "property_reject_"+project.toString().replace("-", "");
        fault(trigger,"RAISE EXCEPTION 'property rejection outbox fault';");
        try {
            assertThatThrownBy(()->commands.admitDispatch(dispatch)).isInstanceOf(RuntimeException.class);
            assertThat(asOwner(()->jdbc.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,dispatch.commandId()))).isEqualTo("ACCEPTED");
            assertThat(count("sys_outbox_event")).isEqualTo(1);
        } finally { dropFault(trigger); }
        assertThat(commands.admitDispatch(dispatch)).isFalse(); assertPropertyStopped(dispatch.commandId());
    }

    /** 当前有效MQTT包括显式正代次和历史无行，原派发许可继续有效。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void propertyDispatchRetainsMqttPositiveCases(boolean explicit)throws Exception{
        var dispatch = preparePropertyDispatch();
        if (explicit) bind("MQTT");
        assertThat(commands.admitDispatch(dispatch)).isTrue();
        assertThat(count("sys_outbox_event")).isEqualTo(1);
    }

    /** 已发表尝试只在原窗口到期后的有效领取点停止，保留响应超时诊断。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void propertyResponseTimeoutStopsWithoutRetry(boolean acknowledged)throws Exception{
        var dispatch = preparePropertyDispatch();
        assertThat(commands.admitDispatch(dispatch)).isTrue();
        commands.markDispatched(dispatch,Instant.now());
        if (acknowledged) ownerUpdate("UPDATE ts_device_command SET status='ACKNOWLEDGED' WHERE id='"+dispatch.commandId()+"'");
        if (acknowledged) ownerUpdate("UPDATE ts_device_command_attempt SET status='ACKNOWLEDGED' WHERE command_id='"+dispatch.commandId()+"'");
        bind("COAP");
        assertThat(commandRepository.claimDue(100)).noneMatch(value->value.commandId().equals(dispatch.commandId()));
        ownerUpdate("UPDATE ts_device_command SET deadline_at=clock_timestamp()-interval '1 second',next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        var due = commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        commands.processDue(due); assertPropertyStopped(dispatch.commandId());
        assertThat(asOwner(()->jdbc.queryForObject("SELECT error_code FROM ts_device_command_attempt WHERE command_id=?",String.class,dispatch.commandId()))).isEqualTo("RESPONSE_TIMEOUT");
        assertThat(count("ts_device_command_attempt")).isEqualTo(1);
    }

    /** 旧token不能因配置失效制造终态，只有当前持久领取有效。 */
    @Test void stalePropertyRetryTokenDoesNotCreateRejection()throws Exception{
        var dispatch = preparePropertyDispatch(); bind("TCP");
        ownerUpdate("UPDATE ts_device_command_attempt SET deadline_at=clock_timestamp()-interval '1 second' WHERE command_id='"+dispatch.commandId()+"'");
        ownerUpdate("UPDATE ts_device_command SET deadline_at=clock_timestamp()-interval '1 second',next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        var due = commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        commands.processDue(new com.things.link.telemetry.domain.DeviceCommandRepository.DueCommand(
                due.tenantId(),due.projectId(),due.commandId(),Uuid7.generate(),due.expectedAttempt(),due.claimKind()));
        assertThat(count("sys_outbox_event")).isEqualTo(1);
        commands.processDue(due); assertPropertyStopped(dispatch.commandId());
    }

    /** 删除接收设备不能被当作无显式配置的历史MQTT。 */
    @Test void deletedPropertyReceiverIsRejected()throws Exception{
        var dispatch = preparePropertyDispatch();
        ownerUpdate("UPDATE dev_device SET deleted_at=clock_timestamp() WHERE id='"+device+"'");
        assertThat(commands.admitDispatch(dispatch)).isFalse(); assertPropertyStopped(dispatch.commandId());
    }

    /** 配置持锁事务先提交后，待发许可必须读取新的协议。 */
    @Test void propertyAdmissionRechecksAfterActualDeviceLockWait()throws Exception{
        var dispatch = preparePropertyDispatch();
        var waitingPid = new CompletableFuture<Integer>();
        try (var holder = owner(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            try {
                int blocker;
                try (var query=holder.createStatement();var rows=query.executeQuery("SELECT pg_backend_pid()")) { rows.next();blocker=rows.getInt(1); }
                try (var lock=holder.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR NO KEY UPDATE")) {
                    lock.setObject(1,device);lock.executeQuery().close();
                }
                var future=pool.submit(()->tx.execute(status->{
                    rls.establish(tenant,project);
                    waitingPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    return commands.admitDispatch(dispatch);
                }));
                int waiter=waitingPid.get(5,TimeUnit.SECONDS);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).pollInSameThread().until(()->{
                    try(var q=holder.prepareStatement("SELECT ?=ANY(pg_blocking_pids(?))")) {
                        q.setInt(1,blocker);q.setInt(2,waiter);try(var rows=q.executeQuery()){rows.next();return rows.getBoolean(1);}
                    }
                });
                try(var insert=holder.prepareStatement("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')")){
                    insert.setObject(1,device);insert.setObject(2,tenant);insert.setObject(3,project);insert.executeUpdate();
                }
                holder.commit(); assertThat(future.get(10,TimeUnit.SECONDS)).isFalse();
                assertPropertyStopped(dispatch.commandId());
            } finally { holder.rollback(); }
        }
    }

    /** 伪造输入不能借配置失效写入终态；拒绝只能来自原持久信封。 */
    @Test void changedPropertyEnvelopeCannotManufactureRejection()throws Exception{
        var original = preparePropertyDispatch(); bind("HTTP");
        var forged = new com.things.link.shared.message.DeviceCommandDispatch(original.eventId(),original.tenantId(),original.projectId(),
                original.commandId(),original.attemptId(),original.attemptNo(),original.targetDeviceId(),original.targetDeviceKey(),
                original.connectionDeviceId(),original.connectionDeviceKey(),original.projectKey(),original.operationType(),null,
                "{}",original.deadlineAt(),original.traceId());
        assertThatThrownBy(()->commands.admitDispatch(forged)).isInstanceOf(com.things.link.telemetry.application.InvalidCommandDispatchException.class);
        assertThat(count("sys_outbox_event")).isEqualTo(1);
        assertThat(commands.admitDispatch(original)).isFalse(); assertPropertyStopped(original.commandId());
        ownerUpdate("UPDATE dev_access_binding SET protocol='MQTT',enabled=true,config_version=config_version+1 WHERE device_id='"+device+"'");
        assertThat(commands.admitDispatch(original)).isFalse(); assertPropertyStopped(original.commandId());
    }

    /** 原接收关系失效永久拒绝；类型恢复不复活旧commandId。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void receiverLossTerminatesPendingCommandAndProperty(boolean property)throws Exception{
        var dispatch=prepareOperationDispatch(property);
        UUID originalType=asOwner(()->jdbc.queryForObject("SELECT device_type_id FROM dev_device WHERE id=?",UUID.class,device));
        ownerUpdate("UPDATE dev_device SET device_type_id=NULL WHERE id='"+device+"'");
        assertThat(commands.admitDispatch(dispatch)).isFalse();assertReceiverStopped(dispatch.commandId());
        assertThat(asOwner(()->jdbc.queryForObject("SELECT error_code FROM ts_device_command_attempt WHERE command_id=?",String.class,dispatch.commandId()))).isEqualTo("COMMAND_ROUTE_UNAVAILABLE");
        ownerUpdate("UPDATE dev_device SET device_type_id='"+originalType+"' WHERE id='"+device+"'");
        assertThat(commands.admitDispatch(dispatch)).isFalse();assertReceiverStopped(dispatch.commandId());
        assertThat(count("ts_device_command_attempt")).isEqualTo(1);
    }

    /** 失败退避的合法领取停止新尝试，但保留已经发生的派发失败原因。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void receiverLossStopsDispatchRetryWithoutRewritingFailure(boolean property)throws Exception{
        var dispatch=prepareOperationDispatch(property);
        commands.recordDispatchFailure(dispatch,com.things.link.shared.message.DeviceCommandDispatchFailure.DISPATCH_FAILED,Instant.now());
        ownerUpdate("UPDATE ts_device_command SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        ownerUpdate("UPDATE dev_device SET device_type_id=NULL WHERE id='"+device+"'");
        var due=commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        commands.processDue(due);commands.processDue(due);assertReceiverStopped(dispatch.commandId());
        assertThat(asOwner(()->jdbc.queryForObject("SELECT error_code FROM ts_device_command_attempt WHERE command_id=?",String.class,dispatch.commandId()))).isEqualTo("DISPATCH_FAILED");
        assertThat(count("ts_device_command_attempt")).isEqualTo(1);
    }

    /** 已发表尝试只在原响应到期点终止，诊断仍为RESPONSE_TIMEOUT。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void receiverLossWaitsForOriginalResponseWindow(boolean property)throws Exception{
        var dispatch=prepareOperationDispatch(property);
        commands.markDispatched(dispatch,Instant.now());
        ownerUpdate("UPDATE dev_device SET device_type_id=NULL WHERE id='"+device+"'");
        assertThat(commandRepository.claimDue(100)).noneMatch(value->value.commandId().equals(dispatch.commandId()));
        ownerUpdate("UPDATE ts_device_command SET deadline_at=clock_timestamp()-interval '1 second',next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        var due=commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        commands.processDue(due);assertReceiverStopped(dispatch.commandId());
        assertThat(asOwner(()->jdbc.queryForObject("SELECT error_code FROM ts_device_command_attempt WHERE command_id=?",String.class,dispatch.commandId()))).isEqualTo("RESPONSE_TIMEOUT");
        assertThat(count("ts_device_command_attempt")).isEqualTo(1);
    }

    /** 终态Outbox故障回滚父命令和attempt，恢复后同一原信封可收束。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void receiverRejectionOutboxFaultRollsBackEverything(boolean property)throws Exception{
        var dispatch=prepareOperationDispatch(property);
        ownerUpdate("UPDATE dev_device SET device_type_id=NULL WHERE id='"+device+"'");
        String trigger="receiver_reject_"+project.toString().replace("-","");
        fault(trigger,"RAISE EXCEPTION 'receiver rejection fault';");
        try{
            assertThatThrownBy(()->commands.admitDispatch(dispatch)).isInstanceOf(RuntimeException.class);
            assertThat(asOwner(()->jdbc.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,dispatch.commandId()))).isEqualTo("ACCEPTED");
            assertThat(asOwner(()->jdbc.queryForObject("SELECT status FROM ts_device_command_attempt WHERE command_id=?",String.class,dispatch.commandId()))).isEqualTo("PENDING");
            assertThat(count("sys_outbox_event")).isEqualTo(1);
        }finally{dropFault(trigger);}
        assertThat(commands.admitDispatch(dispatch)).isFalse();assertReceiverStopped(dispatch.commandId());
    }

    /** 重试继续使用原请求和契约，当前物模型修改不能重新解释已受理副作用。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void retryRetainsAcceptedRequestWhenCurrentModelChanges(boolean property)throws Exception{
        var dispatch=prepareOperationDispatch(property);
        commands.recordDispatchFailure(dispatch,com.things.link.shared.message.DeviceCommandDispatchFailure.DISPATCH_FAILED,Instant.now());
        ownerUpdate("UPDATE ts_device_command SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        if(property) ownerUpdate("UPDATE dev_property_definition SET access_type='REPORT' WHERE project_id='"+project+"'");
        else ownerUpdate("UPDATE dev_command_definition SET input_schema='{\"type\":\"object\",\"required\":[\"future\"]}',timeout_seconds=99 WHERE project_id='"+project+"'");
        var due=commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        commands.processDue(due);
        var retry=asOwner(()->json.readValue(jdbc.queryForObject("SELECT payload::text FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_DISPATCH' AND payload::jsonb->>'attemptNo'='2'",String.class,dispatch.commandId()),com.things.link.shared.message.DeviceCommandDispatch.class));
        assertThat(json.readTree(retry.inputJson())).isEqualTo(json.readTree(dispatch.inputJson()));
        assertThat(retry.operationType()).isEqualTo(dispatch.operationType());
        assertThat(retry.commandKey()).isEqualTo(dispatch.commandKey());
        assertThat(retry.connectionDeviceId()).isEqualTo(dispatch.connectionDeviceId());
        assertThat(retry.connectionDeviceKey()).isEqualTo(dispatch.connectionDeviceKey());
        assertThat(retry.targetDeviceId()).isEqualTo(dispatch.targetDeviceId());
        assertThat(count("ts_device_command_attempt")).isEqualTo(2);
        assertThat(commands.admitDispatch(retry)).isTrue();
    }

    /** 路由失效终态及唯一Outbox同事务可查，不混同协议能力原因。 */
    private void assertReceiverStopped(UUID commandId){
        asOwner(()->{
            assertThat(jdbc.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,commandId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT failure_code FROM ts_device_command WHERE id=?",String.class,commandId)).isEqualTo("COMMAND_ROUTE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT deadline_at IS NULL AND next_attempt_at IS NULL AND retry_token IS NULL AND retry_leased_until IS NULL FROM ts_device_command WHERE id=?",Boolean.class,commandId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'",Integer.class,commandId)).isEqualTo(1);
            return null;
        });
    }

    /** 真实控制面改绑/解绑/删除不能把旧动作自动交给新网关。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,REBIND","true,REBIND","false,UNBIND","true,UNBIND","false,DELETE_CHILD","true,DELETE_CHILD","false,DELETE_GATEWAY","true,DELETE_GATEWAY"})
    void actualTopologyChangeStopsOriginalDelivery(boolean property,String change)throws Exception{
        var f=prepareGatewayDispatch(property);var dispatch=f.dispatch();
        assertThat(dispatch.connectionDeviceId()).isEqualTo(f.gateway());
        asOwner(()->{
            switch(change){
                case "REBIND" -> topology.bind(project,device,f.nextGateway());
                case "UNBIND" -> topology.unbind(project,device);
                case "DELETE_CHILD" -> deviceManagement.delete(project,device);
                case "DELETE_GATEWAY" -> deviceManagement.delete(project,f.gateway());
                default -> throw new IllegalArgumentException(change);
            }
            return null;
        });
        assertThat(commands.admitDispatch(dispatch)).isFalse();
        if(property&&change.equals("DELETE_GATEWAY")) assertPropertyStopped(dispatch.commandId());
        else assertReceiverStopped(dispatch.commandId());
        assertThat(count("ts_device_command_attempt")).isEqualTo(1);
        if(change.equals("REBIND")){
            asOwner(()->topology.bind(project,device,f.gateway()));
            assertThat(commands.admitDispatch(dispatch)).isFalse();assertReceiverStopped(dispatch.commandId());
        }
    }

    /** 旧token不能借已改绑关系写终态，当前领取只终止原命令且不新建attempt。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void topologyChangeDuringBackoffRequiresCurrentClaim(boolean property)throws Exception{
        var f=prepareGatewayDispatch(property);var dispatch=f.dispatch();
        commands.recordDispatchFailure(dispatch,com.things.link.shared.message.DeviceCommandDispatchFailure.DISPATCH_FAILED,Instant.now());
        ownerUpdate("UPDATE ts_device_command SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        var due=commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        asOwner(()->topology.bind(project,device,f.nextGateway()));
        commands.processDue(new com.things.link.telemetry.domain.DeviceCommandRepository.DueCommand(
                due.tenantId(),due.projectId(),due.commandId(),Uuid7.generate(),due.expectedAttempt(),due.claimKind()));
        assertThat(asOwner(()->jdbc.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,dispatch.commandId()))).isEqualTo("ACCEPTED");
        commands.processDue(due);assertReceiverStopped(dispatch.commandId());
        assertThat(count("ts_device_command_attempt")).isEqualTo(1);
        assertThat(asOwner(()->jdbc.queryForObject("SELECT error_code FROM ts_device_command_attempt WHERE command_id=?",String.class,dispatch.commandId()))).isEqualTo("DISPATCH_FAILED");
    }

    /** 已ACK旧工作不被拓扑变更提前撤回，只在原响应窗到期终止且保留超时诊断。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void topologyChangeAfterAckWaitsForResponseDeadline(boolean property)throws Exception{
        var f=prepareGatewayDispatch(property);var dispatch=f.dispatch();
        commands.markDispatched(dispatch,Instant.now());
        ownerUpdate("UPDATE ts_device_command SET status='ACKNOWLEDGED' WHERE id='"+dispatch.commandId()+"'");
        ownerUpdate("UPDATE ts_device_command_attempt SET status='ACKNOWLEDGED' WHERE command_id='"+dispatch.commandId()+"'");
        asOwner(()->topology.bind(project,device,f.nextGateway()));
        assertThat(commandRepository.claimDue(100)).noneMatch(value->value.commandId().equals(dispatch.commandId()));
        ownerUpdate("UPDATE ts_device_command SET deadline_at=clock_timestamp()-interval '1 second',next_attempt_at=clock_timestamp()-interval '1 second' WHERE id='"+dispatch.commandId()+"'");
        var due=commandRepository.claimDue(100).stream().filter(value->value.commandId().equals(dispatch.commandId())).findFirst().orElseThrow();
        commands.processDue(due);assertReceiverStopped(dispatch.commandId());
        assertThat(asOwner(()->jdbc.queryForObject("SELECT error_code FROM ts_device_command_attempt WHERE command_id=?",String.class,dispatch.commandId()))).isEqualTo("RESPONSE_TIMEOUT");
    }

    /** 与真实子设备写锁竞争时原准入回滚，释放后恢复，不得伪造永久拒绝。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void topologyLockContentionIsRecoverableWithoutRejection(boolean property)throws Exception{
        var f=prepareGatewayDispatch(property);var dispatch=f.dispatch();
        try(var holder=owner()){
            holder.setAutoCommit(false);
            try(var lock=holder.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR UPDATE")){
                lock.setObject(1,device);lock.executeQuery().close();
                assertThatThrownBy(()->commands.admitDispatch(dispatch)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(asOwner(()->jdbc.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,dispatch.commandId()))).isEqualTo("ACCEPTED");
                assertThat(asOwner(()->jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'",Integer.class,dispatch.commandId()))).isZero();
            }finally{holder.rollback();}
        }
        assertThat(commands.admitDispatch(dispatch)).isTrue();
    }

    /** 原许可先得时真实控制写等待提交；提交后改绑完成，新准入拒绝旧身份。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void originalPermissionBlocksRebindUntilCommit(boolean property)throws Exception{
        var f=prepareGatewayDispatch(property);var dispatch=f.dispatch();
        var waiterPid=new CompletableFuture<Integer>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var future=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
            tx.executeWithoutResult(status->{
                assertThat(commands.admitDispatch(dispatch)).isTrue();
                int holder=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                future.set(pool.submit(()->asOwner(()->tx.execute(s->{
                    waiterPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    return topology.bind(project,device,f.nextGateway());
                }))));
                try{
                    int waiting=waiterPid.get(5,TimeUnit.SECONDS);
                    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).pollInSameThread().until(()->{
                        try(var observer=owner();var q=observer.prepareStatement("SELECT ?=ANY(pg_blocking_pids(?))")){
                            q.setInt(1,holder);q.setInt(2,waiting);try(var rows=q.executeQuery()){rows.next();return rows.getBoolean(1);}
                        }
                    });
                }catch(Exception failure){throw new IllegalStateException(failure);}
            });
            future.get().get(5,TimeUnit.SECONDS);
        }
        assertThat(commands.admitDispatch(dispatch)).isFalse();assertReceiverStopped(dispatch.commandId());
    }

    /** 准备类型和在线网关，绑定必须经真实控制服务，随后正式自动化产生原命令。 */
    private GatewayDispatch prepareGatewayDispatch(boolean property)throws Exception{
        enable(4);seedActionDevice();
        ownerUpdate("UPDATE dev_type SET device_kind='SUB_DEVICE' WHERE project_id='"+project+"'");
        UUID type=Uuid7.generate(),gateway=Uuid7.generate(),next=Uuid7.generate();
        ownerUpdate("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES('"+type+"','"+tenant+"','"+project+"','gateway-type','gateway','GATEWAY','STANDARD_GATEWAY','WIFI','PUBLISHED')");
        for(UUID id:List.of(gateway,next)) ownerUpdate("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES('"+id+"','"+tenant+"','"+project+"','"+type+"','gw_"+id.toString().replace("-","")+"','gateway','ONLINE')");
        asOwner(()->topology.bind(project,device,gateway));
        return new GatewayDispatch(dispatchPreparedOperation(property),gateway,next);
    }

    /** 原动作与两个真实网关身份。 */
    private record GatewayDispatch(com.things.link.shared.message.DeviceCommandDispatch dispatch,UUID gateway,UUID nextGateway) { }

    /** 正式自动化执行产生唯一原属性信封，供后续状态机复验。 */
    private com.things.link.shared.message.DeviceCommandDispatch preparePropertyDispatch()throws Exception{
        return prepareOperationDispatch(true);
    }

    /** 原自动化动作创建真实命令或属性事实，不手造合法派发Outbox。 */
    private com.things.link.shared.message.DeviceCommandDispatch prepareOperationDispatch(boolean property)throws Exception{
        enable(4); seedActionDevice();
        return dispatchPreparedOperation(property);
    }

    /** 设备和关系已准备后，从正式自动化入口创建动作。 */
    private com.things.link.shared.message.DeviceCommandDispatch dispatchPreparedOperation(boolean property)throws Exception{
        var action=property?new ActionSpec("device-property-set-action",json.createObjectNode().set("properties",json.createObjectNode().put("power",true))):
                new ActionSpec("device-command-action",json.createObjectNode().put("commandKey","reboot").set("input",json.createObjectNode()));
        publishActions(edit("receiver consistency"),List.of(action));
        accept(event(Instant.now(),Map.of("x",1)),1); runner.run(candidate());
        assertThat(state()).isEqualTo("DISPATCHED");
        return asOwner(()->json.readValue(jdbc.queryForObject("SELECT payload::text FROM sys_outbox_event WHERE project_id=? AND event_type='DEVICE_COMMAND_DISPATCH'",
                String.class,project),com.things.link.shared.message.DeviceCommandDispatch.class));
    }

    /** 同事务终态、清空后续租约与唯一Outbox，不冒称设备实际收到。 */
    private void assertPropertyStopped(UUID commandId){
        asOwner(()->{
            assertThat(jdbc.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,commandId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT failure_code FROM ts_device_command WHERE id=?",String.class,commandId)).isEqualTo("PROPERTY_SET_PROTOCOL_UNSUPPORTED");
            assertThat(jdbc.queryForObject("SELECT deadline_at IS NULL AND next_attempt_at IS NULL AND retry_token IS NULL AND retry_leased_until IS NULL FROM ts_device_command WHERE id=?",Boolean.class,commandId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'",Integer.class,commandId)).isEqualTo(1);
            return null;
        });
    }

    @Test void cleanupRequiresOriginalPublicationAndEveryRetryPastReplayWindow()throws Exception{
        enable(4);active("retention");accept(event(Instant.now(),Map.of("x",1)),1);runner.run(candidate());
        UUID delivery=asOwner(()->jdbc.queryForObject("SELECT id FROM rule_notification_delivery WHERE project_id=?",UUID.class,project));
        assertThat(released(tenant,project,delivery)).isFalse();
        ownerUpdate("UPDATE sys_outbox_event SET created_at=clock_timestamp()-interval '40 days' WHERE id='"+delivery+"'");
        assertThat(released(tenant,project,delivery)).isFalse();
        ownerUpdate("UPDATE sys_outbox_event SET status='PUBLISHED',published_at=clock_timestamp() WHERE id='"+delivery+"'");
        assertThat(released(tenant,project,delivery)).isFalse();
        ownerUpdate("UPDATE sys_outbox_event SET published_at=clock_timestamp()-interval '193 hours' WHERE id='"+delivery+"'");
        assertThat(released(tenant,project,delivery)).isTrue();
        assertThat(released(Uuid7.generate(),project,delivery)).isFalse();
        assertThat(released(tenant,Uuid7.generate(),delivery)).isFalse();
        ownerUpdate("UPDATE sys_outbox_event SET lease_token='"+Uuid7.generate()+"',leased_until=clock_timestamp()+interval '1 minute' WHERE id='"+delivery+"'");
        assertThat(released(tenant,project,delivery)).isFalse();
        ownerUpdate("UPDATE sys_outbox_event SET lease_token=NULL,leased_until=NULL WHERE id='"+delivery+"'");
        UUID retry=Uuid7.generate();
        ownerUpdate("INSERT INTO sys_outbox_event(id,tenant_id,project_id,aggregate_type,aggregate_id,event_type,destination_topic,partition_key,payload,trace_id) SELECT '"+retry+"',tenant_id,project_id,aggregate_type,aggregate_id,event_type,destination_topic,partition_key,payload,trace_id FROM sys_outbox_event WHERE id='"+delivery+"'");
        assertThat(released(tenant,project,delivery)).isFalse();
        ownerUpdate("UPDATE sys_outbox_event SET status='PUBLISHED',published_at=clock_timestamp()-interval '193 hours' WHERE id='"+retry+"'");
        assertThat(released(tenant,project,delivery)).isTrue();
        ownerUpdate("DELETE FROM sys_outbox_event WHERE id='"+delivery+"'");
        assertThat(released(tenant,project,delivery)).isFalse();
        assertThatThrownBy(()->jdbc.queryForObject("SELECT notification_outbox_retention_released(?,?,?)",Boolean.class,tenant,project,delivery))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }
    private boolean released(UUID t,UUID p,UUID d)throws Exception{
        try(var c=owner();var q=c.prepareStatement("SELECT notification_outbox_retention_released(?,?,?)")){
            q.setObject(1,t);q.setObject(2,p);q.setObject(3,d);try(var r=q.executeQuery()){r.next();return r.getBoolean(1);}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pause","archive","revoke"})
    void committedActionWinsBeforeConcurrentQualificationChange(String change)throws Exception{
        enable(4);var definition=active("race");accept(event(Instant.now(),Map.of("x",1)),1);var candidate=candidate();
        String trigger="auto_race_"+partition;
        fault(trigger,"PERFORM pg_advisory_xact_lock(593,"+partition+");");
        try(var barrier=owner();var query=barrier.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            query.execute("SELECT pg_advisory_lock(593,"+partition+")");
            var execution=pool.submit(()->runner.run(candidate));
            waitForLock("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND objid="+partition+" AND NOT granted)");
            var modification=pool.submit(()->{changeQualification(change,definition.id());return null;});
            try{
                waitForLock("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE NOT granted AND locktype<>'advisory')");
                assertThat(modification.isDone()).isFalse();
            }finally{query.execute("SELECT pg_advisory_unlock(593,"+partition+")");}
            execution.get(15,TimeUnit.SECONDS);modification.get(15,TimeUnit.SECONDS);
        }finally{dropFault(trigger);}
        assertThat(state()).isEqualTo("DISPATCHED");assertThat(count("sys_outbox_event")).isEqualTo(1);
        assertThat(count("rule_notification_delivery")).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pause","archive","revoke"})
    void qualificationChangeWinsWhileExecutionWaitsOnRealAuthorityLock(String change)throws Exception{
        enable(4);var definition=active("race-first");accept(event(Instant.now(),Map.of("x",1)),1);var candidate=candidate();
        try(var owner=owner();var q=owner.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            owner.setAutoCommit(false);
            q.execute("SELECT id FROM "+(change.equals("revoke")?"sys_account WHERE id='"+account:"sys_project WHERE id='"+project)+"' FOR UPDATE");
            var execution=pool.submit(()->runner.run(candidate));
            try{
                waitForLock("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE NOT granted AND locktype<>'advisory')");
                q.executeUpdate(changeSql(change,definition.id()));owner.commit();
            }finally{owner.rollback();}
            execution.get(15,TimeUnit.SECONDS);
        }
        assertThat(state()).isEqualTo("REJECTED");assertThat(reason()).isEqualTo(switch(change){case "pause"->"DEFINITION_DISABLED";case "archive"->"PROJECT_READ_ONLY";default->"AUTH_REVOKED";});
        assertThat(count("sys_outbox_event")).isZero();assertThat(count("rule_notification_delivery")).isZero();
    }
    /** owner只制造已冻结资格写入的锁交错；实际执行方始终使用生产租约与项目/账号锁。 */
    private void changeQualification(String change,UUID definition)throws Exception{
        try(var c=owner();var q=c.createStatement()){
            c.setAutoCommit(false);
            q.execute("SELECT id FROM "+(change.equals("revoke")?"sys_account WHERE id='"+account:"sys_project WHERE id='"+project)+"' FOR UPDATE");
            q.executeUpdate(changeSql(change,definition));c.commit();
        }
    }
    private String changeSql(String change,UUID definition){return switch(change){
        case "pause"->"UPDATE rule_automation SET status='PAUSED',version=version+1,updated_at=clock_timestamp() WHERE id='"+definition+"'";
        case "archive"->"UPDATE sys_project SET status='ARCHIVED' WHERE id='"+project+"'";
        default->"UPDATE sys_account SET status='DISABLED' WHERE id='"+account+"'";
    };}
    private void waitForLock(String sql){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->{
        try(var c=owner();var q=c.createStatement();var r=q.executeQuery(sql)){r.next();return r.getBoolean(1);}
    });}
    @Test void allSevenActionKindsShareOneExecutionAndAlarmSourceWithoutExternalCalls()throws Exception{
        enable(4);seedActionDevice();UUID alarmRule=Uuid7.generate();
        ownerUpdate("INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES ('"+alarmRule+"','"+tenant+"','"+project+"','auto alarm','auto','"+device+"','x','GT',1,'LTE',1,'WARNING')");
        var edit=edit("seven");var actions=new ArrayList<>(edit.actions());
        actions.add(new ActionSpec("email-action",json.createObjectNode().put("recipient","test@example.com").put("subject","email").put("body","body")));
        actions.add(new ActionSpec("webhook-action",json.createObjectNode().put("url","https://example.com/webhook").put("body","body")));
        actions.add(new ActionSpec("device-command-action",json.createObjectNode().put("commandKey","reboot").set("input",json.createObjectNode())));
        actions.add(new ActionSpec("device-property-set-action",json.createObjectNode().set("properties",json.createObjectNode().put("power",true))));
        actions.add(new ActionSpec("alarm-create-action",json.createObjectNode().put("alarmRuleId",alarmRule.toString())));
        actions.add(new ActionSpec("alarm-clear-action",json.createObjectNode().put("alarmRuleId",alarmRule.toString())));
        publishActions(edit,actions);accept(event(Instant.now(),Map.of("x",2)),1);var candidate=candidate();runner.run(candidate);runner.run(candidate);
        assertThat(state()).isEqualTo("DISPATCHED");assertThat(count("rule_notification_delivery")).isEqualTo(3);
        assertThat(count("rule_device_action_delivery")).isEqualTo(2);assertThat(count("sys_outbox_event")).isEqualTo(5);
        assertThat(count("alarm_instance")).isEqualTo(1);assertThat(count("alarm_event")).isEqualTo(2);
        tx.executeWithoutResult(s->{rls.establish(tenant,project);
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm_instance WHERE project_id=?",String.class,project)).isEqualTo("CLEARED");
            assertThat(jdbc.queryForList("SELECT DISTINCT source_message_id FROM alarm_event WHERE project_id=?",UUID.class,project)).containsExactly(candidate.executionId());
        });
    }
    private void publishActions(AutomationManagementService.Edit edit,List<ActionSpec> actions){
        var d=asOwner(()->management.create(project,new AutomationManagementService.Edit(edit.name(),null,edit.triggerType(),edit.triggerConfig(),List.of(),actions)));
        asOwner(()->management.activate(project,d.id(),latest(d.id()),1));
    }
    private void bind(String protocol)throws Exception{
        ownerUpdate("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES ('"+device+"','"+tenant+"','"+project+"','"+protocol+"')");
    }
    private void seedActionDevice()throws Exception{
        UUID type=Uuid7.generate();
        ownerUpdate("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES ('"+type+"','"+tenant+"','"+project+"','auto-test','auto','DIRECT','STANDARD','WIFI','PUBLISHED')");
        ownerUpdate("UPDATE dev_device SET device_type_id='"+type+"' WHERE id='"+device+"'");
        ownerUpdate("INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES ('"+Uuid7.generate()+"','"+tenant+"','"+project+"','"+type+"','reboot','reboot','{}','{}',30)");
        ownerUpdate("INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) VALUES ('"+Uuid7.generate()+"','"+tenant+"','"+project+"','"+type+"','power','power','SHARED','SWITCH')");
    }
    private com.things.link.rule.domain.AutomationExecutionRepository.Candidate candidate(){return executions.candidates().stream().filter(c->c.projectId().equals(project)).findFirst().orElseThrow();}
    private String state(){return asOwner(()->jdbc.queryForObject("SELECT status FROM rule_automation_execution WHERE project_id=?",String.class,project));}
    private String reason(){return asOwner(()->jdbc.queryForObject("SELECT reason_code FROM rule_automation_execution WHERE project_id=?",String.class,project));}
    private void due()throws Exception{ownerUpdate("UPDATE rule_automation_execution SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE project_id='"+project+"'");}
    private void assertDelay(int seconds){asOwner(()->{assertThat(jdbc.queryForObject("SELECT extract(epoch FROM(next_attempt_at-clock_timestamp()))::int FROM rule_automation_execution WHERE project_id=?",Integer.class,project)).isBetween(seconds-5,seconds);return null;});}
    private void fault(String name,String body)throws Exception{
        ownerUpdate("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid THEN "+body+" END IF; RETURN NEW; END $$");
        ownerUpdate("CREATE TRIGGER "+name+" BEFORE INSERT ON sys_outbox_event FOR EACH ROW EXECUTE FUNCTION "+name+"()");
    }
    private void dropFault(String name)throws Exception{ownerUpdate("DROP TRIGGER IF EXISTS "+name+" ON sys_outbox_event");ownerUpdate("DROP FUNCTION IF EXISTS "+name+"()");}
    private AutomationManagementService.Edit edit(String name){return new AutomationManagementService.Edit(name,null,"PROPERTY_REPORTED",json.createObjectNode().put("deviceId",device.toString()),List.of(),List.of(new ActionSpec("notification-action",json.createObjectNode().put("channel","email").put("recipient","test@example.com").put("subject","test").put("body","test"))));}
    private AutomationDefinition active(String name){return asOwner(()->{var d=management.create(project,edit(name));return management.activate(project,d.id(),latest(d.id()),d.version());});}
    private UUID latest(UUID id){return asOwner(()->jdbc.queryForObject("SELECT id FROM rule_automation_version WHERE project_id=? AND automation_id=? ORDER BY version_number DESC LIMIT 1",UUID.class,project,id));}
    private void enable(long limit)throws Exception{ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit="+limit+" WHERE id='"+policy+"'");}
    private int count(String table){return tx.execute(status->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);});}
    private void accept(AutomationPropertyAccepted event,long offset){ingress.accept(event.deviceId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),json.writeValueAsBytes(event),partition,offset);}
    private AutomationPropertyAccepted event(Instant accepted,Map<String,Object> input){accepted=accepted.truncatedTo(java.time.temporal.ChronoUnit.MICROS);return new AutomationPropertyAccepted(1,Uuid7.generate(),tenant,project,device,"1.0.0",accepted,accepted,input,"test");}
    private <T>T asOwner(Supplier<T> body){var previous=TenantContext.current();TenantContext.set(new TenantScope(tenant,project,account));try{return body.get();}finally{TenantContext.clear();previous.ifPresent(TenantContext::set);}}
    private Connection owner()throws SQLException{return DriverManager.getConnection(DATABASE_URL,POSTGRES.getUsername(),POSTGRES.getPassword());}
    private void ownerUpdate(String sql)throws Exception{try(var c=owner();var q=c.createStatement()){q.execute(sql);}}
}
