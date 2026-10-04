package com.things.link.bootstrap.integration;
import com.things.link.device.application.*;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.webhook.enabled=false","things-link.device.tcp-expiry.enabled=false"})
class WebhookDisabledTcpTests extends OpenDeviceHttpFixture {
    @Autowired DeviceAccessSessionPort sessions;@Autowired DeviceTcpSessionService tcp;
    @Test void disabledSourceStillClosesExpiredSessionWithoutSourceOrRevision(){
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'TCP')",device,tenant,project);
        assertThat(sessions.establish(new DeviceAccessSessionPort.EstablishmentRequest(tenant,project,device,TransportProtocol.TCP,"session","node",null,10_000L))).isInstanceOf(DeviceAccessSessionPort.Establishment.Allowed.class);
        owner.update("UPDATE dev_connection SET last_seen_at=clock_timestamp()-interval '31 seconds' WHERE device_id=?",device);
        assertThat(sessions.active(tenant,project,device)).isEmpty();assertThat(tcp.expire(tenant,project,device)).isTrue();
        assertThat(owner.queryForObject("SELECT disconnect_reason FROM dev_connection WHERE device_id=?",String.class,device)).isEqualTo("heartbeat_timeout");
        assertThat(owner.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?",Long.class,device)).isZero();assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
    }
    @AfterEach void cleanupTcp(){owner.update("DELETE FROM dev_connection WHERE project_id=?",project);owner.update("DELETE FROM dev_access_binding WHERE project_id=?",project);}
}
