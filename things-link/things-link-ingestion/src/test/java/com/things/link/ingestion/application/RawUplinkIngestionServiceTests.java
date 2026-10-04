package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceAccessScopeService;
import com.things.link.device.application.DeviceAccessScopeService.ResolvedDeviceAccessScope;
import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
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

/** 原始上行用例测试，覆盖成功、身份拒绝与 MQTT 传输约束。 */
class RawUplinkIngestionServiceTests {

    /** 设备身份解析替身。 */
    private DeviceAccessScopeService scopeService;
    /** Kafka 生产模板替身。 */
    private KafkaTemplate<String, Object> kafkaTemplate;
    /** 单设备限流替身。 */
    private DeviceUplinkRateLimiter rateLimiter;
    /** 有效租户策略提供者替身。 */
    private EffectiveQuotaPolicyProvider quotaPolicyProvider;
    /** 验证策略降级可观测性的进程内指标注册表。 */
    private SimpleMeterRegistry meterRegistry;
    /** 被测用例。 */
    private RawUplinkIngestionService service;

    /** 每个用例使用全新的替身，避免 verify 调用计数串场。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        scopeService  =  mock(DeviceAccessScopeService.class);
        kafkaTemplate  =  mock(KafkaTemplate.class);
        rateLimiter  =  mock(DeviceUplinkRateLimiter.class);
        quotaPolicyProvider  =  mock(EffectiveQuotaPolicyProvider.class);
        meterRegistry  =  new SimpleMeterRegistry();
        when(rateLimiter.tryAcquire(any(), any(), any())).thenReturn(true);
        service  =  new RawUplinkIngestionService(scopeService, kafkaTemplate, rateLimiter, quotaPolicyProvider,
                new QuotaRuntimeMetrics(meterRegistry));
    }

    /** 合法事件应以解析后的 deviceId 为 key，并保留原始载荷与 Broker 时间。 */
    @Test
    void publishesResolvedRawEnvelopeWithDeviceKey() throws Exception {
        UUID tenantId  =  UUID.randomUUID();
        UUID projectId  =  UUID.randomUUID();
        UUID deviceId  =  UUID.randomUUID();
        long publishedAt  =  Instant.parse("2026-08-05T08:00:00Z").toEpochMilli();
        when(scopeService.resolve("project_1","device_1"))
                .thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenantId, projectId, deviceId)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId))
                .thenReturn(EffectiveQuotaPolicy.safeDefault(tenantId));
        when(kafkaTemplate.send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC), eq(deviceId.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        boolean accepted  =  service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 1, false, publishedAt,"{\"x\":1}"));

        assertThat(accepted).isTrue();
        var captor  =  org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC),
                eq(deviceId.toString()), captor.capture());
        RawUplinkMessage message  =  (RawUplinkMessage) captor.getValue();
        assertThat(message.tenantId()).isEqualTo(tenantId);
        assertThat(message.projectId()).isEqualTo(projectId);
        assertThat(message.payload()).isEqualTo("{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(message.receivedAt()).isEqualTo(Instant.ofEpochMilli(publishedAt));
    }

    /** Broker旧代际原样入Kafka；错租户、项目或网关子设备身份均不许替换解析结果。 */
    @Test
    void preservesAuthenticatedGenerationAndRejectsScopeSubstitution() {
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), device = UUID.randomUUID();
        when(scopeService.resolve("project_1","device_1")).thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenant, project, device)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenant, project)).thenReturn(EffectiveQuotaPolicy.safeDefault(tenant));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var request = request("project_1/device_1","tc/v1/project_1/device_1/up/property/report", 1, false, 1234,"{}");
        var identity = new AuthenticatedDeviceIdentity(tenant, project, device, 3);
        for (var wrong:java.util.List.of(new AuthenticatedDeviceIdentity(UUID.randomUUID(), project, device, 3),
                new AuthenticatedDeviceIdentity(tenant, UUID.randomUUID(), device, 3),
                new AuthenticatedDeviceIdentity(tenant, project, UUID.randomUUID(), 3))) {
            assertThat(service.ingestHandoff(request, wrong)).isEqualTo(HandoffDisposition.PERMANENT_REJECT);
        }
        verifyNoInteractions(kafkaTemplate);
        assertThat(service.ingestHandoff(request, identity)).isEqualTo(HandoffDisposition.ACCEPTED);
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC), eq(device.toString()), captor.capture());
        assertThat(((RawUplinkMessage)captor.getValue()).authenticatedIdentity()).isEqualTo(identity);
    }

    /** 合法但尚未实现的上行类型也必须先进入 raw，后续消费者才能统一分派并留下 DLQ 证据。 */
    @Test
    void publishesUnsupportedMessageTypeToRawForConsumerDispatch() throws Exception {
        UUID deviceId  =  UUID.randomUUID();
        UUID tenantId  =  UUID.randomUUID();
        UUID projectId  =  UUID.randomUUID();
        when(scopeService.resolve("project_1","device_1")).thenReturn(Optional.of(
                new ResolvedDeviceAccessScope(tenantId, projectId, deviceId)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId))
                .thenReturn(EffectiveQuotaPolicy.safeDefault(tenantId));
        when(kafkaTemplate.send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC), eq(deviceId.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        boolean accepted  =  service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/event/alarm", 1, false, 1L,"{}"));

        assertThat(accepted).isTrue();
        var captor  =  org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC),
                eq(deviceId.toString()), captor.capture());
        assertThat(((RawUplinkMessage) captor.getValue()).topic())
                .isEqualTo("tc/v1/project_1/device_1/up/event/alarm");
    }

    /** Topic 与已认证 username 不一致时必须在查询设备前拒绝，防止跨设备注入。 */
    @Test
    void rejectsTopicIdentityMismatchBeforeScopeLookup() {
        boolean accepted  =  service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_2/up/property/report", 1, false, 1L,"{}"));

        assertThat(accepted).isFalse();
        verify(scopeService, never()).resolve(any(), any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    /** 缺少消息类型或含非法层级字符的通配命中不能进入 raw，避免把无类型 Topic 变成永久 DLQ 噪声。 */
    @Test
    void rejectsMalformedWildcardUplinkTopic() {
        assertThat(service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up", 1, false, 1L,"{}"))).isFalse();
        assertThat(service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/event/bad.type", 1, false, 1L,"{}"))).isFalse();

        verify(scopeService, never()).resolve(any(), any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    /** QoS 非 1、retained 或非法 Base64 都是不可重试的协议拒绝，不能写 Kafka。 */
    @Test
    void rejectsInvalidTransportMetadataAndPayload() {
        assertThat(service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 0, false, 1L,"{}"))).isFalse();
        assertThat(service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 1, true, 1L,"{}"))).isFalse();
        EmqxMessagePublishedRequest invalidBase64  =  new EmqxMessagePublishedRequest("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report","%%%", 1, false,"client-1", 1L);
        assertThat(service.ingest(invalidBase64)).isFalse();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    /** 超限消息在确权后静默丢弃，不进入 Kafka，也不以异常要求 EMQX 重试。 */
    @Test
    void dropsRateLimitedMessageBeforeKafkaWithoutThrowing() {
        UUID deviceId  =  UUID.randomUUID();
        UUID tenantId  =  UUID.randomUUID();
        UUID projectId  =  UUID.randomUUID();
        when(scopeService.resolve("project_1","device_1")).thenReturn(Optional.of(
                new ResolvedDeviceAccessScope(tenantId, projectId, deviceId)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId))
                .thenReturn(EffectiveQuotaPolicy.safeDefault(tenantId));
        when(rateLimiter.tryAcquire(eq(tenantId), eq(deviceId), any())).thenReturn(false);

        boolean accepted  =  service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 1, false, 1L,"{}"));

        assertThat(accepted).isFalse();
        verify(rateLimiter).tryAcquire(eq(tenantId), eq(deviceId), any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    /** 确权后的项目策略必须原样传给限流器，不能回退到旧的固定 10/20 常量。 */
    @Test
    void forwardsResolvedOwnerPolicyToRateLimiter() throws Exception {
        UUID tenantId  =  UUID.randomUUID();
        UUID projectId  =  UUID.randomUUID();
        UUID deviceId  =  UUID.randomUUID();
        EffectiveQuotaPolicy changedPolicy  =  new EffectiveQuotaPolicy(tenantId, UUID.randomUUID(), 2L, 3L,
                7L, 14L, 70L, 4_200L, 20L, 10L, 1_200L, 600L, 200L);
        when(scopeService.resolve("project_1","device_1"))
                .thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenantId, projectId, deviceId)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId)).thenReturn(changedPolicy);
        when(kafkaTemplate.send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC), eq(deviceId.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 1, false, 1L,"{}"))).isTrue();
        verify(rateLimiter).tryAcquire(tenantId, deviceId, changedPolicy);
    }

    /** 策略投影异常只降级到有限默认，必须留下 safe_default 指标而非静默掩盖控制面故障。 */
    @Test
    void recordsSafeDefaultWhenProjectPolicyCannotBeResolved() throws Exception {
        UUID tenantId  =  UUID.randomUUID();
        UUID projectId  =  UUID.randomUUID();
        UUID deviceId  =  UUID.randomUUID();
        when(scopeService.resolve("project_1","device_1"))
                .thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenantId, projectId, deviceId)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId))
                .thenThrow(new IllegalStateException("postgres down"));
        when(kafkaTemplate.send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC), eq(deviceId.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 1, false, 1L,"{}"))).isTrue();
        assertThat(meterRegistry.get(QuotaRuntimeMetrics.POLICY_CACHE)
                .tag("result","safe_default").counter().count()).isEqualTo(1D);
    }

    /** send() 同步阻塞也必须计入两秒总预算，不能只限制 Future.get 的后半段。 */
    @Test
    void countsSynchronousKafkaSendDelayAgainstTotalDeadline() {
        UUID tenantId  =  UUID.randomUUID();
        UUID projectId  =  UUID.randomUUID();
        UUID deviceId  =  UUID.randomUUID();
        when(scopeService.resolve("project_1","device_1"))
                .thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenantId, projectId, deviceId)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId))
                .thenReturn(EffectiveQuotaPolicy.safeDefault(tenantId));
        when(kafkaTemplate.send(eq(RawUplinkIngestionService.RAW_UPLINK_TOPIC), eq(deviceId.toString()), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(2_100L);
                    return new CompletableFuture<SendResult<String, Object>>();
                });

        long started  =  System.nanoTime();
        assertThatThrownBy(() -> service.ingestHandoff(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 1, false, 1L,"{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka 原始上行消息发送失败");
        assertThat(System.nanoTime() - started).isLessThan(java.time.Duration.ofSeconds(3).toNanos());
    }

    /** 设备确权二元组与项目事实不匹配时必须拒绝，不能把安全拒绝误当 PostgreSQL 故障降级放行。 */
    @Test
    void rejectsMismatchedTrustedDeviceProjectWithoutRateLimitOrKafka() {
        UUID tenantId  =  UUID.randomUUID();
        UUID projectId  =  UUID.randomUUID();
        UUID deviceId  =  UUID.randomUUID();
        when(scopeService.resolve("project_1","device_1"))
                .thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenantId, projectId, deviceId)));
        when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId))
                .thenThrow(new IllegalArgumentException("device project mismatch"));

        assertThat(service.ingest(request("project_1/device_1",
"tc/v1/project_1/device_1/up/property/report", 1, false, 1L,"{}"))).isFalse();
        verifyNoInteractions(rateLimiter, kafkaTemplate);
    }

    /** 构造带 Base64 原始载荷的 EMQX 回调。 */
    private static EmqxMessagePublishedRequest request(String username, String topic, int qos,
                                                        boolean retained, long timestamp, String payload) {
        return new EmqxMessagePublishedRequest(username, topic,
                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)),
                qos, retained,"client-1", timestamp);
    }
}
