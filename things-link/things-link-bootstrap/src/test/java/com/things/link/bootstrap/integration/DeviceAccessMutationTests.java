package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceAccessActivityService;
import com.things.link.device.application.DeviceAccessControlService;
import com.things.link.device.application.DeviceCredentialService;
import com.things.link.device.application.DeviceService;
import com.things.link.device.application.DeviceTypeService;
import com.things.link.device.domain.DeviceCredential;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 既有凭据/删除/换型写者的真实PG组合，不以调用新内核的次数代替提交事实。 */
@TestPropertySource(properties = {"things-link.device.activity-expiry.enabled=false", "things-link.device.tcp-expiry.enabled=false"})
class DeviceAccessMutationTests extends WebhookFixture {
    @Autowired DeviceCredentialService credentials;
    @Autowired DeviceService devices;
    @Autowired DeviceTypeService types;
    @Autowired DeviceAccessControlService access;
    @Autowired DeviceAccessActivityService activity;

    @BeforeEach void initialOffline() { owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?", device); }
    @AfterEach void cleanupMutation() {
        TenantContext.clear();
        owner.update("DELETE FROM dev_credential WHERE project_id=?", project);
        owner.update("DELETE FROM dev_connection WHERE project_id=?", project);
        owner.update("DELETE FROM dev_access_binding WHERE project_id=?", project);
        owner.update("DELETE FROM sys_outbox_event WHERE project_id=?", project);
    }
    <T> T asConsole(Supplier<T> operation) {
        TenantContext.set(new TenantScope(tenant, project, account));
        try { return operation.get(); } finally { TenantContext.clear(); }
    }
    void mutate(Runnable operation) { asConsole(() -> { operation.run(); return null; }); }
    long config() { return owner.queryForObject("SELECT COALESCE((SELECT config_version FROM dev_access_binding WHERE device_id=?),0)", Long.class, device); }
    long credentialVersion() { return owner.queryForObject("SELECT credential_version FROM dev_device WHERE id=?", Long.class, device); }
    List<PublicWebhookSource> facts() { return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", String.class, project).stream().map(s -> json.readValue(s, PublicWebhookSource.class)).toList(); }
    String reason(PublicWebhookSource fact) { return json.readTree(fact.eventText()).path("payload").path("reason").asString(); }
    void mqtt(long version) {
        owner.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version) VALUES(?,?,?,?,?,'MQTT',?)", Uuid7.generate(), tenant, project, device, "mutation-" + UUID.randomUUID(), version);
        owner.update("UPDATE dev_device SET status='ONLINE' WHERE id=?", device);
    }
    UUID oldCredential() {
        UUID id = Uuid7.generate();
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(?,?,?,?,'ACCESS_TOKEN',?,'mutation-old')", id, tenant, project, device, "a".repeat(64));
        return id;
    }

    @Test void rotationAndRevokeAdvanceBothIdentitiesAndCloseOnlyOnce() {
        oldCredential(); mqtt(0);
        DeviceCredential created = asConsole(() -> credentials.generate(project, device));
        assertThat(created.plainSecret()).hasSize(64); assertThat(config()).isEqualTo(1); assertThat(credentialVersion()).isEqualTo(2);
        assertThat(facts()).hasSize(1); assertThat(reason(facts().getFirst())).isEqualTo("CREDENTIAL_CHANGED");
        mqtt(1); mutate(() -> credentials.revoke(project, device, created.id()));
        assertThat(config()).isEqualTo(2); assertThat(credentialVersion()).isEqualTo(3); assertThat(facts()).hasSize(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_credential WHERE device_id=? AND deleted_at IS NULL", Integer.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='DEVICE_ACCESS_CONFIG_CHANGED'", Integer.class, project)).isEqualTo(2);
        assertThatThrownBy(() -> mutate(() -> credentials.revoke(project, device, created.id()))).isInstanceOf(BusinessException.class);
        assertThat(config()).isEqualTo(2); assertThat(facts()).hasSize(2);
    }

