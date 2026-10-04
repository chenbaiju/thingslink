package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SUBSCRIBE 必须每次复验身份范围、整体替换订阅，并且不泄露 WebSocket worker 的租户上下文。 */
class RealtimeSubscriptionServiceTests {

    /** 协议 JSON 工具。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个测试关闭 sender 虚拟线程。 */
    private RealtimeSubscriptionRegistry registry;

    /** 避免测试异常时 ThreadLocal 泄漏到后续断言。 */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (registry != null) {
            registry.shutdown();
        }
    }

    /** 成功订阅会调用项目成员与每台设备归属复验，并由服务端生成订阅 ID。 */
    @Test
    void reauthorizesEachSubscriptionAndClearsTenantContext() throws Exception {
        ProjectService projectService = mock(ProjectService.class);
        ProjectLifecycleAccessService lifecycleService = mock(ProjectLifecycleAccessService.class);
        when(lifecycleService.tokenSnapshot(any(), any()))
                .thenReturn(new ProjectAccessPolicy(true, true, 0L));
        DeviceIngestionService deviceService = mock(DeviceIngestionService.class);
        RealtimeAuthorizationService authorization = new RealtimeAuthorizationService(
                projectService, lifecycleService, deviceService);
        registry = newRegistry(authorization);
        RealtimeSubscriptionService service = new RealtimeSubscriptionService(objectMapper, registry, authorization);
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        TestConnection connection = new TestConnection("subscription-session", new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), projectId, Instant.parse("2030-01-01T00:00:00Z")));
        assertThat(registry.register(connection)).isTrue();
        clearInvocations(projectService, deviceService);

        service.handle(connection, """
                {"type":"SUBSCRIBE","requestId":"request-1","subscriptions":[
                {"deviceId":"%s","propertyKeys":["temperature","temperature"]}]}
                """.formatted(deviceId));

        String response = connection.awaitMessage();
        assertThat(response).contains("SUBSCRIBED", "request-1", "subscriptionId", "subscriptionCount\":1");
        verify(projectService).requireRoleInProject(projectId);
        verify(deviceService).requireDeviceOwner(projectId, deviceId);
        assertThat(TenantContext.current()).isEmpty();
    }

    /** 文本 PING 是零 JSON 依赖的轻量保活协议，必须返回相同格式的 PONG。 */
    @Test
    void repliesToTextPing() throws Exception {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        registry = newRegistry(authorization);
        TestConnection connection = new TestConnection("ping-session", new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Instant.parse("2030-01-01T00:00:00Z")));
        assertThat(registry.register(connection)).isTrue();
        RealtimeSubscriptionService service = new RealtimeSubscriptionService(objectMapper, registry, authorization);

        service.handle(connection, "PING");

        assertThat(connection.awaitMessage()).isEqualTo("PONG");
    }

    /** 服务端整体替换语义拒绝空请求，且错误类型不暴露内部鉴权或解析细节。 */
    @Test
    void rejectsEmptySubscriptionWithStableProtocolError() throws Exception {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        registry = newRegistry(authorization);
        TestConnection connection = new TestConnection("invalid-session", new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Instant.parse("2030-01-01T00:00:00Z")));
        assertThat(registry.register(connection)).isTrue();
        RealtimeSubscriptionService service = new RealtimeSubscriptionService(objectMapper, registry, authorization);

        service.handle(connection, "{\"type\":\"SUBSCRIBE\",\"requestId\":\"bad\",\"subscriptions\":[]}");

        assertThat(connection.awaitMessage()).contains("ERROR", "INVALID_SUBSCRIPTION");
    }

    /** 项目确定失权必须清空旧订阅、释放会话并1008关闭，不能退化为普通协议错误。 */
    @Test
    void revokesSessionAndOldSubscriptionsWhenProjectAccessIsLost() throws Exception {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        registry = newRegistry(authorization);
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), projectId, Instant.parse("2030-01-01T00:00:00Z"));
        TestConnection connection = new TestConnection("revoked-session", principal);
        assertThat(registry.register(connection)).isTrue();
        RealtimeSubscriptionService service = new RealtimeSubscriptionService(objectMapper, registry, authorization);
        service.handle(connection, subscribe("first", deviceId));
        assertThat(connection.awaitMessage()).contains("SUBSCRIBED");
        doThrow(new RealtimeProjectAccessDeniedException(new IllegalStateException("membership removed")))
                .when(authorization).requireProjectAccess(principal);

        service.handle(connection, subscribe("second", deviceId));

        assertThat(connection.closed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(connection.closeCode).isEqualTo(1008);
        assertThat(connection.closeReason).isEqualTo("realtime authorization revoked");
        assertThat(registry.connectionCount()).isZero();
        assertThat(connection.pollMessage()).isNull();
    }

    /** 设备归属失败属于本次请求错误，必须保留此前合法订阅并继续接收其匹配属性。 */
    @Test
    void deviceAuthorizationFailureKeepsPreviousValidSubscription() throws Exception {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        registry = newRegistry(authorization);
        UUID projectId = Uuid7.generate();
        UUID validDevice = Uuid7.generate();
        UUID invalidDevice = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), projectId, Instant.parse("2030-01-01T00:00:00Z"));
        TestConnection connection = new TestConnection("device-error-session", principal);
        assertThat(registry.register(connection)).isTrue();
        RealtimeSubscriptionService service = new RealtimeSubscriptionService(objectMapper, registry, authorization);
        service.handle(connection, subscribe("valid", validDevice));
        assertThat(connection.awaitMessage()).contains("SUBSCRIBED");
        doThrow(new IllegalArgumentException("device not found"))
                .when(authorization).requireSubscriptionAccess(principal, Set.of(invalidDevice));

        service.handle(connection, subscribe("invalid", invalidDevice));
        assertThat(connection.awaitMessage()).contains("ERROR", "INVALID_SUBSCRIPTION");
        registry.fanout(batch(projectId, validDevice, "26.5"));

        assertThat(connection.awaitMessage()).contains("PROPERTY_BATCH", "temperature", "26.5");
        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    /** 创建符合 S4-4 默认上限的注册表。 */
    private RealtimeSubscriptionRegistry newRegistry(RealtimeAuthorizationService authorization) {
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(any(UUID.class)))
                .thenAnswer(invocation -> EffectiveQuotaPolicy.safeDefault(invocation.getArgument(0)));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        when(leases.create(any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> new TenantConnectionLease.ConnectionLease(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(leases.acquire(any(), any())).thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
        when(leases.renew(any())).thenReturn(TenantConnectionLease.RenewDecision.RENEWED);
        return new RealtimeSubscriptionRegistry(objectMapper, new RealtimeWebSocketProperties(1000, 5, 200,
                200, 500, 10_000, 256, 32 * 1024, 5_000), new RealtimeMetrics(new SimpleMeterRegistry()),
                policies, leases, authorization);
    }

    /** 构造一条单设备温度订阅。 */
    private static String subscribe(String requestId, UUID deviceId) {
        return """
                {"type":"SUBSCRIBE","requestId":"%s","subscriptions":[
                {"deviceId":"%s","propertyKeys":["temperature"]}]}
                """.formatted(requestId, deviceId);
    }

    /** 构造已提交的单属性增量，验证旧订阅是否仍真实生效。 */
    private RealtimePropertyBatch batch(UUID projectId, UUID deviceId, String value) {
        return new RealtimePropertyBatch(projectId, deviceId, Instant.parse("2026-09-04T00:00:00Z"), 1,
                Uuid7.generate(), "1.0.0", Map.of("temperature", "NUMBER"),
                Map.of("temperature", objectMapper.readTree(value)));
    }

    /** 用并发安全队列观察异步 sender 的连接替身。 */
    private static final class TestConnection implements RealtimeConnection {

        /** 本机会话 ID。 */
        private final String id;
        /** 已认证身份。 */
        private final RealtimePrincipal principal;
        /** sender 写入的协议帧。 */
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        /** 关闭动作的确定同步点。 */
        private final CountDownLatch closed = new CountDownLatch(1);
        /** 关闭码。 */
        private volatile Integer closeCode;
        /** 关闭原因。 */
        private volatile String closeReason;

        /** @param id 会话 ID @param principal 已认证身份 */
        private TestConnection(String id, RealtimePrincipal principal) {
            this.id = id;
            this.principal = principal;
        }

        /** {@inheritDoc} */
        @Override
        public String id() {
            return id;
        }

        /** {@inheritDoc} */
        @Override
        public RealtimePrincipal principal() {
            return principal;
        }

        /** {@inheritDoc} */
        @Override
        public void sendText(String payload) {
            messages.add(payload);
        }

        /** {@inheritDoc} */
        @Override
        public void close(int statusCode, String reason) {
            closeCode = statusCode;
            closeReason = reason;
            closed.countDown();
        }

        /** @return 两秒内抵达的协议帧 */
        private String awaitMessage() throws InterruptedException {
            return messages.poll(2, TimeUnit.SECONDS);
        }

        /** @return 短观察期内的下一帧；用于证明安全撤权未追加普通错误。 */
        private String pollMessage() throws InterruptedException {
            return messages.poll(200, TimeUnit.MILLISECONDS);
        }
    }
}
