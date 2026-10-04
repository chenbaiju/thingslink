package com.things.link.support.outbox;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.webhook.PublicWebhookCodec;
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
class PublicWebhookOutboxTests {
    @Test void routeIsFixedAndCannotBeInjected() {
        assertThat(OutboxRouteCatalog.topicFor("PUBLIC_WEBHOOK_SOURCE"))
                .isEqualTo("tc.integration.webhook.source");
        UUID id = Uuid7.generate();
        assertThatThrownBy(() -> new OutboxEvent(id, id, id, "PUBLIC_WEBHOOK_SOURCE", id,
                "PUBLIC_WEBHOOK_SOURCE", "tc.device.downlink", id.toString(), "{}", "test", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"tenant", "project", "aggregate", "device", "key", "trace", "schema", "valid"})
    void checksEveryPersistentIdentityBeforeSending(String variant) throws Exception {
        UUID tenant = Uuid7.generate(), project = Uuid7.generate(), device = Uuid7.generate();
        var mapper = JsonMapper.builder().build();
        var message = new PublicWebhookCodec(mapper).prepare(new PublicWebhookEvent(Uuid7.generate(),"device.online",tenant,project,0,"device",device,device,Instant.now(),Instant.now(),"test","{}"));
        String payload = mapper.writeValueAsString(message);
        if (variant.equals("schema")) payload = payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        var event = new OutboxEvent(Uuid7.generate(), variant.equals("tenant") ? Uuid7.generate() : tenant,
                variant.equals("project") ? Uuid7.generate() : project,
                variant.equals("aggregate") ? "WRONG" : "PUBLIC_WEBHOOK_SOURCE",
                variant.equals("device") ? Uuid7.generate() : device, PublicWebhookSource.EVENT_TYPE,
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
                verify(kafka).send(eq(PublicWebhookSource.TOPIC), eq(device.toString()), eq(message));
            } else {
                verify(repository, timeout(3000)).markRetry(eq(event.id()), eq(token), any(), anyString());
                verifyNoInteractions(kafka);
                verify(repository, never()).markPublished(any(), any());
            }
        } finally { publisher.destroy(); }
    }
}
