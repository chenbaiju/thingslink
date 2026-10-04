package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceCredentialService;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR0193/0197真实Console HTTP、PG和当前角色；不以直接Controller调用替代安全链。 */
class DeviceAccessConfigurationHttpTests extends WebhookFixture {
    @Autowired private AppTokenIssuer appTokens;
    @Autowired private DeviceCredentialService credentials;

    /** 专用配置/凭据/来源先于父夹具设备及项目清理。 */
    @AfterEach void cleanAccess() {
        for (String table : List.of("dev_connection", "dev_access_binding", "dev_credential", "sys_outbox_event")) {
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
        }
    }
    /** 冻结路径以当前真实项目与设备构造。 */
    private String path() { return "/api/v1/projects/" + project + "/devices/" + device + "/access-config"; }
    /** 真实Bearer请求共用断言前返回完整HTTP状态。 */
    private HttpResponse<String> read(String token) throws Exception { return request("GET", path(), null, token, Map.of()); }
    /** 三字段配置写请求，不使用替身主体。 */
    private HttpResponse<String> put(String body, String token) throws Exception { return request("PUT", path(), body, token, Map.of()); }
    /** 合法规范版本字符串由JSON库编码。 */
    private String body(String protocol, boolean enabled, String version) {
        return json.writeValueAsString(Map.of("protocol", protocol, "enabled", enabled, "expectedConfigVersion", version));
    }
    /** 只查询本轮审计，不将service调用次数视为提交。 */
    private long audits() { return owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='DEVICE_ACCESS_CONFIG_CHANGED'", Long.class, project); }
    /** 七字段响应严格脱敏且版本为字符串。 */
    private JsonNode view(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode node = json.readTree(response.body()); assertThat(node.size()).isEqualTo(7);
        assertThat(node.path("configVersion").isString()).isTrue(); assertThat(node.path("credentialVersion").isString()).isTrue();
        assertThat(response.body()).doesNotContain("secret", "hash", "session", "node", "tenantId", "heartbeatSeconds");
        return node;
    }

    @Test void legacyReadIsCompleteRedactedAndDoesNotCreateFacts() throws Exception {
        var node = view(read(token()));
        assertThat(node.path("protocol").asString()).isEqualTo("MQTT"); assertThat(node.path("enabled").asBoolean()).isTrue();
        assertThat(node.path("configured").asBoolean()).isFalse(); assertThat(node.path("configVersion").asString()).isEqualTo("0");
        assertThat(node.path("credentialVersion").asString()).isEqualTo("1"); assertThat(node.path("canManage").asBoolean()).isTrue();
        assertThat(node.path("allowedProtocols")).isEqualTo(json.readTree("[\"MQTT\",\"HTTP\",\"COAP\",\"TCP\"]"));
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_access_binding WHERE device_id=?", Integer.class, device)).isZero();
        assertThat(audits()).isZero(); assertThat(rows("sys_outbox_event")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"OWNER", "ADMIN", "OPERATOR", "VIEWER"})
    void allMembersReadButOnlyCurrentManagersWrite(String role) throws Exception {
        String token = token(); owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?", role, project, account);
        boolean manager = role.equals("OWNER") || role.equals("ADMIN");
        assertThat(view(read(token)).path("canManage").asBoolean()).isEqualTo(manager);
        var result = put(body("HTTP", true, "0"), token);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(manager ? 200 : 403);
        assertThat(audits()).isEqualTo(manager ? 1 : 0);
    }

    @Test void realAppApiKeyAndDeviceSecretCannotEnterConsoleRoute() throws Exception {
        String app = appTokens.issue(new AppAuthenticatedPrincipal(tenant, project, account)).value();
        assertThat(read(app).statusCode()).isEqualTo(401); assertThat(put(body("HTTP", true, "0"), app).statusCode()).isEqualTo(401);
        assertThat(request("GET", path(), null, null, Map.of("X-Api-Key", secret)).statusCode()).isEqualTo(401);
        assertThat(request("PUT", path(), body("HTTP", true, "0"), null, Map.of("X-Api-Key", secret)).statusCode()).isEqualTo(401);
        assertThat(read("71".repeat(32)).statusCode()).isEqualTo(401); assertThat(audits()).isZero();
    }

    @Test void currentMembershipAndDeviceVisibilityAreRechecked() throws Exception {
        String token = token(); owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", project, account);
        assertThat(read(token).statusCode()).isEqualTo(401); assertThat(put(body("HTTP", true, "0"), token).statusCode()).isEqualTo(401);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OWNER')", Uuid7.generate(), project, account);
        String foreign = "/api/v1/projects/" + UUID.randomUUID() + "/devices/" + device + "/access-config";
        assertThat(request("GET", foreign, null, token, Map.of()).statusCode()).isEqualTo(404);
        owner.update("UPDATE dev_device SET deleted_at=clock_timestamp() WHERE id=?", device);
        assertThat(read(token).statusCode()).isEqualTo(404); assertThat(put(body("HTTP", true, "0"), token).statusCode()).isEqualTo(404);
    }

    @Test void foreignTenantCollaboratorUsesActualProjectTenant() throws Exception {
        UUID collaborator = Uuid7.generate(), personal = tx.execute(status -> tenants.createTenant("access-collaborator"));
        additionalAccounts.add(collaborator);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES(?,?,'unused','collaborator')", collaborator, collaborator + "@example.com");
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES(?,?,?)", Uuid7.generate(), personal, collaborator);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'ADMIN')", Uuid7.generate(), project, collaborator);
        try {
            String token = tokens.issue(new AuthenticatedPrincipal(collaborator, personal, project)).value();
            assertThat(view(read(token)).path("canManage").asBoolean()).isTrue();
            var request = (tools.jackson.databind.node.ObjectNode) json.readTree(body("COAP", true, "0"));
            request.put("tenantId", personal.toString()).put("actorAccountId", account.toString());
            assertThat(view(put(json.writeValueAsString(request), token)).path("configVersion").asString()).isEqualTo("1");
            assertThat(owner.queryForObject("SELECT tenant_id FROM dev_access_binding WHERE device_id=?", UUID.class, device)).isEqualTo(tenant);
        } finally {
            owner.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?", personal);
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", personal);
            owner.update("DELETE FROM sys_tenant WHERE id=?", personal);
        }
    }

