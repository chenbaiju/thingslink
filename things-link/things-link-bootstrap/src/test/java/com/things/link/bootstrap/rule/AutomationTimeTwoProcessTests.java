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
@org.springframework.test.context.TestPropertySource(properties={"things-link.automation.time.enabled=true","things-link.automation.time.scan-delay-millis=3600000","things-link.automation.execution.scan-delay-millis=3600000"})
class AutomationTimeTwoProcessTests extends com.things.link.testing.AbstractKafkaIntegrationTest {
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
        stopNodes();
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
            q.executeUpdate("DELETE FROM rule_automation_schedule WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM rule_automation_schedule_state WHERE tenant_id='"+tenant+"'");
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
    final List<Node> nodes=new ArrayList<>();java.nio.file.Path run;
    record Node(Process process,java.nio.file.Path directory){}
    @Test void realTimeProcessesRecoverClaimCrashAndFenceStaleScheduleWithoutPropertyConsumer()throws Exception{
        run=java.nio.file.Path.of("..","..","logs","verify","g3-auto-2d","process-"+Uuid7.generate()).toAbsolutePath().normalize();java.nio.file.Files.createDirectories(run);
        try(var admin=Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){
            String topic="tc.rule.notification";if(!admin.listTopics().names().get().contains(topic))admin.createTopics(List.of(new NewTopic(topic,3,(short)1))).all().get();
        }
        enable(10);Node a=start("A"),b=start("B");assertThat(a.process().pid()).isNotEqualTo(b.process().pid());
        java.nio.file.Files.writeString(run.resolve("environment.json"),json.writeValueAsString(Map.of("java",System.getProperty("java.runtime.version"),"postgres",POSTGRES.getContainerId(),"kafka",KAFKA.getContainerId(),"redis",REDIS.getContainerId(),"pidA",a.process().pid(),"pidB",b.process().pid(),"propertyConsumer",false)));
        // 两个生产JVM竞争同一ONE_SHOT发生点，只有一个执行和一次预留。
        UUID first=due("concurrent");String left=runTime(a,first),right=runTime(b,first);done(a,left);done(b,right);UUID firstExecution=execution(first);
        done(b,run(b,first,firstExecution));assertExecution(firstExecution,1);consumed(firstExecution,a,b);
        // 受控过期证明旧调度工作者不能覆盖接管者，不冒充自然到期证据。
        UUID stale=due("stale");control(a,"arm-schedule");String old=runTime(a,stale);hit(a,"schedule-claimed",20);
        ownerUpdate("UPDATE rule_automation_schedule SET lease_until=clock_timestamp()-interval '1 second' WHERE automation_id='"+stale+"'");
        ownerUpdate("UPDATE sys_tenant_work_slot SET leased_until=clock_timestamp()-interval '1 second' WHERE tenant_id='"+tenant+"' AND work_type='AUTOMATION'");
        done(b,runTime(b,stale));UUID staleExecution=execution(stale);control(a,"release");done(a,old);done(b,run(b,stale,staleExecution));assertExecution(staleExecution,1);consumed(staleExecution,a,b);
        // A真实领取调度并提交后强杀；B只开生产扫描，原30秒租约自然过期后恢复。
        UUID crashed=due("crash");control(a,"arm-schedule");runTime(a,crashed);hit(a,"schedule-claimed",20);
        long started=System.nanoTime();a.process().destroyForcibly();assertThat(a.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        control(b,"time-scan-on");control(b,"scan-on");
        waitFor(()->tx.execute(t->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT count(*) FROM rule_automation_execution WHERE automation_id=? AND status='DISPATCHED'",Integer.class,crashed);})==1,50);
        long elapsed=TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started);assertThat(elapsed).isBetween(25L,50L);control(b,"time-scan-off");control(b,"scan-off");
        UUID crashExecution=execution(crashed);assertExecution(crashExecution,1);consumed(crashExecution,b);
        assertThat(count("sys_automation_quota_reservation")).isEqualTo(3);assertThat(count("rule_notification_delivery")).isEqualTo(3);
        tx.executeWithoutResult(t->{rls.establish(tenant,project);assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_schedule WHERE project_id=? AND next_fire_at IS NOT NULL",Integer.class,project)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_schedule_state WHERE project_id=? AND one_shot_consumed",Integer.class,project)).isEqualTo(3);});
        java.nio.file.Files.writeString(run.resolve("result.json"),json.writeValueAsString(Map.of("concurrent",firstExecution,"staleWorker",staleExecution,"killedAfterScheduleClaim",crashExecution,"naturalRecoverySeconds",elapsed,"quota",3,"notificationFacts",3)));
    }
    /** R4-3d：CRON真实发生点、真实claim后强杀及自然30秒租约接管；不回写任何调度时钟。 */
    @Test void realCronClaimCrashRecoversAfterNaturalLeaseWithoutDuplicatingOccurrence()throws Exception{
        run=java.nio.file.Path.of("..","..","logs","verify","g3-auto-2d","cron-process-"+Uuid7.generate()).toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(run);
        try(var admin=Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){
            String topic="tc.rule.notification";
            if(!admin.listTopics().names().get().contains(topic))admin.createTopics(List.of(new NewTopic(topic,3,(short)1))).all().get();
        }
        enable(10);Node a=start("A"),b=start("B");
        assertThat(a.process().pid()).isNotEqualTo(b.process().pid());
        java.nio.file.Files.writeString(run.resolve("environment.json"),json.writeValueAsString(Map.of(
                "java",System.getProperty("java.runtime.version"),"postgres",POSTGRES.getContainerId(),
                "kafka",KAFKA.getContainerId(),"redis",REDIS.getContainerId(),
                "pidA",a.process().pid(),"pidB",b.process().pid(),"triggerType","CRON","propertyConsumer",false)));
        // 固定每分钟一个合法秒位，选择DB现在后约10秒；自然等待到点，不缩短原生产租约。
        Instant databaseNow=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        int second=databaseNow.plusSeconds(10).atZone(ZoneOffset.UTC).getSecond();
        var config=json.createObjectNode().put("deviceId",device.toString())
                .put("cronExpression",second+" * * * * *").put("timezone","UTC");
        var definition=asOwner(()->management.create(project,new AutomationManagementService.Edit(
                "cron natural recovery",null,"CRON",config,List.of(),edit("template").actions())));
        UUID version=latest(definition.id());
        asOwner(()->management.activate(project,definition.id(),version,definition.version()));
        Instant fireAt=((Timestamp)cronSchedule(definition.id()).get("next_fire_at")).toInstant();
        waitFor(()->!jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant().isBefore(fireAt),75);
        control(a,"arm-schedule");runTime(a,definition.id());hit(a,"schedule-claimed",20);
        var claimed=cronSchedule(definition.id());
        Instant leaseUntil=((Timestamp)claimed.get("lease_until")).toInstant();
        long oldToken=((Number)claimed.get("lease_token")).longValue();
        assertThat(((Timestamp)claimed.get("next_fire_at")).toInstant()).isEqualTo(fireAt);
        assertThat(Duration.between(((Timestamp)claimed.get("observed_at")).toInstant(),leaseUntil).toMillis())
                .as("实际已提交的原30秒调度租约，不是缩短后的测试租约").isBetween(20_000L,30_000L);
        assertThat(count("rule_automation_execution")).isZero();
        assertThat(count("sys_automation_quota_reservation")).isZero();
        long started=System.nanoTime();a.process().destroyForcibly();assertThat(a.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        // 旧租约仍有效时B不能受理；随后只开启生产扫描，等待原DB租约自然到期。
        assertThat(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant()).isBefore(leaseUntil);
        done(b,runTime(b,definition.id()));
        assertThat(count("rule_automation_execution")).isZero();
        assertThat(cronSchedule(definition.id()).get("lease_until")).isEqualTo(Timestamp.from(leaseUntil));
        control(b,"time-scan-on");control(b,"scan-on");
        waitFor(()->tx.execute(t->{rls.establish(tenant,project);return jdbc.queryForObject(
                "SELECT count(*) FROM rule_automation_execution WHERE automation_id=? AND status='DISPATCHED'",Integer.class,definition.id());})==1,50);
        // 及时关扫描，验证本发生点的唯一性；后续每分钟发生点是另一项合法业务，不能误称重复。
        control(b,"time-scan-off");control(b,"scan-off");
        UUID execution=execution(definition.id());assertExecution(execution,1);consumed(execution,b);
        var recovered=cronSchedule(definition.id());
        assertThat(((Number)recovered.get("lease_token")).longValue()).isGreaterThan(oldToken);
        assertThat(((Timestamp)recovered.get("next_fire_at")).toInstant()).isAfter(fireAt);
        tx.executeWithoutResult(t->{
            rls.establish(tenant,project);
            var fact=jdbc.queryForMap("SELECT trigger_type,scheduled_fire_at,accepted_at,automation_version_id FROM rule_automation_execution WHERE id=?",execution);
            assertThat(fact).containsEntry("trigger_type","CRON").containsEntry("scheduled_fire_at",Timestamp.from(fireAt)).containsEntry("automation_version_id",version);
            assertThat(((Timestamp)fact.get("accepted_at")).toInstant()).isAfterOrEqualTo(leaseUntil);
            assertThat(jdbc.queryForObject("SELECT one_shot_consumed FROM rule_automation_schedule_state WHERE automation_version_id=?",Boolean.class,version)).isFalse();
            assertThat(jdbc.queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=? AND metric='AUTOMATION_EXECUTION'",Long.class,project)).isEqualTo(1L);
        });
        done(b,run(b,definition.id(),execution));assertExecution(execution,1);
        assertThat(count("rule_automation_execution")).isEqualTo(1);
        assertThat(count("sys_automation_quota_reservation")).isEqualTo(1);
        assertThat(count("rule_notification_delivery")).isEqualTo(1);
        java.nio.file.Files.writeString(run.resolve("result.json"),json.writeValueAsString(Map.of(
                "triggerType","CRON","executionId",execution,"scheduledFireAt",fireAt,"originalLeaseUntil",leaseUntil,
                "recoverySeconds",TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started),
                "killedPid",a.process().pid(),"replacementPid",b.process().pid(),"quota",1,
                "scope","real PostgreSQL/Kafka process recovery; notification intent/outbox only, no SMTP or device execution")));
    }
    private Map<String,Object> cronSchedule(UUID automation){return tx.execute(t->{rls.establish(tenant,project);
        return jdbc.queryForMap("SELECT next_fire_at,lease_until,lease_token,clock_timestamp() AS observed_at FROM rule_automation_schedule WHERE automation_id=?",automation);
    });}

    private UUID due(String name){var d=active(name);waitFor(()->tx.execute(t->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT next_fire_at<=clock_timestamp() FROM rule_automation_schedule WHERE automation_id=?",Boolean.class,d.id());}),10);return d.id();}
    private String runTime(Node node,UUID automation)throws Exception{return send(node,"run-time",tenant+" "+project+" "+automation);}
    private UUID execution(UUID automation){return tx.execute(t->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT id FROM rule_automation_execution WHERE automation_id=?",UUID.class,automation);});}
    private String status(UUID id){return tx.execute(s->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT status FROM rule_automation_execution WHERE id=?",String.class,id);});}
    private void assertExecution(UUID id,int attempts){tx.executeWithoutResult(s->{rls.establish(tenant,project);
        assertThat(jdbc.queryForMap("SELECT status,attempt_count FROM rule_automation_execution WHERE id=?",id)).containsEntry("status","DISPATCHED").containsEntry("attempt_count",attempts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_automation_attempt WHERE execution_id=?",Integer.class,id)).isEqualTo(attempts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_notification_delivery WHERE automation_execution_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_automation_quota_reservation WHERE execution_id=?",Integer.class,id)).isEqualTo(1);
    });}
    private void consumed(UUID id,Node... consumers)throws Exception{
        UUID delivery=tx.execute(s->{rls.establish(tenant,project);return jdbc.queryForObject("SELECT id FROM rule_notification_delivery WHERE automation_execution_id=?",UUID.class,id);});
        try{waitFor(()->Arrays.stream(consumers).anyMatch(n->java.nio.file.Files.exists(n.directory().resolve("notification-"+delivery))),30);}
        finally{var snapshot=tx.execute(s->{rls.establish(tenant,project);return jdbc.queryForMap("SELECT id,status,created_at,available_at,published_at,leased_until,attempt_count FROM sys_outbox_event WHERE id=?",delivery);});java.nio.file.Files.writeString(run.resolve("outbox-"+delivery+".txt"),snapshot.toString());}
        tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox_event WHERE id=?",String.class,delivery)).isEqualTo("PUBLISHED");});
    }
    private void expire(UUID id)throws Exception{
        ownerUpdate("UPDATE rule_automation_execution SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+id+"'");
        ownerUpdate("UPDATE sys_tenant_work_slot SET leased_until=clock_timestamp()-interval '1 second' WHERE tenant_id='"+tenant+"' AND work_type='AUTOMATION'");
    }
    private String run(Node node,UUID automation,UUID execution)throws Exception{return send(node,"run",tenant+" "+project+" "+automation+" "+execution);}
    private void control(Node node,String operation)throws Exception{done(node,send(node,operation,""));}
    private String send(Node node,String operation,String args)throws Exception{
        String id=Uuid7.generate().toString();node.process().getOutputStream().write((operation+" "+id+" "+args+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));node.process().getOutputStream().flush();return id;
    }
    private void done(Node node,String id)throws Exception{hit(node,"done-"+id,25);assertThat(java.nio.file.Files.readString(node.directory().resolve("done-"+id))).isEqualTo("ok");}
    private void hit(Node node,String file,int seconds){waitFor(()->{assertThat(node.process().isAlive()).as("process alive; log %s",node.directory()).isTrue();return java.nio.file.Files.exists(node.directory().resolve(file));},seconds);}
    private void waitFor(BooleanSupplier ready,int seconds){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofMillis(100)).until(ready::getAsBoolean);}
    private Node start(String name)throws Exception{
        var dir=java.nio.file.Files.createDirectories(run.resolve(name));var root=java.nio.file.Files.createDirectories(dir.resolve("classpath"));
        var target=java.nio.file.Files.createDirectories(root.resolve("com/things/link/bootstrap/rule/fixture"));
        try(var classes=java.nio.file.Files.list(java.nio.file.Path.of("target/test-classes/com/things/link/bootstrap/rule/fixture"))){for(var f:classes.filter(f->f.getFileName().toString().startsWith("AutomationNodeProcess")).toList())java.nio.file.Files.copy(f,target.resolve(f.getFileName()));}
        java.nio.file.Files.copy(java.nio.file.Path.of("src/test/resources/application-test.yml"),root.resolve("application-test.yml"));
        var cp=new ArrayList<String>();cp.add(root.toString());for(var entry:System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(java.io.File.pathSeparator))if(!entry.contains("/target/test-classes"))cp.add(entry);
        var command=new ArrayList<>(List.of(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx384m","-Dautomation.fixture.directory="+dir,"-cp",String.join(java.io.File.pathSeparator,cp),"com.things.link.bootstrap.rule.fixture.AutomationNodeProcess",
            "--server.port=0","--spring.flyway.enabled=false","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+APP_ROLE,"--spring.datasource.password="+APP_ROLE_PASSWORD,
            "--spring.data.redis.host="+REDIS.getHost(),"--spring.data.redis.port="+REDIS.getMappedPort(6379),"--spring.kafka.bootstrap-servers="+KAFKA.getBootstrapServers(),
            "--spring.kafka.admin.auto-create=false","--spring.kafka.listener.auto-startup=false","--things-link.automation.property.enabled=false","--things-link.automation.time.enabled=true","--things-link.kafka.concurrency.automation-property=1","--things-link.kafka.concurrency.rule-notification=1","--things-link.outbox.publisher.enabled=true"));
        long started=System.nanoTime();
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();
        var node=new Node(process,dir);nodes.add(node);
        try{hit(node,"ready",90);}
        catch(org.awaitility.core.ConditionTimeoutException | AssertionError failure){
            throw new AssertionError("automation time child did not become ready; pid="+process.pid()
                    +", alive="+process.isAlive()
                    +", elapsedSeconds="+TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)
                    +", log="+dir.resolve("node.log")
                    +", logBytes="+childLogBytes(dir.resolve("node.log")),failure);
        }
        return node;
    }
    private String childLogBytes(java.nio.file.Path path){
        try{return Long.toString(java.nio.file.Files.size(path));}
        catch(Exception failure){return "unavailable("+failure.getClass().getSimpleName()+")";}
    }
    private void stopNodes()throws Exception{
        for(var n:nodes){if(n.process().isAlive()){n.process().getOutputStream().write("quit\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));n.process().getOutputStream().flush();if(!n.process().waitFor(10,TimeUnit.SECONDS)){n.process().destroyForcibly();n.process().waitFor(10,TimeUnit.SECONDS);}}
            var cp=n.directory().resolve("classpath");if(java.nio.file.Files.exists(cp))try(var files=java.nio.file.Files.walk(cp)){for(var f:files.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(f);}
            try(var files=java.nio.file.Files.list(n.directory())){for(var f:files.filter(f->!f.getFileName().toString().equals("node.log")).toList())java.nio.file.Files.deleteIfExists(f);}
        }
    }
    private AutomationManagementService.Edit edit(String name){return new AutomationManagementService.Edit(name,null,"ONE_SHOT",json.createObjectNode().put("deviceId",device.toString()).put("runAt",Instant.now().plusSeconds(2).toString()),List.of(),List.of(new ActionSpec("notification-action",json.createObjectNode().put("channel","email").put("recipient","test@example.com").put("subject","test").put("body","test"))));}
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
