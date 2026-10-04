package com.things.link.bootstrap.rule;

import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.automation.*;
import com.things.link.rule.domain.AutomationDefinition;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.shared.tenant.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractKafkaIntegrationTest;
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

/** 本地真实PG/Kafka首次计划与offset；不是动作派发/浏览器/云资格。 */
class PropertyAutomationAdmissionTests extends AbstractKafkaIntegrationTest {
    @Autowired AutomationManagementService management;
    @Autowired AutomationEventIngress ingress;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired @Qualifier("automationPropertyKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<Object,Object> factory;
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
    @Test void disabledPlanCannotPublishButDraftAndVersionsRemainManageable()throws Exception{
        var definition=asOwner(()->management.create(project,edit("first")));UUID version=latest(definition.id());
        assertThatThrownBy(()->asOwner(()->management.activate(project,definition.id(),version,1))).hasMessageContaining("尚未开通");
        enable(2);var active=asOwner(()->management.activate(project,definition.id(),version,1));
        var revised=asOwner(()->management.revise(project,definition.id(),active.version(),edit("revised")));
        assertThat(revised.activeVersionId()).isEqualTo(version);
        assertThatThrownBy(()->asOwner(()->management.pause(project,definition.id(),1))).hasMessageContaining("版本冲突");
        var paused=asOwner(()->management.pause(project,definition.id(),revised.version()));assertThat(paused.activeVersionId()).isEqualTo(version);
        assertThat(count("rule_automation_version")).isEqualTo(2);
    }
    @Test void eventFreezesVersionAndChargesOnceAcrossReplayAndRepublish()throws Exception{
        enable(5);var active=active("first");var event=event(Instant.now(),Map.of("x",1));accept(event,1);
        var revised=asOwner(()->management.revise(project,active.id(),active.version(),edit("new")));
        asOwner(()->management.activate(project,active.id(),latest(active.id()),revised.version()));accept(event,2);
        assertThat(count("rule_automation_execution")).isEqualTo(1);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        asOwner(()->{assertThat(jdbc.queryForObject("SELECT automation_version_id FROM rule_automation_execution WHERE project_id=?",UUID.class,project)).isEqualTo(active.activeVersionId());return null;});
    }
    @Test void emptyPlanStaysEmptyAndPerDefinitionQuotaRejectionDoesNotSpend()throws Exception{
        enable(1);var empty=event(Instant.now(),Map.of("x",1));accept(empty,1);active("one");active("two");accept(empty,2);
        assertThat(count("rule_automation_execution")).isZero();
        accept(event(Instant.now(),Map.of("x",2)),3);
        assertThat(count("rule_automation_execution")).isEqualTo(2);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        asOwner(()->{assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_execution WHERE project_id=? AND status='REJECTED' AND reason_code='QUOTA'",Integer.class,project)).isEqualTo(1);return null;});
    }
    @Test void revokedAuthorAndArchivedProjectNeverEnqueueActions()throws Exception{
        enable(4);active("one");ownerUpdate("UPDATE sys_account SET status='DISABLED' WHERE id='"+account+"'");
        accept(event(Instant.now(),Map.of("x",1)),1);
        assertThat(count("sys_automation_quota_reservation")).isZero();
        asOwner(()->{assertThat(jdbc.queryForObject("SELECT reason_code FROM rule_automation_execution WHERE project_id=?",String.class,project)).isEqualTo("AUTH_REVOKED");return null;});
        ownerUpdate("UPDATE sys_project SET status='ARCHIVED' WHERE id='"+project+"'");accept(event(Instant.now(),Map.of("x",2)),2);
        asOwner(()->{assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_event_receipt WHERE project_id=? AND reason_code='PROJECT_READ_ONLY'",Integer.class,project)).isEqualTo(1);return null;});
    }
    @Test void outerRollbackRemovesPlanExecutionsAndQuota()throws Exception{
        enable(4);active("one");var event=event(Instant.now(),Map.of("x",1));
        tx.executeWithoutResult(status->{accept(event,1);status.setRollbackOnly();});
        assertThat(count("rule_automation_event_receipt")).isZero();assertThat(count("rule_automation_execution")).isZero();assertThat(count("sys_automation_quota_reservation")).isZero();
        accept(event,1);assertThat(count("rule_automation_execution")).isEqualTo(1);
    }
    @Test void malformedAndWrongScopeRecordsPersistNoTrustedProjectData()throws Exception{
        ingress.accept(new byte[0],"{bad".getBytes(),partition,1);
        var source=event(Instant.now(),Map.of("x",1));var wrong=new AutomationPropertyAccepted(1,source.sourceEventId(),Uuid7.generate(),project,device,"1.0.0",source.occurredAt(),source.acceptedAt(),source.payload(),"test");
        accept(wrong,2);assertThat(count("rule_automation_event_receipt")).isZero();
        try(var c=owner();var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM rule_automation_ingress_rejection WHERE partition_id="+partition)){r.next();assertThat(r.getInt(1)).isEqualTo(2);}
    }
    @Test void crossTenantAdminUsesProjectOwnerQuotaAndLosesAuthorityImmediately()throws Exception{
        enable(5);collaborator=Uuid7.generate();collaboratorTenant=Uuid7.generate();
        ownerUpdate("INSERT INTO sys_tenant(id,name) VALUES ('"+collaboratorTenant+"','collaborator')");
        ownerUpdate("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES ('"+collaborator+"','"+collaborator+"@example.com','{noop}unused','collaborator')");
        ownerUpdate("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES ('"+Uuid7.generate()+"','"+collaboratorTenant+"','"+collaborator+"')");
        ownerUpdate("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES ('"+Uuid7.generate()+"','"+project+"','"+collaborator+"','ADMIN')");
        UUID definition;
        TenantContext.set(new TenantScope(collaboratorTenant,project,collaborator));
        try{
            var draft=management.create(project,edit("collaborator"));definition=draft.id();
            UUID version=jdbc.queryForObject("SELECT id FROM rule_automation_version WHERE automation_id=?",UUID.class,definition);
            management.activate(project,definition,version,1);
        }finally{TenantContext.clear();}
        accept(event(Instant.now(),Map.of("x",1)),1);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        ownerUpdate("UPDATE sys_project_member SET role='VIEWER' WHERE project_id='"+project+"' AND account_id='"+collaborator+"'");
        accept(event(Instant.now(),Map.of("x",2)),2);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        asOwner(()->{assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_execution WHERE project_id=? AND reason_code='AUTH_REVOKED'",Integer.class,project)).isEqualTo(1);return null;});
    }
    @Test void triggerDeviceIdentityIsNormalizedBeforeMatching()throws Exception{
        enable(5);var source=edit("normalized");
        var command=new AutomationManagementService.Edit(source.name(),null,source.triggerType(),
                json.createObjectNode().put("deviceId",device.toString().toUpperCase(java.util.Locale.ROOT)),source.conditions(),source.actions());
        var draft=asOwner(()->management.create(project,command));
        asOwner(()->management.activate(project,draft.id(),latest(draft.id()),1));
        accept(event(Instant.now(),Map.of("x",1)),1);assertThat(count("rule_automation_execution")).isEqualTo(1);
    }
    @Test void deviceSubscriptionLimitIsReleasedByPause()throws Exception{
        enable(20);var first=active("subscription-0");
        for(int i=1;i<10;i++)active("subscription-"+i);
        var extra=asOwner(()->management.create(project,edit("subscription-extra")));UUID version=latest(extra.id());
        assertThatThrownBy(()->asOwner(()->management.activate(project,extra.id(),version,1))).hasMessageContaining("上限");
        asOwner(()->management.pause(project,first.id(),first.version()));
        assertThat(asOwner(()->management.activate(project,extra.id(),version,1)).status()).isEqualTo(AutomationDefinition.Status.ACTIVE);
    }
    @Test void realKafkaCommitsPermanentRejectionButRetainsOffsetAcrossDatabaseFailure()throws Exception{
        enable(5);active("one");String group="automation-test-"+Uuid7.generate();
        var properties=Map.<String,Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers());
        String trigger="rule_automation_event_receipt_fault_"+partition;
        var failed=new CountDownLatch(1);
        var assigned=new CountDownLatch(1);
        var container=factory.createContainer(AutomationPropertyAccepted.TOPIC);
        container.getContainerProperties().setGroupId(group);
        container.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener(){
            @Override public void onPartitionsAssigned(Consumer<?,?> consumer,Collection<TopicPartition> partitions){consumer.seekToEnd(partitions);partitions.forEach(consumer::position);assigned.countDown();}
        });
        container.getContainerProperties().setMessageListener((MessageListener<Object,Object>) record->{
            try{ingress.accept((byte[])record.key(),(byte[])record.value(),record.partition(),record.offset());}
            catch(RuntimeException failure){failed.countDown();throw failure;}
        });
        try(var admin=AdminClient.create(properties);var producer=new KafkaProducer<byte[],byte[]>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()),new ByteArraySerializer(),new ByteArraySerializer())){
            if(!admin.listTopics().names().get(10,TimeUnit.SECONDS).contains(AutomationPropertyAccepted.TOPIC))
                admin.createTopics(List.of(new NewTopic(AutomationPropertyAccepted.TOPIC,6,(short)1))).all().get(10,TimeUnit.SECONDS);
            int partitionCount=admin.describeTopics(List.of(AutomationPropertyAccepted.TOPIC)).allTopicNames().get(10,TimeUnit.SECONDS).get(AutomationPropertyAccepted.TOPIC).partitions().size();
            container.start();assertThat(assigned.await(20,TimeUnit.SECONDS)).isTrue();
            assertThat(container.getAssignedPartitions()).hasSize(partitionCount);
            var old=event(Instant.now().minusSeconds(8*86400),Map.of("x",1));long oldOffset=send(producer,old);await(()->committed(admin,group)>oldOffset);
            assertThat(count("rule_automation_execution")).isZero();
            long badOffset=producer.send(new ProducerRecord<byte[],byte[]>(AutomationPropertyAccepted.TOPIC,0,new byte[0],"{invalid".getBytes())).get(10,TimeUnit.SECONDS).offset();
            kafkaOffsets.add(badOffset);await(()->committed(admin,group)>badOffset);
            try(var c=owner();var q=c.createStatement();var r=q.executeQuery("SELECT reason_code FROM rule_automation_ingress_rejection WHERE partition_id=0 AND record_offset="+badOffset)){
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("ENVELOPE_INVALID");
            }
            ownerUpdate("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid THEN RAISE EXCEPTION 'owned database failure fixture' USING ERRCODE='08006'; END IF; RETURN NEW; END $$");
            ownerUpdate("CREATE TRIGGER "+trigger+" BEFORE INSERT ON rule_automation_event_receipt FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
            long next=send(producer,event(Instant.now(),Map.of("x",2)));assertThat(failed.await(15,TimeUnit.SECONDS)).isTrue();
            assertThat(committed(admin,group)).isEqualTo(badOffset+1);
            assertThat(count("rule_automation_execution")).isZero();assertThat(count("sys_automation_quota_reservation")).isZero();
            ownerUpdate("DROP TRIGGER "+trigger+" ON rule_automation_event_receipt");ownerUpdate("DROP FUNCTION "+trigger+"()");
            await(()->committed(admin,group)>next);assertThat(count("rule_automation_execution")).isEqualTo(1);assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        }finally{container.stop();ownerUpdate("DROP TRIGGER IF EXISTS "+trigger+" ON rule_automation_event_receipt");ownerUpdate("DROP FUNCTION IF EXISTS "+trigger+"()");}
    }
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
    private long send(KafkaProducer<byte[],byte[]> producer,AutomationPropertyAccepted event)throws Exception{long offset=producer.send(new ProducerRecord<>(AutomationPropertyAccepted.TOPIC,0,event.deviceId().toString().getBytes(),json.writeValueAsBytes(event))).get(10,TimeUnit.SECONDS).offset();kafkaOffsets.add(offset);return offset;}
    private static long committed(AdminClient admin,String group){try{var value=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(new TopicPartition(AutomationPropertyAccepted.TOPIC,0));return value==null?-1:value.offset();}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError("未取得实际状态证据");Thread.sleep(50);}}
}
