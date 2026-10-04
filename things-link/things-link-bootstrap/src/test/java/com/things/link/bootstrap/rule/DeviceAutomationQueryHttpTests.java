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
@Import(DeviceAutomationQueryHttpTests.Configuration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceAutomationQueryHttpTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_automations").withUsername("thingslink").withPassword("thingslink");
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
    private UUID tenant, collaboratorTenant, owner, collaborator, viewer, stranger, project, device, secondDevice;

    @BeforeEach void fixture() {
        tenant = tx.execute(s -> tenants.createTenant("设备自动化目录项目租户"));
        collaboratorTenant = tx.execute(s -> tenants.createTenant("设备自动化目录协作者租户"));
        owner = account(tenant); collaborator = account(collaboratorTenant); viewer = account(tenant); stranger = account(collaboratorTenant);
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'设备自动化目录','sh-1',?)", project, tenant, "ch" + project.toString().replace("-", ""));
        for (var entry : Map.of(owner, "OWNER", collaborator, "OPERATOR", viewer, "VIEWER").entrySet())
            jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)", UUID.randomUUID(), project, entry.getKey(), entry.getValue());
        device = UUID.randomUUID(); secondDevice = UUID.randomUUID();
        inProject(() -> {
            UUID type = UUID.randomUUID();
            jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (?,?,?,?,'设备自动化目录类型','DIRECT','STANDARD','WIFI','PUBLISHED')", type, tenant, project, type.toString());
            for (UUID id : List.of(device, secondDevice))
                jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,'设备自动化目录设备','OFFLINE')", id, tenant, project, type, id.toString());
        });
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("device_automations");
    }
    private UUID account(UUID home) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at) VALUES (?,?,'test','历史读取测试','ACTIVE',now())", id, id + "@example.test");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), home, id);
        return id;
    }
    private void inProject(Runnable action) { tx.executeWithoutResult(s -> { scope.establish(tenant, project); action.run(); }); }
    record Auto(UUID id,UUID version,UUID target,String trigger) { }
    private Auto definition(UUID target,String trigger,int sequence,boolean publish,boolean condition,boolean action) {
        UUID id=new UUID(project.getMostSignificantBits(),sequence);
        inProject(()->jdbc.update("INSERT INTO rule_automation(id,tenant_id,project_id,name,status,version,created_by,created_at,updated_at) VALUES (?,?,?,?,'DRAFT',1,?,?,?)",
                id,tenant,project,"自动化"+sequence,owner,Timestamp.from(TIME),Timestamp.from(TIME)));
        return version(id,target,trigger,1,publish,condition,action);
    }
    private Auto version(UUID id,UUID target,String trigger,int number,boolean publish,boolean condition,boolean action) {
        UUID version=UUID.randomUUID();
        String config=JSON.writeValueAsString(switch(trigger) {
            case "ONE_SHOT" -> Map.of("deviceId",target.toString(),"runAt",TIME.toString(),"payload",Map.of());
            case "CRON" -> Map.of("deviceId",target.toString(),"cronExpression","0 * * * * *","timezone","UTC","payload",Map.of());
            default -> Map.of("deviceId",target.toString());
        });
        String conditions=condition?"[{\"nodeType\":\"property-compare-condition\",\"config\":{\"propertyKey\":\"relay\",\"operator\":\"EQ\",\"value\":true}}]":"[]";
        String actions=action?"[{\"nodeType\":\"device-command-action\",\"config\":{\"commandKey\":\"restart\",\"input\":{}}}]":"[{\"nodeType\":\"notification-action\",\"config\":{}}]";
        inProject(()->{
            jdbc.update("INSERT INTO rule_automation_version(id,tenant_id,project_id,automation_id,version_number,trigger_type,trigger_config,conditions,actions,created_by,created_at) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?)",
                    version,tenant,project,id,number,trigger,config,conditions,actions,owner,Timestamp.from(TIME));
            if(publish)jdbc.update("UPDATE rule_automation SET status='ACTIVE',active_version_id=?,version=version+1 WHERE id=?",version,id);
        });
        return new Auto(id,version,target,trigger);
    }
    private UUID execution(Auto auto,int sequence,boolean action) {
        UUID id=new UUID(project.getLeastSignificantBits(),sequence);
        inProject(()->{
            jdbc.update("""
                    INSERT INTO rule_automation_execution(id,tenant_id,project_id,automation_id,automation_version_id,trigger_type,
                    occurrence_key,scheduled_fire_at,device_id,input_snapshot,input_digest,responsible_account_id,trace_id,
                    occurred_at,accepted_at,status,attempt_count,created_at,completed_at,recovery_deadline)
                    VALUES (?,?,?,?,?,?,'fixture:'||?::text,?,?,'{"secret":"must-not-leak"}'::jsonb,?,?,'fixture',?,?,'DISPATCHED',1,?,?,?)
                    """,id,tenant,project,auto.id(),auto.version(),auto.trigger(),id,Timestamp.from(TIME),auto.target(),"a".repeat(64),owner,
                    Timestamp.from(TIME),Timestamp.from(TIME),Timestamp.from(TIME),Timestamp.from(TIME),Timestamp.from(TIME.plusSeconds(86400)));
            if(action)assertThat(jdbc.queryForObject("SELECT rule_device_action_delivery_record_automation(?,?,?,?,?,?,?,'COMMAND',NULL,'REJECTED','UNSUPPORTED','fixture',?)",
                    Boolean.class,UUID.randomUUID(),tenant,project,auto.id(),auto.version(),id,auto.target(),Timestamp.from(TIME))).isTrue();
        });
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
    @Test void definitionsOnlyFollowPublishedPointerAndIncludePausedPropertyAndTimeContext() throws Exception {
        var property=definition(device,"PROPERTY_REPORTED",1,true,true,true);
        var once=definition(device,"ONE_SHOT",2,true,false,false);
        var cron=definition(device,"CRON",3,true,false,true);
        definition(device,"ONE_SHOT",4,false,false,true);definition(secondDevice,"ONE_SHOT",5,true,false,true);
        version(property.id(),secondDevice,"PROPERTY_REPORTED",2,false,false,false);
        inProject(()->jdbc.update("UPDATE rule_automation SET status='PAUSED' WHERE id=?",cron.id()));
        assertThat(all("automations")).containsExactly(cron.id().toString(),once.id().toString(),property.id().toString());
        var rows=read("automations",owner,project,device,200).path("items");
        assertThat(rows.get(0).path("triggerType").asString()).isEqualTo("CRON");
        assertThat(rows.get(0).path("status").asString()).isEqualTo("PAUSED");
        assertThat(rows.get(1).path("hasDeviceAction").asBoolean()).isFalse();
        assertThat(rows.get(2).path("usesConditionInput").asBoolean()).isTrue();
        assertThat(rows.get(2).path("activeVersionId").asString()).isEqualTo(property.version().toString());
        assertThat(rows.get(0).propertyNames()).containsExactlyInAnyOrder("id","name","status","activeVersionId","versionNumber","triggerType","usesConditionInput","hasDeviceAction","createdAt");
    }
    @Test void historyKeepsOriginalVersionDeviceAndActionFactAfterRetargetAndDelete() throws Exception {
        var original=definition(device,"ONE_SHOT",1,true,false,true);
        UUID record=execution(original,1,true);
        var revised=version(original.id(),secondDevice,"CRON",2,true,false,false);execution(revised,2,true);
        assertThat(all("automations")).isEmpty();
        inProject(()->jdbc.update("UPDATE rule_automation SET deleted_at=now() WHERE id=?",original.id()));
        assertThat(all("automation-executions")).containsExactly(record.toString());
        var row=read("automation-executions",viewer,project,device,200).path("items").get(0);
        assertThat(row.path("automationVersionId").asString()).isEqualTo(original.version().toString());
        assertThat(row.path("triggerType").asString()).isEqualTo("ONE_SHOT");
        assertThat(row.path("deviceActionRecordCount").asLong()).isEqualTo(1);
        assertThat(row.path("status").asString()).isEqualTo("DISPATCHED");
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id","automationId","automationVersionId","triggerType","status","reasonCode","attemptCount","deviceActionRecordCount","occurredAt","acceptedAt","createdAt","completedAt");
    }
    @Test void historyPaginatesAtEqualTimeAndZeroActionRecordsAreNotDeviceSuccess() throws Exception {
        var auto=definition(device,"ONE_SHOT",1,true,false,false);List<UUID> expected=new ArrayList<>();
        for(int i=1;i<=5;i++)expected.add(execution(auto,i,false));
        assertThat(all("automation-executions")).containsExactlyElementsOf(expected.reversed().stream().map(UUID::toString).toList());
        assertThat(read("automation-executions",owner,project,device,200).path("items").get(0).path("deviceActionRecordCount").asLong()).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"automations","automation-executions"})
    void currentMembershipAndSelectedProjectApplyToBothPurposes(String kind) throws Exception {
        var a=definition(device,"ONE_SHOT",1,true,false,true);definition(device,"ONE_SHOT",2,true,false,true);execution(a,1,false);execution(a,2,false);
        boolean manage=kind.equals("automations");
        read(kind,viewer,project,device,manage?403:200);read(kind,collaborator,project,device,manage?403:200);
        jdbc.update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?",project,collaborator);
        assertThat(read(kind,collaborator,project,device,200).path("items").size()).isEqualTo(2);
        read(kind,null,project,device,401);read(kind,stranger,project,device,401);read(kind,owner,UUID.randomUUID(),device,404);
        String cursor=read(kind,owner,project,device,200,"limit","1").path("nextCursor").asString();
        read(kind,collaborator,project,device,400,"limit","1","cursor",cursor);
        read(kind,owner,project,secondDevice,400,"limit","1","cursor",cursor);
        read(kind,owner,project,device,400,"limit","2","cursor",cursor);
        read(manage?"automation-executions":"automations",owner,project,device,400,"limit","1","cursor",cursor);
        for(String limit:List.of("0","51","nope"))read(kind,owner,project,device,400,"limit",limit);
        read(kind,owner,project,device,400,"cursor","");read(kind,owner,project,device,400,"unexpected","x");
        read(kind,owner,project,device,400,"limit","1","limit","2");
        jdbc.update("UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?",project,collaborator);
        read(kind,collaborator,project,device,401);
        inProject(()->jdbc.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",device));
        read(kind,owner,project,device,404);
    }
    @Test void emptyPageAndDeviceIndexesExist() throws Exception {
        assertThat(all("automations")).isEmpty();
        assertThat(all("automation-executions")).isEmpty();
        read("automation-executions",viewer,project,UUID.randomUUID(),404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('rule_automation_device_directory_idx','rule_automation_version_all_device_idx','rule_automation_execution_device_idx')",Integer.class)).isEqualTo(3);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean DynamicPropertyRegistrar deviceAutomationDatabase() {
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
