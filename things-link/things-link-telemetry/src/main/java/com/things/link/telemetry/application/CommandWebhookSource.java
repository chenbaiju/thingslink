package com.things.link.telemetry.application;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.webhook.PublicWebhookSourceWriter;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;

/** ADR0203：原命令终态与公开来源同事务，项目许可在领域锁前捕获。 */
@Service
public class CommandWebhookSource {
    private final PublicWebhookSourceWriter source;
    private final ProjectLifecycleAccessService projects;
    private final DeviceCommandRepository commands;
    private final ObjectMapper json;
    /** 只依赖项目公开许可和中性Outbox，不依赖集成领域。 */
    public CommandWebhookSource(PublicWebhookSourceWriter source, ProjectLifecycleAccessService projects,
            DeviceCommandRepository commands, ObjectMapper json) {
        this.source = source; this.projects = projects; this.commands = commands; this.json = json;
    }
    /** 调用者已建立完整RLS；在命令/设备锁前冻结可写项目代次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public OptionalLong capture(UUID tenant, UUID project) {
        if (!source.enabled()) return OptionalLong.empty();
        var generation = projects.lockReadableGeneration(tenant, project);
        return generation.isPresent() && projects.snapshot(tenant, project).writeAllowed() ? generation : OptionalLong.empty();
    }
    /** 仅在成功终态CAS后调用，读取同一命令行锁保护的实际持久值。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(DeviceCommandTerminalEvent event, OptionalLong generation) {
        if (generation.isEmpty()) return;
        DeviceCommand command = commands.findByCommandId(event.projectId(), event.commandId()).orElseThrow();
        if (!command.tenantId().equals(event.tenantId()) || !command.projectId().equals(event.projectId())
                || !command.status().name().equals(event.status().name()) || !command.terminal()
                || command.operationType() != event.operationType() || command.completedAt() == null) {
            throw new IllegalStateException("命令终态来源与持久事实不一致");
        }
        var payload = json.createObjectNode();
        payload.put("commandId", command.id().toString()); payload.put("operationType", command.operationType().name());
        payload.put("commandKey", command.commandKey()); payload.put("connectionDeviceId", command.connectionDeviceId().toString());
        payload.put("status", command.status().name()); payload.put("failureCode", command.failureCode());
        payload.put("responseJson", command.responseJson()); payload.put("attemptCount", Integer.toString(command.attemptCount()));
        payload.put("acceptedAt", time(command.acceptedAt())); payload.put("completedAt", time(command.completedAt()));
        source.append(new PublicWebhookEvent(event.eventId(), "command.completed", command.tenantId(), command.projectId(),
                generation.getAsLong(), "command", command.id(), command.targetDeviceId(), command.completedAt(),
                source.recordedAt(), command.traceId(), payload.toString()));
    }
    /** 数据库时间按公开微秒合同编码。 */
    private static String time(Instant value) { return value == null ? null : value.truncatedTo(ChronoUnit.MICROS).toString(); }
}
