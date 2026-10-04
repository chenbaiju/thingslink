package com.things.link.bootstrap.enduser;

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

/** 自有普通APP数据库；真实HTTP、普通APP角色与自有数据库，验证当前三重授权过滤。 */
@AutoConfigureMockMvc
@Import(DeviceEndUserHttpTests.Configuration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceEndUserHttpTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_end_users").withUsername("thingslink").withPassword("thingslink");
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
        tenant = tx.execute(s -> tenants.createTenant("设备授权目录项目租户"));
        collaboratorTenant = tx.execute(s -> tenants.createTenant("设备授权目录协作者租户"));
        owner = account(tenant); collaborator = account(collaboratorTenant); viewer = account(tenant); stranger = account(collaboratorTenant);
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'设备授权目录','sh-1',?)", project, tenant, "ch" + project.toString().replace("-", ""));
        for (var entry : Map.of(owner, "OWNER", collaborator, "OPERATOR", viewer, "VIEWER").entrySet())
            jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)", UUID.randomUUID(), project, entry.getKey(), entry.getValue());
        device = UUID.randomUUID(); secondDevice = UUID.randomUUID();
        inProject(() -> {
            UUID type = UUID.randomUUID();
            jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (?,?,?,?,'设备授权目录类型','DIRECT','STANDARD','WIFI','PUBLISHED')", type, tenant, project, type.toString());
            for (UUID id : List.of(device, secondDevice))
                jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,'设备授权目录设备','OFFLINE')", id, tenant, project, type, id.toString());
        });
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("device_end_users");
    }
    private UUID account(UUID home) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at) VALUES (?,?,'test','历史读取测试','ACTIVE',now())", id, id + "@example.test");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), home, id);
        return id;
    }
    private void inProject(Runnable action) { tx.executeWithoutResult(s -> { scope.establish(tenant, project); action.run(); }); }
    private UUID binding(UUID target, String role, String userStatus, String roleStatus, String relationStatus, int sequence) {
        UUID id = new UUID(project.getMostSignificantBits(), sequence);
        UUID appUser = UUID.randomUUID();
        inProject(() -> {
            jdbc.update("INSERT INTO app_user(id,tenant_id,username,password_hash,display_name,status) VALUES (?,?,?,'must-not-leak',?,?)",
                    appUser, tenant, appUser.toString(), sequence % 2 == 0 ? null : "显示名" + sequence, userStatus);
            if (roleStatus != null)
                jdbc.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role,status) VALUES (?,?,?,?,'OBSERVER',?)",
                        UUID.randomUUID(), tenant, project, appUser, roleStatus);
            jdbc.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role,status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    id, tenant, project, appUser, target, role, relationStatus, Timestamp.from(TIME));
        });
        return id;
    }
    private JsonNode read(UUID actor, UUID pathProject, UUID target, int expected, String... query) throws Exception {
        var request = get("/api/v1/projects/{projectId}/devices/{deviceId}/end-users", pathProject, target);
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
    @Test void filtersAllThreeStatesBeforePagingAndReturnsOnlyLowSensitivityFields() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 1; i <= 5; i++) expected.add(binding(device, i == 1 ? "PRIMARY" : i % 2 == 0 ? "MEMBER" : "READ_ONLY", "ACTIVE", "ACTIVE", "ACTIVE", i));
        binding(device, "MEMBER", "LOCKED", "ACTIVE", "ACTIVE", 6);
        binding(device, "MEMBER", "ACTIVE", "DISABLED", "ACTIVE", 7);
        binding(device, "MEMBER", "ACTIVE", "ACTIVE", "CLOSED", 8);
        binding(device, "MEMBER", "ACTIVE", null, "ACTIVE", 9);
        binding(secondDevice, "MEMBER", "ACTIVE", "ACTIVE", "ACTIVE", 10);
        List<String> seen = new ArrayList<>(); String cursor = null;
        do {
            JsonNode page = cursor == null ? read(owner, project, device, 200, "limit", "2")
                    : read(owner, project, device, 200, "limit", "2", "cursor", cursor);
            for (JsonNode item : page.path("items")) {
                seen.add(item.path("id").asString());
                assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "appUserId", "displayName", "relationRole", "createdAt");
                assertThat(item.toString()).doesNotContain("must-not-leak", "password", "username", "email", "phone");
            }
            cursor = page.path("nextCursor").isNull() || page.path("nextCursor").isMissingNode() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).containsExactlyElementsOf(expected.reversed().stream().map(UUID::toString).toList());
    }
    @Test void includesViewerAndCrossTenantCollaboratorButRejectsStaleMembership() throws Exception {
        UUID id = binding(device, "PRIMARY", "ACTIVE", "ACTIVE", "ACTIVE", 1);
        for (UUID actor : List.of(owner, viewer, collaborator))
            assertThat(read(actor, project, device, 200).path("items").get(0).path("id").asString()).isEqualTo(id.toString());
        jdbc.update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?", project, collaborator);
        read(collaborator, project, device, 200);
        read(stranger, project, device, 401); read(null, project, device, 401);
        jdbc.update("UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?", project, collaborator);
        read(collaborator, project, device, 401);
    }
    @Test void rejectsInvalidDevicesAndAnotherProjectWithoutPretendingEmpty() throws Exception {
        read(owner, project, UUID.randomUUID(), 404);
        read(owner, UUID.randomUUID(), device, 404);
        inProject(() -> jdbc.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", device));
        read(owner, project, device, 404);
    }
    @Test void cursorIsBoundToIdentityDeviceAndLimitAndParametersAreStrict() throws Exception {
        binding(device, "MEMBER", "ACTIVE", "ACTIVE", "ACTIVE", 1);
        binding(device, "MEMBER", "ACTIVE", "ACTIVE", "ACTIVE", 2);
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
    @Test void nextPageRechecksCurrentGrantInsteadOfPreservingRevokedRows() throws Exception {
        UUID old = binding(device, "MEMBER", "ACTIVE", "ACTIVE", "ACTIVE", 1);
        binding(device, "MEMBER", "ACTIVE", "ACTIVE", "ACTIVE", 2);
        String cursor = read(owner, project, device, 200, "limit", "1").path("nextCursor").asString();
        inProject(() -> jdbc.update("UPDATE app_user_device SET status='CLOSED' WHERE id=?", old));
        assertThat(read(owner, project, device, 200, "limit", "1", "cursor", cursor).path("items").isEmpty()).isTrue();
    }
    @Test void emptyPageAndIndexAreExplicit() throws Exception {
        assertThat(read(viewer, project, device, 200).path("items").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='app_user_device_current_directory_idx'", String.class))
                .contains("tenant_id, project_id, device_id, created_at DESC, id DESC", "ACTIVE");
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean DynamicPropertyRegistrar deviceEndUserDatabase() {
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
