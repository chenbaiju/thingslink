package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmNotificationTemplate;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 告警激活后在同一事务内冻结投递意图并写入可靠 Outbox 的应用服务。
 *
 * <p>D-040：确定性模板渲染错误（subject 超 256 / body 为空 / body 超 20_000）按 target 降级为
 * {@code TEMPLATE_INVALID} 终态意图，不回滚告警事实与遥测；基础设施及编程错误仍整体回滚，由
 * Kafka/基础设施恢复机制处理。</p>
 */
@Service
public class AlarmNotificationDeliveryService {
    /** 投递域专属失败码：模板渲染结果非法（区别于控制面 API 的 AlarmErrorCode 命名空间，D-040）。 */
    public static final String TEMPLATE_RENDER_INVALID = "TEMPLATE_RENDER_INVALID";

    /** 配置与投递事实端口。 */
    private final AlarmNotificationRepository repository;

    /** enduser 实现的有效设备关系与安装实例受众端口。 */
    private final AlarmPushAudiencePort pushAudiencePort;

    /** 跨领域可靠 Outbox 端口。 */
    private final TransactionalOutboxRepository outboxRepository;

    /** 冻结消息 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /** 低基数投递意图观测。 */
    private final AlarmMetrics metrics;
    /** PostgreSQL 权威通知日额度；只有严重超额才抑制非关键外部渠道。 */
    private final ProjectDailyQuotaDecisionService dailyQuotaDecisionService;

    /**
     * @param repository 通知领域端口
     * @param pushAudiencePort enduser 实现的有效安装受众端口
     * @param outboxRepository 事务 Outbox
     * @param objectMapper JSON 映射器
     * @param metrics 低基数指标
     * @param dailyQuotaDecisionService PostgreSQL 权威通知日额度
     */
    public AlarmNotificationDeliveryService(
            AlarmNotificationRepository repository,
            AlarmPushAudiencePort pushAudiencePort,
            TransactionalOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            AlarmMetrics metrics,
            ProjectDailyQuotaDecisionService dailyQuotaDecisionService) {
        this.repository = repository;
        this.pushAudiencePort = pushAudiencePort;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.dailyQuotaDecisionService = dailyQuotaDecisionService;
    }

    /**
     * 展开一次真正写入的 ACTIVATED 事件。
     *
     * <p>调用方已先完成实例 CAS 与事件插入；确定性模板渲染错误按 target 降级为 TEMPLATE_INVALID
     * 终态意图（不回滚告警事实与遥测），基础设施及编程错误仍整体回滚，由 Kafka/基础设施恢复机制处理。</p>
     *
     * @param instance 已进入 ACTIVE 的事故快照
     * @param event 已真正插入的 ACTIVATED 事件
     */
    public void createForActivatedEvent(AlarmInstance instance, AlarmEvent event) {
        if (event.eventType() != AlarmEvent.EventType.ACTIVATED)
            throw new IllegalArgumentException("仅 ACTIVATED 事件可创建通知投递");
        Instant now = event.receivedAt();
        var quota = dailyQuotaDecisionService.decisionTrustedProject(
                instance.tenantId(), instance.projectId(), QuotaMetric.NOTIFICATION_DELIVERY);
        // 零额度明确禁用；正额度 HARD_LIMIT 仍按邮件/PUSH 的既有软限继续。
        boolean suppressForQuota = quota.disabled() || quota.status() == QuotaStatus.DEGRADED;
        for (AlarmNotificationRepository.DeliveryTarget target :
                repository.findDeliveryTargets(instance.projectId(), instance.ruleId())) {
            AlarmNotificationTemplate template = target.template();
            String subject = render(template.subjectTemplate(), instance, event);
            String body = render(template.bodyTemplate(), instance, event);
            // D-040：模板固定变量替换后的确定性配置错误按 target 终态降级；TEMPLATE_INVALID 优先于额度抑制。
            if (isTemplateResultInvalid(subject, body)) {
                recordTemplateInvalidDelivery(instance, event, target, template, subject, body, now);
                continue;
            }
            recordQueuedDelivery(instance, event, target, template, subject, body, suppressForQuota, now);
        }
        createPushDeliveries(instance, event, suppressForQuota, now);
    }