    @Test void credentialChangeKeepsDisabledProtocolButEndsActivity() {
        oldCredential();
        asConsole(() -> access.change(project, device, 0, TransportProtocol.COAP, true));
        activity.record(tenant, project, device, TransportProtocol.COAP);
        asConsole(() -> credentials.generate(project, device));
        assertThat(config()).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT protocol FROM dev_access_binding WHERE device_id=?", String.class, device)).isEqualTo("COAP");
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?", Boolean.class, device)).isFalse();
        asConsole(() -> access.change(project, device, 2, TransportProtocol.COAP, false));
        asConsole(() -> credentials.generate(project, device));
        assertThat(config()).isEqualTo(4);
        assertThat(owner.queryForObject("SELECT enabled FROM dev_access_binding WHERE device_id=?", Boolean.class, device)).isFalse();
        assertThat(facts()).hasSize(2);
    }

    @Test void sourceFailureRestoresOldCredentialHashVersionAndConnection() {
        UUID old = oldCredential(); mqtt(0);
        owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try { assertThatThrownBy(() -> asConsole(() -> credentials.generate(project, device))).isInstanceOf(DataAccessException.class); }
        finally { owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(config()).isZero(); assertThat(credentialVersion()).isEqualTo(1); assertThat(facts()).isEmpty();
        assertThat(owner.queryForObject("SELECT credential_hash FROM dev_credential WHERE id=? AND deleted_at IS NULL", String.class, old)).isEqualTo("a".repeat(64));
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_credential WHERE device_id=?", Integer.class, device)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isEqualTo(1);
    }

    @Test void failedRevokeDoesNotAdvanceConfigurationOrCloseSessions() {
        oldCredential(); mqtt(0);
        assertThatThrownBy(() -> mutate(() -> credentials.revoke(project, device, UUID.randomUUID()))).isInstanceOf(BusinessException.class);
        assertThat(config()).isZero(); assertThat(credentialVersion()).isEqualTo(1); assertThat(facts()).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isEqualTo(1);
    }

    @Test void exhaustedConfigurationRollsBackGeneratedCredential() {
        UUID old = oldCredential();
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',?)", device, tenant, project, Long.MAX_VALUE);
        assertThatThrownBy(() -> asConsole(() -> credentials.generate(project, device))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_CONFIG_EXHAUSTED));
        assertThat(credentialVersion()).isEqualTo(1); assertThat(config()).isEqualTo(Long.MAX_VALUE);
        assertThat(owner.queryForObject("SELECT deleted_at IS NULL FROM dev_credential WHERE id=?", Boolean.class, old)).isTrue();
    }

