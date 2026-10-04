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
class AutomationManagementHttpTests extends AbstractIntegrationTest {
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
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"enabled",true);
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
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired com.things.link.iam.application.TokenIssuer tokens;
    final java.net.http.HttpClient client=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Test void managesStrictVersionedDefinitionsAndBoundCursors()throws Exception{
        String base=base();var body=body("first");
        var first=call("POST",base,body,201);String id=first.get("id").asString(),path=base+"/"+id;
        assertThat(first.has("tenantId")).isFalse();assertThat(first.has("createdBy")).isFalse();
        body.put("responsibleAccountId",account.toString());call("POST",base,body,400);body.remove("responsibleAccountId");
        body.put("name","second");call("POST",base,body,201);
        var page=call("GET",base+"?limit=1",null,200);String next=page.get("nextCursor").asString();
        assertThat(call("GET",base+"?limit=1&cursor="+next,null,200).get("items").get(0).get("id").asString()).isEqualTo(id);
        assertThat(call("GET",base+"?status=ACTIVE&cursor="+next,null,400).get("code").asInt()).isEqualTo(40049);
        call("GET",base+"?limit=101",null,400);call("GET",base+"?status=UNKNOWN",null,400);
        body.put("name","first");body.put("expectedVersion",1);call("PUT",path,body,200);call("PUT",path,body,409);
        var versions=call("GET",path+"/version-history?limit=1",null,200);String history=versions.get("nextCursor").asString();
        var old=call("GET",path+"/version-history?limit=1&cursor="+history,null,200).get("items").get(0);
        assertThat(old.get("versionNumber").asLong()).isEqualTo(1);
        call("GET",path+"/versions/"+old.get("id").asString(),null,200);
        call("GET",path+"/version-history?cursor="+next,null,400);
        String active=path+"/versions/"+old.get("id").asString()+"/activate?expectedVersion=2";
        assertThat(call("POST",active,null,403).get("code").asInt()).isEqualTo(40055);
        ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit=10 WHERE id='"+policy+"'");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"enabled",false);
        assertThat(call("POST",active,null,403).get("code").asInt()).isEqualTo(40055);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"enabled",true);
        call("POST",active,null,200);call("POST",path+"/pause?expectedVersion=3",null,200);
        call("DELETE",path+"?expectedVersion=4",null,204);call("GET",path,null,404);
    }
    @Test void executionsAreMemberReadableWithoutPrivatePayloadOrConfiguration()throws Exception{
        ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit=10 WHERE id='"+policy+"'");
        String path=base()+"/"+call("POST",base(),body("run"),201).get("id").asString();
        String version=call("GET",path+"/version-history",null,200).get("items").get(0).get("id").asString();
        call("POST",path+"/versions/"+version+"/activate?expectedVersion=1",null,200);
        for(int i=0;i<2;i++){var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var event=new AutomationPropertyAccepted(1,Uuid7.generate(),tenant,project,device,"1.0.0",now,now,Map.of("secret","private-input"),"test");
            ingress.accept(device.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),json.writeValueAsBytes(event),partition,i);}
        for(var candidate:executions.candidates())if(candidate.projectId().equals(project))runner.run(candidate);
        for(var candidate:executions.candidates())if(candidate.projectId().equals(project))runner.run(candidate);
        try(var c=owner();var q=c.prepareStatement("SELECT payload::text FROM sys_outbox_event WHERE tenant_id=?")){
            q.setObject(1,tenant);try(var rows=q.executeQuery()){while(rows.next())deliveries.accept(json.readValue(rows.getString(1),com.things.link.shared.message.RuleNotificationDeliveryRequest.class));}}
        String logs="/api/v1/projects/"+project+"/automation-executions";
        for(String role:List.of("OWNER","ADMIN","OPERATOR","VIEWER")){
            ownerUpdate("UPDATE sys_project_member SET role='"+role+"' WHERE project_id='"+project+"'");
            if(role.equals("VIEWER")||role.equals("OPERATOR"))call("GET",path,null,403);
            var page=call("GET",logs+"?limit=1",null,200);String next=page.get("nextCursor").asString();
            var second=call("GET",logs+"?limit=1&cursor="+next,null,200);
            assertThat(second.get("items").size()).isEqualTo(1);
            assertThat(second.get("items").get(0).get("id")).isNotEqualTo(page.get("items").get(0).get("id"));
            call("GET",logs+"?status=FAILED&cursor="+next,null,400);
            String id=page.get("items").get(0).get("id").asString();var detail=call("GET",logs+"/"+id,null,200);
            assertThat(detail.toString()).doesNotContain("private-input","recipient","inputSnapshot","input_snapshot","leaseToken","conditions","actions");
            assertThat(detail.get("summary").get("status").asString()).isEqualTo("DISPATCHED");assertThat(detail.get("attempts").size()).isEqualTo(1);
            assertThat(detail.get("notifications").size()).isEqualTo(1);
        }
        call("GET",logs+"?from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z",null,400);
        call("GET",logs+"/"+Uuid7.generate(),null,404);
        ownerUpdate("DELETE FROM sys_project_member WHERE project_id='"+project+"'");call("GET",logs,null,401);
    }
    @Test void transientDatabaseFailureHas503AndNoPartialDefinition()throws Exception{
        String name="auto_http_"+partition;
        ownerUpdate("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid THEN RAISE EXCEPTION 'transient fixture' USING ERRCODE='40001'; END IF; RETURN NEW; END $$");
        ownerUpdate("CREATE TRIGGER "+name+" BEFORE INSERT ON rule_automation_version FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        try{assertThat(call("POST",base(),body("rollback"),503).get("code").asInt()).isEqualTo(40056);
            try(var c=owner();var q=c.prepareStatement("SELECT count(*) FROM rule_automation WHERE project_id=?")){q.setObject(1,project);try(var r=q.executeQuery()){r.next();assertThat(r.getInt(1)).isZero();}}
        }finally{ownerUpdate("DROP TRIGGER "+name+" ON rule_automation_version");ownerUpdate("DROP FUNCTION "+name+"()");}
    }
    /** 生产Console通过真实HTTP创建/发布；文件屏障只触发测试源事件，不替换API响应。 */
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="thingslink.test.automation-browser",matches="true")
    void realConsoleManagesPropertyAutomation()throws Exception{
        var root=java.nio.file.Path.of("../..").toAbsolutePath().normalize();var directory=root.resolve(System.getProperty("thingslink.test.automation-evidence-dir","logs/verify/g3-auto-1c-4"));
        java.nio.file.Files.createDirectories(directory);var ready=directory.resolve("browser.ready");java.nio.file.Files.deleteIfExists(ready);
        String password="AutomationJourney-Only-2030!";
        var encoder=passwords;
        try(var c=owner();var q=c.prepareStatement("UPDATE sys_account SET password_hash=?,email_verified_at=clock_timestamp() WHERE id=?")){q.setString(1,encoder.encode(password));q.setObject(2,account);q.executeUpdate();}
        ownerUpdate("UPDATE sys_project SET name='规则测试项目' WHERE id='"+project+"'");
        ownerUpdate("UPDATE dev_device SET name='自动化设备' WHERE id='"+device+"'");
        ownerUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit=10 WHERE id='"+policy+"'");
        var builder=new ProcessBuilder("node",root.resolve("scripts/tests/console-automation-management-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("browser.log").toFile());
        builder.environment().putAll(Map.of("RULE_BACKEND","http://127.0.0.1:"+port,"RULE_EMAIL",account+"@example.com","RULE_PASSWORD",password,"AUTO_READY",ready.toString()));
        var process=builder.start();boolean accepted=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        try{
            while(process.isAlive()&&System.nanoTime()<deadline){
                if(!accepted&&java.nio.file.Files.exists(ready)){
                    var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var event=new AutomationPropertyAccepted(1,Uuid7.generate(),tenant,project,device,"1.0.0",now,now,Map.of("temperature",42),"browser");
                    ingress.accept(device.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),json.writeValueAsBytes(event),partition,0);
                    for(var candidate:executions.candidates())if(candidate.projectId().equals(project))runner.run(candidate);
                    accepted=true;
                }
                process.waitFor(100,TimeUnit.MILLISECONDS);
            }
            assertThat(process.isAlive()).as("浏览器需在90秒内退出").isFalse();System.out.println(java.nio.file.Files.readString(directory.resolve("browser.log")));assertThat(process.exitValue()).isZero();assertThat(accepted).isTrue();
        }finally{if(process.isAlive())process.destroyForcibly();java.nio.file.Files.deleteIfExists(ready);}
    }
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
