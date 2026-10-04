package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceAccessActivityService;
import com.things.link.device.application.DeviceAccessControlService;
import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真库控制事务；手动造过龄仅验证结算时间，不冒充自动扫描或真实协议资格。 */
@TestPropertySource(properties = {"things-link.device.activity-expiry.enabled=false",
        "things-link.device.tcp-expiry.enabled=false"})
class DeviceAccessControlTests extends WebhookFixture {
    @Autowired DeviceAccessControlService control;
    @Autowired DeviceAccessActivityService activity;

    /** 从真实未连接状态开始，避免父设备查询夹具的ONLINE占位被当作连接事实。 */
    @BeforeEach void offline() { owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?", device); }

    /** 本片新增事实先于父夹具清理，审计不可变记录随独占测试数据库销毁。 */
    @AfterEach void clearControl() {
        TenantContext.clear();
        owner.update("DELETE FROM dev_connection WHERE project_id=?", project);
        owner.update("DELETE FROM dev_access_binding WHERE project_id=?", project);
        owner.update("DELETE FROM sys_outbox_event WHERE project_id=?", project);
    }

    /** 仅模拟已经认证的Console线程范围；权限仍由真实成员表判断。 */
    DeviceAccessBinding change(long version, TransportProtocol protocol, boolean enabled) {
        TenantContext.set(new TenantScope(tenant, project, account));
        try { return control.change(project, device, version, protocol, enabled); }
        finally { TenantContext.clear(); }
    }

    /** 读取持久审计数量，不把方法被调用视为提交证据。 */
    int audits() { return owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='DEVICE_ACCESS_CONFIG_CHANGED'", Integer.class, project); }
    long version() { return owner.queryForObject("SELECT COALESCE((SELECT config_version FROM dev_access_binding WHERE device_id=?),0)", Long.class, device); }
    List<PublicWebhookSource> sources() { return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", String.class, project).stream().map(s -> json.readValue(s, PublicWebhookSource.class)).toList(); }
    String reason(PublicWebhookSource source) { return json.readTree(source.eventText()).path("payload").path("reason").asString(); }

    /** 只造明确的MQTT当前事实；过时代次用于证明不能混入权威来源。 */
    void mqtt(long epoch) {
        owner.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version) VALUES(?,?,?,?,?,'MQTT',?)", Uuid7.generate(), tenant, project, device, "control-" + UUID.randomUUID(), epoch);
        owner.update("UPDATE dev_device SET status='ONLINE' WHERE id=?", device);
    }

    @Test void exactNoopHasNoBindingAuditSourceOrPhysicalClose() {
        mqtt(0);
        assertThat(change(0, TransportProtocol.MQTT, true).configVersion()).isZero();
        assertThat(version()).isZero(); assertThat(audits()).isZero(); assertThat(sources()).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isEqualTo(1);
    }

    @Test void mqttRoundTripAndDisableNeverReviveOldFacts() {
        mqtt(0); mqtt(0);
        assertThat(change(0, TransportProtocol.HTTP, true).configVersion()).isEqualTo(1);
        assertThat(sources()).hasSize(1); assertThat(reason(sources().getFirst())).isEqualTo("CONFIG_CHANGED");
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
        change(1, TransportProtocol.MQTT, true); mqtt(2);
        change(2, TransportProtocol.MQTT, false); change(3, TransportProtocol.MQTT, true);
        assertThat(sources()).hasSize(2); assertThat(sources().stream().map(this::reason)).containsExactlyInAnyOrder("CONFIG_CHANGED", "CONFIG_DISABLED");
        assertThat(audits()).isEqualTo(4);
        assertThat(owner.queryForObject("SELECT status FROM dev_device WHERE id=?", String.class, device)).isEqualTo("OFFLINE");
    }

    @Test void staleExpectedFailsEvenWhenRequestedValueMatches() {
        change(0, TransportProtocol.HTTP, true);
        assertThatThrownBy(() -> change(0, TransportProtocol.HTTP, true)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_CONFIG_CONFLICT));
        assertThat(version()).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    }

    @Test void unexpiredActivityClosesOnceAndKeepsHistory() {
        change(0, TransportProtocol.HTTP, true); activity.record(tenant, project, device, TransportProtocol.HTTP);
        change(1, TransportProtocol.COAP, true);
        assertThat(sources()).hasSize(2);
        assertThat(sources().stream().map(this::reason)).containsExactlyInAnyOrder("ACTIVITY", "CONFIG_CHANGED");
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?", Boolean.class, device)).isFalse();
        assertThat(owner.queryForObject("SELECT last_activity_at FROM dev_access_binding WHERE device_id=?", Timestamp.class, device)).isNotNull();
    }

    @Test void expiredActivityUsesOriginalDeadlineWithoutDuplicateControlEdge() {
        change(0, TransportProtocol.COAP, true); activity.record(tenant, project, device, TransportProtocol.COAP);
        owner.update("UPDATE dev_access_binding SET last_activity_at=clock_timestamp()-interval '301 seconds' WHERE device_id=?", device);
        Instant deadline = owner.queryForObject("SELECT last_activity_at FROM dev_access_binding WHERE device_id=?", Timestamp.class, device).toInstant().plusSeconds(300);
        change(1, TransportProtocol.MQTT, true);
        var expired = sources().stream().filter(s -> reason(s).equals("ACTIVITY_EXPIRED")).toList();
        assertThat(sources()).hasSize(2); assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().event().occurredAt()).isEqualTo(deadline);
    }

