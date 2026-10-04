package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceAccessScopeService;
import com.things.link.device.infrastructure.emqx.EmqxConnectionEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0192：用实际HTTP/PG证明MQTT不能绕过当前绑定或误关其他协议。 */
@TestPropertySource(properties={"things-link.device.activity-expiry.enabled=false","things-link.device.tcp-expiry.enabled=false"})
class WebhookMqttPlaneTests extends WebhookFixture {
    /** 生产范围解析被认证、ACL和MQTT交接共用。 */
    @Autowired DeviceAccessScopeService scopes;
    /** 真实原事务回调，未用模拟状态替代持久会话。 */
    @Autowired EmqxConnectionEventService connections;
    /** 与实际回调安全链一致的测试环境秘密。 */
    @Value("${things-link.security.broker-callback.secret}") String brokerSecret;
    /** 本轮一次性设备密钥，与公开API Key区分。 */
    private static final String DEVICE_SECRET="b3".repeat(32);
    /** 平台生成的项目路由。 */
    String projectKey;
    /** 平台生成的设备路由。 */
    String deviceKey;
    /** 实际MQTT认证名。 */
    String username;

    /** 每例使用新设备，避免跨例认证缓存影响反例。 */
    @BeforeEach void seedCredential(){
        owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?",device);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'plane-test')",tenant,project,device,DEVICE_SECRET);
        projectKey=owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?",String.class,project);
        deviceKey=owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?",String.class,device);
        username=projectKey+"/"+deviceKey;
    }
    /** 仅删除本例拥有的事实，保留共享环境。 */
    @AfterEach void cleanup(){
        for(String table:new String[]{"dev_connection","dev_credential","dev_access_binding","sys_outbox_event"})owner.update("DELETE FROM "+table+" WHERE project_id=?",project);
    }
    /** 绑定禁用也不能隐式回落MQTT；各入口必须得到相同裁决。 */
    @ParameterizedTest @CsvSource({"HTTP,true","HTTP,false","COAP,true","COAP,false","TCP,true","TCP,false"})
    void nativeBindingNeverAdmitsMqttEvenWhenDisabled(String protocol,boolean enabled)throws Exception{
        bind(protocol,enabled);
        assertThat(scopes.resolve(projectKey,deviceKey)).isEmpty();
        assertThat(auth()).isEqualTo("deny");assertThat(acl()).isEqualTo("deny");
        assertThat(mqttConnected(connections,username,"native","127.0.0.1","node",mqttIdentity(device))).isFalse();
        assertThat(rows("dev_connection")).isZero();assertThat(sourceCount()).isZero();
        assertThat(status()).isEqualTo("OFFLINE");
    }
    /** 热凭据缓存只能证明原凭据，不得缓存当前协议许可。 */
    @Test void warmCredentialsCannotBypassChangedPlane()throws Exception{
        assertThat(auth()).isEqualTo("allow");assertThat(acl()).isEqualTo("allow");
        bind("TCP",true);
        assertThat(auth()).isEqualTo("deny");assertThat(acl()).isEqualTo("deny");
        assertThat(scopes.resolve(projectKey,deviceKey)).isEmpty();
    }
    /** Broker客户端名与TCP会话名碰撞时，只能清理MQTT行。 */
    @Test void mqttDisconnectCannotCloseSameNamedTcpSession(){
        bind("TCP",true);
        UUID row=UUID.randomUUID();
        owner.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,connected_at,config_version,generation,owner_instance,last_seen_at,heartbeat_interval_millis) VALUES(?,?,?,?,'same','TCP',now(),1,1,'tcp-owner',now(),30000)",row,tenant,project,device);
        assertThat(mqttDisconnected(connections,username,"same","late-mqtt",mqttIdentity(device))).isFalse();
        assertThat(owner.queryForObject("SELECT disconnected_at IS NULL FROM dev_connection WHERE id=?",Boolean.class,row)).isTrue();
        assertThat(sourceCount()).isZero();
    }
    /** ADR0193配置已切换时旧身份回调拒绝；原子关闭由6c-2c控制服务负责。 */
    @Test void lateMqttIdentityCannotChangeNativeFacts(){
        assertThat(mqttConnected(connections,username,"old","127.0.0.1","node",mqttIdentity(device))).isTrue();
        bind("HTTP",true);
        owner.update("UPDATE dev_access_binding SET last_activity_at=now(),activity_online=true WHERE device_id=?",device);
        assertThat(mqttDisconnected(connections,username,"old","late",mqttIdentity(device))).isFalse();
        assertThat(status()).isEqualTo("ONLINE");assertThat(sourceCount()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,device)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL",Integer.class,device)).isEqualTo(1);
    }
    /** 归档不接纳迟到connected，但仍允许真实旧会话收敛。 */
    @Test void archivedProjectRejectsNewConnectionButClosesExisting(){
        assertThat(mqttConnected(connections,username,"before","127.0.0.1","node",mqttIdentity(device))).isTrue();
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        assertThat(mqttConnected(connections,username,"late","127.0.0.1","node",mqttIdentity(device))).isFalse();
        assertThat(mqttDisconnected(connections,username,"before","normal",mqttIdentity(device))).isTrue();
        assertThat(sourceCount()).isEqualTo(1);assertThat(status()).isEqualTo("OFFLINE");
    }
    /** 权限故障必须拒绝，不能把查不到配置当作存量MQTT。 */
    @Test void bindingDatabaseFailureDoesNotBecomeLegacyMqttPermission()throws Exception{
        owner.execute("REVOKE SELECT ON dev_access_binding FROM thingslink_app");
        try{
            assertThatThrownBy(()->scopes.resolve(projectKey,deviceKey)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(()->mqttConnected(connections,username,"failure","127.0.0.1","node",mqttIdentity(device))).isInstanceOf(DataAccessException.class);
            assertThat(sourceCount()).isZero();
        }finally{owner.execute("GRANT SELECT ON dev_access_binding TO thingslink_app");}
        assertThat(auth()).isEqualTo("allow");
    }
    /** 本片用真实配置事实制造反例，管理入口由6c独立交付。 */
    void bind(String protocol,boolean enabled){owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,heartbeat_seconds,enabled) VALUES(?,?,?,?,30,?)",device,tenant,project,protocol,enabled);}
    /** 走实际认证安全链并保留原身份缓存。 */
    String auth()throws Exception{return callback("auth",Map.of("username",username,"password",DEVICE_SECRET,"clientid","plane-client"));}
    /** 走实际ACL安全链，证明Topic合法仍须当前平面许可。 */
    String acl()throws Exception{return callback("acl",mqttBody(Map.of("username",username,"topic","tc/v1/"+username+"/up/property/report","access","publish","clientid","plane-client")));}
    /** 不绕过Broker共享秘密过滤器。 */
    String callback(String route,Map<String,Object> body)throws Exception{
        var response=request("POST","/api/v1/emqx/"+route,json.writeValueAsString(body),null,Map.of("X-Broker-Callback-Token",brokerSecret));
        assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body()).path("result").asString();
    }
    /** 读取本设备持久状态。 */
    String status(){return owner.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,device);}
    /** 只统计此项目公开来源，避免误计其他Outbox类型。 */
    int sourceCount(){return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",Integer.class,project);}
}
