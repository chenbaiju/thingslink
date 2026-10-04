package com.things.link.telemetry.application;

import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.webhook.PublicWebhookSourceWriter;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/** 公开 Webhook 关闭时，命令终态仍能形成可信内部来源，非终态不能冒充完成。 */
class CommandPrivateSourceTests {
    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID commandId = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private final Instant completed = Instant.parse("2026-09-28T12:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final TransactionalOutboxRepository outbox = mock(TransactionalOutboxRepository.class);
    private final TransactionLocalRlsScope rls = mock(TransactionLocalRlsScope.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProjectLifecycleAccessService projects = mock(ProjectLifecycleAccessService.class);
    private final DeviceCommandRepository commands = mock(DeviceCommandRepository.class);

    @Test
    void internalSourcePublishesOnlyPersistedTerminalWhilePublicWebhookIsOff() {
        when(projects.lockReadableGeneration(tenant, project)).thenReturn(OptionalLong.of(7));
        when(projects.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 7));
        when(jdbc.queryForObject(eq("SELECT clock_timestamp()"), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(completed));
        var persisted = command(true);
        when(commands.findByCommandId(project, commandId)).thenReturn(Optional.of(persisted));

        var source = source(false, true);
        var generation = source.capture(tenant, project);
        assertThat(generation).hasValue(7);
        source.append(terminal(), generation);

        var written = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).append(written.capture());
        assertThat(written.getValue().eventType()).isEqualTo(PublicWebhookSource.EVENT_TYPE);
        var descriptor = json.readValue(written.getValue().payload(), PublicWebhookSource.class);
        assertThat(descriptor.event().eventType()).isEqualTo("command.completed");
        assertThat(descriptor.event().projectGeneration()).isEqualTo(7);
        assertThat(descriptor.event().resourceId()).isEqualTo(commandId);
        assertThat(json.readTree(descriptor.eventText()).path("payload").path("status").asText())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void bothSourcesOffOrFrozenProjectCannotPublishTerminal() {
        assertThat(source(false, false).capture(tenant, project)).isEmpty();
        when(projects.lockReadableGeneration(tenant, project)).thenReturn(OptionalLong.of(7));
        when(projects.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, false, 7));
        var source = source(false, true);
        var generation = source.capture(tenant, project);
        assertThat(generation).isEmpty();
        source.append(terminal(), generation);
        verify(outbox, never()).append(any());
    }

    @Test
    void acknowledgementOrMismatchedPersistedStatusCannotPublishCompletion() {
        var persisted = command(false);
        when(commands.findByCommandId(project, commandId)).thenReturn(Optional.of(persisted));
        assertThatThrownBy(() -> source(false, true).append(terminal(), OptionalLong.of(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("命令终态来源与持久事实不一致");
        verify(outbox, never()).append(any());
    }

    private CommandWebhookSource source(boolean publicEnabled, boolean internalEnabled) {
        var writer = new PublicWebhookSourceWriter(outbox, rls, jdbc, json,
                publicEnabled, internalEnabled);
        return new CommandWebhookSource(writer, projects, commands, json);
    }

    private DeviceCommandTerminalEvent terminal() {
        return new DeviceCommandTerminalEvent(UUID.randomUUID(), tenant, project, commandId,
                DeviceCommandDispatch.OperationType.COMMAND,
                DeviceCommandTerminalEvent.Status.SUCCEEDED, null, completed, "private-command-test");
    }

    private DeviceCommand command(boolean terminal) {
        var command = mock(DeviceCommand.class);
        when(command.id()).thenReturn(commandId);
        when(command.tenantId()).thenReturn(tenant);
        when(command.projectId()).thenReturn(project);
        when(command.status()).thenReturn(terminal
                ? DeviceCommand.Status.SUCCEEDED : DeviceCommand.Status.ACKNOWLEDGED);
        when(command.terminal()).thenReturn(terminal);
        when(command.operationType()).thenReturn(DeviceCommandDispatch.OperationType.COMMAND);
        if (terminal) {
            when(command.completedAt()).thenReturn(completed);
            when(command.commandKey()).thenReturn("start");
            when(command.connectionDeviceId()).thenReturn(device);
            when(command.targetDeviceId()).thenReturn(device);
            when(command.responseJson()).thenReturn("{}");
            when(command.attemptCount()).thenReturn(1);
            when(command.acceptedAt()).thenReturn(completed.minusSeconds(10));
            when(command.traceId()).thenReturn("private-command-test");
        }
        return command;
    }
}
