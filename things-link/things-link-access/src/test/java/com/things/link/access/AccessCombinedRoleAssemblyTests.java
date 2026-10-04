package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.ingestion.infrastructure.protocol.coap.DeviceAccessCoapServer;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.ingestion.infrastructure.protocol.tcp.TcpDownlinkReadiness;
import com.things.link.testing.tls.TestTlsMaterial;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 同时启用 TCP/TLS 与 CoAP/DTLS 时验证接入进程活动组件白名单。 */
@SpringBootTest(classes = ThingsLinkAccessApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "things-link.access.http.enabled=true",
        "things-link.access.http.allow-insecure-loopback=true",
        "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0",
        "things-link.access.coap.enabled=true",
        "things-link.access.coap.port=0",
        "things-link.ingress.handoff.enabled=false"
})
class AccessCombinedRoleAssemblyTests {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("access_combined_assembly")
            .withUsername("thingslink")
            .withPassword("thingslink");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:4.1.0"))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static final Path TLS;

    static {
        try {
            TLS = TestTlsMaterial.ensure(TestTlsMaterial.checkoutRoot(Path.of("")));
            POSTGRES.start();
            REDIS.start();
            KAFKA.start();
            try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    KAFKA.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic("tc.device.downlink", 12, (short) 1)))
                        .all().get(20, TimeUnit.SECONDS);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("TCP test dependencies are unavailable", exception);
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("things-link.access.tls.certificate",
                () -> TLS.resolve(TestTlsMaterial.CERT).toUri().toString());
        registry.add("things-link.access.tls.private-key",
                () -> TLS.resolve(TestTlsMaterial.KEY).toUri().toString());
    }

    @Autowired
    DeviceAccessTcpServer tcp;

    @Autowired
    DeviceAccessCoapServer coap;

    @Autowired
    TcpDownlinkReadiness readiness;

    @Autowired
    ApplicationContext context;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping routes;

    @Test
    void bindsBothProtocolsWithOnlyAccessRoutesAndDedicatedConsumer() {
        assertThat(tcp.boundPort()).isPositive();
        assertThat(coap.boundPort()).isPositive();
        assertThat(readiness.ready()).isTrue();
        assertThat(listeners.getListenerContainers()).hasSize(1);
        var downlink = listeners.getListenerContainer(TcpDownlinkReadiness.LISTENER_ID);
        assertThat(downlink).isNotNull();
        assertThat(downlink.isRunning()).isTrue();
        Set<String> controllers = routes.getHandlerMethods().values().stream()
                .map(handler -> handler.getBeanType().getName())
                .filter(name -> name.startsWith("com.things.link.") && name.endsWith("Controller"))
                .collect(Collectors.toSet());
        assertThat(controllers).hasSize(8);
        assertThat(controllers).noneMatch(name -> name.contains("ProjectController"));
        assertThat(context.containsBean("flyway")).isFalse();
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                .isFalse();
        assertThat(context.containsBean("deviceAccessCoapServer")).isTrue();
    }
}
