package com.things.link.bootstrap.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.ThingsLinkApplication;
import com.things.link.testing.AbstractIntegrationTest;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 显式平台角色的真实 Bean 图与路由负例；测试环境仍由平台执行 Flyway。 */
@SpringBootTest(classes = ThingsLinkApplication.class, properties = {
        "things-link.deployment.role=platform-api",
        "things-link.access.http.enabled=true",
        "things-link.access.tcp.enabled=true",
        "things-link.access.coap.enabled=true"
})
class PlatformApiRoleAssemblyTests extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping routes;

    @Autowired
    ApplicationContext context;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Autowired
    Flyway flyway;

    @Test
    void retainsManagementAndMigrationWithoutDeviceEndpointsOrProtocolListeners() {
        Set<String> controllers = routes.getHandlerMethods().values().stream()
                .map(handler -> handler.getBeanType().getName())
                .filter(name -> name.startsWith("com.things.link."))
                .collect(Collectors.toSet());
        assertThat(controllers).contains("com.things.link.project.api.controller.ProjectController");
        assertThat(controllers).noneMatch(name -> name.contains(".Emqx")
                || name.contains(".DeviceRegistrationController")
                || name.contains(".DeviceAccessHttp"));
        assertThat(context.containsBean("deviceAccessTcpServer")).isFalse();
        assertThat(context.containsBean("deviceAccessCoapServer")).isFalse();
        assertThat(context.containsBean("deviceAccessSecurityFilterChain")).isFalse();
        assertThat(listeners.getListenerContainer("tcpCommandDownlink")).isNull();
        assertThat(flyway.info().current()).isNotNull();
    }
}
