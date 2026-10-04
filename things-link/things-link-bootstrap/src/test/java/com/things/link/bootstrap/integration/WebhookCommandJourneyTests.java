package com.things.link.bootstrap.integration;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.telemetry.application.DeviceCommandService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** 真正受理与设备结果到Kafka/TLS，不直接构造公共完成事件。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.webhook.enabled=true","things-link.integration.webhook.current-signing-key-id=a","things-link.integration.webhook.signing-keys-json={\"a\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\"}"})
@Import(WebhookCompletionJourney.ReceiverConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookCommandJourneyTests extends OpenCommandHttpFixture {
    private static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:4.1.0").withStartupTimeout(Duration.ofSeconds(120));
    static { KAFKA.start(); }
    @DynamicPropertySource static void kafka(DynamicPropertyRegistry registry) { registry.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers); }
    @AfterAll static void stopKafka() { KAFKA.stop(); }
    @Autowired ApplicationContext application;
    @Autowired DeviceCommandService commands;
    @Test void commandCompletionSurvivesAdmissionAndDeliveryFailure() throws Exception {
        WebhookCompletionJourney.verify(application,owner,tenant,project,account,device,"command.completed",()->{
            UUID id=service.submit(principal,device,Uuid7.generate().toString(),"start",json.readTree("{}")).commandId();
            var reply=new DeviceCommandReply(Uuid7.generate(),tenant,project,device,id,Instant.now(),Instant.now(),
                    DeviceCommandReply.Status.SUCCESS,"{\"exact\":9007199254740993.12345678901234567890123456789}",null,null,"command-journey");
            assertThat(commands.applyReply(reply)).isTrue(); assertThat(commands.applyReply(reply)).isFalse();
            assertThat(owner.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,id)).isEqualTo("SUCCEEDED");
        });
    }
}
