package com.things.link.support.mqtt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Broker ingress 环境绑定测试，锁定无默认秘密、单活 clientId 与 fail-closed 校验。 */
class BrokerIngressPropertiesTests {

    /** 关闭迁移开关时允许空密码，但不能误匹配任何服务身份。 */
    @Test
    void disabledIdentityNeverMatches() {
        BrokerIngressProperties properties = new BrokerIngressProperties(false, null, null, null);

        properties.requireValidWhenEnabled();

        assertThat(properties.matches(properties.username(), "anything", BrokerIngressProperties.CLIENT_ID)).isFalse();
        assertThat(properties.brokerUri()).isEqualTo("tcp://localhost:1883");
    }

    /** 启用时必须同时精确匹配用户名、固定 clientId 和常量时间比较的环境密码。 */
    @Test
    void enabledIdentityRequiresAllCredentialParts() {
        String password = "0123456789abcdef0123456789abcdef";
        BrokerIngressProperties properties = new BrokerIngressProperties(
                true, "tcp://localhost:1883", "thingslink-uplink-ingress", password);

        properties.requireValidWhenEnabled();

        assertThat(properties.matches("thingslink-uplink-ingress", password,
                BrokerIngressProperties.CLIENT_ID)).isTrue();
        assertThat(properties.matches("thingslink-uplink-ingress", password, "second-owner")).isFalse();
        assertThat(properties.matches("thingslink-uplink-ingress", password + "x",
                BrokerIngressProperties.CLIENT_ID)).isFalse();
    }

    /** 独立部署使用自己的服务身份，认证和客户端必须读取同一个 clientId。 */
    @Test
    void independentDeploymentCanUseItsOwnClientId() {
        String password = "0123456789abcdef0123456789abcdef";
        BrokerIngressProperties properties = new BrokerIngressProperties(true, "tcp://localhost:1883",
                "jagonzn-uplink-ingress", password, "jagonzn-uplink-ingress-v1");
        properties.requireValidWhenEnabled();
        assertThat(properties.matches("jagonzn-uplink-ingress", password, properties.clientId())).isTrue();
        assertThat(properties.matches("jagonzn-uplink-ingress", password,
                BrokerIngressProperties.CLIENT_ID)).isFalse();
    }

    /** 短密码、设备式用户名和非 MQTT URI 必须在连接前拒绝。 */
    @Test
    void enabledIdentityRejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new BrokerIngressProperties(true, "http://localhost", "service", "x".repeat(32))
                .requireValidWhenEnabled()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BrokerIngressProperties(true, "tcp://localhost", "project/device",
                "x".repeat(32)).requireValidWhenEnabled()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BrokerIngressProperties(true, "tcp://localhost", "service", "short")
                .requireValidWhenEnabled()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BrokerIngressProperties(true, "tcp://localhost", "service",
                "x".repeat(32), "bad/id").requireValidWhenEnabled()).isInstanceOf(IllegalStateException.class);
    }
}
