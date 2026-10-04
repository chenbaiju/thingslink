package com.things.link.device.infrastructure.emqx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.things.link.device.api.controller.EmqxAuthController;
import com.things.link.device.application.DeviceMqttAccessService;
import java.util.Optional;
import com.things.link.device.infrastructure.credential.DeviceCredentialAuthenticationService;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationMetrics;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheResource;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 认证缓存原身份、失效竞态与Broker属性输出的纯合同反例。 */
class EmqxAuthenticationIdentityTests {
    /** 认证身份租户。 */
    private static final UUID TENANT = new UUID(0, 1);
    /** 认证身份项目。 */
    private static final UUID PROJECT = new UUID(0, 2);
    /** 认证身份设备。 */
    private static final UUID DEVICE = new UUID(0, 3);
    /** 缓存必须保留校验时版本，即使数据库已经升级也不得补查替换。 */
    @Test void cachePreservesOriginalIdentityAndControllerAttributes() {
        var fixture = new Fixture();
        var original = fixture.service.authenticateIdentity("project/device", "secret", "client");
        fixture.version.set(2);
        var response = new EmqxAuthController(fixture.service).auth(Map.of(
                "username", "project/device", "password", "secret", "clientid", "client")).getBody();
        assertThat(original.identity().credentialVersion()).isEqualTo(1);
        assertThat(response).containsEntry("result", "allow").containsEntry("client_attrs", Map.of(
                "tc_auth_tenant_id", TENANT.toString(), "tc_auth_project_id", PROJECT.toString(),
                "tc_auth_device_id", DEVICE.toString(), "tc_auth_credential_version", "1", "tc_auth_config_version", "0",
                "tc_auth_mountpoint", "tc/private/device/"+DEVICE+"/0/", "tc_auth_wire_client_id", "client",
                "tc_auth_connection_id", new UUID(0,4).toString()));
        assertThat(response).containsEntry("clientid_override", "tc-device-7f27abc7388bff16f4a16b724caa8dedf531b744f4340f1496b4b43b31965495");
        verify(fixture.tx, times(1)).execute(any());
    }
    /** 乱序失效事件不能降代际，旧查询晚返回也不能复活缓存。 */
    @Test void invalidationDuringLoadDoesNotUpgradeOrCacheOldFact() {
        var fixture = new Fixture();
        when(fixture.jdbc.update(anyString(), eq(PROJECT), eq(DEVICE), anyString())).thenAnswer(call -> {
            fixture.invalidate(3); fixture.invalidate(2); return 1;
        });
        assertThat(fixture.service.authenticateIdentity("project/device", "secret", "client")
                .identity().credentialVersion()).isEqualTo(1);
        fixture.version.set(2);
        assertThat(fixture.service.authenticateIdentity("project/device", "secret", "client")
                .identity().credentialVersion()).isEqualTo(2);
        fixture.service.authenticateIdentity("project/device", "secret", "client");
        verify(fixture.tx, times(3)).execute(any());
    }
    /** 事务最终失败不得发布成功缓存，后续请求必须重做实际认证。 */
    @Test void failedTransactionCannotPublishCache() {
        var fixture = new Fixture();
        doAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            callback.doInTransaction(null);
            throw new DataAccessResourceFailureException("test-only database failure");
        }).when(fixture.tx).execute(any());
        assertThat(fixture.service.authenticateIdentity("project/device", "secret", "client").identity()).isNull();
        assertThat(fixture.service.authenticate("project/device", "secret", "client"))
                .isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
        verify(fixture.tx, times(2)).execute(any());
    }
    /** 服务身份只签发空挂载点，拒绝响应没有属性，防止混用内部订阅者。 */
    @Test void nonDeviceResultsHaveNoDeviceAttributes() {
        var fixture = new Fixture();
        var controller = new EmqxAuthController(fixture.service);
        assertThat(controller.auth(Map.of("username", "invalid", "password", "secret")).getBody())
                .doesNotContainKey("client_attrs").containsEntry("result", "deny");
        var properties = new BrokerIngressProperties(true, "tcp://localhost:1883", "thingslink-uplink-ingress", "x".repeat(32));
        var credentials = new DeviceCredentialAuthenticationService(fixture.jdbc, fixture.tx,
                mock(CacheInvalidationMetrics.class));
        var service = new EmqxAuthService(credentials, properties, new BrokerIngressReadiness(), mock(DeviceMqttAccessService.class));
        var response = new EmqxAuthController(service).auth(Map.of("username", properties.username(),
                "password", properties.password(), "clientid", BrokerIngressProperties.CLIENT_ID)).getBody();
        assertThat(response).containsEntry("result", "allow").containsEntry("client_attrs", Map.of("tc_auth_mountpoint", ""))
                .doesNotContainKey("clientid_override");
    }
    /** 缺失/非法原Client ID不得查询凭据或签发未隔离会话。 */
    @Test void rejectsMissingOrInvalidClientIdBeforeCredentials() {
        var fixture = new Fixture();
        for (String client : new String[]{null,"","a\0b","\ud800","界".repeat(21846)}) {
            assertThat(fixture.service.authenticate("project/device","secret",client)).isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
        }
        verify(fixture.tx,times(0)).execute(any());
    }

    /** 热凭据缓存不缓存平面许可，配置变化后立即拒绝。 */
    @Test void warmCredentialRequiresCurrentMqttScope() {
        var fixture = new Fixture();
        assertThat(fixture.service.authenticate("project/device", "secret", "client")).isEqualTo(EmqxAuthService.EmqxAuthResult.ALLOW);
        when(fixture.scopes.issueConnection(eq("project"), eq("device"), any(), anyString())).thenReturn(Optional.empty());
        assertThat(fixture.service.authenticate("project/device", "secret", "client")).isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
        verify(fixture.tx, times(1)).execute(any());
    }
    /** 当前身份比较失败时必须丢弃缓存身份，不向拒绝响应泄露属性。 */
    @Test void rejectedCurrentIdentityDoesNotLeakCachedAttributes() {
        var fixture = new Fixture();
        when(fixture.scopes.issueConnection(eq("project"), eq("device"), any(), anyString())).thenReturn(Optional.empty());
        var result = fixture.service.authenticateIdentity("project/device", "secret", "client");
        assertThat(result.result()).isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
        assertThat(result.identity()).isNull();
        assertThat(result.configVersion()).isNull();
    }
    /** 最小数据库事务模拟，仅用于缓存合同；SQL快照并发由真实PG专项覆盖。 */
    private static final class Fixture {
        /** 数据库门面。 */
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        /** 模拟真实事务提交返回时机。 */
        private final TransactionTemplate tx = mock(TransactionTemplate.class);
        /** 当前数据库代际，不直接写入缓存。 */
        private final AtomicLong version = new AtomicLong(1);
        /** 与三协议接入共用的凭据校验实现。 */
        private final DeviceCredentialAuthenticationService credentials;
        /** 当前许可与凭据缓存分开模拟。 */
        private final DeviceMqttAccessService scopes = mock(DeviceMqttAccessService.class);
        /** 被测实际服务。 */
        private final EmqxAuthService service;
        /** 初始化单SQL身份结果与可控事务。 */
        private Fixture() {
            when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
            when(jdbc.queryForList(anyString(), eq("project"))).thenReturn(List.of(Map.of("id", PROJECT, "tenant_id", TENANT)));
            when(jdbc.queryForList(anyString(), eq(PROJECT), eq(TENANT), eq("device"), eq("project"), anyString()))
                    .thenAnswer(call -> List.of(Map.of("id", DEVICE, "tenant_id", TENANT, "project_id", PROJECT,
                            "credential_version", version.get())));
            credentials = new DeviceCredentialAuthenticationService(jdbc, tx, mock(CacheInvalidationMetrics.class));
            when(scopes.issueConnection(eq("project"), eq("device"), any(), anyString())).thenReturn(Optional.of(new DeviceMqttAccessService.ConnectionGrant(0,new UUID(0,4))));
            service = new EmqxAuthService(credentials,
                    new BrokerIngressProperties(false, "tcp://localhost:1883", "thingslink-uplink-ingress", "x".repeat(32)),
                    new BrokerIngressReadiness(), scopes);
        }
        /** 注入凭据失效事件。 */
        private void invalidate(long value) {
            credentials.handle(new CacheInvalidationEvent(UUID.randomUUID(), CacheResource.DEVICE_CREDENTIAL,
                    CacheInvalidationOperation.REVOKE, DEVICE, null, value, 0, Instant.now()));
        }
    }
}