    @Test void noopConflictAndUnknownFieldsKeepOriginalCasContract() throws Exception {
        String token = token(); var noop = view(put(body("MQTT", true, "0"), token));
        assertThat(noop.path("configured").asBoolean()).isFalse(); assertThat(audits()).isZero();
        var change = (tools.jackson.databind.node.ObjectNode) json.readTree(body("TCP", true, "0"));
        change.put("tcpBound", true).put("heartbeatSeconds", 999).put("configVersion", "777");
        assertThat(view(put(json.writeValueAsString(change), token)).path("configVersion").asString()).isEqualTo("1");
        assertThat(owner.queryForObject("SELECT heartbeat_seconds FROM dev_access_binding WHERE device_id=?", Integer.class, device)).isNull();
        assertThat(view(put(body("TCP", true, "1"), token)).path("configVersion").asString()).isEqualTo("1");
        var conflict = put(body("TCP", true, "0"), token); assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).path("code").asInt()).isEqualTo(30065); assertThat(audits()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"SUB", "UNTYPED", "GATEWAY", "PASSTHROUGH"})
    void allowedProtocolsAndManageFeedbackMatchActualType(String kind) throws Exception {
        switch (kind) {
            case "SUB" -> owner.update("UPDATE dev_type SET device_kind='SUB_DEVICE' WHERE id=?", type);
            case "UNTYPED" -> {
                device = unbound; // 使用原本无发布模型的设备，不能绕过D112版本化类型不可变门禁。
                owner.update("UPDATE dev_device SET device_type_id=NULL WHERE id=?", device);
            }
            case "GATEWAY" -> owner.update("UPDATE dev_type SET device_kind='GATEWAY',access_protocol='STANDARD_GATEWAY' WHERE id=?", type);
            default -> owner.update("UPDATE dev_type SET access_protocol='MODBUS_RTU_PASSTHROUGH' WHERE id=?", type);
        }
        boolean configurable = kind.equals("GATEWAY") || kind.equals("PASSTHROUGH");
        var node = view(read(token())); assertThat(node.path("canManage").asBoolean()).isEqualTo(configurable);
        assertThat(node.path("allowedProtocols")).isEqualTo(json.readTree(configurable ? "[\"MQTT\"]" : "[]"));
        var rejected = put(body(configurable ? "HTTP" : "MQTT", true, "0"), token());
        assertThat(rejected.statusCode()).isEqualTo(409); assertThat(json.readTree(rejected.body()).path("code").asInt()).isEqualTo(30066);
        assertThat(audits()).isZero();
    }

    @Test void archivedProjectStillReadsButCannotManageOrWrite() throws Exception {
        String token = token(); owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        assertThat(view(read(token)).path("canManage").asBoolean()).isFalse();
        var rejected = put(body("HTTP", true, "0"), token); assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(json.readTree(rejected.body()).path("code").asInt()).isEqualTo(50017); assertThat(audits()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {
        "{}", "[]", "null", "{\"protocol\":\"MQTT\",\"enabled\":true}",
        "{\"protocol\":\"mqtt\",\"enabled\":true,\"expectedConfigVersion\":\"0\"}",
        "{\"protocol\":1,\"enabled\":true,\"expectedConfigVersion\":\"0\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":\"true\",\"expectedConfigVersion\":\"0\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":1,\"expectedConfigVersion\":\"0\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":null,\"expectedConfigVersion\":\"0\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":0}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":null}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":\"00\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":\" 0\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":\"+0\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":\"-1\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":\"1.0\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":\"1e1\"}",
        "{\"protocol\":\"MQTT\",\"enabled\":true,\"expectedConfigVersion\":\"9223372036854775808\"}"
    })
    void malformedKnownFieldsNeverCreateFacts(String body) throws Exception {
        assertThat(put(body, token()).statusCode()).as(body).isEqualTo(400);
        assertThat(audits()).isZero(); assertThat(owner.queryForObject("SELECT count(*) FROM dev_access_binding WHERE device_id=?", Integer.class, device)).isZero();
    }

    @Test void bigintVersionsRemainExactAndExhaustionIsAtomic() throws Exception {
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',9223372036854775807)", device, tenant, project);
        owner.update("UPDATE dev_device SET credential_version=9223372036854775807 WHERE id=?", device);
        var node = view(read(token())); assertThat(node.path("configVersion").asString()).isEqualTo("9223372036854775807");
        assertThat(node.path("credentialVersion").asString()).isEqualTo("9223372036854775807");
        assertThat(view(put(body("MQTT", true, "9223372036854775807"), token())).path("configVersion").asString()).isEqualTo("9223372036854775807");
        var rejected = put(body("HTTP", true, "9223372036854775807"), token());
        assertThat(rejected.statusCode()).isEqualTo(409); assertThat(json.readTree(rejected.body()).path("code").asInt()).isEqualTo(30067);
        assertThat(audits()).isZero();
    }

    @Test void concurrentHttpCasHasOneWinnerAndOneAudit() throws Exception {
        String token = token(); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return put(body("HTTP", true, "0"), token); });
            var second = executor.submit(() -> { start.await(); return put(body("COAP", true, "0"), token); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS).statusCode(), second.get(15, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(audits()).isEqualTo(1); assertThat(view(read(token)).path("configVersion").asString()).isEqualTo("1");
    }

    @ParameterizedTest @ValueSource(strings = {"sys_outbox_event", "sys_audit_log"})
    void sourceOrAuditFailureRollsBackConfigAndConnection(String deniedTable) throws Exception {
        owner.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version) VALUES(?,?,?,?,?,'MQTT',0)",
                Uuid7.generate(), tenant, project, device, "owned-fault-" + device);
        owner.execute("REVOKE INSERT ON " + deniedTable + " FROM thingslink_app");
        try { assertThat(put(body("HTTP", true, "0"), token()).statusCode()).isEqualTo(500); }
        finally { owner.execute("GRANT INSERT ON " + deniedTable + " TO thingslink_app"); }
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_access_binding WHERE device_id=?", Integer.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isEqualTo(1);
        assertThat(audits()).isZero(); assertThat(rows("sys_outbox_event")).isZero();
    }

    @Test void concurrentCredentialRotationNeverProducesMixedVersionView() throws Exception {
        String token = token(); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var rotations = executor.submit(() -> {
                start.await(); TenantContext.set(new TenantScope(tenant, project, account));
                try { for (int index = 0; index < 16; index++) { credentials.generate(project, device); Thread.sleep(250); } }
                finally { TenantContext.clear(); }
                return true;
            });
            start.countDown();
            for (int index = 0; index < 24; index++) {
                // 保留真实限流器，以低于账号秒窗口的速率读取。
                Thread.sleep(250);
                var node = view(read(token));
                assertThat(Long.parseLong(node.path("credentialVersion").asString())).isEqualTo(Long.parseLong(node.path("configVersion").asString()) + 1);
            }
            assertThat(rotations.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(view(read(token)).path("configVersion").asString()).isEqualTo("16");
    }
}
