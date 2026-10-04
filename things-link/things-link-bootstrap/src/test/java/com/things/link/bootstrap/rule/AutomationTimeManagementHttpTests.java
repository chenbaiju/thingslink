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
@org.springframework.boot.test.context.SpringBootTest(webEnvironment=org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties={"things-link.automation.time.enabled=true","things-link.automation.time.scan-delay-millis=100","things-link.automation.execution.scan-delay-millis=100"})
class AutomationTimeManagementHttpTests extends AbstractIntegrationTest {
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired com.things.link.rule.api.controller.AutomationController controller;
    @Autowired com.things.link.rule.application.RuleNotificationDeliveryStore deliveries;
    @Autowired AutomationManagementService management;
    @Autowired AutomationEventIngress ingress;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired AutomationExecutionRunner runner;
    @Autowired com.things.link.telemetry.application.DeviceCommandClaimPort commandClaims;
    @Autowired com.things.link.rule.domain.AutomationExecutionRepository executions;
    final JsonMapper json=JsonMapper.builder().build();
    UUID tenant,project,account,device,policy,collaborator,collaboratorTenant;
    int partition;
    final List<Long> kafkaOffsets=new ArrayList<>();
    @BeforeEach void seed()throws Exception{
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"enabled",false);
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
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired com.things.link.iam.application.TokenIssuer tokens;
    final java.net.http.HttpClient client=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Test void timeOnlyDeploymentPublishesSnapshotAndReportsNextPointAndPause()throws Exception{
        enable();var body=body("cron");body.put("triggerType","CRON");body.put("triggerConfig",Map.of("deviceId",device,"cronExpression","0 0 0 * * *","timezone","America/New_York","payload",Map.of("temperature",42)));
        String path=base()+"/"+call("POST",base(),body,201).path("id").asString();String version=call("GET",path+"/version-history",null,200).path("items").get(0).path("id").asString();
        var active=call("POST",path+"/versions/"+version+"/activate?expectedVersion=1",null,200);
        assertThat(active.path("nextFireAt").asString()).isNotBlank();assertThat(active.path("scheduleTimezone").asString()).isEqualTo("America/New_York");
        assertThat(call("POST",path+"/pause?expectedVersion=2",null,200).path("nextFireAt").isNull()).isTrue();
        call("POST",path+"/versions/"+version+"/activate?expectedVersion=3",null,200);
        ownerUpdate("UPDATE sys_project_member SET role='VIEWER' WHERE project_id='"+project+"'");call("GET",path,null,403);call("POST",base(),body,403);
    }
    @Test void onceNaturallyRunsAndExpiredVersionCannotReactivate()throws Exception{
        enable();var body=body("once");body.put("triggerType","ONE_SHOT");body.put("triggerConfig",Map.of("deviceId",device,"runAt",Instant.now().plusSeconds(3).toString(),"payload",Map.of("private","hidden")));
        String path=base()+"/"+call("POST",base(),body,201).path("id").asString();String version=call("GET",path+"/version-history",null,200).path("items").get(0).path("id").asString();
        call("POST",path+"/versions/"+version+"/activate?expectedVersion=1",null,200);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->{try(var c=owner();var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM rule_automation_execution WHERE project_id='"+project+"' AND status='DISPATCHED'")){r.next();return r.getInt(1)==1;}});
        var page=call("GET","/api/v1/projects/"+project+"/automation-executions",null,200);assertThat(page.toString()).doesNotContain("hidden","inputSnapshot");
        assertThat(page.path("items").get(0).path("triggerType").asString()).isEqualTo("ONE_SHOT");
        call("POST",path+"/versions/"+version+"/activate?expectedVersion=2",null,409);
    }
    @Test void invalidTemporalConfigurationIsRejectedByRealHttp()throws Exception{
        for(String expression:List.of("* * * * * *","@hourly")){
            var body=body("bad");body.put("triggerType","CRON");body.put("triggerConfig",Map.of("deviceId",device,"cronExpression",expression));
            assertThat(call("POST",base(),body,400).path("code").asInt()).isEqualTo(40052);
        }
        var body=body("past");body.put("triggerType","ONE_SHOT");body.put("triggerConfig",Map.of("deviceId",device,"runAt","2000-01-01T00:00:00Z"));enable();
        String path=base()+"/"+call("POST",base(),body,201).path("id").asString();String v=call("GET",path+"/version-history",null,200).path("items").get(0).path("id").asString();call("POST",path+"/versions/"+v+"/activate?expectedVersion=1",null,409);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"ONE_SHOT","CRON"})
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="thingslink.test.automation-time-browser",matches="true")
    void realConsoleTimeTrigger(String trigger)throws Exception{
        var root=java.nio.file.Path.of("../..").toAbsolutePath().normalize();var directory=root.resolve(System.getProperty("thingslink.test.automation-time-evidence-dir","logs/verify/g3-auto-2c")+"/browser-"+trigger);
        java.nio.file.Files.createDirectories(directory);var ready=directory.resolve("browser.ready");java.nio.file.Files.deleteIfExists(ready);
        String password="AutomationJourney-Only-2030!";
        try(var c=owner();var q=c.prepareStatement("UPDATE sys_account SET password_hash=?,email_verified_at=clock_timestamp() WHERE id=?")){q.setString(1,passwords.encode(password));q.setObject(2,account);q.executeUpdate();}
        ownerUpdate("UPDATE sys_project SET name='规则测试项目' WHERE id='"+project+"'");ownerUpdate("UPDATE dev_device SET name='自动化设备' WHERE id='"+device+"'");enable();
        var builder=new ProcessBuilder("node",root.resolve("scripts/tests/console-time-automation-journey.cjs").toString());builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("browser.log").toFile());
        builder.environment().putAll(Map.of("RULE_BACKEND","http://127.0.0.1:"+port,"RULE_EMAIL",account+"@example.com","RULE_PASSWORD",password,"AUTO_READY",ready.toString(),"AUTO_TRIGGER",trigger));
        var process=builder.start();try{
            assertThat(process.waitFor(100,TimeUnit.SECONDS)).isTrue();System.out.println(java.nio.file.Files.readString(directory.resolve("browser.log")));assertThat(process.exitValue()).isZero();
        }finally{if(process.isAlive())process.destroyForcibly();java.nio.file.Files.deleteIfExists(ready);}
    }
    private void enable()throws Exception{ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit=10 WHERE id='"+policy+"'");}
    private String base(){return "/api/v1/projects/"+project+"/automations";}
    private Map<String,Object> body(String name){var body=new LinkedHashMap<String,Object>();body.put("name",name);body.put("triggerType","PROPERTY_REPORTED");body.put("triggerConfig",Map.of("deviceId",device));body.put("conditions",List.of());body.put("actions",List.of(Map.of("nodeType","notification-action","config",Map.of("channel","EMAIL","recipient","private@example.com"))));return body;}
    private tools.jackson.databind.JsonNode call(String method,String path,Object body,int expected)throws Exception{
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
            .header("Authorization","Bearer "+tokens.issue(new com.things.link.iam.application.AuthenticatedPrincipal(account,tenant,project)).value()).header("Content-Type","application/json")
            .method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()==429){Thread.sleep(1100);response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());}
        assertThat(response.statusCode()).as(method+" "+path+" "+response.body()).isEqualTo(expected);return response.body().isBlank()?json.nullNode():json.readTree(response.body());
    }
    private Connection owner()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private void ownerUpdate(String sql)throws Exception{try(var c=owner();var q=c.createStatement()){q.execute(sql);}}
}
