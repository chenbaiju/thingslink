package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.UplinkTimestampPolicy;
import com.things.link.rule.application.queue.PublishedRuleCatalog;
import com.things.link.rule.application.queue.PublishedRulePlan;
import com.things.link.rule.application.queue.PublishedRuleStep;
import com.things.link.rule.application.queue.RuleExecutionCoordinator;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionSubmission;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 normalized 只做规则 admission，不再直接调用 telemetry。 */
class NormalizedUplinkKafkaConsumerAdmissionTests {
    /** 所有测试消息共用的可信归属，便于默认版本裁决严格校验。 */
    private static final UUID TENANT_ID = UUID.randomUUID();
    /** 测试项目。 */
    private static final UUID PROJECT_ID = UUID.randomUUID();
    /** 测试设备。 */
    private static final UUID DEVICE_ID = UUID.randomUUID();

    /** normalized 数据库入口必须使用独立长退避工厂，避免退化回 raw 链路的短重试合同。 */
    @Test
    void usesDedicatedDurableDatabaseIngressFactory() throws NoSuchMethodException {
        KafkaListener listener = NormalizedUplinkKafkaConsumer.class
                .getDeclaredMethod("consume", ConsumerRecord.class)
                .getAnnotation(KafkaListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.containerFactory()).isEqualTo("normalizedIngressKafkaListenerContainerFactory");
    }

