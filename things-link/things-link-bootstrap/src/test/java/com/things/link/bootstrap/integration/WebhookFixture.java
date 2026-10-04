package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.webhook.enabled=true","things-link.integration.webhook.current-signing-key-id=a","things-link.integration.webhook.signing-keys-json={\"a\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\"}"})
abstract class WebhookFixture extends OpenDeviceHttpFixture {
    @Autowired WebhookSubscriptionService service;@Autowired WebhookSigningKeys signing;@Autowired TransactionLocalRlsScope rls;
    @Autowired org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduledTasks;
    @Autowired com.things.link.integration.infrastructure.WebhookDeliveryWorker manualWorker;
    // Transaction tests drive claim/dispatch explicitly; worker saturation has separate tests.
    @BeforeEach void manualWebhookDispatch()throws InterruptedException{
        var tasks=scheduledTasks.getScheduledTasks().stream().filter(task->{
            Runnable runnable=task.getTask().getRunnable();
            // Verified Spring Task source: unwrap only this known wrapper; fail closed on future changes.
            if(runnable.getClass().getName().equals("org.springframework.scheduling.config.Task$OutcomeTrackingRunnable"))
                runnable=(Runnable)org.springframework.test.util.ReflectionTestUtils.getField(runnable,"runnable");
            return runnable instanceof org.springframework.scheduling.support.ScheduledMethodRunnable method
                &&Set.of(com.things.link.integration.infrastructure.WebhookDeliveryWorker.class,com.things.link.integration.infrastructure.WebhookRetentionMaintenance.class).contains(method.getMethod().getDeclaringClass())
                &&method.getMethod().getName().equals("tick");
        }).toList();
        assertThat(tasks).as("exact manual Webhook worker and retention schedules").hasSize(2);tasks.forEach(task->task.cancel(false));
        // Drain any task admitted before cancellation; these tests call the real dispatcher directly.
        manualWorker.destroy();
    }
    @AfterEach void removeWebhooks(){new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).execute(s->{owner.queryForList("SELECT id FROM sys_project WHERE id=? FOR UPDATE",project);for(String table:List.of("integ_webhook_attempt","integ_webhook_delivery","integ_webhook_conflict","integ_webhook_event","integ_webhook_ingress_state"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);owner.update("DELETE FROM integ_webhook_revision WHERE project_id=?",project);owner.update("DELETE FROM integ_webhook_subscription WHERE project_id=?",project);owner.update("DELETE FROM integ_webhook_operation WHERE project_id=?",project);owner.update("DELETE FROM integ_webhook_recovery_operation WHERE project_id=?",project);return null;});}
    /** 来源事务测试的原始认证夹具；真实Broker测试必须从生产认证响应获取属性。 */
    com.things.link.device.application.DeviceMqttIdentity mqttIdentity(UUID id) {
        return new com.things.link.device.application.DeviceMqttIdentity(
            new com.things.link.shared.message.AuthenticatedDeviceIdentity(tenant,project,id,1),0);
    }
    /** 将来源夹具的原属性并入HTTP回调；不查询当前代次补齐旧身份。 */
    Map<String,Object> mqttBody(Map<String,Object> fields) {
        var original=mqttIdentity(device);
        var body=new LinkedHashMap<String,Object>(original.attributes());body.putAll(fields);
        if(fields.get("clientid") instanceof String raw){
            var value=mqttConnection(original,raw,true);body.put("clientid",value.clientId());body.put("tc_auth_connection_id",value.ticket().toString());
        }
        return body;
    }
    /** 来源事务夹具也签发持久原连接票据；真实Broker类仍使用真实认证响应。 */
    @Autowired com.things.link.device.domain.DeviceMqttConnectionRepository mqttLedger;
    private final java.util.concurrent.ConcurrentHashMap<String, MqttFixtureConnection> mqttConnections = new java.util.concurrent.ConcurrentHashMap<>();
    @BeforeEach void resetMqttConnections() { mqttConnections.clear(); }
    private record MqttFixtureConnection(String clientId, UUID ticket) { }
    private MqttFixtureConnection mqttConnection(com.things.link.device.application.DeviceMqttIdentity original, String raw, boolean issue) {
        var session = new com.things.link.device.application.DeviceMqttSessionIdentity(original, raw);
        String key = original.toString() + "/" + raw;
        if (!issue) return mqttConnections.getOrDefault(key, new MqttFixtureConnection(session.effectiveClientId(), UUID.randomUUID()));
        return mqttConnections.computeIfAbsent(key, ignored -> {
            var identity = original.identity();
            var scope = new com.things.link.device.domain.DeviceMqttConnectionRepository.Scope(identity.tenantId(), identity.projectId(), identity.deviceId(), identity.credentialVersion(), original.configVersion(), session.effectiveClientId());
            UUID ticket = tx.execute(s -> mqttLedger.issue(scope).map(com.things.link.device.domain.DeviceMqttConnectionRepository.Ticket::id).orElseGet(UUID::randomUUID));
            return new MqttFixtureConnection(session.effectiveClientId(), ticket);
        });
    }
    /** 缓存原票据使重复/迟到断连仍引用首次认证身份，不能重新签发升级。 */
    boolean mqttConnected(com.things.link.device.infrastructure.emqx.EmqxConnectionEventService service, String username, String raw,
            String ip, String node, com.things.link.device.application.DeviceMqttIdentity original) {
        var value = mqttConnection(original, raw, true);
        return service.connected(username, value.clientId(), ip, node, original, value.ticket());
    }
    boolean mqttDisconnected(com.things.link.device.infrastructure.emqx.EmqxConnectionEventService service, String username, String raw,
            String reason, com.things.link.device.application.DeviceMqttIdentity original) {
        var value = mqttConnection(original, raw, false);
        return service.disconnected(username, value.clientId(), reason, original, value.ticket());
    }
    WebhookSubscriptionService.Spec spec(){return new WebhookSubscriptionService.Spec("receiver","https://receiver.example.com/events",List.of("device.online","device.property.report"),List.of(device));}
    WebhookSubscriptionService.Result create(UUID operation){return service.create(tenant,project,account,operation,spec());}
    WebhookSubscriptionService.Result change(UUID id,long expected,String kind){return service.change(tenant,project,account,Uuid7.generate(),id,expected,kind,null);}
    int rows(String table){return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project);}
}
