package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceAccessControlService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 来源关闭仍必须提交真实配置和会话失效，不借功能开关跳过当前许可。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "things-link.integration.api-key.enabled=true", "things-link.integration.webhook.enabled=false"})
class DeviceAccessControlDisabledTests extends OpenDeviceHttpFixture {
    @Autowired DeviceAccessControlService control;
    @Autowired com.things.link.device.application.DeviceCredentialService credentials;
    @Autowired com.things.link.device.application.DeviceService devices;
    @AfterEach void clean() {
        TenantContext.clear();
        owner.update("DELETE FROM dev_credential WHERE project_id=?", project);
        owner.update("DELETE FROM dev_connection WHERE project_id=?", project);
        owner.update("DELETE FROM dev_access_binding WHERE project_id=?", project);
    }

    @Test void disabledSourceClosesFactsWithoutOutboxOrRevision() {
        owner.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version) VALUES(?,?,?,?,?,'MQTT',0)", Uuid7.generate(), tenant, project, device, "disabled-source");
        TenantContext.set(new TenantScope(tenant, project, account));
        try { assertThat(control.change(project, device, 0, TransportProtocol.HTTP, true).configVersion()).isEqualTo(1); }
        finally { TenantContext.clear(); }
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?", Long.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='DEVICE_ACCESS_CONFIG_CHANGED'", Integer.class, project)).isEqualTo(1);
    }
    /** 默认关闭来源时，旧公开凭据/删除写者也必须完成代次及事实关闭。 */
    @Test void credentialAndDeletionRemainEffectiveWithoutPublicSource() {
        owner.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version) VALUES(?,?,?,?,?,'MQTT',0)", Uuid7.generate(), tenant, project, device, "disabled-credential");
        TenantContext.set(new TenantScope(tenant, project, account));
        try {
            credentials.generate(project, device);
            assertThat(owner.queryForObject("SELECT config_version FROM dev_access_binding WHERE device_id=?", Long.class, device)).isEqualTo(1);
            devices.delete(project, device);
        } finally { TenantContext.clear(); }
        assertThat(owner.queryForObject("SELECT deleted_at IS NOT NULL FROM dev_device WHERE id=?", Boolean.class, device)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?", Long.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project)).isZero();
    }

}
