package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 启动真实 Bean 图，核对静态扫描后的路由与活动消费注册。 */
@SpringBootTest(classes = ThingsLinkAccessApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "things-link.access.http.enabled=true",
        "things-link.access.http.allow-insecure-loopback=true",
        "things-link.ingress.handoff.enabled=false",
        "things-link.access.tcp.enabled=false",
        "things-link.access.coap.enabled=false"
})
@AutoConfigureMockMvc
class AccessAssemblyContextTests {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("access_assembly")
            .withUsername("thingslink")
            .withPassword("thingslink");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping routes;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc http;

    @Test
    void registersAccessRoutesWithoutPlatformConsumers() {
        Set<String> paths = routes.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPathPatternsCondition().getPatterns().stream())
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(paths).contains("/api/v1/emqx/register");
        assertThat(paths).anyMatch(path -> path.startsWith("/device-access/v1/"));
        assertThat(paths).noneMatch(path -> path.startsWith("/api/v1/projects"));
        assertThat(routes.getHandlerMethods().values().stream()
                .map(handler -> handler.getBeanType().getName())
                .filter(name -> name.startsWith("com.things.link.") && name.endsWith("Controller"))
                .collect(Collectors.toSet())).hasSize(8);
        assertThat(listeners.getListenerContainers()).isEmpty();
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                .isFalse();
        assertThat(context.containsBean("flyway")).isFalse();
        assertThat(context.containsBean("realtimeRedisMessageListenerContainer")).isFalse();
        assertThat(context.containsBean("minioPrivateObjectStorage")).isFalse();
        assertThat(context.containsBean("projectInvitationMailer")).isFalse();
        assertThat(context.containsBean("projectInvitationRegistrationService")).isFalse();
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(2);
        assertThat(context.containsBean("accessBrokerSecurityFilterChain")).isTrue();
        assertThat(context.containsBean("jwtDecoder")).isFalse();
        assertThat(context.containsBean("appJwtDecoder")).isFalse();
    }

    @Test
    void brokerCallbackRequiresSecretBeforeControllerAndUnknownManagementRouteIsDenied() throws Exception {
        http.perform(post("/api/v1/emqx/auth").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/emqx/register").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/v1/projects").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
}
