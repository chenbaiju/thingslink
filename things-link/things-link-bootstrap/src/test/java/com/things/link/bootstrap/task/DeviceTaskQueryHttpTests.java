package com.things.link.bootstrap.task;

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

/** 自有普通APP数据库；真实HTTP、普通APP角色与自有数据库，验证当前组和原执行目标分离。 */
@AutoConfigureMockMvc
@Import(DeviceTaskQueryHttpTests.Configuration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceTaskQueryHttpTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_tasks").withUsername("thingslink").withPassword("thingslink");
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
    private UUID tenant, collaboratorTenant, owner, collaborator, viewer, stranger, project, device, secondDevice, deviceType;

    @BeforeEach void fixture() {
        tenant = tx.execute(s -> tenants.createTenant("设备任务目录项目租户"));
        collaboratorTenant = tx.execute(s -> tenants.createTenant("设备任务目录协作者租户"));
        owner = account(tenant); collaborator = account(collaboratorTenant); viewer = account(tenant); stranger = account(collaboratorTenant);
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'设备任务目录','sh-1',?)", project, tenant, "ch" + project.toString().replace("-", ""));
        for (var entry : Map.of(owner, "OWNER", collaborator, "OPERATOR", viewer, "VIEWER").entrySet())
            jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)", UUID.randomUUID(), project, entry.getKey(), entry.getValue());
        device = UUID.randomUUID(); secondDevice = UUID.randomUUID();
        inProject(() -> {
            UUID type = UUID.randomUUID(); deviceType=type;
            jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (?,?,?,?,'设备任务目录类型','DIRECT','STANDARD','WIFI','PUBLISHED')", type, tenant, project, type.toString());
            for (UUID id : List.of(device, secondDevice))
                jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,'设备任务目录设备','OFFLINE')", id, tenant, project, type, id.toString());
        });
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("device_tasks");
    }
    private UUID account(UUID home) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at) VALUES (?,?,'test','历史读取测试','ACTIVE',now())", id, id + "@example.test");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), home, id);
        return id;
    }
    private void inProject(Runnable action) { tx.executeWithoutResult(s -> { scope.establish(tenant, project); action.run(); }); }
    private UUID group(String ruleJson) {
        UUID id=UUID.randomUUID();
        inProject(()->jdbc.update("INSERT INTO dev_group(id,tenant_id,project_id,name,group_type,rule_json) VALUES (?,?,?,?,?,?::jsonb)",
                id,tenant,project,id.toString(),ruleJson==null?"STATIC":"DYNAMIC",ruleJson));
        return id;
    }
    private void member(UUID groupId, UUID target) {
        inProject(()->jdbc.update("INSERT INTO dev_group_member(tenant_id,project_id,group_id,device_id) VALUES (?,?,?,?)",tenant,project,groupId,target));
    }
    private UUID job(UUID groupId, int sequence) {
        UUID id=new UUID(project.getMostSignificantBits(),sequence);
        inProject(()->jdbc.update("INSERT INTO task_job(id,tenant_id,project_id,name,status,version,target_type,target_group_id,command_key,input,created_by,created_at,updated_at) VALUES (?,?,?,?,'PAUSED',2,?,?,'restart','{\"secret\":\"must-not-leak\"}'::jsonb,?,?,?)",
                id,tenant,project,"任务"+sequence,groupId==null?"ALL_DEVICES":"DEVICE_GROUP",groupId,owner,Timestamp.from(TIME),Timestamp.from(TIME)));
        return id;
    }
    private UUID execution(UUID jobId, UUID groupId, UUID target, int sequence) {
        UUID id=new UUID(project.getLeastSignificantBits(),sequence);
        inProject(()->{
            jdbc.update("INSERT INTO task_execution(id,tenant_id,project_id,job_id,target_type,target_group_id,command_key,input,requested_by,trigger_type,status,started_at,created_at,updated_at) VALUES (?,?,?,?,?,?,'original-command','{\"secret\":\"must-not-leak\"}'::jsonb,?,'MANUAL','SUCCEEDED',?,?,?)",
                    id,tenant,project,jobId,groupId==null?"ALL_DEVICES":"DEVICE_GROUP",groupId,owner,Timestamp.from(TIME),Timestamp.from(TIME),Timestamp.from(TIME));
            if (target!=null)
                jdbc.update("INSERT INTO task_target(execution_id,tenant_id,project_id,device_id,status) VALUES (?,?,?,?,'SKIPPED')",id,tenant,project,target);
        });
        return id;
    }
    private List<String> all(String kind) throws Exception {
        List<String> ids=new ArrayList<>(); String cursor=null;
        do {
            JsonNode page=cursor==null?read(kind,owner,project,device,200,"limit","2")
                    :read(kind,owner,project,device,200,"limit","2","cursor",cursor);
            for (JsonNode row:page.path("items")) {
                ids.add(row.path("id").asString());
                assertThat(row.toString()).doesNotContain("must-not-leak","requestedBy","createdBy","input");
            }
            cursor=page.path("nextCursor").isNull()||page.path("nextCursor").isMissingNode()?null:page.path("nextCursor").asString();
        } while(cursor!=null);
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
    @Test void filtersStaticMembershipBeforePagingAndIncludesPausedAllDeviceDefinitions() throws Exception {
        UUID included=group(null),excluded=group(null); member(included,device); member(excluded,secondDevice);
        List<UUID> expected=new ArrayList<>();
        for(int i=1;i<=5;i++) expected.add(job(i%2==0?included:null,i));
        // 一整批不匹配的较新任务不能截断后面的匹配项。
        for(int i=6;i<=110;i++) job(excluded,i);
        UUID deleted=job(null,111); inProject(()->jdbc.update("UPDATE task_job SET deleted_at=now() WHERE id=?",deleted));
        assertThat(all("task-jobs")).containsExactlyElementsOf(expected.reversed().stream().map(UUID::toString).toList());
        var first=read("task-jobs",owner,project,device,200).path("items").get(0);
        assertThat(first.path("status").asString()).isEqualTo("PAUSED");
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("id","name","status","version","targetType","targetGroupId","commandKey","createdAt");
    }
    @Test void dynamicTypeStatusAndAnyAllTagsUseCurrentDeviceFacts() throws Exception {
        inProject(()->jdbc.update("INSERT INTO dev_tag(id,tenant_id,project_id,device_id,tag_key,tag_value) VALUES (?,?,?,?,'site','office')",UUID.randomUUID(),tenant,project,device));
        String dimensions="\"deviceTypeIds\":[\""+deviceType+"\"],\"statuses\":[\"OFFLINE\"],";
        UUID any=group("{"+dimensions+"\"tags\":{\"site\":\"office\",\"area\":\"west\"},\"tagMatch\":\"ANY\"}");
        UUID all=group("{"+dimensions+"\"tags\":{\"site\":\"office\",\"area\":\"west\"},\"tagMatch\":\"ALL\"}");
        UUID wrongType=group("{\"deviceTypeIds\":[\""+UUID.randomUUID()+"\"],\"tags\":{},\"statuses\":[]}");
        UUID anyJob=job(any,1),allJob=job(all,2);job(wrongType,3);
        assertThat(all("task-jobs")).containsExactly(anyJob.toString());
        inProject(()->jdbc.update("INSERT INTO dev_tag(id,tenant_id,project_id,device_id,tag_key,tag_value) VALUES (?,?,?,?,'area','west')",UUID.randomUUID(),tenant,project,device));
        assertThat(all("task-jobs")).containsExactly(allJob.toString(),anyJob.toString());
        inProject(()->jdbc.update("UPDATE dev_device SET status='ONLINE' WHERE id=?",device));
        assertThat(all("task-jobs")).isEmpty();
    }
    @Test void deletingGroupOrDefinitionDoesNotRewriteActualTargetHistory() throws Exception {
        UUID group=group(null);member(group,device);UUID job=job(group,1);
        UUID real=execution(job,group,device,1);execution(job,group,secondDevice,2);execution(job,group,null,3);
        inProject(()->{
            jdbc.update("DELETE FROM dev_group_member WHERE group_id=?",group);
            jdbc.update("UPDATE dev_group SET deleted_at=now() WHERE id=?",group);
            jdbc.update("UPDATE task_job SET deleted_at=now(),command_key='changed' WHERE id=?",job);
        });
        assertThat(all("task-jobs")).isEmpty();
        assertThat(all("task-executions")).containsExactly(real.toString());
        var row=read("task-executions",owner,project,device,200).path("items").get(0);
        assertThat(row.path("commandKey").asString()).isEqualTo("original-command");
        assertThat(row.path("executionStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(row.path("targetStatus").asString()).isEqualTo("SKIPPED");
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id","jobId","triggerType","executionStatus","targetStatus","commandKey","commandId","startedAt","acceptedAt","completedAt");
    }
    @Test void historyStablePagingUsesExecutionTimeAndIdOnlyForOriginalTargets() throws Exception {
        UUID job=job(null,1); List<UUID> ids=new ArrayList<>();
        for(int i=1;i<=5;i++)ids.add(execution(job,null,device,i));
        execution(job,null,secondDevice,6);
        assertThat(all("task-executions")).containsExactlyElementsOf(ids.reversed().stream().map(UUID::toString).toList());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"task-jobs","task-executions"})
    void bothEndpointsReauthorizeRoleDeviceAndRejectCursorTransfer(String kind) throws Exception {
        UUID job=job(null,1);job(null,2);execution(job,null,device,1);execution(job,null,device,2);
            for(UUID actor:List.of(owner,viewer,collaborator)) assertThat(read(kind,actor,project,device,200).path("items").size()).isEqualTo(2);
            read(kind,null,project,device,401);read(kind,stranger,project,device,401);
            read(kind,owner,UUID.randomUUID(),device,404);read(kind,owner,project,UUID.randomUUID(),404);
            String cursor=read(kind,owner,project,device,200,"limit","1").path("nextCursor").asString();
            read(kind,collaborator,project,device,400,"limit","1","cursor",cursor);
            read(kind,owner,project,secondDevice,400,"limit","1","cursor",cursor);
            read(kind,owner,project,device,400,"limit","2","cursor",cursor);
            read(kind.equals("task-jobs")?"task-executions":"task-jobs",owner,project,device,400,"limit","1","cursor",cursor);
            for(String limit:List.of("0","51","nope"))read(kind,owner,project,device,400,"limit",limit);
            read(kind,owner,project,device,400,"cursor","");read(kind,owner,project,device,400,"unknown","x");
            read(kind,owner,project,device,400,"limit","1","limit","2");
        jdbc.update("UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?",project,collaborator);
        inProject(()->jdbc.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",device));
        read(kind,collaborator,project,device,401);read(kind,owner,project,device,404);
    }
    @Test void emptyResultsAndIndexesRemainExplicit() throws Exception {
        assertThat(all("task-jobs")).isEmpty();assertThat(all("task-executions")).isEmpty();
        for(String index:List.of("task_job_device_directory_idx","task_target_device_history_idx","task_execution_device_history_idx"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname=?",Integer.class,index)).isEqualTo(1);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean DynamicPropertyRegistrar deviceTaskDatabase() {
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
