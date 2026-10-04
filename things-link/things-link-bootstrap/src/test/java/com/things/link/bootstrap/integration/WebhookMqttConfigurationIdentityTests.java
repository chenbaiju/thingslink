package com.things.link.bootstrap.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实认证/ACL/生命周期HTTP验证原配置身份，不能补查升级旧认证许可。 */
class WebhookMqttConfigurationIdentityTests extends WebhookFixture {
    /** 本例设备密钥，始终与管理API Key隔离。 */
    private static final String DEVICE_SECRET = "d5".repeat(32);
    /** 真实回调认证秘密。 */
    @Value("${things-link.security.broker-callback.secret}") private String brokerSecret;
    /** 设备路由。 */
    private String username;

    /** 在原始数据库创建实际设备凭据供生产认证端口校验。 */
    @BeforeEach
    void seedCredential() {
        owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?", device);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'config-test')", tenant, project, device, DEVICE_SECRET);
        username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project)
                + "/" + owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?", String.class, device);
    }

    /** 仅清理本例独占数据，允许原失败证据继续保留。 */
    @AfterEach
    void clearFacts() {
        for (String table : new String[]{"dev_connection", "dev_credential", "dev_access_binding", "sys_outbox_event"}) {
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
        }
    }

    /** 获取生产认证器发放的原始属性，不从测试数据库补配置代次。 */
    private Map<String, Object> authenticate() throws Exception {
        var result = callback("auth", Map.of("username", username, "password", DEVICE_SECRET, "clientid", "config-client"));
        assertThat(result.path("result").asString()).isEqualTo("allow");
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (String key : new String[]{"tc_auth_tenant_id", "tc_auth_project_id", "tc_auth_device_id",
                "tc_auth_credential_version", "tc_auth_config_version", "tc_auth_connection_id"}) {
            if (result.path("client_attrs").has(key)) attrs.put(key, result.path("client_attrs").path(key).asString());
        }
        attrs.put("clientid", result.path("clientid_override").asString());
        return attrs;
    }

    /** 真实HTTP权限链，不模拟Broker回调安全过滤器。 */
    private tools.jackson.databind.JsonNode callback(String route, Map<String, Object> body) throws Exception {
        var response = request("POST", "/api/v1/emqx/" + route, json.writeValueAsString(body), null,
                Map.of("X-Broker-Callback-Token", brokerSecret));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    /** 对原认证属性发起设备发布授权。 */
    private String acl(Map<String, Object> attrs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(attrs);
        body.putAll(Map.of("username", username, "topic", "tc/v1/" + username + "/up/property/report", "access", "publish"));
        return callback("acl", body).path("result").asString();
    }

    /** 生命周期携带Broker原认证属性，配置改变后保持原样以制造真实迟到反例。 */
    private boolean event(String kind, String client, Map<String, Object> attrs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(attrs);
        body.putAll(Map.of("username", username, "clientid", attrs.getOrDefault("clientid",client), "node", "fixture-node", "reason", "normal"));
        return callback("events/" + kind, body).path("accepted").asBoolean();
    }

    /** 原五字段加连接票据及有效Client ID，MQTT默认配置是0；完整属性正向可授权与建连/关闭。 */
    @Test
    void emitsOriginalIdentityAndConnectionTicket() throws Exception {
        var attrs = authenticate();
        assertThat(attrs).hasSize(7).containsEntry("tc_auth_config_version", "0")
                .containsEntry("tc_auth_credential_version", "1");
        assertThat(acl(attrs)).isEqualTo("allow");
        assertThat(event("connected", "current", attrs)).isTrue();
        assertThat(event("disconnected", "current", attrs)).isTrue();
    }

    /** 真实认证HTTP只签发服务器命名空间，原Client ID原样保留，跨配置隔离会话。 */
    @Test
    void signsSessionNamespaceWithoutTrustingRequestAttributes() throws Exception {
        String wire = " client 中文 ";
        var request = Map.<String,Object>of("username",username,"password",DEVICE_SECRET,"clientid",wire,
                "client_attrs",Map.of("tc_auth_mountpoint","forged/","tc_auth_wire_client_id","forged"),
                "clientid_override","forged");
        var first = callback("auth",request);
        assertThat(first.path("result").asString()).isEqualTo("allow");
        assertThat(first.path("clientid_override").asString()).matches("tc-device-[0-9a-f]{64}");
        assertThat(first.path("client_attrs").size()).isEqualTo(8);
        assertThat(first.path("client_attrs").path("tc_auth_mountpoint").asString()).isEqualTo("tc/private/device/"+device+"/0/");
        assertThat(first.path("client_attrs").path("tc_auth_wire_client_id").asString()).isEqualTo(wire);
        assertThat(callback("auth",request).path("clientid_override")).isEqualTo(first.path("clientid_override"));
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',9)",device,tenant,project);
        var changed = callback("auth",request);
        assertThat(changed.path("clientid_override")).isNotEqualTo(first.path("clientid_override"));
        assertThat(changed.path("client_attrs").path("tc_auth_mountpoint").asString()).isEqualTo("tc/private/device/"+device+"/9/");
    }

    /** 缺失或空原Client ID不签发成功响应，也不回退原Client ID会话。 */
    @Test
    void deniesMissingWireClientIdentity() throws Exception {
        var missing = callback("auth",Map.of("username",username,"password",DEVICE_SECRET));
        var empty = callback("auth",Map.of("username",username,"password",DEVICE_SECRET,"clientid",""));
        for (var result : java.util.List.of(missing,empty)) {
            assertThat(result.path("result").asString()).isEqualTo("deny");
            assertThat(result.has("client_attrs")).isFalse();
            assertThat(result.has("clientid_override")).isFalse();
        }
    }

    /** 即使共享Broker秘密和路由都正确，缺原认证属性也不能取得设备许可。 */
    @Test
    void missingOriginalIdentityIsNotGuessedFromUsername() throws Exception {
        assertThat(acl(Map.of())).isEqualTo("deny");
        assertThat(event("connected", "missing", Map.of())).isFalse();
        assertThat(rows("dev_connection")).isZero();
    }

    /** 半套、跨设备、旧版本和非规范版本字符串均不能降级到当前查询。 */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-device", "wrong-tenant", "wrong-project", "credential", "config", "leading-zero", "overflow"})
    void rejectsMalformedOrForeignServerAttributes(String failure) throws Exception {
        Map<String, Object> attrs = authenticate();
        switch (failure) {
            case "missing" -> attrs.remove("tc_auth_device_id");
            case "wrong-device" -> attrs.put("tc_auth_device_id", UUID.randomUUID().toString());
            case "wrong-tenant" -> attrs.put("tc_auth_tenant_id", UUID.randomUUID().toString());
            case "wrong-project" -> attrs.put("tc_auth_project_id", UUID.randomUUID().toString());
            case "credential" -> attrs.put("tc_auth_credential_version", "2");
            case "config" -> attrs.put("tc_auth_config_version", "1");
            case "leading-zero" -> attrs.put("tc_auth_config_version", "00");
            case "overflow" -> attrs.put("tc_auth_config_version", "9223372036854775808");
            default -> throw new AssertionError(failure);
        }
        assertThat(acl(attrs)).isEqualTo("deny");
        assertThat(event("connected", "invalid", attrs)).isFalse();
        assertThat(rows("dev_connection")).isZero();
    }

    /** 切原生再切回MQTT不能复活原配置身份，重新认证才获取当前代次。 */
    @Test
    void mqttRoundTripRejectsOldAclAndLifecycleButNewAuthenticationWorks() throws Exception {
        var old = authenticate();
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')", device, tenant, project);
        owner.update("UPDATE dev_access_binding SET protocol='MQTT',config_version=2 WHERE device_id=?", device);
        assertThat(acl(old)).isEqualTo("deny");
        assertThat(event("connected", "late", old)).isFalse();
        var fresh = authenticate();
        assertThat(fresh).containsEntry("tc_auth_config_version", "2");
        assertThat(event("connected", "new", fresh)).isTrue();
        assertThat(event("disconnected", "new", old)).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isEqualTo(1);
        assertThat(event("disconnected", "new", fresh)).isTrue();
    }

    /** 原凭据校验缓存不能被当前配置解析升级为新的凭据身份。 */
    @Test
    void changedCredentialVersionRejectsWarmOriginalAuthentication() throws Exception {
        var original = authenticate();
        owner.update("UPDATE dev_device SET credential_version=credential_version+1 WHERE id=?", device);
        assertThat(acl(original)).isEqualTo("deny");
        assertThat(callback("auth", Map.of("username", username, "password", DEVICE_SECRET, "clientid", "cached"))
                .path("result").asString()).isEqualTo("deny");
    }
}
