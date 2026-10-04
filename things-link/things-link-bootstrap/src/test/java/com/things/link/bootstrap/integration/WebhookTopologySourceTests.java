package com.things.link.bootstrap.integration;
import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.infrastructure.emqx.EmqxConnectionEventService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WebhookTopologySourceTests extends WebhookFixture {
    @Autowired DeviceTopologyIngestionService topology;
    @Autowired EmqxConnectionEventService connections;
    @Autowired ObjectMapper mapper;
    @Autowired com.things.link.device.application.DeviceAccessControlService access;
    @Autowired com.things.link.device.application.DeviceCredentialService credentials;
    UUID gateway,otherGateway,child;
    @BeforeEach void seedTopology()throws Exception{
        UUID gatewayType=Uuid7.generate(),childType=Uuid7.generate();gateway=Uuid7.generate();otherGateway=Uuid7.generate();child=Uuid7.generate();
        for(var entry:Map.of(gatewayType,"GATEWAY",childType,"SUB_DEVICE").entrySet())owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES (?,?,?,?,'拓扑测试',?,?)",entry.getKey(),tenant,project,"t"+entry.getKey().toString().replace("-",""),entry.getValue().equals("GATEWAY")?"STANDARD_GATEWAY":"STANDARD",entry.getValue());
        for(var entry:Map.of(gateway,gatewayType,otherGateway,gatewayType,child,childType).entrySet())owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,'拓扑测试','INACTIVE')",entry.getKey(),tenant,project,entry.getValue(),"d"+entry.getKey().toString().replace("-",""));
        bind(gateway);assertThat(facts()).isEmpty();
    }
    @AfterEach void cleanupTopology(){new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).execute(s->{owner.queryForList("SELECT id FROM sys_project WHERE id=? FOR UPDATE",project);for(String table:List.of("dev_credential","dev_access_binding","dev_connection","sys_outbox_event","sys_inbox_message","dev_topo","dev_shadow","dev_device"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);return null;});}
    String topologyPath(){return "/api/v1/projects/"+project+"/device-topologies";}
    void bind(UUID target)throws Exception{var response=request("POST",topologyPath(),mapper.writeValueAsString(Map.of("subDeviceId",child,"gatewayId",target)),token(),Map.of());assertThat(response.statusCode()).as(response.body()).isEqualTo(201);}
    void unbind()throws Exception{var response=request("DELETE",topologyPath()+"/"+child,null,token(),Map.of());assertThat(response.statusCode()).as(response.body()).isEqualTo(204);}
    void delete(UUID target)throws Exception{var response=request("DELETE","/api/v1/projects/"+project+"/devices/"+target,null,token(),Map.of());assertThat(response.statusCode()).as(response.body()).isEqualTo(204);}
    DeviceTopologyMessage message(DeviceTopologyMessage.Type type,UUID origin,Instant at){return new DeviceTopologyMessage(Uuid7.generate(),tenant,project,origin,type,"d"+child.toString().replace("-",""),null,null,at,"topology-public-source");}
    DeviceTopologyMessage login(){var m=message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN,gateway,Instant.now());topology.ingest(m);return m;}
    List<PublicWebhookSource> facts(){return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' AND aggregate_id=? ORDER BY created_at,id",String.class,project,child).stream().map(s->mapper.readValue(s,PublicWebhookSource.class)).toList();}
    String reason(){return mapper.readTree(facts().getLast().eventText()).path("payload").path("reason").asString();}
    @Test void loginLogoutPreserveOriginalIdentityAndSkipDuplicatesLateAndWrongGateway(){var first=login();topology.ingest(first);topology.ingest(message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN,gateway,Instant.now()));topology.ingest(message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGOUT,gateway,first.receivedAt().minusSeconds(1)));topology.ingest(message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGOUT,otherGateway,Instant.now()));assertThat(facts()).hasSize(1);var event=facts().getFirst();assertThat(event.event().traceId()).isEqualTo(first.traceId());assertThat(mapper.readTree(event.eventText()).path("payload").path("sourceMessageId").asString()).isEqualTo(first.messageId().toString());topology.ingest(message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGOUT,gateway,Instant.now()));assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("TOPOLOGY_LOGOUT");}
    @Test void controlUnbindEmitsOneOfflineAndRetryIsSilent()throws Exception{login();unbind();unbind();assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("TOPOLOGY_UNBOUND");assertThat(facts().getLast().event().eventType()).isEqualTo("device.offline");}
    @Test void rebindRecordsOldGatewayAndOldMessagesCannotReachNewRelation()throws Exception{login();bind(otherGateway);assertThat(reason()).isEqualTo("TOPOLOGY_REBOUND");assertThat(mapper.readTree(facts().getLast().eventText()).path("payload").path("gatewayId").asString()).isEqualTo(gateway.toString());topology.ingest(message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN,gateway,Instant.now()));assertThat(facts()).hasSize(2);topology.ingest(message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN,otherGateway,Instant.now()));assertThat(facts()).hasSize(3);}
    @Test void gatewayReportedUnbindUsesOriginalMessage(){login();var message=message(DeviceTopologyMessage.Type.TOPO_DELETE,gateway,Instant.now());topology.ingest(message);topology.ingest(message);assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("TOPOLOGY_UNBOUND");assertThat(mapper.readTree(facts().getLast().eventText()).path("payload").path("sourceMessageId").asString()).isEqualTo(message.messageId().toString());}
    @Test void actualGatewayDisconnectCascadesChildSourceInSameTransaction(){String username=owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?",String.class,project)+"/d"+gateway.toString().replace("-","");assertThat(mqttConnected(connections,username,"gateway-session","127.0.0.1","test",mqttIdentity(gateway))).isTrue();login();assertThat(mqttDisconnected(connections,username,"gateway-session","normal",mqttIdentity(gateway))).isTrue();assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("GATEWAY_DISCONNECTED");assertThat(owner.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,child)).isEqualTo("OFFLINE");}
    @Test void childDeletionClosesItsReachability()throws Exception{login();delete(child);assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("DEVICE_DELETED");}
    @Test void gatewayDeletionClosesChildReachability()throws Exception{login();delete(gateway);assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("GATEWAY_DELETED");}
    @Test void rolledBackLoginLeavesNeitherSourceNorInbox(){var message=message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN,gateway,Instant.now());tx.execute(s->{topology.ingest(message);s.setRollbackOnly();return null;});assertThat(facts()).isEmpty();assertThat(owner.queryForObject("SELECT count(*) FROM sys_inbox_message WHERE message_id=?",Integer.class,message.messageId())).isZero();assertThat(owner.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,child)).isEqualTo("INACTIVE");}
    @Test void sourceFailureRollsBackTopologyInboxAndProjection(){var message=message(DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN,gateway,Instant.now());owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");try{assertThatThrownBy(()->topology.ingest(message)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app");}assertThat(facts()).isEmpty();assertThat(owner.queryForObject("SELECT count(*) FROM sys_inbox_message WHERE message_id=?",Integer.class,message.messageId())).isZero();assertThat(owner.queryForObject("SELECT online_status FROM dev_topo WHERE sub_device_id=? AND unbound_at IS NULL",String.class,child)).isEqualTo("UNKNOWN");assertThat(owner.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?",Long.class,child)).isZero();}
    /** 原生控制台线程范围；实际项目成员/类型/设备权限仍由生产服务查询。 */
    <T> T console(java.util.function.Supplier<T> action) {
        com.things.link.shared.tenant.TenantContext.set(new com.things.link.shared.tenant.TenantScope(tenant,project,account));
        try { return action.get(); } finally { com.things.link.shared.tenant.TenantContext.clear(); }
    }
    void gatewayConnected() {
        String username=owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?",String.class,project)+"/d"+gateway.toString().replace("-","");
        assertThat(mqttConnected(connections,username,"control-gateway","127.0.0.1","test",mqttIdentity(gateway))).isTrue();
    }
    List<PublicWebhookSource> gatewayFacts() {
        return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' AND aggregate_id=? ORDER BY created_at,id",String.class,project,gateway).stream().map(s->mapper.readValue(s,PublicWebhookSource.class)).toList();
    }
    @Test void disablingGatewayClosesOwnAndChildEdgesOnce() {
        gatewayConnected();login();
        console(()->access.change(project,gateway,0,TransportProtocol.MQTT,false));
        assertThat(gatewayFacts()).hasSize(2);assertThat(facts()).hasSize(2);
        assertThat(reason()).isEqualTo("GATEWAY_DISCONNECTED");
        assertThat(mapper.readTree(gatewayFacts().getLast().eventText()).path("payload").path("reason").asString()).isEqualTo("CONFIG_DISABLED");
        console(()->access.change(project,gateway,1,TransportProtocol.MQTT,false));
        assertThat(gatewayFacts()).hasSize(2);assertThat(facts()).hasSize(2);
    }
    @Test void deletingConnectedGatewayDoesNotDuplicateChildOffline() throws Exception {
        gatewayConnected();login();delete(gateway);
        assertThat(gatewayFacts()).hasSize(2);assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("GATEWAY_DELETED");
        assertThat(mapper.readTree(gatewayFacts().getLast().eventText()).path("payload").path("reason").asString()).isEqualTo("DEVICE_DELETED");
    }
    @Test void credentialFailureRollsBackGatewayAndChildThenRecovers() {
        gatewayConnected();login();
        owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try { assertThatThrownBy(()->console(()->credentials.generate(project,gateway))).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(gatewayFacts()).hasSize(1);assertThat(facts()).hasSize(1);
        assertThat(owner.queryForObject("SELECT credential_version FROM dev_device WHERE id=?",Long.class,gateway)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,child)).isEqualTo("ONLINE");
        console(()->credentials.generate(project,gateway));
        assertThat(gatewayFacts()).hasSize(2);assertThat(facts()).hasSize(2);assertThat(reason()).isEqualTo("GATEWAY_DISCONNECTED");
    }

    /** 同配置接管不制造网关/子设备离线抖动；迟到旧断连只能影响原连接。 */
    @Test void replacementAndOldDisconnectKeepGatewayChildOnline() {
        gatewayConnected(); login();
        var original = mqttIdentity(gateway);
        var session = new com.things.link.device.application.DeviceMqttSessionIdentity(original, "control-gateway");
        var scope = new com.things.link.device.domain.DeviceMqttConnectionRepository.Scope(tenant, project, gateway, 1, 0, session.effectiveClientId());
        var ticket = tx.execute(s -> mqttLedger.issue(scope).orElseThrow());
        String username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project) + "/d" + gateway.toString().replace("-", "");
        assertThat(connections.connected(username, session.effectiveClientId(), "127.0.0.1", "node", original, ticket.id())).isTrue();
        assertThat(mqttDisconnected(connections, username, "control-gateway", "takenover", original)).isTrue();
        assertThat(gatewayFacts()).hasSize(1); assertThat(facts()).hasSize(1);
        assertThat(owner.queryForObject("SELECT online_status FROM dev_topo WHERE sub_device_id=? AND unbound_at IS NULL", String.class, child)).isEqualTo("ONLINE");
        assertThat(connections.disconnected(username, session.effectiveClientId(), "normal", original, ticket.id())).isTrue();
        assertThat(connections.disconnected(username, session.effectiveClientId(), "normal", original, ticket.id())).isTrue();
        assertThat(gatewayFacts()).hasSize(2); assertThat(facts()).hasSize(2); assertThat(reason()).isEqualTo("GATEWAY_DISCONNECTED");
    }
    /** 新网关断连先于connected仍关闭前一连接及子设备，迟到新connected不能复活。 */
    @Test void pendingNewDisconnectClosesGatewayAndChildOnce() {
        gatewayConnected(); login();
        var original = mqttIdentity(gateway);
        var session = new com.things.link.device.application.DeviceMqttSessionIdentity(original, "control-gateway");
        var scope = new com.things.link.device.domain.DeviceMqttConnectionRepository.Scope(tenant, project, gateway, 1, 0, session.effectiveClientId());
        var ticket = tx.execute(s -> mqttLedger.issue(scope).orElseThrow());
        String username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project) + "/d" + gateway.toString().replace("-", "");
        assertThat(connections.disconnected(username, session.effectiveClientId(), "normal", original, ticket.id())).isTrue();
        assertThat(connections.connected(username, session.effectiveClientId(), "127.0.0.1", "node", original, ticket.id())).isFalse();
        assertThat(mqttDisconnected(connections, username, "control-gateway", "takenover", original)).isTrue();
        assertThat(gatewayFacts()).hasSize(2); assertThat(facts()).hasSize(2);
        assertThat(owner.queryForObject("SELECT status FROM dev_device WHERE id=?", String.class, child)).isEqualTo("OFFLINE");
        assertThat(owner.queryForObject("SELECT online_status FROM dev_topo WHERE sub_device_id=? AND unbound_at IS NULL", String.class, child)).isEqualTo("OFFLINE");
    }

}
