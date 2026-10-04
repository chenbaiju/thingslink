package com.things.link.device.infrastructure.emqx;

import com.things.link.device.infrastructure.credential.DeviceCredentialAuthenticationService;
import com.things.link.device.application.DeviceMqttAccessService;
import com.things.link.support.cache.CacheInvalidationMetrics;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 服务身份认证测试，锁定固定 clientId/密码且证明不查询设备凭据表。 */
class EmqxIngressIdentityAuthenticationTests {

    /** 正确服务凭据在设备 username 解析前放行，且不触碰数据库。 */
    @Test
    void authenticatesFixedIngressWithoutDatabaseLookup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        String password = "0123456789abcdef0123456789abcdef";
        BrokerIngressProperties properties = new BrokerIngressProperties(
                true, "tcp://localhost:1883", "thingslink-uplink-ingress", password);
        EmqxAuthService service = new EmqxAuthService(
                new DeviceCredentialAuthenticationService(jdbc, tx, mock(CacheInvalidationMetrics.class)),
                properties, new BrokerIngressReadiness(), mock(DeviceMqttAccessService.class));

        assertThat(service.authenticate(properties.username(), password, BrokerIngressProperties.CLIENT_ID))
                .isEqualTo(EmqxAuthService.EmqxAuthResult.ALLOW);
        verifyNoInteractions(jdbc, tx);
    }

    /** 错误密码、错误 clientId 或关闭开关一律拒绝且不得回退数据库猜测身份。 */
    @Test
    void rejectsInvalidIngressWithoutDeviceFallback() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        BrokerIngressProperties properties = new BrokerIngressProperties(
                true, "tcp://localhost:1883", "thingslink-uplink-ingress", "x".repeat(32));
        EmqxAuthService service = new EmqxAuthService(
                new DeviceCredentialAuthenticationService(jdbc, tx, mock(CacheInvalidationMetrics.class)),
                properties, new BrokerIngressReadiness(), mock(DeviceMqttAccessService.class));

        assertThat(service.authenticate(properties.username(), "wrong", BrokerIngressProperties.CLIENT_ID))
                .isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
        assertThat(service.authenticate(properties.username(), properties.password(), "second-owner"))
                .isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
        verifyNoInteractions(jdbc, tx);
    }

    /** 首个 durable SUBACK 前拒绝普通设备，避免规则已启用但 Broker 尚无固定会话的丢失窗口。 */
    @Test
    void rejectsDeviceBeforeIngressSubscriptionIsReady() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        BrokerIngressProperties properties = new BrokerIngressProperties(
                true, "tcp://localhost:1883", "thingslink-uplink-ingress", "x".repeat(32));
        EmqxAuthService service = new EmqxAuthService(
                new DeviceCredentialAuthenticationService(jdbc, tx, mock(CacheInvalidationMetrics.class)),
                properties, new BrokerIngressReadiness(), mock(DeviceMqttAccessService.class));

        assertThat(service.authenticate("project/device", "device-secret", "device-client"))
                .isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
        verifyNoInteractions(jdbc, tx);
    }
}