    /**
     * 通过 enduser 端口把每条 PUSH 路由展开为每个有效安装实例一条投递事实。
     *
     * <p>告警域只接收稳定 appUser/pushToken ID；provider 和受保护 token 不进入创建事务，避免轮换后
     * 继续使用冻结凭据。A2c 发送时将基于这些稳定 ID 重新复核并加载最新凭据。</p>
     */
    private void createPushDeliveries(
            AlarmInstance instance, AlarmEvent event, boolean suppressForQuota, Instant now) {
        List<AlarmNotificationRepository.PushDeliveryRoute> routes =
                repository.findPushDeliveryRoutes(instance.projectId(), instance.ruleId());
        if (routes.isEmpty()) return;
        List<AlarmPushAudiencePort.PushAudience> audiences = pushAudiencePort.listActiveInstallations(
                instance.tenantId(), instance.projectId(), instance.originatorId());
        for (AlarmNotificationRepository.PushDeliveryRoute route : routes) {
            AlarmNotificationTemplate template = route.template();
            String subject = render(template.subjectTemplate(), instance, event);
            String body = render(template.bodyTemplate(), instance, event);
            for (AlarmPushAudiencePort.PushAudience audience : audiences) {
                recordPushDelivery(
                        instance,
                        event,
                        route,
                        audience,
                        subject,
                        body,
                        suppressForQuota,
                        now);
            }
        }
    }

    /** 为单个安装实例写终态非法模板或正常待投递事实。 */
    private void recordPushDelivery(
            AlarmInstance instance,
            AlarmEvent event,
            AlarmNotificationRepository.PushDeliveryRoute route,
            AlarmPushAudiencePort.PushAudience audience,
            String subject,
            String body,
            boolean suppressForQuota,
            Instant now) {
        boolean invalid = isTemplateResultInvalid(subject, body);
        UUID deliveryId = Uuid7.generate();
        UUID outboxId = invalid || suppressForQuota ? null : Uuid7.generate();
        AlarmNotificationDelivery delivery = new AlarmNotificationDelivery(
                deliveryId,
                instance.tenantId(),
                instance.projectId(),
                instance.id(),
                event.id(),
                route.binding().id(),
                null,
                audience.appUserId(),
                audience.pushTokenId(),
                NotificationChannel.PUSH,
                "PUSH",
                invalid ? snapshotSubject(subject) : subject,
                invalid ? snapshotBody(body) : body,
                route.template().version(),
                invalid
                        ? AlarmNotificationDelivery.Status.TEMPLATE_INVALID
                        : suppressForQuota
                                ? AlarmNotificationDelivery.Status.SUPPRESSED_QUOTA
                                : AlarmNotificationDelivery.Status.QUEUED,
                0,
                3,
                null,
                outboxId,
                null,
                invalid ? TEMPLATE_RENDER_INVALID : null,
                now,
                now,
                invalid ? now : null);
        if (!repository.createDelivery(delivery)) {
            metrics.recordNotificationIntent(
                    NotificationChannel.PUSH,
                    AlarmMetrics.NotificationIntentResult.DEDUPLICATED);
            return;
        }
        metrics.recordNotificationIntent(
                NotificationChannel.PUSH,
                invalid
                        ? AlarmMetrics.NotificationIntentResult.TEMPLATE_INVALID
                        : AlarmMetrics.NotificationIntentResult.CREATED);
        if (outboxId != null) appendOutbox(instance, event, deliveryId, outboxId, now);
    }

    /** @return subject 超 256 / body 为空 / body 超 20_000 时触发确定性模板配置错误 */
    private static boolean isTemplateResultInvalid(String subject, String body) {
        return (subject != null && subject.length() > 256)
                || body == null
                || body.length() > 20_000;
    }

    /** 写 TEMPLATE_INVALID 终态意图：保留审计事实，不写 Outbox、不进入外部投递或重试（D-040）。 */
    private void recordTemplateInvalidDelivery(AlarmInstance instance, AlarmEvent event,
            AlarmNotificationRepository.DeliveryTarget target, AlarmNotificationTemplate template,
            String subject, String body, Instant now) {
        AlarmNotificationDelivery delivery =
                new AlarmNotificationDelivery(
                        Uuid7.generate(),
                        instance.tenantId(),
                        instance.projectId(),
                        instance.id(),
                        event.id(),
                        target.binding().id(),
                        target.recipient().id(),
                        null,
                        null,
                        target.recipient().channel(),
                        target.recipient().target(),
                        snapshotSubject(subject),
                        snapshotBody(body),
                        template.version(),
                        AlarmNotificationDelivery.Status.TEMPLATE_INVALID,
                        0,
                        3,
                        null,
                        null,
                        null,
                        TEMPLATE_RENDER_INVALID,
                        now,
                        now,
                        now);
        // 唯一键是最终仲裁；Kafka 至少一次重放或并发竞争都不能生成第二条 TEMPLATE_INVALID 意图。
        if (!repository.createDelivery(delivery)) {
            metrics.recordNotificationIntent(
                    target.recipient().channel(),
                    AlarmMetrics.NotificationIntentResult.DEDUPLICATED);
            return;
        }
        metrics.recordNotificationIntent(
                target.recipient().channel(), AlarmMetrics.NotificationIntentResult.TEMPLATE_INVALID);
    }

