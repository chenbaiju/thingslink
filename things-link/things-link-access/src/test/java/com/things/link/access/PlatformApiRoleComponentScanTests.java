package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.mock.env.MockEnvironment;

/** ADR0215：显式平台角色不注册设备端点、线协议及接入专属 Kafka 消费者。 */
class PlatformApiRoleComponentScanTests {

    private static final Set<String> ACCESS_COMPONENTS = Set.of(
            "com.things.link.device.api.controller.DeviceRegistrationController",
            "com.things.link.device.api.controller.EmqxAuthController",
            "com.things.link.device.api.controller.EmqxAclController",
            "com.things.link.device.api.controller.EmqxEventController",
            "com.things.link.ingestion.api.controller.EmqxUplinkController",
            "com.things.link.ingestion.api.controller.EmqxCommandReplyController",
            "com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpPropertyReportController",
            "com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpCommandController",
            "com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpSecurityConfiguration",
            "com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpDataPlaneFilter",
            "com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer",
            "com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpMetrics",
            "com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpRuntime",
            "com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpSessionRegistry",
            "com.things.link.ingestion.infrastructure.protocol.tcp.TcpDownlinkReadiness",
            "com.things.link.ingestion.infrastructure.protocol.tcp.TcpCommandDownlinkKafkaConsumer",
            "com.things.link.ingestion.infrastructure.protocol.tcp.TcpDownlinkKafkaConfiguration",
            "com.things.link.ingestion.infrastructure.protocol.coap.DeviceAccessCoapServer",
            "com.things.link.ingestion.infrastructure.protocol.coap.DeviceAccessCoapBusinessHandler");

    @Test
    void explicitPlatformRoleExcludesAccessComponentsButKeepsManagementApi() {
        Set<String> platform = scan("platform-api");
        assertThat(platform).doesNotContainAnyElementsOf(ACCESS_COMPONENTS);
        assertThat(platform).contains("com.things.link.project.api.controller.ProjectController",
                "com.things.link.assistant.api.controller.DeviceEvidenceController",
                "com.things.link.assistant.application.DeviceEvidenceService");
    }

    @Test
    void deviceAndCompatibilityRolesRetainDeclaredAccessComponents() {
        assertThat(scan("device-access")).containsAll(ACCESS_COMPONENTS);
        assertThat(scan(null)).containsAll(ACCESS_COMPONENTS);
    }

    private static Set<String> scan(String role) {
        var environment = new MockEnvironment()
                .withProperty("things-link.access.http.enabled", "true")
                .withProperty("things-link.access.tcp.enabled", "true")
                .withProperty("things-link.access.coap.enabled", "true");
        if (role != null) {
            environment.withProperty("things-link.deployment.role", role);
        }
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        scanner.setEnvironment(environment);
        return scanner.findCandidateComponents("com.things.link").stream()
                .map(definition -> definition.getBeanClassName())
                .collect(Collectors.toSet());
    }
}
