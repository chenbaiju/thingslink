package com.things.link.device.api.controller;

import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.device.application.DeviceMqttAccessService;
import com.things.link.device.application.ApplicationMqttAccess;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EMQX ACL 测试，证明服务内部订阅与设备上下行权限互不扩张。 */
class EmqxAclControllerTests {

    /** 唯一服务身份只能订阅固定内部 Topic。 */
    @Test
    void serviceIdentityCanOnlySubscribeInternalTopic() {
        EmqxAclController controller = new EmqxAclController(properties(true));

        assertThat(result(controller, "thingslink-uplink-ingress",
                BrokerIngressProperties.INTERNAL_TOPIC, "subscribe")).isEqualTo("allow");
        assertThat(result(controller, "thingslink-uplink-ingress",
                BrokerIngressProperties.INTERNAL_TOPIC, "publish")).isEqualTo("deny");
        assertThat(result(controller, "thingslink-uplink-ingress",
                "tc/v1/p/d/down/command", "subscribe")).isEqualTo("deny");
    }

    /** 设备原有上下行权限保持不变，且不能访问内部交接 Topic。 */
    @Test
    void deviceIdentityCannotAccessInternalTopic() {
        EmqxAclController controller = deviceController(true);

        assertThat(result(controller, "project_1/device_1",
                "tc/v1/project_1/device_1/up/property/report", "publish")).isEqualTo("allow");
        assertThat(result(controller, "project_1/device_1",
                BrokerIngressProperties.INTERNAL_TOPIC, "subscribe")).isEqualTo("deny");
    }

    /** 迁移开关关闭时服务身份必须 fail-closed。 */
    @Test
    void disabledServiceIdentityIsDenied() {
        EmqxAclController controller = new EmqxAclController(properties(false));

        assertThat(result(controller, "thingslink-uplink-ingress",
                BrokerIngressProperties.INTERNAL_TOPIC, "subscribe")).isEqualTo("deny");
    }

    /** 合法Topic形状不代表当前设备仍获MQTT许可。 */
    @Test void nativeOrMissingScopeCannotPublishOrSubscribe() {
        var controller = deviceController(false);
        assertThat(result(controller, "project_1/device_1", "tc/v1/project_1/device_1/up/property/report", "publish")).isEqualTo("deny");
        assertThat(result(controller, "project_1/device_1", "tc/v1/project_1/device_1/down/command", "subscribe")).isEqualTo("deny");
    }
    /** 范围解析结果单独配置，不以Topic合法掩盖许可缺失。 */
    private static EmqxAclController deviceController(boolean allowed) {
        var scopes = mock(DeviceMqttAccessService.class);
        when(scopes.permits(org.mockito.ArgumentMatchers.eq("project_1"), org.mockito.ArgumentMatchers.eq("device_1"),
                org.mockito.ArgumentMatchers.any(com.things.link.device.application.DeviceMqttIdentity.class),
                org.mockito.ArgumentMatchers.eq(new UUID(0,4)), org.mockito.ArgumentMatchers.eq("tc-device-"+"a".repeat(64)))).thenReturn(allowed);
        return new EmqxAclController(properties(true), new StaticListableBeanFactory().getBeanProvider(ApplicationMqttAccess.class), scopes);
    }
    /** 调用控制器并提取固定 result 字段。 */
    private static String result(EmqxAclController controller, String username, String topic, String access) {
        var body = new java.util.LinkedHashMap<String,Object>(new com.things.link.device.application.DeviceMqttIdentity(
                new com.things.link.shared.message.AuthenticatedDeviceIdentity(new UUID(0,1),new UUID(0,2),new UUID(0,3),0),0).attributes());
        body.putAll(Map.of("username", username, "topic", topic, "access", access,
                "clientid", "tc-device-"+"a".repeat(64), "tc_auth_connection_id", new UUID(0,4).toString()));
        return controller.acl(body)
                .getBody().get("result");
    }

    /** 构造不泄露到仓库配置的测试专用身份。 */
    private static BrokerIngressProperties properties(boolean enabled) {
        return new BrokerIngressProperties(enabled, "tcp://localhost:1883", "thingslink-uplink-ingress",
                "0123456789abcdef0123456789abcdef");
    }
}
