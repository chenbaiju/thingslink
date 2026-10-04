package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmNotificationBinding;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.AlarmNotificationRecipient;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmNotificationTemplate;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-040：模板渲染确定性错误按 target 降级为 TEMPLATE_INVALID 终态意图，不回滚告警事实与遥测。 */
class AlarmNotificationDeliveryServiceTests {

    /** 通知领域端口。 */
    private AlarmNotificationRepository repository;
    /** enduser 有效安装受众端口。 */
    private AlarmPushAudiencePort pushAudiencePort;
    /** 事务 Outbox 端口。 */
    private TransactionalOutboxRepository outboxRepository;
    /** 低基数指标。 */
    private AlarmMetrics metrics;
    /** 通知日额度决策端口。 */
    private ProjectDailyQuotaDecisionService quotaDecision;
    /** 被测服务。 */
    private AlarmNotificationDeliveryService service;

    /** 每个用例重建 mock，避免跨用例污染。 */
    @BeforeEach
    void setUp() {
        repository = mock(AlarmNotificationRepository.class);
        pushAudiencePort = mock(AlarmPushAudiencePort.class);
        outboxRepository = mock(TransactionalOutboxRepository.class);
        metrics = mock(AlarmMetrics.class);
        quotaDecision = mock(ProjectDailyQuotaDecisionService.class);
        service = new AlarmNotificationDeliveryService(
                repository, pushAudiencePort, outboxRepository, new ObjectMapper(), metrics, quotaDecision);
        when(quotaDecision.decisionTrustedProject(any(), any(), eq(QuotaMetric.NOTIFICATION_DELIVERY)))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.NORMAL, false));
    }

    /** 正额度 HARD_LIMIT 保留两条 PUSH 投递，零额度仅留抑制审计；均不冻结厂商 token。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void expandsPushRoutePerActiveInstallationUnlessExplicitlyDisabled(boolean disabled) {
        when(quotaDecision.decisionTrustedProject(any(), any(), eq(QuotaMetric.NOTIFICATION_DELIVERY)))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.HARD_LIMIT, disabled));
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        AlarmNotificationRepository.PushDeliveryRoute route = pushRoute(instance);
        AlarmPushAudiencePort.PushAudience first = new AlarmPushAudiencePort.PushAudience(
                UUID.randomUUID(), UUID.randomUUID());
        AlarmPushAudiencePort.PushAudience second = new AlarmPushAudiencePort.PushAudience(
                UUID.randomUUID(), UUID.randomUUID());
        when(repository.findPushDeliveryRoutes(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(route));
        when(pushAudiencePort.listActiveInstallations(
                        instance.tenantId(), instance.projectId(), instance.originatorId()))
                .thenReturn(List.of(first, second));
        when(repository.createDelivery(any())).thenReturn(true);

        service.createForActivatedEvent(instance, event);

        ArgumentCaptor<AlarmNotificationDelivery> captor =
                ArgumentCaptor.forClass(AlarmNotificationDelivery.class);
        verify(repository, times(2)).createDelivery(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(delivery -> {
                    assertThat(delivery.channel()).isEqualTo(NotificationChannel.PUSH);
                    assertThat(delivery.recipientId()).isNull();
                    assertThat(delivery.targetSnapshot()).isEqualTo("PUSH");
                    assertThat(delivery.status()).isEqualTo(disabled
                            ? AlarmNotificationDelivery.Status.SUPPRESSED_QUOTA
                            : AlarmNotificationDelivery.Status.QUEUED);
                    if (disabled) assertThat(delivery.lastOutboxEventId()).isNull();
                })
                .extracting(AlarmNotificationDelivery::pushTokenId)
                .containsExactlyInAnyOrder(first.pushTokenId(), second.pushTokenId());
        verify(outboxRepository, times(disabled ? 0 : 2)).append(any());
    }

    /** subject 合法原始长度，但渲染后超 256：写 TEMPLATE_INVALID 终态意图，不写 Outbox。 */
    @Test
    void degradesSubjectRenderedOverLimitToTemplateInvalid() {
        String subjectTemplate = "${alarm.instanceId}".repeat(13);
        assertThat(subjectTemplate).hasSizeLessThanOrEqualTo(256);
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        AlarmNotificationRepository.DeliveryTarget target = target(subjectTemplate, "正文");
        when(repository.findDeliveryTargets(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(target));
        when(repository.createDelivery(any())).thenReturn(true);

        service.createForActivatedEvent(instance, event);

        AlarmNotificationDelivery delivery = capturedDelivery();
        assertThat(delivery.status()).isEqualTo(AlarmNotificationDelivery.Status.TEMPLATE_INVALID);
        assertThat(delivery.subjectSnapshot()).hasSizeLessThanOrEqualTo(256);
        assertThat(delivery.lastErrorCode()).isEqualTo(AlarmNotificationDeliveryService.TEMPLATE_RENDER_INVALID);
        assertThat(delivery.lastOutboxEventId()).isNull();
        assertThat(delivery.terminalAt()).isEqualTo(event.receivedAt());
        verify(outboxRepository, never()).append(any());
        verify(metrics).recordNotificationIntent(
                NotificationChannel.EMAIL, AlarmMetrics.NotificationIntentResult.TEMPLATE_INVALID);
    }

    /** body 合法原始长度，但渲染后超 20_000：写 TEMPLATE_INVALID，body 快照截断到 20_000。 */
    @Test
    void degradesBodyRenderedOverLimitWithSafeSnapshot() {
        String bodyTemplate = "${alarm.instanceId}".repeat(1052);
        assertThat(bodyTemplate).hasSizeLessThanOrEqualTo(20_000);
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        AlarmNotificationRepository.DeliveryTarget target = target("主题", bodyTemplate);
        when(repository.findDeliveryTargets(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(target));
        when(repository.createDelivery(any())).thenReturn(true);

        service.createForActivatedEvent(instance, event);

        AlarmNotificationDelivery delivery = capturedDelivery();
        assertThat(delivery.status()).isEqualTo(AlarmNotificationDelivery.Status.TEMPLATE_INVALID);
        assertThat(delivery.bodySnapshot()).hasSizeLessThanOrEqualTo(20_000);
        assertThat(delivery.lastErrorCode()).isEqualTo(AlarmNotificationDeliveryService.TEMPLATE_RENDER_INVALID);
        verify(outboxRepository, never()).append(any());
    }

    /** body 为 null 是防御性分支（正常配置不可达）：仍降级为 TEMPLATE_INVALID，body 快照降级为空串。 */
    @Test
    void degradesNullBodyToTemplateInvalidDefensively() {
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        AlarmNotificationRepository.DeliveryTarget target = target("主题", null);
        when(repository.findDeliveryTargets(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(target));
        when(repository.createDelivery(any())).thenReturn(true);

        service.createForActivatedEvent(instance, event);

        AlarmNotificationDelivery delivery = capturedDelivery();
        assertThat(delivery.status()).isEqualTo(AlarmNotificationDelivery.Status.TEMPLATE_INVALID);
        assertThat(delivery.bodySnapshot()).isEmpty();
        verify(outboxRepository, never()).append(any());
    }

    /** 同一组内一个非法 target、一个合法 target：非法项终态留档，合法项仍 QUEUED + Outbox。 */
    @Test
    void mixedInvalidAndValidTargets() {
        String oversizedBody = "${alarm.instanceId}".repeat(1052);
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        AlarmNotificationRepository.DeliveryTarget invalid = target("主题", oversizedBody);
        AlarmNotificationRepository.DeliveryTarget valid = target("主题", "正文");
        when(repository.findDeliveryTargets(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(invalid, valid));
        when(repository.createDelivery(any())).thenReturn(true);

        service.createForActivatedEvent(instance, event);

        ArgumentCaptor<AlarmNotificationDelivery> captor =
                ArgumentCaptor.forClass(AlarmNotificationDelivery.class);
        verify(repository, times(2)).createDelivery(captor.capture());
        List<AlarmNotificationDelivery> deliveries = captor.getAllValues();
        assertThat(deliveries).extracting(AlarmNotificationDelivery::status)
                .containsExactlyInAnyOrder(
                        AlarmNotificationDelivery.Status.TEMPLATE_INVALID,
                        AlarmNotificationDelivery.Status.QUEUED);
        verify(outboxRepository).append(any());
        verify(metrics).recordNotificationIntent(
                NotificationChannel.EMAIL, AlarmMetrics.NotificationIntentResult.TEMPLATE_INVALID);
        verify(metrics).recordNotificationIntent(
                NotificationChannel.EMAIL, AlarmMetrics.NotificationIntentResult.CREATED);
    }

    /** 重放命中唯一键：仍记 DEDUPLICATED，不重复增加 template_invalid 指标。 */
    @Test
    void deduplicatesTemplateInvalidIntent() {
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        AlarmNotificationRepository.DeliveryTarget target = target("主题", null);
        when(repository.findDeliveryTargets(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(target));
        when(repository.createDelivery(any())).thenReturn(false);

        service.createForActivatedEvent(instance, event);

        verify(metrics).recordNotificationIntent(
                NotificationChannel.EMAIL, AlarmMetrics.NotificationIntentResult.DEDUPLICATED);
        verify(metrics, never()).recordNotificationIntent(
                NotificationChannel.EMAIL, AlarmMetrics.NotificationIntentResult.TEMPLATE_INVALID);
        verify(outboxRepository, never()).append(any());
    }

    /** 基础设施写入失败必须仍向外抛出，不能被降级逻辑吞掉。 */
    @Test
    void propagatesInfrastructureFailure() {
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        AlarmNotificationRepository.DeliveryTarget target = target("主题", "正文");
        when(repository.findDeliveryTargets(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(target));
        when(repository.createDelivery(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.createForActivatedEvent(instance, event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    /** UTF-16 代理对不得在截断点被拆散：跨 20_000 边界的表情符整体丢弃。 */
    @Test
    void truncatesWithoutBreakingSurrogatePairs() {
        AlarmInstance instance = instance();
        AlarmEvent event = activatedEvent(instance);
        // 19999 个 'x' + 一个表情符(代理对占 2 char) + 'y'，总长 20002；表情符横跨 19999/20000 边界。
        String body = "x".repeat(19_999) + "\uD83D\uDE00" + "y";
        AlarmNotificationRepository.DeliveryTarget target = target("主题", body);
        when(repository.findDeliveryTargets(instance.projectId(), instance.ruleId()))
                .thenReturn(List.of(target));
        when(repository.createDelivery(any())).thenReturn(true);

        service.createForActivatedEvent(instance, event);

        String snapshot = capturedDelivery().bodySnapshot();
        // 长度 19999 且末位为 'x'，证明截断在代理对边界回退了一个 char、没有留下孤立高位代理。
        assertThat(snapshot).hasSize(19_999).endsWith("x");
    }

    /** @return 捕获最近一次 createDelivery 的投递意图 */
    private AlarmNotificationDelivery capturedDelivery() {
        ArgumentCaptor<AlarmNotificationDelivery> captor =
                ArgumentCaptor.forClass(AlarmNotificationDelivery.class);
        verify(repository).createDelivery(captor.capture());
        return captor.getValue();
    }

    /** @return 直接进入 ACTIVE 的告警实例快照 */
    private static AlarmInstance instance() {
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        UUID projectId = UUID.randomUUID();
        return new AlarmInstance(
                UUID.randomUUID(), UUID.randomUUID(), projectId, UUID.randomUUID(),
                AlarmRule.OriginatorType.DEVICE, UUID.randomUUID(), "temperature",
                AlarmRule.Severity.CRITICAL, AlarmInstance.ConditionState.ACTIVE,
                AlarmInstance.AckState.UNACKNOWLEDGED, null, now, null, now, null, null, null,
                now, now, 0.0, 0, now, now);
    }

    /** @return 真正写入的 ACTIVATED 事件 */
    private static AlarmEvent activatedEvent(AlarmInstance instance) {
        Instant now = instance.activatedAt();
        return new AlarmEvent(
                UUID.randomUUID(), instance.tenantId(), instance.projectId(), instance.id(),
                AlarmEvent.EventType.ACTIVATED, UUID.randomUUID(), "trace-1", 37.5, now, now,
                null, instance.conditionState(), instance.ackState(), null);
    }

    /** @return 一个 EMAIL 渠道的投递上下文（subject/body 模板由参数给定） */
    private static AlarmNotificationRepository.DeliveryTarget target(String subject, String body) {
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        UUID projectId = UUID.randomUUID();
        AlarmNotificationBinding binding = new AlarmNotificationBinding(
                UUID.randomUUID(), UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), NotificationChannel.EMAIL, true, 1, now, now, null);
        AlarmNotificationRecipient recipient = new AlarmNotificationRecipient(
                UUID.randomUUID(), UUID.randomUUID(), projectId, UUID.randomUUID(),
                NotificationChannel.EMAIL, "ops@example.com", true, 1, now, now, null);
        AlarmNotificationTemplate template = new AlarmNotificationTemplate(
                UUID.randomUUID(), UUID.randomUUID(), projectId, "邮件", NotificationChannel.EMAIL,
                subject, body, true, 1, now, now, null);
        return new AlarmNotificationRepository.DeliveryTarget(binding, recipient, template);
    }

    /** @return 与给定告警规则同项目的 PUSH 路由，不含伪造配置收件人 */
    private static AlarmNotificationRepository.PushDeliveryRoute pushRoute(AlarmInstance instance) {
        Instant now = instance.createdAt();
        AlarmNotificationBinding binding = new AlarmNotificationBinding(
                UUID.randomUUID(), instance.tenantId(), instance.projectId(), instance.ruleId(),
                UUID.randomUUID(), UUID.randomUUID(), NotificationChannel.PUSH, true, 1, now, now, null);
        AlarmNotificationTemplate template = new AlarmNotificationTemplate(
                binding.templateId(), instance.tenantId(), instance.projectId(), "PUSH",
                NotificationChannel.PUSH, "告警", "值=${alarm.value}", true, 1, now, now, null);
        return new AlarmNotificationRepository.PushDeliveryRoute(binding, template);
    }
}
