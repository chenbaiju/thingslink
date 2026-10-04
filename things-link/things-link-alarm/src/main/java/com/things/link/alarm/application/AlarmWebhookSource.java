package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmTimestampPrecision;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.webhook.PublicWebhookSourceWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/** ADR0201：已持久告警事件与中性公开Outbox同事务提交，不通过可丢失提示总线。 */
@Service
public class AlarmWebhookSource {
    private final PublicWebhookSourceWriter source;
    private final ProjectLifecycleAccessService projects;
    private final TransactionLocalRlsScope rls;
    private final ObjectMapper json;

    /** 来源域只依赖project公开许可及support中性可靠交接。 */
    public AlarmWebhookSource(PublicWebhookSourceWriter source, ProjectLifecycleAccessService projects,
            TransactionLocalRlsScope rls, ObjectMapper json) {
        this.source = source; this.projects = projects; this.rls = rls; this.json = json;
    }

    /** 参数来自原RLS规则或已授权实例；必须在告警状态写入前持有项目许可。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public OptionalLong capture(UUID tenant, UUID project) {
        if (!source.enabled()) return OptionalLong.empty();
        rls.establish(tenant, project);
        var generation = projects.lockReadableGeneration(tenant, project);
        return generation.isPresent() && projects.snapshot(tenant, project).writeAllowed() ? generation : OptionalLong.empty();
    }

    /** 只接受本事务已成功追加的事件；重复/no-op不得调用此端口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AlarmInstance instance, AlarmEvent event, OptionalLong generation) {
        boolean triggered = event.eventType() == AlarmEvent.EventType.ACTIVATED;
        boolean recovered = event.eventType() == AlarmEvent.EventType.CLEARED && instance.activatedAt() != null;
        if (generation.isEmpty() || (!triggered && !recovered)) return;
        if (instance.originatorType() != AlarmRule.OriginatorType.DEVICE
                || !instance.id().equals(event.instanceId()) || !instance.tenantId().equals(event.tenantId())
                || !instance.projectId().equals(event.projectId())) throw new IllegalArgumentException("告警来源身份不一致");
        var payload = json.createObjectNode();
        payload.put("alarmId", instance.id().toString()); payload.put("ruleId", instance.ruleId().toString());
        payload.put("alarmType", instance.alarmType()); payload.put("severity", instance.severity().name());
        payload.put("conditionState", event.conditionState().name()); payload.put("ackState", event.ackState().name());
        payload.put("clearReason", event.clearReason() == null ? null : event.clearReason().name());
        payload.put("sourceMessageId", event.sourceMessageId() == null ? null : event.sourceMessageId().toString());
        payload.put("actorId", event.actorId() == null ? null : event.actorId().toString());
        payload.put("valueJson", event.value() == null ? null : BigDecimal.valueOf(event.value()).toPlainString());
        payload.put("activatedAt", time(instance.activatedAt())); payload.put("clearedAt", time(instance.clearedAt()));
        // 与仓储写入使用同一微秒舍入合同，避免跨平台时钟纳秒尾数引起一微秒偏差。
        Instant occurredAt = AlarmTimestampPrecision.toMicros(
                event.actorId() == null ? event.occurredAt() : event.receivedAt());
        source.append(new PublicWebhookEvent(event.id(), triggered ? "alarm.triggered" : "alarm.recovered",
                instance.tenantId(), instance.projectId(), generation.getAsLong(), "alarm", instance.id(),
                instance.originatorId(), occurredAt, source.recordedAt(), event.traceId(), payload.toString()));
    }

    /** 与PostgreSQL持久时刻保持微秒精度，避免载荷声称数据库不存在的纳秒。 */
    private static String time(Instant value) {
        return value == null ? null : AlarmTimestampPrecision.toMicros(value).toString();
    }
}
