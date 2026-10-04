package com.things.link.bootstrap.integration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 原认证HTTP到ACL/回调/来源的真实事务组合；不替代Broker线协议验收。 */
class MqttConnectionCallbackTests extends WebhookFixture {
    /** 生产回调过滤器凭据，仅在测试独占实例使用。 */
    @Value("${things-link.security.broker-callback.secret}") String callbackSecret;
    /** 核对重放不虚增已持久指标。 */
    @Autowired MeterRegistry meters;
    /** 独占设备凭据，禁止日志输出。 */
    private static final String PASSWORD = "e7".repeat(32);
    /** 实际项目/设备路由。 */
    String username;

    /** 每例新设备，走真实密码认证和持久票据签发。 */
    @BeforeEach void seed() {
        owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?", device);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'callback-ticket')", tenant, project, device, PASSWORD);
        username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project) + "/"
                + owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?", String.class, device);
    }
    /** 清理只覆盖本项目；票据也由设备外键级联兜底。 */
    @AfterEach void clean() {
        for (String table : new String[]{"dev_connection", "dev_mqtt_connection_ticket", "dev_mqtt_session_cursor", "dev_credential", "sys_outbox_event"})
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
    }
    /** 两次同Client ID认证生成不同连接UUID，但持久会话名与配置保持不变。 */
    @Test void actualAuthenticationIssuesDifferentConnectionIdentityForSameSession() throws Exception {
        var first = auth("same"); var second = auth("same");
        assertThat(first.get("tc_auth_connection_id")).isNotEqualTo(second.get("tc_auth_connection_id"));
        assertThat(first.get("clientid")).isEqualTo(second.get("clientid"));
        assertThat(rows("dev_connection")).isZero(); assertThat(sources()).isZero();
        assertThat(acl(first)).isEqualTo("allow");
    }
    /** 原连接重放只改变一次状态、来源及计数。 */
    @Test void repeatedCallbacksDoNotDuplicateFactsOrMetrics() throws Exception {
        var identity = auth("same"); double beforeConnect = count("connected"), beforeDisconnect = count("disconnected");
        assertThat(event("connected", identity)).isTrue(); assertThat(event("connected", identity)).isTrue();
        assertThat(rows("dev_connection")).isEqualTo(1); assertThat(sources()).isEqualTo(1);
        assertThat(count("connected") - beforeConnect).isEqualTo(1);
        assertThat(event("disconnected", identity)).isTrue(); assertThat(event("disconnected", identity)).isTrue();
        assertThat(sources()).isEqualTo(2); assertThat(count("disconnected") - beforeDisconnect).isEqualTo(1);
    }
    /** 同Client ID旧断连在新connected之后到达，不得关闭新连接或形成离线来源。 */
    @Test void delayedOldDisconnectCannotCloseNewConnection() throws Exception {
        var old = auth("same"); event("connected", old);
        var current = auth("same"); event("connected", current);
        assertThat(event("disconnected", old)).isTrue();
        assertThat(active(current)).isTrue(); assertThat(sources()).isEqualTo(1);
        assertThat(acl(old)).isEqualTo("deny"); assertThat(acl(current)).isEqualTo("allow");
    }
    /** 没有先见connected的disconnect仍冻结终态，不能随后复活。 */
    @Test void disconnectBeforeConnectNeverCreatesOnlineFact() throws Exception {
        var identity = auth("same"); assertThat(event("disconnected", identity)).isTrue();
        assertThat(event("connected", identity)).isFalse(); assertThat(acl(identity)).isEqualTo("deny");
        assertThat(rows("dev_connection")).isZero(); assertThat(sources()).isZero();
    }
    /** 新断连先到时会结束原同会话连接，后到的新connected不能恢复。 */
    @Test void newerDisconnectClosesPriorConnectionWithoutResurrection() throws Exception {
        var old = auth("same"); event("connected", old);
        var current = auth("same"); assertThat(event("disconnected", current)).isTrue();
        assertThat(active(old)).isFalse(); assertThat(event("connected", current)).isFalse();
        assertThat(sources()).isEqualTo(2); assertThat(status()).isEqualTo("OFFLINE");
    }
    /** 另一个Client ID仍活跃时，当前连接关闭不生成设备离线。 */
    @Test void otherClientIdKeepsDeviceOnline() throws Exception {
        var first = auth("one"); var second = auth("two"); event("connected", first); event("connected", second);
        event("disconnected", first); assertThat(status()).isEqualTo("ONLINE"); assertThat(sources()).isEqualTo(1);
        event("disconnected", second); assertThat(status()).isEqualTo("OFFLINE"); assertThat(sources()).isEqualTo(2);
    }
    /** 缺失/畸形/未知身份不能凭旧五属性走兼容旁路。 */
    @Test void missingMalformedAndUnknownNonceDenyBothAclAndLifecycle() throws Exception {
        var identity = auth("same");
        for (String value : new String[]{null, "1-1-1-1-1", UUID.randomUUID().toString()}) {
            var invalid = new LinkedHashMap<>(identity);
            if (value == null) invalid.remove("tc_auth_connection_id"); else invalid.put("tc_auth_connection_id", value);
            assertThat(acl(invalid)).isEqualTo("deny"); assertThat(event("connected", invalid)).isFalse();
        }
        assertThat(rows("dev_connection")).isZero(); assertThat(sources()).isZero();
    }
    /** 较早签发但最后才到的认证身份不替代高次序已受理连接。 */
    @Test void oldAuthenticationResultCannotRegainPermissionAfterNewConnection() throws Exception {
        var old = auth("same"); var current = auth("same"); event("connected", current);
        assertThat(event("connected", old)).isFalse(); assertThat(acl(old)).isEqualTo("deny");
        assertThat(active(current)).isTrue(); assertThat(rows("dev_connection")).isEqualTo(1);
    }
    /** 真实来源写入失败返回5xx，整笔回滚，恢复权限后相同回调可重试。 */
    @Test void sourceFailureReturnsRetryableStatusAndRollsBackTicketAndConnection() throws Exception {
        var identity = auth("same");
        owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try { assertThat(callback("events/connected", identity).statusCode()).isBetween(500, 599); }
        finally { owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(rows("dev_connection")).isZero(); assertThat(rows("dev_mqtt_session_cursor")).isZero();
        assertThat(owner.queryForObject("SELECT state FROM dev_mqtt_connection_ticket WHERE id=?", String.class,
                UUID.fromString((String) identity.get("tc_auth_connection_id")))).isEqualTo("PENDING");
        assertThat(event("connected", identity)).isTrue(); assertThat(sources()).isEqualTo(1);
    }
    /** 签发数据库故障不得返回allow或伪造无票据认证。 */
    @Test void authenticationDatabaseFailureCannotReturnAllow() throws Exception {
        owner.execute("REVOKE INSERT ON dev_mqtt_connection_ticket FROM thingslink_app");
        try {
            var response = callback("auth", Map.of("username", username, "password", PASSWORD, "clientid", "same"));
            assertThat(response.statusCode()).isBetween(500, 599);
            assertThat(response.body()).doesNotContain("\"result\":\"allow\"");
        } finally { owner.execute("GRANT INSERT ON dev_mqtt_connection_ticket TO thingslink_app"); }
        assertThat(rows("dev_mqtt_connection_ticket")).isZero();
        assertThat(auth("same")).containsKey("tc_auth_connection_id");
    }
    /** 多个HTTP认证并发不能由共享锁升级造成死锁或票据重用。 */
    @Test void concurrentAuthenticationIssuesUniqueCommittedTickets() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<Map<String, Object>>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> auth("same"));
            var ids = new java.util.HashSet<Object>();
            for (var future : executor.invokeAll(tasks, 20, TimeUnit.SECONDS)) ids.add(future.get().get("tc_auth_connection_id"));
            assertThat(ids).hasSize(12);
        }
        assertThat(rows("dev_mqtt_connection_ticket")).isEqualTo(12); assertThat(rows("dev_connection")).isZero();
    }
    /** 真实认证响应的原属性用于后续请求，不从当前库补齐。 */
    private Map<String, Object> auth(String rawClientId) throws Exception {
        var response = callback("auth", Map.of("username", username, "password", PASSWORD, "clientid", rawClientId));
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body()); assertThat(body.path("result").asString()).isEqualTo("allow");
        var attributes = new LinkedHashMap<String, Object>();
        body.path("client_attrs").properties().forEach(entry -> attributes.put(entry.getKey(), entry.getValue().asString()));
        attributes.put("username", username); attributes.put("clientid", body.path("clientid_override").asString());
        attributes.put("peerhost", "127.0.0.1"); attributes.put("node", "test-node"); attributes.put("reason", "normal");
        return attributes;
    }
    /** 实际HTTP链保留Broker秘密校验。 */
    private HttpResponse<String> callback(String path, Map<String, ?> body) throws Exception {
        return request("POST", "/api/v1/emqx/" + path, json.writeValueAsString(body), null,
                Map.of("X-Broker-Callback-Token", callbackSecret));
    }
    /** 读取实际生命周期受理决策。 */
    private boolean event(String kind, Map<String, Object> identity) throws Exception {
        var response = callback("events/" + kind, identity); assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("accepted").asBoolean();
    }
    /** Topic合法不替代原连接票据许可。 */
    private String acl(Map<String, Object> identity) throws Exception {
        var body = new LinkedHashMap<>(identity); body.put("topic", "tc/v1/" + username + "/up/property/report"); body.put("access", "publish");
        var response = callback("acl", body); assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("result").asString();
    }
    /** 精确原连接仍处于活跃状态。 */
    private boolean active(Map<String, Object> identity) {
        return Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM dev_connection WHERE mqtt_connection_id=? AND disconnected_at IS NULL)",
                Boolean.class, UUID.fromString((String) identity.get("tc_auth_connection_id"))));
    }
    /** 只统计当前项目公开来源。 */
    private int sources() { return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project); }
    /** 读取设备当前实际状态。 */
    private String status() { return owner.queryForObject("SELECT status FROM dev_device WHERE id=?", String.class, device); }
    /** 使用既有低基数计数器。 */
    private double count(String event) { return meters.get("thingslink.device.connection.events").tag("event", event).counter().count(); }
}