    /** 无活动规则也必须可靠发布 processed，不能绕过续接 Topic。 */
    @Test
    void publishesUnchangedMessageWhenPlanIsEmpty() {
        PublishedRuleCatalog catalog = mock(PublishedRuleCatalog.class);
        RuleExecutionCoordinator coordinator = mock(RuleExecutionCoordinator.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StandardUplinkMessage message = message();
        when(catalog.resolve(message.tenantId(), message.projectId(), message.messageId()))
                .thenReturn(new PublishedRulePlan(List.of()));
        when(kafkaTemplate.send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message)).thenReturn(CompletableFuture.completedFuture(null));

        consumer(catalog, coordinator, kafkaTemplate).consume(record(message));

        verify(kafkaTemplate).send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message);
        verify(coordinator, never()).submitAndAwait(any());
    }

    /** 活动计划必须以首版本绑定回执键，并等待协调器持久终态。 */
    @Test
    void freezesPlanAndAwaitsRuleTerminalState() {
        PublishedRuleCatalog catalog = mock(PublishedRuleCatalog.class);
        RuleExecutionCoordinator coordinator = mock(RuleExecutionCoordinator.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StandardUplinkMessage message = message();
        PublishedRuleStep first = new PublishedRuleStep(UUID.randomUUID(), UUID.randomUUID(), 3,
                "input => ({temperature: input.temperature + 1})");
        PublishedRulePlan plan = new PublishedRulePlan(List.of(first));
        when(catalog.resolve(message.tenantId(), message.projectId(), message.messageId())).thenReturn(plan);
        when(coordinator.submitAndAwait(any())).thenReturn(RuleExecutionSubmission.ACCEPTED);

        consumer(catalog, coordinator, kafkaTemplate).consume(record(message));

        org.mockito.ArgumentCaptor<RuleExecutionEnvelope> captor =
                org.mockito.ArgumentCaptor.forClass(RuleExecutionEnvelope.class);
        verify(coordinator).submitAndAwait(captor.capture());
        verify(catalog).resolve(message.tenantId(), message.projectId(), message.messageId());
        assertThat(captor.getValue().key().ruleId()).isEqualTo(first.ruleId());
        assertThat(captor.getValue().key().ruleVersionId()).isEqualTo(first.ruleVersionId());
        assertThat(captor.getValue().plan()).isEqualTo(plan);
        verify(kafkaTemplate, never()).send(eq(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC),
                any(), any());
    }

    /** 等于五分钟未来偏差边界仍属可容忍设备时钟误差。 */
    @Test
    void acceptsTimestampAtFutureSkewBoundary() {
        PublishedRuleCatalog catalog = mock(PublishedRuleCatalog.class);
        RuleExecutionCoordinator coordinator = mock(RuleExecutionCoordinator.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StandardUplinkMessage message = message(
                Instant.parse("2026-08-13T08:05:00Z"), Instant.parse("2026-08-13T08:00:00Z"));
        when(catalog.resolve(message.tenantId(), message.projectId(), message.messageId()))
                .thenReturn(new PublishedRulePlan(List.of()));
        when(kafkaTemplate.send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message)).thenReturn(CompletableFuture.completedFuture(null));

        consumer(catalog, coordinator, kafkaTemplate).consume(record(message));

        verify(kafkaTemplate).send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message);
    }

    /** 超过五分钟一纳秒也必须在规则目录和业务续接前拒绝，防止未来影子及规则副作用。 */
    @Test
    void rejectsTimestampBeyondFutureSkewBeforeRuleAdmission() {
        PublishedRuleCatalog catalog = mock(PublishedRuleCatalog.class);
        RuleExecutionCoordinator coordinator = mock(RuleExecutionCoordinator.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StandardUplinkMessage message = message(
                Instant.parse("2026-08-13T08:05:00.000000001Z"),
                Instant.parse("2026-08-13T08:00:00Z"));

        assertThatThrownBy(() -> consumer(catalog, coordinator, kafkaTemplate).consume(record(message)))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessage("设备发生时间超过最大未来偏差");

        verifyNoInteractions(catalog, coordinator, kafkaTemplate);
    }

    /** 设备时钟回拨属于迟到事实，时间策略必须放行，后续由影子 CAS 防止当前值倒退。 */
    @Test
    void acceptsClockRollbackMessage() {
        PublishedRuleCatalog catalog = mock(PublishedRuleCatalog.class);
        RuleExecutionCoordinator coordinator = mock(RuleExecutionCoordinator.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StandardUplinkMessage message = message(
                Instant.parse("2026-08-13T07:00:00Z"), Instant.parse("2026-08-13T08:00:00Z"));
        when(catalog.resolve(message.tenantId(), message.projectId(), message.messageId()))
                .thenReturn(new PublishedRulePlan(List.of()));
        when(kafkaTemplate.send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message)).thenReturn(CompletableFuture.completedFuture(null));

        consumer(catalog, coordinator, kafkaTemplate).consume(record(message));

        verify(kafkaTemplate).send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message);
    }

    /** HISTORY_ONLY 必须可靠续接 telemetry，但不得读取规则目录或提交规则执行。 */
    @Test
    void historyOnlyBypassesRuleAdmission() {
        PublishedRuleCatalog catalog = mock(PublishedRuleCatalog.class);
        RuleExecutionCoordinator coordinator = mock(RuleExecutionCoordinator.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StandardUplinkMessage message = message(
                Instant.parse("2026-08-13T07:00:00Z"), Instant.parse("2026-08-13T08:00:00Z"));
        when(kafkaTemplate.send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message)).thenReturn(CompletableFuture.completedFuture(null));

        consumer(catalog, coordinator, kafkaTemplate,
                DeviceIngestionContext.Eligibility.HISTORY_ONLY).consume(record(message));

        verifyNoInteractions(catalog, coordinator);
        verify(kafkaTemplate).send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                message.deviceId().toString(), message);
    }

    /** 零值或负值会关闭安全窗口，配置装配必须 fail-closed。 */
    @Test
    void rejectsNonPositiveFutureSkewConfiguration() {
        assertThatThrownBy(() -> new UplinkTimestampPolicy(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UplinkTimestampPolicy(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** @return 使用固定时钟的被测 admission consumer */
    private static NormalizedUplinkKafkaConsumer consumer(
            PublishedRuleCatalog catalog,
            RuleExecutionCoordinator coordinator,
            KafkaTemplate<String, Object> kafkaTemplate) {
        return consumer(catalog, coordinator, kafkaTemplate, DeviceIngestionContext.Eligibility.CURRENT);
    }

    /** @return 使用指定版本资格与固定时钟的被测 admission consumer */
    private static NormalizedUplinkKafkaConsumer consumer(
            PublishedRuleCatalog catalog,
            RuleExecutionCoordinator coordinator,
            KafkaTemplate<String, Object> kafkaTemplate,
            DeviceIngestionContext.Eligibility eligibility) {
        DeviceIngestionService deviceIngestionService = mock(DeviceIngestionService.class);
        when(deviceIngestionService.validateReportedProperties(
                eq(TENANT_ID), eq(PROJECT_ID), eq(DEVICE_ID), eq("1.0.0"),
                any(Instant.class), any(Map.class)))
                .thenReturn(new DeviceIngestionContext(TENANT_ID, UUID.randomUUID(), "1.0.0",
                        "a".repeat(64), "PG_JSONB_TEXT_V1_SHA256", "{}",
                        eligibility, Map.of("temperature", "NUMBER"), false));
        return new NormalizedUplinkKafkaConsumer(catalog, coordinator, kafkaTemplate,
                new RuleUplinkMessageMapper(new ObjectMapper()),
                new UplinkTimestampPolicy(Duration.ofMinutes(5)),
                Clock.fixed(Instant.parse("2026-08-13T08:01:00Z"), ZoneOffset.UTC), deviceIngestionService);
    }

    /** @param message 标准消息 @return normalized consumer record */
    private static ConsumerRecord<String, StandardUplinkMessage> record(StandardUplinkMessage message) {
        return new ConsumerRecord<>(NormalizedUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC,
                0, 0, message.deviceId().toString(), message);
    }

    /** @return 完整可信字段标准消息 */
    private static StandardUplinkMessage message() {
        return message(Instant.parse("2026-08-13T08:00:00Z"),
                Instant.parse("2026-08-13T08:00:01Z"));
    }

    /** @param occurredAt 设备发生时间 @param receivedAt 平台接收时间 @return 完整可信字段标准消息 */
    private static StandardUplinkMessage message(Instant occurredAt, Instant receivedAt) {
        return new StandardUplinkMessage(Uuid7.generate(), TENANT_ID, PROJECT_ID,
                DEVICE_ID, null, TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", occurredAt,
                receivedAt, "trace-s8-2c", 32,
                Map.of("temperature", 26.5));
    }
}
