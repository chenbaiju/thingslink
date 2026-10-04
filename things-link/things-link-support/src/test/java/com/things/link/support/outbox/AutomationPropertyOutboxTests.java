package com.things.link.support.outbox;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.support.observability.OutboxMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实发布器的错误元数据必须在网络调用之前失败关闭。 */
class AutomationPropertyOutboxTests {
    @Test void routeIsFixedAndCannotBeInjected() {
        assertThat(OutboxRouteCatalog.topicFor("AUTOMATION_PROPERTY_ACCEPTED"))
                .isEqualTo("tc.rule.automation.property.accepted");
        UUID id = Uuid7.generate();
        assertThatThrownBy(() -> new OutboxEvent(id, id, id, "AUTOMATION_PROPERTY", id,
                "AUTOMATION_PROPERTY_ACCEPTED", "tc.device.downlink", id.toString(), "{}", "test", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"tenant", "project", "aggregate", "device", "key", "trace", "schema", "valid"})
    void checksEveryPersistentIdentityBeforeSending(String variant) throws Exception {
        UUID tenant = Uuid7.generate(), project = Uuid7.generate(), device = Uuid7.generate();
        var mapper = JsonMapper.builder().build();
        var message = new AutomationPropertyAccepted(1, Uuid7.generate(), tenant, project, device,
                "1.0.0", Instant.now(), Instant.now(), Map.of("x", 1), "test");
        String payload = mapper.writeValueAsString(message);
        if (variant.equals("schema")) payload = payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        var event = new OutboxEvent(Uuid7.generate(), variant.equals("tenant") ? Uuid7.generate() : tenant,
                variant.equals("project") ? Uuid7.generate() : project,
                variant.equals("aggregate") ? "WRONG" : "AUTOMATION_PROPERTY",
                variant.equals("device") ? Uuid7.generate() : device, AutomationPropertyAccepted.EVENT_TYPE,
                variant.equals("key") ? "wrong" : device.toString(), payload,
                variant.equals("trace") ? "wrong" : "test", Instant.now());
        var repository = mock(TransactionalOutboxRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,Object> kafka = mock(KafkaTemplate.class);
        UUID token = Uuid7.generate();
        when(repository.claimReady(8, Duration.ofSeconds(30))).thenReturn(new OutboxClaim(token, List.of(event)));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(event.id(), token)).thenReturn(true);
        var publisher = new KafkaTransactionalOutboxPublisher(repository, kafka,
                new OutboxMetrics(new SimpleMeterRegistry()), mapper, 8, 30);
        try {
            publisher.publishReadyEvents();
            if (variant.equals("valid")) {
                verify(repository, timeout(3000)).markPublished(event.id(), token);
                verify(kafka).send(eq(AutomationPropertyAccepted.TOPIC), eq(device.toString()), eq(message));
            } else {
                verify(repository, timeout(3000)).markRetry(eq(event.id()), eq(token), any(), anyString());
                verifyNoInteractions(kafka);
                verify(repository, never()).markPublished(any(), any());
            }
        } finally { publisher.destroy(); }
    }
}