    @Test void tcpExpiryUsesActualSessionPeriodAndOldMqttIsCleanupOnly() {
        change(0, TransportProtocol.TCP, true); mqtt(0);
        UUID row = Uuid7.generate();
        owner.update("""
                INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version,
                    owner_instance,generation,last_seen_at,heartbeat_interval_millis)
                VALUES(?,?,?,?,?,'TCP',1,'control-test',1,clock_timestamp()-interval '31 seconds',10000)
                """, row, tenant, project, device, "tcp-" + row);
        Instant deadline = owner.queryForObject("SELECT last_seen_at FROM dev_connection WHERE id=?", Timestamp.class, row).toInstant().plusSeconds(30);
        change(1, TransportProtocol.HTTP, true);
        assertThat(sources()).hasSize(1); assertThat(reason(sources().getFirst())).isEqualTo("HEARTBEAT_EXPIRED");
        assertThat(sources().getFirst().event().occurredAt()).isEqualTo(deadline);
        assertThat(owner.queryForObject("SELECT disconnected_at FROM dev_connection WHERE id=?", Timestamp.class, row).toInstant()).isEqualTo(deadline);
    }

    @Test void oldEpochRowsDoNotInventCurrentOfflineEdge() {
        change(0, TransportProtocol.MQTT, false); change(1, TransportProtocol.MQTT, true); mqtt(0);
        change(2, TransportProtocol.HTTP, true);
        assertThat(sources()).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
    }

    @Test void sourceFailureRollsBackVersionConnectionProjectionAndAudit() {
        mqtt(0);
        owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try { assertThatThrownBy(() -> change(0, TransportProtocol.HTTP, true)).isInstanceOf(DataAccessException.class); }
        finally { owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(version()).isZero(); assertThat(audits()).isZero(); assertThat(sources()).isEmpty();
        assertThat(owner.queryForObject("SELECT status FROM dev_device WHERE id=?", String.class, device)).isEqualTo("ONLINE");
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isEqualTo(1);
        change(0, TransportProtocol.HTTP, true); assertThat(sources()).hasSize(1);
    }

    @Test void auditFailureRollsBackSourceAndClosedFacts() {
        mqtt(0); owner.execute("REVOKE INSERT ON sys_audit_log FROM thingslink_app");
        try { assertThatThrownBy(() -> change(0, TransportProtocol.HTTP, true)).isInstanceOf(DataAccessException.class); }
        finally { owner.execute("GRANT INSERT ON sys_audit_log TO thingslink_app"); }
        assertThat(version()).isZero(); assertThat(sources()).isEmpty();
        assertThat(owner.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?", Long.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isEqualTo(1);
    }

    @Test void concurrentSameVersionHasExactlyOneWinnerAndAudit() throws Exception {
        mqtt(0);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(pool.submit(() -> {
                try { change(0, TransportProtocol.HTTP, true); return true; }
                catch (BusinessException e) { assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_CONFIG_CONFLICT); return false; }
            }));
            int winners = 0; for (var result : results) if (result.get(20, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(version()).isEqualTo(1); assertThat(audits()).isEqualTo(1); assertThat(sources()).hasSize(1);
    }

    @Test void memberAndArchivedProjectCannotWrite() {
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", project, account);
        assertThatThrownBy(() -> change(0, TransportProtocol.HTTP, true)).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_project_member SET role='OWNER' WHERE project_id=? AND account_id=?", project, account);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        assertThatThrownBy(() -> change(0, TransportProtocol.HTTP, true)).isInstanceOf(BusinessException.class);
        assertThat(version()).isZero(); assertThat(audits()).isZero();
    }

    @Test void nonstandardPayloadCannotEnableNativeTransport() {
        owner.update("UPDATE dev_type SET access_protocol='MODBUS_RTU_PASSTHROUGH' WHERE id=?", type);
        assertThatThrownBy(() -> change(0, TransportProtocol.TCP, true)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_PROTOCOL_UNSUPPORTED));
        assertThat(change(0, TransportProtocol.MQTT, false).configVersion()).isEqualTo(1);
    }
    @Test void targetOutsideCurrentProjectIsNotFoundWithoutMutatingCurrentDevice() {
        TenantContext.set(new TenantScope(tenant, project, account));
        try {
            assertThatThrownBy(() -> control.change(project, UUID.randomUUID(), 0, TransportProtocol.HTTP, true))
                    .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_NOT_FOUND));
        } finally { TenantContext.clear(); }
        assertThat(version()).isZero(); assertThat(audits()).isZero();
    }

    @Test void currentTenantContextDoesNotReplaceActualProjectOwner() {
        TenantContext.set(new TenantScope(UUID.randomUUID(), project, account));
        try { assertThat(control.change(project, device, 0, TransportProtocol.HTTP, true).tenantId()).isEqualTo(tenant); }
        finally { TenantContext.clear(); }
        assertThat(owner.queryForObject("SELECT tenant_id FROM sys_audit_log WHERE project_id=? AND action='DEVICE_ACCESS_CONFIG_CHANGED'", UUID.class, project)).isEqualTo(tenant);
    }

}
