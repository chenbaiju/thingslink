package com.things.link.bootstrap.telemetry.command;

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

/** 自有普通APP数据库；只种子化读取事实，不声称验证命令写入或设备物理执行。 */
@AutoConfigureMockMvc
@Import(DeviceCommandHistoryHttpTests.Configuration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceCommandHistoryHttpTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("command_history").withUsername("thingslink").withPassword("thingslink");
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
    private UUID tenant, collaboratorTenant, owner, collaborator, viewer, stranger, project, device, secondDevice, definition;

    @BeforeEach void fixture() {
        tenant = tx.execute(s -> tenants.createTenant("命令历史项目租户"));
        collaboratorTenant = tx.execute(s -> tenants.createTenant("命令历史协作者租户"));
        owner = account(tenant); collaborator = account(collaboratorTenant); viewer = account(tenant); stranger = account(collaboratorTenant);
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'命令历史','sh-1',?)", project, tenant, "ch" + project.toString().replace("-", ""));
        for (var entry : Map.of(owner, "OWNER", collaborator, "OPERATOR", viewer, "VIEWER").entrySet())
            jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)", UUID.randomUUID(), project, entry.getKey(), entry.getValue());
        device = UUID.randomUUID(); secondDevice = UUID.randomUUID(); definition = UUID.randomUUID();
        inProject(() -> {
            UUID type = UUID.randomUUID();
            jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (?,?,?,?,'命令历史类型','DIRECT','STANDARD','WIFI','PUBLISHED')", type, tenant, project, type.toString());
            for (UUID id : List.of(device, secondDevice))
                jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,'命令历史设备','OFFLINE')", id, tenant, project, type, id.toString());
            jdbc.update("INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name) VALUES (?,?,?,?,'restart','重启')", definition, tenant, project, type);
        });
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("command_history");
    }
    private UUID account(UUID home) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at) VALUES (?,?,'test','历史读取测试','ACTIVE',now())", id, id + "@example.test");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), home, id);
        return id;
    }
    private void inProject(Runnable action) { tx.executeWithoutResult(s -> { scope.establish(tenant, project); action.run(); }); }
    private UUID fact(UUID target, String operation, int sequence) {
        UUID id = new UUID(project.getMostSignificantBits(), sequence);
        inProject(() -> jdbc.update("""
                INSERT INTO ts_device_command(id,tenant_id,project_id,target_device_id,connection_device_id,
                    command_definition_id,operation_type,command_key,request_payload,status,idempotency_key,
                    requested_by,timeout_seconds,attempt_count,max_attempts,next_attempt_at,trace_id,accepted_at)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,'ACCEPTED',?,?,30,1,3,?,'history-fixture',?)
                """, id, tenant, project, target, target, operation.equals("COMMAND") ? definition : null,
                operation, operation.equals("COMMAND") ? "restart" : null, "{\"secretInput\":\"must-not-leak\"}",
                project + ":" + sequence, owner, Timestamp.from(TIME), Timestamp.from(TIME)));
        return id;
    }
    private JsonNode read(UUID actor, UUID pathProject, UUID target, int expected, String... query) throws Exception {
        var request = get("/api/v1/projects/{projectId}/devices/{deviceId}/commands", pathProject, target);
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
    @Test void filtersBeforePagingAndUsesIdToResolveEqualTimestamps() throws Exception {
        for (int i = 1; i <= 5; i++) fact(device, i % 2 == 0 ? "PROPERTY_SET" : "COMMAND", i);
        for (int i = 6; i <= 10; i++) fact(secondDevice, "COMMAND", i);
        List<String> seen = new ArrayList<>(); String cursor = null;
        do {
            JsonNode page = cursor == null ? read(owner, project, device, 200, "limit", "2")
                    : read(owner, project, device, 200, "limit", "2", "cursor", cursor);
            for (JsonNode item : page.path("items")) {
                seen.add(item.path("id").asString());
                assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "deviceId", "operationType", "commandKey", "status", "attemptCount", "maxAttempts", "acceptedAt", "completedAt");
                assertThat(item.toString()).doesNotContain("must-not-leak", "requestedBy", "idempotencyKey", "input", "output", "attempts");
            }
            cursor = page.path("nextCursor").isNull() || page.path("nextCursor").isMissingNode() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).containsExactly(new UUID(project.getMostSignificantBits(),5).toString(),new UUID(project.getMostSignificantBits(),4).toString(),new UUID(project.getMostSignificantBits(),3).toString(),new UUID(project.getMostSignificantBits(),2).toString(),new UUID(project.getMostSignificantBits(),1).toString());
    }
    @Test void authorizesCurrentRoleAndOwnerRlsForCrossTenantCollaborator() throws Exception {
        UUID id = fact(device, "PROPERTY_SET", 1);
        assertThat(read(collaborator, project, device, 200).path("items").get(0).path("id").asString()).isEqualTo(id.toString());
        read(viewer, project, device, 403); read(stranger, project, device, 401); read(null, project, device, 401);
        jdbc.update("UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?", project, collaborator);
        read(collaborator, project, device, 401);
    }
    @Test void rejectsDeletedUnknownDeviceAndAnotherProject() throws Exception {
        fact(device, "COMMAND", 1);
        read(owner, project, UUID.randomUUID(), 404);
        read(owner, UUID.randomUUID(), device, 404);
        inProject(() -> jdbc.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", device));
        read(owner, project, device, 404);
    }
    @Test void cursorCannotMoveBetweenIdentityDeviceLimitOrPurpose() throws Exception {
        fact(device, "COMMAND", 1); fact(device, "PROPERTY_SET", 2);
        String cursor = read(owner, project, device, 200, "limit", "1").path("nextCursor").asString();
        read(collaborator, project, device, 400, "limit", "1", "cursor", cursor);
        read(owner, project, secondDevice, 400, "limit", "1", "cursor", cursor);
        read(owner, project, device, 400, "limit", "2", "cursor", cursor);
        read(owner, project, device, 400, "limit", "1", "cursor", cursor + "x");
        for (String limit : List.of("0", "51", "-1", "nope")) read(owner, project, device, 400, "limit", limit);
        read(owner, project, device, 400, "cursor", "");
        read(owner, project, device, 400, "unexpected", "x");
        read(owner, project, device, 400, "limit", "1", "limit", "2");
    }
    @Test void emptyPageAndIndexExistenceDoNotRequireAnyCommand() throws Exception {
        assertThat(read(owner, project, device, 200).path("items").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='ts_device_command_target_history_idx'", String.class))
                .contains("project_id, target_device_id, accepted_at DESC, id DESC");
    }
    @Test void gatewayConnectionColumnDoesNotReplaceOriginalTarget() throws Exception {
        UUID id = fact(device, "COMMAND", 1);
        // 只校验历史读取轴；当前拓扑不重写已受理命令的原连接事实。
        inProject(() -> jdbc.update("UPDATE ts_device_command SET connection_device_id=? WHERE id=?", secondDevice, id));
        assertThat(read(owner, project, secondDevice, 200).path("items").isEmpty()).isTrue();
        assertThat(read(owner, project, device, 200).path("items").size()).isEqualTo(1);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean DynamicPropertyRegistrar commandHistoryDatabase() {
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
