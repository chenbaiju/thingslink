package com.things.link.support.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import com.things.link.support.observability.OutboxMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.core.KafkaTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 事务 Outbox Kafka 发布器测试，确保 broker 确认与失败重试不会混淆。 */
class KafkaTransactionalOutboxPublisherTests {

    /** 成功得到 Kafka 确认后，必须带原租约推进数据库状态。 */
    @Test
    void marksPublishedOnlyAfterKafkaAcknowledges() {
        TransactionalOutboxRepository repository = mock(TransactionalOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = event();
        UUID leaseToken = UUID.randomUUID();
        when(repository.claimReady(8, Duration.ofSeconds(30)))
                .thenReturn(new OutboxClaim(leaseToken, List.of(event)));
        when(kafkaTemplate.send(
                        eq(KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC),
                        eq(event.partitionKey()),
                        any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(event.id(), leaseToken)).thenReturn(true);

        newPublisher(repository, kafkaTemplate).publishReadyEvents();

        verify(repository, timeout(2000)).markPublished(event.id(), leaseToken);
        awaitMetricCount("published", KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC);
    }

    /** Kafka 发送失败只能释放供重试，不能误标记为已发布。 */
    @Test
    void releasesLeaseForRetryWhenKafkaFails() {
        TransactionalOutboxRepository repository = mock(TransactionalOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = event();
        UUID leaseToken = UUID.randomUUID();
        when(repository.claimReady(8, Duration.ofSeconds(30)))
                .thenReturn(new OutboxClaim(leaseToken, List.of(event)));
        when(kafkaTemplate.send(
                        eq(KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC),
                        eq(event.partitionKey()),
                        any()))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        when(repository.markRetry(
                        eq(event.id()),
                        eq(leaseToken),
                        any(Instant.class),
                        eq("ExecutionException")))
                .thenReturn(true);

        newPublisher(repository, kafkaTemplate).publishReadyEvents();

        verify(repository, timeout(2000))
                .markRetry(
                        eq(event.id()),
                        eq(leaseToken),
                        any(Instant.class),
                        eq("ExecutionException"));
        awaitMetricCount("retry", KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC);
    }

    /** S6-2 通知意图只能路由到受控通知主题，不能错误进入设备下行分区。 */
    @Test
    void routesNotificationDeliveryRequestToNotificationTopic() {
        TransactionalOutboxRepository repository = mock(TransactionalOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = notificationEvent();
        UUID leaseToken = UUID.randomUUID();
        when(repository.claimReady(8, Duration.ofSeconds(30)))
                .thenReturn(new OutboxClaim(leaseToken, List.of(event)));
        when(kafkaTemplate.send(
                        eq(KafkaTransactionalOutboxPublisher.NOTIFICATION_TOPIC),
                        eq(event.partitionKey()),
                        any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(event.id(), leaseToken)).thenReturn(true);

        newPublisher(repository, kafkaTemplate).publishReadyEvents();

        verify(kafkaTemplate, timeout(2000))
                .send(
                        eq(KafkaTransactionalOutboxPublisher.NOTIFICATION_TOPIC),
                        eq(event.partitionKey()),
                        any());
        awaitMetricCount("published", KafkaTransactionalOutboxPublisher.NOTIFICATION_TOPIC);
    }

    /** S9-2 终态事件必须进入独立回写主题，不能与设备下行竞争同一消费组。 */
    @Test
    void routesDeviceTerminalEventToTerminalTopic() {
        TransactionalOutboxRepository repository = mock(TransactionalOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = terminalEvent();
        UUID leaseToken = UUID.randomUUID();
        when(repository.claimReady(8, Duration.ofSeconds(30)))
                .thenReturn(new OutboxClaim(leaseToken, List.of(event)));
        when(kafkaTemplate.send(
                        eq(KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_TERMINAL_TOPIC),
                        eq(event.partitionKey()),
                        any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(event.id(), leaseToken)).thenReturn(true);

        assertThat(JsonMapper.builder().build().readValue(event.payload(), DeviceCommandTerminalEvent.class)
                .commandId()).isEqualTo(event.aggregateId());

        newPublisher(repository, kafkaTemplate).publishReadyEvents();

        verify(kafkaTemplate, timeout(2000)).send(
                eq(KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_TERMINAL_TOPIC),
                eq(event.partitionKey()),
                any());
    }

    /** 一个 stripe 等待 broker 时，映射到另一 stripe 的 lane 必须继续确认，不能退回全局串行。 */
    @Test
    void blockedStripeDoesNotStopAnotherStripe() {
        TransactionalOutboxRepository repository = mock(TransactionalOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent blocked = event();
        OutboxEvent progressing = eventOnDifferentStripe(blocked);
        UUID leaseToken = UUID.randomUUID();
        CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>> blockedAck =
                new CompletableFuture<>();
        when(repository.claimReady(8, Duration.ofSeconds(30)))
                .thenReturn(new OutboxClaim(leaseToken, List.of(blocked, progressing)));
        when(kafkaTemplate.send(
                eq(KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC),
                eq(blocked.partitionKey()), any())).thenReturn(blockedAck);
        when(kafkaTemplate.send(
                eq(KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC),
                eq(progressing.partitionKey()), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(progressing.id(), leaseToken)).thenReturn(true);

        KafkaTransactionalOutboxPublisher publisher = newPublisher(repository, kafkaTemplate);
        try {
            publisher.publishReadyEvents();

            verify(repository, timeout(2000)).markPublished(progressing.id(), leaseToken);
            verify(repository, org.mockito.Mockito.never()).markPublished(blocked.id(), leaseToken);
        } finally {
            blockedAck.complete(null);
        }
    }

    /** CI102：回调已被调用但尚未返回时，不能据此读取尚未登记的published/retry结果指标。 */
    @ParameterizedTest
    @ValueSource(strings = {"published", "retry"})
    void repositoryInvocationDoesNotMeanDeliveryMetricIsComplete(String result) throws Exception {
        TransactionalOutboxRepository repository = mock(TransactionalOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = notificationEvent();
        UUID leaseToken = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.claimReady(8, Duration.ofSeconds(30)))
                .thenReturn(new OutboxClaim(leaseToken, List.of(event)));
        when(kafkaTemplate.send(eq(event.destinationTopic()), eq(event.partitionKey()), any()))
                .thenReturn(result.equals("published") ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        org.mockito.stubbing.Answer<Boolean> heldResult = invocation -> {
            entered.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("测试未在原2秒观察预算内释放仓储回调");
            }
            return true;
        };
        if (result.equals("published")) {
            when(repository.markPublished(event.id(), leaseToken)).thenAnswer(heldResult);
        } else {
            when(repository.markRetry(eq(event.id()), eq(leaseToken), any(Instant.class),
                    eq("ExecutionException"))).thenAnswer(heldResult);
        }
        try {
            newPublisher(repository, kafkaTemplate).publishReadyEvents();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            verify(kafkaTemplate).send(eq(event.destinationTopic()), eq(event.partitionKey()), any());
            // 原读取在这个真实异步窗口必然失败；不是通过预注册零值计数器掩盖缺失。
            assertThatThrownBy(() -> metricCount(result, event.destinationTopic()))
                    .isInstanceOf(MeterNotFoundException.class);
            release.countDown();
            awaitMetricCount(result, event.destinationTopic());
        } finally {
            release.countDown();
        }
    }

    /** 手工构造没有Spring销毁回调，所有路径都先收束stripe，再关闭本例注册表。 */
    @AfterEach
    void closePublisherAndRegistry() throws InterruptedException {
        try {
            if (publisher != null) publisher.destroy();
        } finally {
            if (registry != null) registry.close();
        }
    }

    /** 每例最多一个手工发布器，用于在断言失败时也释放其工作线程。 */
    private KafkaTransactionalOutboxPublisher publisher;

    /** 每个测试独立持有注册表，防止前一个用例的计数污染后一个断言。 */
    private SimpleMeterRegistry registry;

    /** 创建带独立指标注册表的发布器。 */
    private KafkaTransactionalOutboxPublisher newPublisher(
            TransactionalOutboxRepository repository, KafkaTemplate<String, Object> kafkaTemplate) {
        registry = new SimpleMeterRegistry();
        publisher = new KafkaTransactionalOutboxPublisher(
                repository,
                kafkaTemplate,
                new OutboxMetrics(registry),
                JsonMapper.builder().build(),
                8,
                30);
        return publisher;
    }

    /** 创建最小完整 JSON Outbox 事件。 */
    private static OutboxEvent event() {
        return new OutboxEvent(
                Uuid7.generate(),
                Uuid7.generate(),
                Uuid7.generate(),
                "DEVICE_COMMAND",
                Uuid7.generate(),
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_DISPATCH_EVENT,
                Uuid7.generate().toString(),
                "{\"eventId\":\"0198d3b0-29b2-7000-8000-000000000000\","
                    + "\"tenantId\":\"0198d3b0-29b2-7000-8000-000000000001\","
                    + "\"projectId\":\"0198d3b0-29b2-7000-8000-000000000002\","
                    + "\"commandId\":\"0198d3b0-29b2-7000-8000-000000000003\","
                    + "\"attemptId\":\"0198d3b0-29b2-7000-8000-000000000004\",\"attemptNo\":1,"
                    + "\"targetDeviceId\":\"0198d3b0-29b2-7000-8000-000000000005\",\"targetDeviceKey\":\"target\","
                    + "\"connectionDeviceId\":\"0198d3b0-29b2-7000-8000-000000000006\",\"connectionDeviceKey\":\"connection\","
                    + "\"projectKey\":\"project\",\"commandKey\":\"reboot\",\"inputJson\":\"{}\","
                    + "\"deadlineAt\":\"2026-08-08T00:00:00Z\",\"traceId\":\"0123456789abcdef0123456789abcdef\"}",
                "0123456789abcdef0123456789abcdef",
                Instant.now());
    }

    /** @return 与给定事件稳定落入不同发布 stripe 的同契约事件 */
    private static OutboxEvent eventOnDifferentStripe(OutboxEvent reference) {
        int referenceStripe = stripe(reference.destinationTopic(), reference.partitionKey());
        for (int candidate = 0; candidate < 100; candidate++) {
            String key = "different-lane-" + candidate;
            if (stripe(reference.destinationTopic(), key) != referenceStripe) {
                return new OutboxEvent(
                        Uuid7.generate(), reference.tenantId(), reference.projectId(),
                        reference.aggregateType(), Uuid7.generate(), reference.eventType(), key,
                        reference.payload(), reference.traceId(), reference.availableAt().plusMillis(1));
            }
        }
        throw new AssertionError("未找到不同 Outbox stripe 的测试 key");
    }

    /** 复制生产固定哈希规则，测试只用它构造不同 stripe，不据此证明生产行为。 */
    private static int stripe(String topic, String key) {
        return Math.floorMod(Objects.hash(topic, key), 4);
    }

    /** 创建不含邮箱、URL、正文或凭据的最小通知请求事件。 */
    private static OutboxEvent notificationEvent() {
        UUID eventId = Uuid7.generate();
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deliveryId = Uuid7.generate();
        UUID instanceId = Uuid7.generate();
        UUID alarmEventId = Uuid7.generate();
        return new OutboxEvent(
                eventId,
                tenantId,
                projectId,
                "ALARM_NOTIFICATION_DELIVERY",
                deliveryId,
                KafkaTransactionalOutboxPublisher.NOTIFICATION_DELIVERY_REQUEST_EVENT,
                deliveryId.toString(),
                "{\"eventId\":\"%s\",\"tenantId\":\"%s\",\"projectId\":\"%s\",\"deliveryId\":\"%s\",\"alarmInstanceId\":\"%s\",\"alarmEventId\":\"%s\",\"attemptNo\":1,\"requestedAt\":\"2026-08-09T00:00:00Z\",\"traceId\":\"0123456789abcdef0123456789abcdef\"}"
                        .formatted(
                                eventId, tenantId, projectId, deliveryId, instanceId, alarmEventId),
                "0123456789abcdef0123456789abcdef",
                Instant.now());
    }

    /** 构造不含设备回复正文的终态回写事件。 */
    private static OutboxEvent terminalEvent() {
        UUID eventId = Uuid7.generate();
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID commandId = Uuid7.generate();
        return new OutboxEvent(
                eventId, tenantId, projectId, "DEVICE_COMMAND", commandId,
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_TERMINAL_EVENT, commandId.toString(),
                ("{\"eventId\":\"%s\",\"tenantId\":\"%s\",\"projectId\":\"%s\","
                        + "\"commandId\":\"%s\",\"operationType\":\"PROPERTY_SET\","
                        + "\"status\":\"SUCCEEDED\",\"failureCode\":null,"
                        + "\"completedAt\":\"2026-08-13T00:00:00Z\","
                        + "\"traceId\":\"0123456789abcdef0123456789abcdef\"}")
                        .formatted(eventId, tenantId, projectId, commandId),
                "0123456789abcdef0123456789abcdef", Instant.now());
    }

    /**
     * CI102及验证方针4：Mockito看到调用不是完成屏障；保留原2秒预算，等待最终固定标签计数。
     * 缺失counter和已注册未累加的0均继续等待，精确1保证没有以“指标存在”或放宽计数冒充成功。
     */
    private void awaitMetricCount(String result, String topic) {
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var counter = registry.find(OutboxMetrics.DELIVERY)
                    .tags("result", result, "topic", topic).counter();
            assertThat(counter).as("最终投递指标 result=%s topic=%s", result, topic).isNotNull();
            assertThat(counter.count()).isEqualTo(1D);
        });
    }

    /** 查询固定标签的投递结果计数。 */
    private double metricCount(String result, String topic) {
        return registry.get(OutboxMetrics.DELIVERY)
                .tags("result", result, "topic", topic)
                .counter()
                .count();
    }
}
