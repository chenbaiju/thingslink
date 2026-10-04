package com.things.link.bootstrap.integration;
import com.things.link.device.application.*;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.webhook.enabled=false","things-link.device.activity-expiry.enabled=false"})
class WebhookDisabledActivityTests extends OpenDeviceHttpFixture {
    @Autowired DeviceAccessActivityService activity;
    @Test void disabledSourceStillMaintainsWindowWithoutRevisionOrOutbox(){
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'COAP')",device,tenant,project);
        assertThat(activity.record(tenant,project,device,TransportProtocol.COAP)).isEqualTo(DeviceAccessSessionPort.ActivityResult.ACCEPTED);
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,device)).isTrue();
        owner.update("UPDATE dev_access_binding SET last_activity_at=clock_timestamp()-interval '301 seconds' WHERE device_id=?",device);
        assertThat(activity.expire(tenant,project,device)).isTrue();
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,device)).isFalse();
        assertThat(owner.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?",Long.class,device)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
    }
    @AfterEach void cleanupBinding(){owner.update("DELETE FROM dev_access_binding WHERE project_id=?",project);}
}
