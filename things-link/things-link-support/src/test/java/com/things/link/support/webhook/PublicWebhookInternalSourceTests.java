package com.things.link.support.webhook;

import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 内部服务间事件源不要求打开公开 Webhook 出站功能。 */
class PublicWebhookInternalSourceTests {
    @Test
    void privateOnlyStillEnqueuesDurableSource() {
        var outbox = mock(TransactionalOutboxRepository.class);
        var rls = mock(TransactionLocalRlsScope.class);
        var mapper = JsonMapper.builder().build();
        var writer = new PublicWebhookSourceWriter(outbox, rls, mock(JdbcTemplate.class), mapper,
                false, true);
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), device = UUID.randomUUID();
        var event = new PublicWebhookEvent(UUID.randomUUID(), "device.property.report", tenant,
                project, 1, "device", device, device, Instant.now(), Instant.now(), "private-test", "{}");
        assertThat(writer.enabled()).isTrue();
        writer.append(event);
        var captured = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).append(captured.capture());
        assertThat(captured.getValue().eventType()).isEqualTo(PublicWebhookSource.EVENT_TYPE);
        assertThat(captured.getValue().tenantId()).isEqualTo(tenant);
        assertThat(captured.getValue().projectId()).isEqualTo(project);
        verify(rls).establish(tenant, project);
    }

    @Test
    void bothTogglesOffDoNotWriteAndPublicOnlyStillWorks() {
        var outbox = mock(TransactionalOutboxRepository.class);
        var rls = mock(TransactionLocalRlsScope.class);
        var mapper = JsonMapper.builder().build();
        UUID id = UUID.randomUUID();
        var event = new PublicWebhookEvent(UUID.randomUUID(), "device.online", id, id, 0,
                "device", id, id, Instant.now(), Instant.now(), "test", "{}");
        var disabled = new PublicWebhookSourceWriter(outbox, rls, mock(JdbcTemplate.class),
                mapper, false, false);
        assertThat(disabled.enabled()).isFalse();
        disabled.append(event);
        verifyNoInteractions(outbox, rls);
        var publicOnly = new PublicWebhookSourceWriter(outbox, rls, mock(JdbcTemplate.class),
                mapper, true, false);
        assertThat(publicOnly.enabled()).isTrue();
        publicOnly.append(event);
        verify(outbox).append(any(OutboxEvent.class));
    }
}
