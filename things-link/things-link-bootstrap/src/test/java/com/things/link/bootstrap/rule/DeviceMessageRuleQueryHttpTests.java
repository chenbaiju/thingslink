package com.things.link.bootstrap.rule;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.infrastructure.security.JwtTokenIssuer;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 自有普通APP数据库；真实HTTP、普通APP角色与自有数据库，验证发布版本、原设备上下文与动作记录归属。 */
@AutoConfigureMockMvc
@Import(DeviceMessageRuleQueryHttpTests.Configuration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceMessageRuleQueryHttpTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_message_rules").withUsername("thingslink").withPassword("thingslink");
    private static final String URL = start();
    private static String start() { DATABASE.start(); return DATABASE.getJdbcUrl(); }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant TIME = Instant.parse("2026-09-30T12:00:00Z");
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") ApplicationRunner ignoredSharedQuota;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope scope;
    @Autowired TenantProvisioning tenants;
    @Autowired JwtTokenIssuer issuer;
    @Autowired com.things.link.rule.infrastructure.persistence.JdbcRuleExecutionLogStore logStore;
    private UUID tenant, collaboratorTenant, owner, collaborator, viewer, stranger, project, device, secondDevice;

    @BeforeEach void fixture() {
        tenant = tx.execute(s -> tenants.createTenant("设备消息规则目录项目租户"));
        collaboratorTenant = tx.execute(s -> tenants.createTenant("设备消息规则目录协作者租户"));
        owner = account(tenant); collaborator = account(collaboratorTenant); viewer = account(tenant); stranger = account(collaboratorTenant);
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'设备消息规则目录','sh-1',?)", project, tenant, "ch" + project.toString().replace("-", ""));
        for (var entry : Map.of(owner, "OWNER", collaborator, "OPERATOR", viewer, "VIEWER").entrySet())
            jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)", UUID.randomUUID(), project, entry.getKey(), entry.getValue());
        device = UUID.randomUUID(); secondDevice = UUID.randomUUID();
        inProject(() -> {
            UUID type = UUID.randomUUID();
            jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (?,?,?,?,'设备消息规则目录类型','DIRECT','STANDARD','WIFI','PUBLISHED')", type, tenant, project, type.toString());
            for (UUID id : List.of(device, secondDevice))
                jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,'设备消息规则目录设备','OFFLINE')", id, tenant, project, type, id.toString());
        });
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("device_message_rules");
    }
    private UUID account(UUID home) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at) VALUES (?,?,'test','历史读取测试','ACTIVE',now())", id, id + "@example.test");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), home, id);
        return id;
    }
    private void inProject(Runnable action) { tx.executeWithoutResult(s -> { scope.establish(tenant, project); action.run(); }); }
    record Rule(UUID id,UUID version) { }
    private Rule definition(int sequence,boolean publish,boolean action) {
        UUID id=new UUID(project.getMostSignificantBits(),sequence);
        inProject(()->jdbc.update("INSERT INTO rule_message(id,tenant_id,project_id,name,status,version,created_by,created_at,updated_at) VALUES (?,?,?,?,'DRAFT',1,?,?,?)",
                id,tenant,project,"规则"+sequence,owner,Timestamp.from(TIME),Timestamp.from(TIME)));
        return version(id,1,publish,action);
    }
    private Rule version(UUID id,int number,boolean publish,boolean action) {
        UUID version=UUID.randomUUID();
        String actions=action?"[{\"nodeType\":\"device-command-action\",\"config\":{\"commandKey\":\"restart\",\"input\":{}}}]":"[]";
        inProject(()->{
            jdbc.update("INSERT INTO rule_version(id,tenant_id,project_id,rule_id,version_number,source,source_sha256,actions,created_by,created_at) VALUES (?,?,?,?,?,'input => input',?,?::jsonb,?,?)",
                    version,tenant,project,id,number,"a".repeat(64),actions,owner,Timestamp.from(TIME));
            if(publish)jdbc.update("UPDATE rule_message SET status='ACTIVE',active_version_id=?,version=version+1 WHERE id=?",version,id);
        });
        return new Rule(id,version);
    }
    private UUID log(Rule rule,UUID target,int sequence) {
        UUID id=new UUID(project.getLeastSignificantBits(),sequence);
        String fn=target==null?"rule_execution_log_append(?,?,?,?,?,?,1,'SUCCESS','SUCCESS',0::bigint,2,2,?)":"rule_execution_log_append_device(?,?,?,?,?,?,1,'SUCCESS','SUCCESS',0::bigint,2,2,?,?)";
        List<Object> args=new ArrayList<>(List.of(id,tenant,project,id,rule.id(),rule.version(),Timestamp.from(TIME)));
        if(target!=null)args.add(target);
        assertThat(jdbc.queryForObject("SELECT "+fn,Boolean.class,args.toArray())).isTrue();
        return id;
    }
    private UUID action(Rule rule,UUID target,int sequence) {
        UUID id=new UUID(project.getLeastSignificantBits(),sequence);
        assertThat(jdbc.queryForObject("SELECT rule_device_action_delivery_record(?,?,?,?,?,?,?,'COMMAND',NULL,'REJECTED','UNSUPPORTED','fixture',?)",
                Boolean.class,id,tenant,project,rule.id(),rule.version(),id,target,Timestamp.from(TIME))).isTrue();
        return id;
    }
    private List<String> all(String kind) throws Exception {
        List<String> ids=new ArrayList<>();String cursor=null;
        do{
            var page=cursor==null?read(kind,owner,project,device,200,"limit","2"):read(kind,owner,project,device,200,"limit","2","cursor",cursor);
            for(JsonNode row:page.path("items")){ids.add(row.path("id").asString());assertThat(row.toString()).doesNotContain("must-not-leak","triggerConfig","inputSnapshot","responsibleAccountId");}
            cursor=page.path("nextCursor").isNull()||page.path("nextCursor").isMissingNode()?null:page.path("nextCursor").asString();
        }while(cursor!=null);
        return ids;
    }
    private JsonNode read(String kind, UUID actor, UUID pathProject, UUID target, int expected, String... query) throws Exception {
        var request = get("/api/v1/projects/{projectId}/devices/{deviceId}/"+kind, pathProject, target);
        for (int i = 0; i < query.length; i += 2) request.param(query[i], query[i + 1]);
        if (actor != null) {
            UUID home = actor.equals(collaborator) || actor.equals(stranger) ? collaboratorTenant : tenant;
            request.header("Authorization", "Bearer " + issuer.issue(new AuthenticatedPrincipal(actor, home, project)).value());
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        return JSON.readTree(response.getContentAsString());
    }
    @Test void candidatesRemainProjectCandidatesAndOnlyUsePublishedVersion() throws Exception {
        var first=definition(1,true,true);var paused=definition(2,true,false);definition(3,false,true);
        version(first.id(),2,false,false);
        inProject(()->jdbc.update("UPDATE rule_message SET status='PAUSED' WHERE id=?",paused.id()));
        assertThat(all("message-rule-candidates")).containsExactly(paused.id().toString(),first.id().toString());
        var rows=read("message-rule-candidates",owner,project,device,200).path("items");
        assertThat(rows).isEqualTo(read("message-rule-candidates",owner,project,secondDevice,200).path("items"));
        assertThat(rows.get(0).path("relationScope").asString()).isEqualTo("PROJECT_CANDIDATE");
        assertThat(rows.get(0).path("status").asString()).isEqualTo("PAUSED");
        assertThat(rows.get(0).path("hasDeviceAction").asBoolean()).isFalse();
        assertThat(rows.get(1).path("activeVersionId").asString()).isEqualTo(first.version().toString());
        assertThat(rows.get(0).propertyNames()).containsExactlyInAnyOrder("id","name","status","activeVersionId","versionNumber","relationScope","hasDeviceAction","createdAt");
    }
    @Test void oldUnknownLogsAreNotBackfilledWhileActionsRetainOriginalDeviceAndVersion() throws Exception {
        var original=definition(1,true,true);UUID known=log(original,device,1);UUID unknown=log(original,null,2);
        UUID delivery=action(original,device,3);action(original,secondDevice,4);log(original,secondDevice,5);
        version(original.id(),2,true,false);
        inProject(()->jdbc.update("UPDATE rule_message SET deleted_at=now() WHERE id=?",original.id()));
        assertThat(all("message-rule-candidates")).isEmpty();
        assertThat(all("message-rule-executions")).containsExactly(known.toString());
        assertThat(all("message-rule-actions")).containsExactly(delivery.toString());
        inProject(()->assertThat(jdbc.queryForObject("SELECT device_id FROM rule_execution_log WHERE id=?",UUID.class,unknown)).isNull());
        var row=read("message-rule-executions",viewer,project,device,200).path("items").get(0);
        assertThat(row.path("ruleVersionId").asString()).isEqualTo(original.version().toString());
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id","ruleId","ruleVersionId","messageId","attempt","status","resultCode","durationMillis","inputBytes","outputBytes","createdAt");
        var delivered=read("message-rule-actions",viewer,project,device,200).path("items").get(0);
        assertThat(delivered.path("status").asString()).isEqualTo("REJECTED");
        assertThat(delivered.path("commandId").isNull()).isTrue();
        assertThat(delivered.propertyNames()).containsExactlyInAnyOrder("id","ruleId","ruleVersionId","messageId","operationType","status","commandId","failureCode","createdAt","completedAt");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"message-rule-executions","message-rule-actions"})
    void historyUsesStableDeviceKeysetAtEqualTimes(String kind) throws Exception {
        var rule=definition(1,true,true);List<UUID> ids=new ArrayList<>();
        for(int i=1;i<=5;i++)ids.add(kind.endsWith("actions")?action(rule,device,i):log(rule,device,i));
        assertThat(all(kind)).containsExactlyElementsOf(ids.reversed().stream().map(UUID::toString).toList());
    }
    @Test void jdbcAppenderPreservesLegacyAndEnforcesDeviceOwnershipAndIdempotency() throws Exception {
        var rule=definition(1,true,false);
        var key=new com.things.link.rule.application.queue.RuleExecutionKey(project,UUID.randomUUID(),rule.id(),rule.version());
        var entry=new com.things.link.rule.application.queue.RuleExecutionLogEntry(tenant,key,1,
                com.things.link.rule.application.queue.RuleExecutionLogEntry.Status.SUCCESS,"SUCCESS",java.time.Duration.ZERO,2,2,TIME,device);
        assertThat(logStore.append(entry)).isTrue();assertThat(logStore.append(entry)).isFalse();
        var wrong=new com.things.link.rule.application.queue.RuleExecutionLogEntry(tenant,key,2,entry.status(),"SUCCESS",java.time.Duration.ZERO,2,2,TIME,UUID.randomUUID());
        assertThat(logStore.append(wrong)).isFalse();
        var foreign=new com.things.link.rule.application.queue.RuleExecutionLogEntry(collaboratorTenant,key,2,entry.status(),"SUCCESS",java.time.Duration.ZERO,2,2,TIME,device);
        assertThat(logStore.append(foreign)).isFalse();
        var legacy=new com.things.link.rule.application.queue.RuleExecutionLogEntry(tenant,key,2,entry.status(),"SUCCESS",java.time.Duration.ZERO,2,2,TIME);
        assertThat(logStore.append(legacy)).isTrue();
        // A later attempt with known identity cannot overwrite an existing unknown attempt.
        assertThat(logStore.append(new com.things.link.rule.application.queue.RuleExecutionLogEntry(tenant,key,2,entry.status(),"SUCCESS",java.time.Duration.ZERO,2,2,TIME,device))).isFalse();
        assertThat(all("message-rule-executions")).hasSize(1);
        inProject(()->{
            assertThat(jdbc.queryForObject("SELECT device_id FROM rule_execution_log WHERE message_id=? AND attempt=2",UUID.class,key.messageId())).isNull();
            jdbc.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",device);
        });
        assertThat(logStore.append(new com.things.link.rule.application.queue.RuleExecutionLogEntry(tenant,key,3,entry.status(),"SUCCESS",java.time.Duration.ZERO,2,2,TIME,device))).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"message-rule-candidates","message-rule-executions","message-rule-actions"})
    void currentMembershipAndSelectedProjectApplyToBothPurposes(String kind) throws Exception {
        var a=definition(1,true,true);definition(2,true,true);log(a,device,1);log(a,device,2);action(a,device,1);action(a,device,2);
        boolean manage=kind.equals("message-rule-candidates");
        read(kind,viewer,project,device,manage?403:200);read(kind,collaborator,project,device,manage?403:200);
        jdbc.update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?",project,collaborator);
        assertThat(read(kind,collaborator,project,device,200).path("items").size()).isEqualTo(2);
        read(kind,null,project,device,401);read(kind,stranger,project,device,401);read(kind,owner,UUID.randomUUID(),device,404);
        String cursor=read(kind,owner,project,device,200,"limit","1").path("nextCursor").asString();
        read(kind,collaborator,project,device,400,"limit","1","cursor",cursor);
        read(kind,owner,project,secondDevice,400,"limit","1","cursor",cursor);
        read(kind,owner,project,device,400,"limit","2","cursor",cursor);
        read(manage?"message-rule-executions":"message-rule-candidates",owner,project,device,400,"limit","1","cursor",cursor);
        for(String limit:List.of("0","51","nope"))read(kind,owner,project,device,400,"limit",limit);
        read(kind,owner,project,device,400,"cursor","");read(kind,owner,project,device,400,"unexpected","x");
        read(kind,owner,project,device,400,"limit","1","limit","2");
        jdbc.update("UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?",project,collaborator);
        read(kind,collaborator,project,device,401);
        inProject(()->jdbc.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",device));
        read(kind,owner,project,device,404);
    }
    @Test void emptyResultsAndIndexesDoNotImplyCompleteLegacyHistory() throws Exception {
        for(String kind:List.of("message-rule-candidates","message-rule-executions","message-rule-actions")) assertThat(all(kind)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('rule_message_candidate_device_idx','rule_execution_log_device_idx','rule_device_action_delivery_message_device_idx')",Integer.class)).isEqualTo(3);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean DynamicPropertyRegistrar deviceMessageRuleDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> URL);
                registry.add("spring.flyway.url", () -> URL);
                registry.add("spring.flyway.user", DATABASE::getUsername);
                registry.add("spring.flyway.password", DATABASE::getPassword);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }
}