    @Test void mqttDeletionClosesSourceAndMissingRepeatDoesNothing() {
        mqtt(0); mutate(() -> devices.delete(project, device));
        assertThat(facts()).hasSize(1); assertThat(reason(facts().getFirst())).isEqualTo("DEVICE_DELETED");
        assertThat(owner.queryForObject("SELECT deleted_at IS NOT NULL FROM dev_device WHERE id=?", Boolean.class, device)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
        assertThatThrownBy(() -> mutate(() -> devices.delete(project, device))).isInstanceOf(BusinessException.class);
        assertThat(facts()).hasSize(1);
    }

    @Test void activityDeletionUsesControlReasonAndRollsBackOnSourceFailure() {
        asConsole(() -> access.change(project, device, 0, TransportProtocol.HTTP, true));
        activity.record(tenant, project, device, TransportProtocol.HTTP);
        owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try { assertThatThrownBy(() -> mutate(() -> devices.delete(project, device))).isInstanceOf(DataAccessException.class); }
        finally { owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(owner.queryForObject("SELECT deleted_at IS NULL FROM dev_device WHERE id=?", Boolean.class, device)).isTrue();
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?", Boolean.class, device)).isTrue();
        mutate(() -> devices.delete(project, device));
        assertThat(facts()).hasSize(2); assertThat(facts().stream().map(this::reason)).contains("DEVICE_DELETED");
    }

    @Test void typeEditAndDeletionCannotOrphanNativeConfiguration() {
        asConsole(() -> access.change(project, device, 0, TransportProtocol.HTTP, true));
        String key = owner.queryForObject("SELECT type_key FROM dev_type WHERE id=?", String.class, type);
        assertThatThrownBy(() -> asConsole(() -> types.update(project, type, key, "changed", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.MODBUS_RTU_PASSTHROUGH, DeviceType.NetworkType.WIFI)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_PROTOCOL_UNSUPPORTED));
        assertThatThrownBy(() -> mutate(() -> types.delete(project, type))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_PROTOCOL_UNSUPPORTED));
        asConsole(() -> access.change(project, device, 1, TransportProtocol.MQTT, true));
        assertThat(asConsole(() -> types.update(project, type, key, "changed", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.MODBUS_RTU_PASSTHROUGH, DeviceType.NetworkType.WIFI)).payloadProtocol()).isEqualTo(DeviceType.PayloadProtocol.MODBUS_RTU_PASSTHROUGH);
    }

    @Test void deviceRetypingCannotEvadeDisabledNativeConfiguration() {
        device = unbound;
        asConsole(() -> access.change(project, device, 0, TransportProtocol.TCP, false));
        assertThatThrownBy(() -> asConsole(() -> devices.update(project, device, null, "changed", null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_PROTOCOL_UNSUPPORTED));
        assertThat(owner.queryForObject("SELECT device_type_id FROM dev_device WHERE id=?", UUID.class, device)).isEqualTo(type);
    }

    @Test void credentialWriterCannotPassAnExclusiveTypeEdit() throws Exception {
        oldCredential();
        try (Connection holder = owner.getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT id FROM dev_type WHERE id=? FOR UPDATE")) {
                statement.setObject(1, type); statement.executeQuery().close();
                assertThatThrownBy(() -> asConsole(() -> credentials.generate(project, device)))
                        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_TYPE_BUSY));
            } finally { holder.rollback(); }
        }
        assertThat(config()).isZero(); assertThat(credentialVersion()).isEqualTo(1);
        assertThat(asConsole(() -> credentials.generate(project, device)).plainSecret()).hasSize(64);
    }
    /** 四协议共享实际生成和删除入口；只造权威事实，不把造数写成真实协议资格。 */
    @ParameterizedTest
    @EnumSource(TransportProtocol.class)
    void credentialAndDeletionCoverEveryAuthoritativeProtocol(TransportProtocol protocol) {
        long initial = asConsole(() -> access.change(project, device, 0, protocol, true)).configVersion();
        makeOnline(protocol, initial);
        asConsole(() -> credentials.generate(project, device));
        assertThat(config()).isEqualTo(initial + 1);
        makeOnline(protocol, initial + 1);
        mutate(() -> devices.delete(project, device));
        assertThat(facts().stream().filter(f -> reason(f).equals("CREDENTIAL_CHANGED"))).hasSize(1);
        assertThat(facts().stream().filter(f -> reason(f).equals("DEVICE_DELETED"))).hasSize(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?", Boolean.class, device)).isFalse();
    }

    private void makeOnline(TransportProtocol protocol, long epoch) {
        if (protocol == TransportProtocol.MQTT) { mqtt(epoch); return; }
        if (protocol != TransportProtocol.TCP) { activity.record(tenant, project, device, protocol); return; }
        UUID row = Uuid7.generate();
        owner.update("""
                INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version,
                    owner_instance,generation,last_seen_at,heartbeat_interval_millis)
                VALUES(?,?,?,?,?,'TCP',?,'mutation-test',?,clock_timestamp(),10000)
                """, row, tenant, project, device, "tcp-" + row, epoch, epoch + 1);
    }

    @Test void concurrentCredentialAndConfigurationShareOneDeviceFence() throws Exception {
        oldCredential(); mqtt(0);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var credential = pool.submit(() -> { start.await(); return asConsole(() -> credentials.generate(project, device)); });
            var configuration = pool.submit(() -> {
                start.await();
                try { asConsole(() -> access.change(project, device, 0, TransportProtocol.HTTP, true)); return true; }
                catch (BusinessException e) { assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_CONFIG_CONFLICT); return false; }
            });
            start.countDown();
            assertThat(credential.get(20, TimeUnit.SECONDS).plainSecret()).hasSize(64);
            boolean changed = configuration.get(20, TimeUnit.SECONDS);
            assertThat(config()).isEqualTo(changed ? 2 : 1);
        }
        assertThat(credentialVersion()).isEqualTo(2); assertThat(facts()).hasSize(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
    }

}