    /** 合法模板走既有额度/QUEUED/Outbox 路径；严重超额时 SUPPRESSED_QUOTA 且不写 Outbox。 */
    private void recordQueuedDelivery(AlarmInstance instance, AlarmEvent event,
            AlarmNotificationRepository.DeliveryTarget target, AlarmNotificationTemplate template,
            String subject, String body, boolean suppressForQuota, Instant now) {
        UUID deliveryId = Uuid7.generate();
        // 只有真正进入外部投递才生成并写 outboxId，避免「看似存在但实际没有 Outbox」的悬空关联。
        UUID outboxId = suppressForQuota ? null : Uuid7.generate();
        AlarmNotificationDelivery delivery =
                new AlarmNotificationDelivery(
                        deliveryId,
                        instance.tenantId(),
                        instance.projectId(),
                        instance.id(),
                        event.id(),
                        target.binding().id(),
                        target.recipient().id(),
                        null,
                        null,
                        target.recipient().channel(),
                        target.recipient().target(),
                        subject,
                        body,
                        template.version(),
                        suppressForQuota ? AlarmNotificationDelivery.Status.SUPPRESSED_QUOTA
                                : AlarmNotificationDelivery.Status.QUEUED,
                        0,
                        3,
                        null,
                        outboxId,
                        null,
                        null,
                        now,
                        now,
                        null);
        // 唯一键是最终仲裁；Kafka 至少一次重放或状态机 CAS 竞争都不能生成第二条意图。
        if (!repository.createDelivery(delivery)) {
            metrics.recordNotificationIntent(
                    target.recipient().channel(),
                    AlarmMetrics.NotificationIntentResult.DEDUPLICATED);
            return;
        }
        metrics.recordNotificationIntent(
                target.recipient().channel(), AlarmMetrics.NotificationIntentResult.CREATED);
        if (suppressForQuota) {
            // 告警与投递审计事实已经持久化；严重超额时不制造新的外部副作用或 Outbox 重试。
            return;
        }
        appendOutbox(instance, event, deliveryId, outboxId, now);
    }

    /** 为一条已经成功创建的 QUEUED 投递追加同事务 Outbox。 */
    private void appendOutbox(
            AlarmInstance instance, AlarmEvent event, UUID deliveryId, UUID outboxId, Instant now) {
        NotificationDeliveryRequest request =
                new NotificationDeliveryRequest(
                        outboxId,
                        instance.tenantId(),
                        instance.projectId(),
                        deliveryId,
                        instance.id(),
                        event.id(),
                        1,
                        now,
                        event.traceId());
        outboxRepository.append(
                new OutboxEvent(
                        outboxId,
                        instance.tenantId(),
                        instance.projectId(),
                        "ALARM_NOTIFICATION_DELIVERY",
                        deliveryId,
                        NotificationDeliveryRequest.EVENT_TYPE,
                        deliveryId.toString(),
                        objectMapper.writeValueAsString(request),
                        event.traceId(),
                        now));
    }

    /** @return subject 有界快照：null 保留 null，超限安全截断到 256（不拆散代理对） */
    private static String snapshotSubject(String subject) {
        return truncate(subject, 256);
    }

    /** @return body 有界快照：null 写空串，超限安全截断到 20_000（不拆散代理对） */
    private static String snapshotBody(String body) {
        if (body == null) return "";
        return truncate(body, 20_000);
    }

    /** 有界截断：截断点落在 UTF-16 代理对中间时回退一个 char，不追加省略号。 */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    /** 仅替换冻结白名单变量，永不解释 SpEL、脚本、JSONPath 或用户函数。 */
    private static String render(String source, AlarmInstance instance, AlarmEvent event) {
        if (source == null) return null;
        Map<String, String> values =
                Map.of(
                        "${alarm.type}", instance.alarmType(),
                        "${alarm.severity}", instance.severity().name(),
                        "${alarm.value}", String.valueOf(event.value()),
                        "${alarm.activatedAt}", String.valueOf(instance.activatedAt()),
                        "${alarm.instanceId}", instance.id().toString());
        String rendered = source;
        for (Map.Entry<String, String> value : values.entrySet())
            rendered = rendered.replace(value.getKey(), value.getValue());
        return rendered;
    }
}
