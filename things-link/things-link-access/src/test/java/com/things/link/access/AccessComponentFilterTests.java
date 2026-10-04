package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.mock.env.MockEnvironment;

class AccessComponentFilterTests {

    @Test
    void scansOnlyDeclaredHttpAndTcpConsumerWhileLeavingPassiveServicesAvailable() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        scanner.setEnvironment(new MockEnvironment()
                .withProperty("things-link.access.tcp.enabled", "true")
                .withProperty("things-link.access.http.enabled", "true")
                .withProperty("things-link.access.coap.enabled", "true"));
        scanner.addExcludeFilter(new AccessComponentFilter());
        var candidates = scanner.findCandidateComponents("com.things.link");
        Set<String> components = candidates.stream()
                .map(definition -> definition.getBeanClassName())
                .collect(Collectors.toSet());

        assertThat(components).contains(
                "com.things.link.device.api.controller.EmqxAuthController",
                "com.things.link.device.api.controller.DeviceRegistrationController",
                "com.things.link.ingestion.api.controller.EmqxUplinkController",
                "com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpPropertyReportController",
                "com.things.link.ingestion.infrastructure.protocol.tcp.TcpCommandDownlinkKafkaConsumer",
                "com.things.link.device.application.DeviceAccessAcceptanceService",
                "com.things.link.telemetry.application.DeviceCommandService");
        assertThat(components).doesNotContain(
                "com.things.link.device.api.controller.DeviceController",
                "com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer",
                "com.things.link.support.outbox.OutboxSchedulingConfiguration",
                "com.things.link.ingestion.infrastructure.RealtimeRedisSubscriber",
                "com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketConfiguration",
                "com.things.link.ota.application.OtaPublicationProcessor",
                "com.things.link.export.application.ProjectExportWorker",
                "com.things.link.alarm.application.AlarmNotificationWorkSource",
                "com.things.link.support.notification.mail.MailConfiguration",
                "com.things.link.iam.application.ProjectInvitationMailer",
                "com.things.link.iam.application.ProjectInvitationRegistrationService",
                "com.things.link.telemetry.application.OverviewService",
                "com.things.link.support.storage.MinioPrivateObjectStorage",
                "com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration");
        assertThat(components.stream().filter(name -> name.endsWith("Controller")).toList())
                .hasSize(8);
        assertThat(candidates.stream().map(AnnotatedBeanDefinition.class::cast)
                .filter(definition -> definition.getMetadata().hasAnnotatedMethods(
                        "org.springframework.kafka.annotation.KafkaListener"))
                .map(definition -> definition.getBeanClassName()).toList())
                .containsExactly("com.things.link.ingestion.infrastructure.protocol.tcp.TcpCommandDownlinkKafkaConsumer");
    }
}
