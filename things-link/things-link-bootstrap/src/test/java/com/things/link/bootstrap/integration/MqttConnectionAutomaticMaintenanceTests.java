package com.things.link.bootstrap.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 真实调度即使Webhook关闭也维护原连接元数据，不依赖手工调用tick。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "things-link.integration.api-key.enabled=true","things-link.integration.webhook.enabled=false",
        "things-link.device.mqtt-connection-maintenance.initial-delay-millis=100",
        "things-link.device.mqtt-connection-maintenance.fixed-delay-millis=100"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MqttConnectionAutomaticMaintenanceTests extends OpenDeviceHttpFixture {
    /** 插入独占过期事实，等待生产调度器真实清除，无新公开来源。 */
    @Test void scheduledMaintenanceWorksWithWebhookDisabled() {
        assertThat(owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id,created_at,expires_at)
                VALUES(gen_random_uuid(),?,?,?,1,0,?,clock_timestamp()-interval '301 seconds',clock_timestamp()-interval '1 second')
                """,tenant,project,device,"tc-device-"+"a".repeat(64))).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(owner.queryForObject(
                "SELECT count(*) FROM dev_mqtt_connection_ticket WHERE device_id=?",Integer.class,device)).isZero());
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
    }
}
