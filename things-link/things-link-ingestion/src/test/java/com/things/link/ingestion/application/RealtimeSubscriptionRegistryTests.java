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
import com.things.link.shared.tenant.TenantScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 本机实时订阅索引必须在限额、替换和慢客户端场景下保持租户隔离。 */
class RealtimeSubscriptionRegistryTests {

    /** 测试使用的 JSON 工具，与注册表的序列化器保持一致。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个测试显式关闭虚拟 sender，避免在 JVM 退出时遗留等待线程。 */
    private RealtimeSubscriptionRegistry registry;

    /** 多实例扇出测试使用的第二个本机注册表。 */
    private RealtimeSubscriptionRegistry secondRegistry;

    /** 清理当前测试创建的异步注册表。 */
    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.shutdown();
        }
        if (secondRegistry != null) {
            secondRegistry.shutdown();
        }
    }

    /** 新 SUBSCRIBE 整体替换旧集合，fanout 只向替换后的精确属性键发送。 */
    @Test
    void replacesWholeSubscriptionSetAndFansOutOnlyMatchedProperties() throws Exception {
        registry = newRegistry(100, 100, 100, 10, 100, 100, 8);
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        TestConnection connection = new TestConnection("session-1", principal(projectId));
        assertThat(registry.register(connection)).isTrue();
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("temperature")));

        registry.fanout(batch(projectId, deviceId, Map.of("temperature", json("26"), "humidity", json("60"))));

        String temperature = connection.awaitMessage();
        assertThat(temperature).contains("PROPERTY_BATCH", "temperature", "26");
        assertThat(temperature).doesNotContain("humidity");

        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("humidity")));
        registry.fanout(batch(projectId, deviceId, Map.of("temperature", json("27"))));
        assertThat(connection.pollMessage()).isNull();
        registry.fanout(batch(projectId, deviceId, Map.of("humidity", json("61"))));
        assertThat(connection.awaitMessage()).contains("humidity", "61");
    }

    /** 全局连接阈值先于登记生效，拒绝连接必须以策略违规 1008 关闭且不污染计数。 */
    @Test
    void rejectsConnectionWhenGlobalLimitIsReached() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(1, 5, 200, 200, 500, 10_000, 256, meters);
        UUID projectId = Uuid7.generate();
        TestConnection accepted = new TestConnection("accepted", principal(projectId));
        TestConnection rejected = new TestConnection("rejected", principal(projectId));

        assertThat(registry.register(accepted)).isTrue();
        assertThat(registry.register(rejected)).isFalse();

        assertThat(rejected.closeCode).isEqualTo(1008);
        assertThat(registry.connectionCount()).isEqualTo(1);
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "global_connection")).isEqualTo(1);
    }

    /** 单账号连接阈值跨项目聚合，不能用切换项目绕过账号资源上限。 */
    @Test
    void rejectsConnectionWhenAccountLimitIsReached() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 1, 10, 10, 100, 100, 8, meters);
        UUID accountId = Uuid7.generate();
        TestConnection accepted = new TestConnection("account-accepted",
                principal(accountId, Uuid7.generate(), Uuid7.generate()));
        TestConnection rejected = new TestConnection("account-rejected",
                principal(accountId, Uuid7.generate(), Uuid7.generate()));

        assertThat(registry.register(accepted)).isTrue();
        assertThat(registry.register(rejected)).isFalse();

        assertThat(rejected.closeCode).isEqualTo(1008);
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "account_connection")).isEqualTo(1);
    }

    /** 单项目连接阈值跨账号聚合，避免同一项目消耗超出实例预算。 */
    @Test
    void rejectsConnectionWhenProjectLimitIsReached() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 10, 1, 10, 100, 100, 8, meters);
        UUID projectId = Uuid7.generate();
        TestConnection accepted = new TestConnection("project-accepted",
                principal(Uuid7.generate(), Uuid7.generate(), projectId));
        TestConnection rejected = new TestConnection("project-rejected",
                principal(Uuid7.generate(), Uuid7.generate(), projectId));

        assertThat(registry.register(accepted)).isTrue();
        assertThat(registry.register(rejected)).isFalse();

        assertThat(rejected.closeCode).isEqualTo(1008);
        assertThat(rejected.closeReason).isEqualTo("realtime connection limit");
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "project_connection")).isEqualTo(1);
    }

    /** 跨租户协作者必须消耗项目所有者租户额度，不能使用 JWT 中协作者自己的 tenantId。 */
    @Test
    void usesProjectOwnerTenantForSharedConnectionLease() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID collaboratorTenantId = Uuid7.generate();
        UUID ownerTenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenReturn(policy(ownerTenantId, 1L));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        TenantConnectionLease.ConnectionLease lease = new TenantConnectionLease.ConnectionLease(
                ownerTenantId, "node:session");
        when(leases.create(ownerTenantId, "cross-tenant")).thenReturn(lease);
        when(leases.acquire(lease, 1L)).thenReturn(TenantConnectionLease.LeaseDecision.REJECTED);
        registry = newRegistry(meters, policies, leases);
        TestConnection connection = new TestConnection("cross-tenant",
                principal(Uuid7.generate(), collaboratorTenantId, projectId));

        assertThat(registry.register(connection)).isFalse();

        assertThat(connection.closeCode).isEqualTo(1008);
        assertThat(connection.closeReason).isEqualTo("tenant connection quota exceeded");
        verify(policies).resolveTrustedProject(projectId);
        verify(leases).create(ownerTenantId, "cross-tenant");
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "tenant_connection")).isEqualTo(1);
        assertThat(resultCounter(meters, RealtimeMetrics.WEBSOCKET_QUOTA_DECISIONS, "rejected")).isEqualTo(1);
    }

    /** 权威策略显式 NULL 表示套餐不限，不占 Redis 租约但仍受本机固定项目上限约束。 */
    @Test
    void explicitUnlimitedSkipsRedisLeaseButKeepsLocalLimits() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID projectId = Uuid7.generate();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenReturn(policy(Uuid7.generate(), null));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        registry = new RealtimeSubscriptionRegistry(objectMapper, new RealtimeWebSocketProperties(10,
                10, 1, 10, 100, 100, 8, 32 * 1024, 5_000), new RealtimeMetrics(meters), policies, leases,
                availableAuthorization());

        assertThat(registry.register(new TestConnection("unlimited-first", principal(projectId)))).isTrue();
        TestConnection rejectedByLocalLimit = new TestConnection("unlimited-second", principal(projectId));
        assertThat(registry.register(rejectedByLocalLimit)).isFalse();

        verify(leases, never()).create(any(), any());
        assertThat(rejectedByLocalLimit.closeCode).isEqualTo(1008);
        assertThat(resultCounter(meters, RealtimeMetrics.WEBSOCKET_QUOTA_DECISIONS, "unlimited")).isEqualTo(1);
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "project_connection")).isEqualTo(1);
    }

    /** 套餐 0 明确表示禁用 WebSocket，新连接以 1008 拒绝且无需访问 Redis。 */
    @Test
    void zeroPolicyLimitRejectsWithoutCreatingRedisLease() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID projectId = Uuid7.generate();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenReturn(policy(Uuid7.generate(), 0L));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        registry = newRegistry(meters, policies, leases);
        TestConnection connection = new TestConnection("disabled", principal(projectId));

        assertThat(registry.register(connection)).isFalse();

        assertThat(connection.closeCode).isEqualTo(1008);
        assertThat(connection.closeReason).isEqualTo("tenant connection quota exceeded");
        verify(leases, never()).create(any(), any());
        assertThat(resultCounter(meters, RealtimeMetrics.WEBSOCKET_QUOTA_DECISIONS, "rejected")).isEqualTo(1);
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "tenant_connection")).isEqualTo(1);
    }

    /** 策略依赖故障时不猜 owner tenant、不建租约，以本机有限上限继续服务并记录安全默认。 */
    @Test
    void providerFailureUsesFiniteLocalSafeDefaultWithoutGuessingTenant() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID projectId = Uuid7.generate();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenThrow(new IllegalStateException("database unavailable"));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        registry = new RealtimeSubscriptionRegistry(objectMapper, new RealtimeWebSocketProperties(10,
                10, 1, 10, 100, 100, 8, 32 * 1024, 5_000), new RealtimeMetrics(meters), policies, leases,
                availableAuthorization());

        TestConnection accepted = new TestConnection("safe-default-first", principal(projectId));
        TestConnection rejectedByLocalLimit = new TestConnection("safe-default-second", principal(projectId));
        assertThat(registry.register(accepted)).isTrue();
        assertThat(registry.register(rejectedByLocalLimit)).isFalse();

        verify(leases, never()).create(any(), any());
        assertThat(rejectedByLocalLimit.closeCode).isEqualTo(1008);
        assertThat(resultCounter(meters, RealtimeMetrics.WEBSOCKET_QUOTA_DECISIONS, "safe_default")).isEqualTo(1);
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "project_connection")).isEqualTo(1);
    }

    /** Redis 申请故障只降低为本机有限上限；注销仍尽力释放可能已执行但响应丢失的租约。 */
    @Test
    void redisLeaseFailureFailsOpenWithinLocalLimitsAndReleasesOnClose() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID ownerTenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenReturn(policy(ownerTenantId, 1L));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        TenantConnectionLease.ConnectionLease lease = new TenantConnectionLease.ConnectionLease(
                ownerTenantId, "node:fail-open");
        when(leases.create(ownerTenantId, "fail-open")).thenReturn(lease);
        when(leases.acquire(lease, 1L)).thenReturn(TenantConnectionLease.LeaseDecision.UNAVAILABLE);
        registry = newRegistry(meters, policies, leases);
        TestConnection connection = new TestConnection("fail-open", principal(projectId));

        assertThat(registry.register(connection)).isTrue();
        registry.unregister(connection.id());

        verify(leases).release(lease);
        assertThat(resultCounter(meters, RealtimeMetrics.WEBSOCKET_QUOTA_DECISIONS, "fail_open")).isEqualTo(1);
    }

    /** 丢失租约不能由心跳无条件复活，也不能因此踢掉已经建立的浏览器连接。 */
    @Test
    void lostLeaseIsObservedWithoutClosingExistingConnection() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID ownerTenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenReturn(policy(ownerTenantId, 1L));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        TenantConnectionLease.ConnectionLease lease = new TenantConnectionLease.ConnectionLease(
                ownerTenantId, "node:lost");
        when(leases.create(ownerTenantId, "lost")).thenReturn(lease);
        when(leases.acquire(lease, 1L)).thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
        when(leases.renew(lease)).thenReturn(TenantConnectionLease.RenewDecision.LOST);
        registry = newRegistry(meters, policies, leases);
        TestConnection connection = new TestConnection("lost", principal(projectId));
        assertThat(registry.register(connection)).isTrue();

        registry.renewTenantLeases();

        assertThat(registry.connectionCount()).isEqualTo(1);
        assertThat(connection.closeCode).isNull();
        assertThat(resultCounter(meters, RealtimeMetrics.WEBSOCKET_QUOTA_DECISIONS, "lease_lost")).isEqualTo(1);
    }

    /** 进程优雅关闭必须逐一释放租约，避免重启后等待 TTL 才恢复套餐名额。 */
    @Test
    void shutdownReleasesEveryRegisteredTenantLease() {
        UUID ownerTenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenReturn(policy(ownerTenantId, 2L));
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        when(leases.create(any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> new TenantConnectionLease.ConnectionLease(
                        invocation.getArgument(0), "node:" + invocation.getArgument(1)));
        when(leases.acquire(any(), any())).thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
        registry = newRegistry(new SimpleMeterRegistry(), policies, leases);
        TestConnection first = new TestConnection("shutdown-first", principal(projectId));
        TestConnection second = new TestConnection("shutdown-second", principal(projectId));
        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isTrue();

        registry.shutdown();

        verify(leases).release(new TenantConnectionLease.ConnectionLease(ownerTenantId, "node:shutdown-first"));
        verify(leases).release(new TenantConnectionLease.ConnectionLease(ownerTenantId, "node:shutdown-second"));
        assertThat(registry.connectionCount()).isZero();
    }

    /** 会话、账号与项目订阅数分别核算，任何一层超限都不能污染已有集合和指标。 */
    @Test
    void enforcesAllSubscriptionLimitLayers() {
        assertSessionSubscriptionLimit();
        assertAccountSubscriptionLimit();
        assertProjectSubscriptionLimit();
    }

    /** 注销必须一次释放连接、订阅和队列资源，使同身份的新会话可立即复用配额。 */
    @Test
    void releasesQuotasAndActiveGaugesOnUnregister() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(1, 1, 1, 1, 1, 1, 8, meters);
        UUID accountId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        RealtimePrincipal principal = principal(accountId, Uuid7.generate(), projectId);
        TestConnection first = new TestConnection("first", principal);
        assertThat(registry.register(first)).isTrue();
        registry.replaceSubscriptions(first.id(), Map.of(deviceId, Set.of("temperature")));
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_ACTIVE_SESSIONS)).isEqualTo(1);
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_ACTIVE_SUBSCRIPTIONS)).isEqualTo(1);

        registry.unregister(first.id());

        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_ACTIVE_SESSIONS)).isZero();
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_ACTIVE_SUBSCRIPTIONS)).isZero();
        TestConnection replacement = new TestConnection("replacement", principal);
        assertThat(registry.register(replacement)).isTrue();
        registry.replaceSubscriptions(replacement.id(), Map.of(deviceId, Set.of("temperature")));
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_ACTIVE_SESSIONS)).isEqualTo(1);
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_ACTIVE_SUBSCRIPTIONS)).isEqualTo(1);
    }

    /** 队列先按设备加属性合并；新键仍超过上限时仅关闭该慢会话为 1013。 */
    @Test
    void closesOnlySlowSessionWhenCoalescingCannotKeepQueueBounded() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 10, 10, 10, 100, 100, 2, meters);
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        BlockingConnection connection = new BlockingConnection("slow", principal(projectId));
        assertThat(registry.register(connection)).isTrue();
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("p1", "p2", "p3", "p4")));

        registry.fanout(batch(projectId, deviceId, Map.of("p1", json("1"))));
        assertThat(connection.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
        registry.fanout(batch(projectId, deviceId, Map.of("p2", json("2"))));
        registry.fanout(batch(projectId, deviceId, Map.of("p3", json("3"))));
        registry.fanout(batch(projectId, deviceId, Map.of("p4", json("4"))));

        assertThat(connection.closeCode).isEqualTo(1013);
        assertThat(registry.connectionCount()).isZero();
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_QUEUE_DROPPED)).isEqualTo(1);
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_QUEUE_DEPTH)).isZero();
        connection.releaseSend.countDown();
    }

    /** 同一 device + property 积压只保留最新事实，队列深度不增长且合并指标递增。 */
    @Test
    void coalescesSamePropertyAndReportsBoundedQueueMetrics() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 10, 10, 10, 100, 100, 2, meters);
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        BlockingConnection connection = new BlockingConnection("coalescing", principal(projectId));
        assertThat(registry.register(connection)).isTrue();
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("temperature")));

        registry.fanout(batch(projectId, deviceId, Map.of("temperature", json("1"))));
        assertThat(connection.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
        registry.fanout(batch(projectId, deviceId, Map.of("temperature", json("2"))));
        registry.fanout(batch(projectId, deviceId, Map.of("temperature", json("3"))));

        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_QUEUE_DEPTH)).isEqualTo(1);
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_QUEUE_MERGED)).isEqualTo(1);
        connection.releaseSend.countDown();
        assertThat(connection.awaitMessage()).contains("temperature", "1");
        assertThat(connection.awaitMessage()).contains("temperature", "3");
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_QUEUE_DEPTH)).isZero();
    }

    /** 底层发送异常只清理故障会话，并同时释放 Gauge 与递增失败 Counter。 */
    @Test
    void recordsSendFailureAndReleasesFailedSession() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 10, 10, 10, 100, 100, 8, meters);
        FailingConnection connection = new FailingConnection("send-failure", principal(Uuid7.generate()));
        assertThat(registry.register(connection)).isTrue();

        registry.sendControl(connection.id(), "PONG");

        assertThat(connection.failedClose.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(connection.closeCode).isEqualTo(1011);
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_SEND_FAILURE)).isEqualTo(1);
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_ACTIVE_SESSIONS)).isZero();
        assertThat(gauge(meters, RealtimeMetrics.WEBSOCKET_QUEUE_DEPTH)).isZero();
    }

    /** 同一项目 Redis 广播可驱动两个独立实例，而其他项目连接不会收到跨项目数据。 */
    @Test
    void fansOutAcrossIndependentInstanceRegistriesWithProjectIsolation() throws Exception {
        registry = newRegistry(10, 10, 10, 10, 100, 100, 8);
        secondRegistry = newRegistry(10, 10, 10, 10, 100, 100, 8);
        UUID projectId = Uuid7.generate();
        UUID otherProjectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        TestConnection first = new TestConnection("node-a", principal(projectId));
        TestConnection second = new TestConnection("node-b", principal(projectId));
        TestConnection isolated = new TestConnection("node-b-other", principal(otherProjectId));
        assertThat(registry.register(first)).isTrue();
        assertThat(secondRegistry.register(second)).isTrue();
        assertThat(secondRegistry.register(isolated)).isTrue();
        registry.replaceSubscriptions(first.id(), Map.of(deviceId, Set.of("temperature")));
        secondRegistry.replaceSubscriptions(second.id(), Map.of(deviceId, Set.of("temperature")));
        secondRegistry.replaceSubscriptions(isolated.id(), Map.of(deviceId, Set.of("temperature")));

        RealtimePropertyBatch update = batch(projectId, deviceId, Map.of("temperature", json("28")));
        registry.fanout(update);
        secondRegistry.fanout(update);

        assertThat(first.awaitMessage()).contains("temperature", "28");
        assertThat(second.awaitMessage()).contains("temperature", "28");
        assertThat(isolated.pollMessage()).isNull();
    }

    /**
     * 两个同账号、同项目且同代次的会话在一次维护中只查一次权威项目，避免按连接放大数据库读取。
     */
    @Test
    void groupsPeriodicAuthorizationByUniqueAccountAndProject() {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        registry = newRegistry(authorization, clock);
        UUID accountId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(
                accountId, Uuid7.generate(), projectId, clock.instant().plusSeconds(60));
        TestConnection first = new TestConnection("deduplicated-first", principal);
        TestConnection second = new TestConnection("deduplicated-second", principal);
        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isTrue();
        clearInvocations(authorization);

        clock.advance(Duration.ofSeconds(5));
        registry.maintainAuthorizationsAndLeases();

        verify(authorization, times(1)).requireProjectAccess(principal);
        assertThat(registry.connectionCount()).isEqualTo(2);
    }

    /** 删除恢复前后两代连接不得共用复核结果；旧代关闭而当前代续过原十秒截止。 */
    @Test
    void separatesPeriodicAuthorizationByProjectGeneration() {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        registry = newRegistry(authorization, clock);
        UUID accountId = Uuid7.generate();
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        RealtimePrincipal stale = new RealtimePrincipal(
                accountId, tenantId, projectId, 0L, clock.instant().plusSeconds(60));
        RealtimePrincipal current = new RealtimePrincipal(
                accountId, tenantId, projectId, 1L, clock.instant().plusSeconds(60));
        TestConnection staleConnection = new TestConnection("stale-generation", stale);
        TestConnection currentConnection = new TestConnection("current-generation", current);
        assertThat(registry.register(staleConnection)).isTrue();
        assertThat(registry.register(currentConnection)).isTrue();
        clearInvocations(authorization);
        doAnswer(invocation -> {
            RealtimePrincipal checked = invocation.getArgument(0);
            if (checked.projectGeneration() == 0L) {
                throw new RealtimeProjectAccessDeniedException(
                        new IllegalStateException("stale project generation"));
            }
            return null;
        }).when(authorization).requireProjectAccess(any());

        clock.advance(Duration.ofSeconds(5));
        registry.maintainAuthorizationsAndLeases();

        assertThat(staleConnection.closeCode).isEqualTo(1008);
        assertThat(staleConnection.closeReason).isEqualTo("realtime authorization revoked");
        assertThat(currentConnection.closeCode).isNull();
        assertThat(registry.connectionCount()).isEqualTo(1);

        // 当前代首轮成功已把截止从第10秒延至第15秒；第11秒仍能再次复核并继续保留。
        clock.advance(Duration.ofSeconds(6));
        registry.maintainAuthorizationsAndLeases();

        assertThat(currentConnection.closeCode).isNull();
        assertThat(registry.connectionCount()).isEqualTo(1);
        verify(authorization).requireProjectAccess(stale);
        verify(authorization, times(2)).requireProjectAccess(current);
    }

    /** 确定失权先清队列并1008关闭；已经进入底层sendText的唯一一帧允许完成。 */
    @Test
    void revocationDropsQueuedFramesButLetsOneInFlightFrameFinish() throws Exception {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        TenantConnectionLease leases = availableLeases();
        registry = newRegistry(authorization, clock, availablePolicies(), leases);
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), projectId, clock.instant().plusSeconds(60));
        BlockingConnection connection = new BlockingConnection("revoked-with-queue", principal);
        assertThat(registry.register(connection)).isTrue();
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("p1", "p2")));
        registry.fanout(batch(projectId, deviceId, Map.of("p1", json("1"))));
        assertThat(connection.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
        registry.fanout(batch(projectId, deviceId, Map.of("p2", json("2"))));
        doThrow(new RealtimeProjectAccessDeniedException(new IllegalStateException("revoked")))
                .when(authorization).requireProjectAccess(principal);

        clock.advance(Duration.ofSeconds(5));
        registry.maintainAuthorizationsAndLeases();
        connection.releaseSend.countDown();

        assertThat(connection.awaitMessage()).contains("p1", "1");
        assertThat(connection.pollMessage()).isNull();
        assertThat(connection.closeCode).isEqualTo(1008);
        assertThat(connection.closeReason).isEqualTo("realtime authorization revoked");
        assertThat(registry.connectionCount()).isZero();
        verify(leases).release(org.mockito.ArgumentMatchers.any());
    }

    /** 瞬时数据库故障保留原十秒截止，连续故障耗尽时1011关闭且恢复不会复活旧连接。 */
    @Test
    void authorizationDependencyFailureClosesOnlyAfterOriginalDeadlineExpires() {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        registry = newRegistry(authorization, clock);
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), clock.instant().plusSeconds(60));
        TestConnection connection = new TestConnection("authorization-unavailable", principal);
        assertThat(registry.register(connection)).isTrue();
        doThrow(new IllegalStateException("postgres unavailable"))
                .when(authorization).requireProjectAccess(principal);

        clock.advance(Duration.ofSeconds(5));
        registry.maintainAuthorizationsAndLeases();
        assertThat(registry.connectionCount()).isEqualTo(1);
        assertThat(connection.closeCode).isNull();

        clock.advance(Duration.ofSeconds(5));
        registry.maintainAuthorizationsAndLeases();
        assertThat(registry.connectionCount()).isZero();
        assertThat(connection.closeCode).isEqualTo(1011);
        assertThat(connection.closeReason).isEqualTo("realtime authorization unavailable");

        org.mockito.Mockito.reset(authorization);
        registry.maintainAuthorizationsAndLeases();
        assertThat(registry.connectionCount()).isZero();
    }

    /** 权威查询即使成功，返回时已越过原授权截止也必须1011收束，不能用迟到成功复活旧会话。 */
    @Test
    void delayedSuccessfulAuthorizationCannotExtendAnExpiredDeadline() {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        registry = newRegistry(authorization, clock);
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), clock.instant().plusSeconds(60));
        TestConnection connection = new TestConnection("authorization-returned-too-late", principal);
        assertThat(registry.register(connection)).isTrue();
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(11));
            return null;
        }).when(authorization).requireProjectAccess(principal);

        registry.maintainAuthorizationsAndLeases();

        assertThat(registry.connectionCount()).isZero();
        assertThat(connection.closeCode).isEqualTo(1011);
        assertThat(connection.closeReason).isEqualTo("realtime authorization unavailable");
        org.mockito.Mockito.reset(authorization);
        registry.refreshAuthorization(connection.id());
        registry.maintainAuthorizationsAndLeases();
        assertThat(registry.connectionCount()).isZero();
    }

    /** 登记中的权威项目策略查询不能越过JWT截止；返回时到期须1008拒绝且不留下本机会话。 */
    @Test
    void rejectsRegistrationWhenJwtExpiresDuringAuthoritativeQuotaResolution() {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        UUID projectId = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), projectId, clock.instant().plusSeconds(1));
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(1));
            return policy(Uuid7.generate(), null);
        });
        TenantConnectionLease leases = availableLeases();
        registry = newRegistry(authorization, clock, policies, leases);
        TestConnection connection = new TestConnection("expired-during-registration", principal);

        assertThat(registry.register(connection)).isFalse();

        assertThat(connection.closeCode).isEqualTo(1008);
        assertThat(connection.closeReason).isEqualTo("realtime token expired");
        assertThat(registry.connectionCount()).isZero();
        verify(leases, never()).acquire(any(), any());
    }

    /** PostgreSQL授权查询阻塞时，独立Redis租约维护入口仍可续期，不能共用数据库等待链。 */
    @Test
    void renewsTenantLeaseWhileAuthorizationQueryIsBlocked() throws Exception {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        TenantConnectionLease leases = availableLeases();
        registry = newRegistry(authorization, clock, availablePolicies(), leases);
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), clock.instant().plusSeconds(60));
        TestConnection connection = new TestConnection("authorization-blocks-not-lease", principal);
        assertThat(registry.register(connection)).isTrue();
        clearInvocations(leases);
        CountDownLatch authorizationEntered = new CountDownLatch(1);
        CountDownLatch releaseAuthorization = new CountDownLatch(1);
        doAnswer(invocation -> {
            authorizationEntered.countDown();
            if (!releaseAuthorization.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test authorization release timed out");
            }
            return null;
        }).when(authorization).requireProjectAccess(principal);
        clock.advance(Duration.ofSeconds(5));
        Thread authorizationMaintenance = Thread.ofVirtual()
                .name("blocked-realtime-authorization-test")
                .start(registry::maintainAuthorizationsAndLeases);

        try {
            assertThat(authorizationEntered.await(2, TimeUnit.SECONDS)).isTrue();

            registry.renewTenantLeases();

            verify(leases, times(1)).renew(any());
            assertThat(registry.connectionCount()).isEqualTo(1);
        } finally {
            releaseAuthorization.countDown();
            authorizationMaintenance.join(2_000);
        }
        assertThat(authorizationMaintenance.isAlive()).isFalse();
    }

    /** JWT到期先清未发送帧并1008关闭，已进入sendText的一帧仍可完成。 */
    @Test
    void jwtDeadlineDropsQueuedFramesButPreservesTheInFlightBoundary() throws Exception {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));
        registry = newRegistry(authorization, clock);
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(
                Uuid7.generate(), Uuid7.generate(), projectId, clock.instant().plusSeconds(5));
        BlockingConnection connection = new BlockingConnection("expired-with-queue", principal);
        assertThat(registry.register(connection)).isTrue();
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("p1", "p2")));
        registry.fanout(batch(projectId, deviceId, Map.of("p1", json("1"))));
        assertThat(connection.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
        registry.fanout(batch(projectId, deviceId, Map.of("p2", json("2"))));

        clock.advance(Duration.ofSeconds(5));
        registry.maintainAuthorizationsAndLeases();
        connection.releaseSend.countDown();

        assertThat(connection.awaitMessage()).contains("p1", "1");
        assertThat(connection.pollMessage()).isNull();
        assertThat(connection.closeCode).isEqualTo(1008);
        assertThat(connection.closeReason).isEqualTo("realtime token expired");
        // register必须二次权威复核一次；JWT到期维护应直接收束，不能再访问项目库。
        verify(authorization, times(1)).requireProjectAccess(principal);
    }

    /** 共享配额解析必须处于JWT显式范围，策略返回的owner tenant才可成为Redis租约键。 */
    @Test
    void resolvesSharedQuotaInsidePrincipalScopeAndLeasesOwnerTenant() {
        UUID accountId = Uuid7.generate();
        UUID collaboratorTenant = Uuid7.generate();
        UUID ownerTenant = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(accountId, collaboratorTenant, projectId,
                Instant.parse("2030-01-01T00:00:00Z"));
        AtomicReference<TenantScope> observed = new AtomicReference<>();
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(projectId)).thenAnswer(invocation -> {
            observed.set(TenantContext.current().orElseThrow());
            return policy(ownerTenant, 2L);
        });
        TenantConnectionLease leases = availableLeases();
        ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
        when(lifecycle.tokenSnapshot(accountId, projectId))
                .thenReturn(new ProjectAccessPolicy(true, true, 0L));
        RealtimeAuthorizationService authorization = new RealtimeAuthorizationService(
                mock(ProjectService.class), lifecycle, mock(DeviceIngestionService.class));
        registry = newRegistry(authorization, Clock.systemUTC(), policies, leases);

        assertThat(registry.register(new TestConnection("scoped-quota", principal))).isTrue();

        assertThat(observed.get()).isEqualTo(new TenantScope(collaboratorTenant, projectId, accountId));
        assertThat(TenantContext.current()).isEmpty();
        verify(leases).create(ownerTenant, "scoped-quota");
    }

    /** 创建带可测试小配额的注册表。 */
    private RealtimeSubscriptionRegistry newRegistry(int global, int accountConnections, int projectConnections,
                                                     int sessionSubscriptions, int accountSubscriptions,
                                                     int projectSubscriptions, int queueCapacity) {
        return newRegistry(global, accountConnections, projectConnections, sessionSubscriptions,
                accountSubscriptions, projectSubscriptions, queueCapacity, new SimpleMeterRegistry());
    }

    /** 创建注册到指定测试指标仓库的有界注册表。 */
    private RealtimeSubscriptionRegistry newRegistry(int global, int accountConnections, int projectConnections,
                                                     int sessionSubscriptions, int accountSubscriptions,
                                                     int projectSubscriptions, int queueCapacity,
                                                     SimpleMeterRegistry meters) {
        return new RealtimeSubscriptionRegistry(objectMapper, new RealtimeWebSocketProperties(global,
                accountConnections, projectConnections, sessionSubscriptions, accountSubscriptions,
                projectSubscriptions, queueCapacity, 32 * 1024, 5_000), new RealtimeMetrics(meters),
                availablePolicies(), availableLeases(), availableAuthorization());
    }

    /** 创建使用指定策略与租约替身的宽松本机注册表。 */
    private RealtimeSubscriptionRegistry newRegistry(SimpleMeterRegistry meters,
                                                     EffectiveQuotaPolicyProvider policies,
                                                     TenantConnectionLease leases) {
        return new RealtimeSubscriptionRegistry(objectMapper, new RealtimeWebSocketProperties(10,
                10, 10, 10, 100, 100, 8, 32 * 1024, 5_000), new RealtimeMetrics(meters), policies, leases,
                availableAuthorization());
    }

    /** 创建带可控授权端口与时钟的宽松注册表。 */
    private RealtimeSubscriptionRegistry newRegistry(RealtimeAuthorizationService authorization, Clock clock) {
        return newRegistry(authorization, clock, availablePolicies(), availableLeases());
    }

    /** 创建带可控授权、时钟、策略和租约的宽松注册表。 */
    private RealtimeSubscriptionRegistry newRegistry(RealtimeAuthorizationService authorization, Clock clock,
                                                     EffectiveQuotaPolicyProvider policies,
                                                     TenantConnectionLease leases) {
        if (mockingDetails(authorization).isMock()) {
            doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                    .when(authorization).inPrincipalScope(any(), any());
        }
        return new RealtimeSubscriptionRegistry(objectMapper, new RealtimeWebSocketProperties(10,
                10, 10, 10, 100, 100, 8, 32 * 1024, 5_000), new RealtimeMetrics(new SimpleMeterRegistry()),
                policies, leases, authorization, clock);
    }

    /** 构造只改变 WebSocket 租户并发上限的完整有效策略。 */
    private static EffectiveQuotaPolicy policy(UUID ownerTenantId, Long websocketConnectionLimit) {
        return new EffectiveQuotaPolicy(ownerTenantId, Uuid7.generate(), 1L, 1L,
                10L, 20L, 1_000L, 60_000L, 20L, 10L, 1_200L, 600L, websocketConnectionLimit);
    }

    /** @return 按项目返回有限安全默认的策略替身 */
    private static EffectiveQuotaPolicyProvider availablePolicies() {
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        when(policies.resolveTrustedProject(any(UUID.class)))
                .thenAnswer(invocation -> EffectiveQuotaPolicy.safeDefault(invocation.getArgument(0)));
        return policies;
    }

    /** @return 始终成功且不访问 Redis 的租约替身 */
    private static TenantConnectionLease availableLeases() {
        TenantConnectionLease leases = mock(TenantConnectionLease.class);
        when(leases.create(any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> new TenantConnectionLease.ConnectionLease(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(leases.acquire(any(), any())).thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
        when(leases.renew(any())).thenReturn(TenantConnectionLease.RenewDecision.RENEWED);
        return leases;
    }

    /** @return 默认通过全部权威项目复核的授权替身 */
    private static RealtimeAuthorizationService availableAuthorization() {
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                .when(authorization).inPrincipalScope(any(), any());
        return authorization;
    }

    /** 创建已认证且固定租户归属的本机身份。 */
    private static RealtimePrincipal principal(UUID projectId) {
        return new RealtimePrincipal(Uuid7.generate(), Uuid7.generate(), projectId,
                Instant.parse("2030-01-01T00:00:00Z"));
    }

    /** 创建指定账号、用户和项目的身份，用于跨层限额聚合测试。 */
    private static RealtimePrincipal principal(UUID accountId, UUID userId, UUID projectId) {
        return new RealtimePrincipal(accountId, userId, projectId, Instant.parse("2030-01-01T00:00:00Z"));
    }

    /** 验证单会话精确属性订阅限额和固定 scope 指标。 */
    private void assertSessionSubscriptionLimit() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 10, 10, 1, 100, 100, 8, meters);
        UUID projectId = Uuid7.generate();
        TestConnection connection = new TestConnection("session-limit", principal(projectId));
        assertThat(registry.register(connection)).isTrue();

        assertThatThrownBy(() -> registry.replaceSubscriptions(connection.id(),
                Map.of(Uuid7.generate(), Set.of("p1", "p2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("单会话属性订阅超过上限");
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "session_subscription")).isEqualTo(1);
        registry.shutdown();
    }

    /** 验证同账号多会话订阅聚合上限。 */
    private void assertAccountSubscriptionLimit() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 10, 10, 10, 1, 100, 8, meters);
        UUID accountId = Uuid7.generate();
        TestConnection first = new TestConnection("account-sub-first",
                principal(accountId, Uuid7.generate(), Uuid7.generate()));
        TestConnection second = new TestConnection("account-sub-second",
                principal(accountId, Uuid7.generate(), Uuid7.generate()));
        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isTrue();
        registry.replaceSubscriptions(first.id(), Map.of(Uuid7.generate(), Set.of("p1")));

        assertThatThrownBy(() -> registry.replaceSubscriptions(second.id(),
                Map.of(Uuid7.generate(), Set.of("p2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号属性订阅超过上限");
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "account_subscription")).isEqualTo(1);
        registry.shutdown();
    }

    /** 验证同项目跨账号订阅聚合上限。 */
    private void assertProjectSubscriptionLimit() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry = newRegistry(10, 10, 10, 10, 100, 1, 8, meters);
        UUID projectId = Uuid7.generate();
        TestConnection first = new TestConnection("project-sub-first",
                principal(Uuid7.generate(), Uuid7.generate(), projectId));
        TestConnection second = new TestConnection("project-sub-second",
                principal(Uuid7.generate(), Uuid7.generate(), projectId));
        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isTrue();
        registry.replaceSubscriptions(first.id(), Map.of(Uuid7.generate(), Set.of("p1")));

        assertThatThrownBy(() -> registry.replaceSubscriptions(second.id(),
                Map.of(Uuid7.generate(), Set.of("p2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("项目属性订阅超过上限");
        assertThat(counter(meters, RealtimeMetrics.WEBSOCKET_LIMIT_REJECTED, "project_subscription")).isEqualTo(1);
    }

    /** 序号超过浏览器安全整数后，已出队的高水位仍拒绝迟到和同序号变造。 */
    @Test
    void keepsAcceptedRevisionAfterDequeueAndPublishesPropertySource() throws Exception {
        registry = newRegistry(100, 100, 100, 10, 100, 100, 8);
        UUID projectId = Uuid7.generate(), deviceId = Uuid7.generate(), modelId = Uuid7.generate();
        TestConnection connection = new TestConnection("accepted-order", principal(projectId));
        registry.register(connection);
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("temperature")));
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 9007199254740993L, "2"));
        JsonNode frame = objectMapper.readTree(connection.awaitMessage());
        assertThat(frame.get("reportedRevisions").get("temperature").asString()).isEqualTo("9007199254740993");
        assertThat(frame.get("thingModelVersionIds").get("temperature").asString()).isEqualTo(modelId.toString());
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 9007199254740992L, "1"));
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 9007199254740993L, "999"));
        registry.fanout(batch(projectId, deviceId, Map.of("temperature", json("0"))));
        assertThat(connection.pollMessage()).isNull();
        registry.fanout(orderedBatch(projectId, deviceId, modelId, Long.MAX_VALUE, "3"));
        assertThat(connection.awaitMessage()).contains("9223372036854775807");
    }

    /** 待发合并与出队使用同一序号语义，替换订阅清掉旧待发而不伪造水位。 */
    @Test
    void rejectsOlderPendingValueAndClearsQueueOnSubscriptionReplacement() throws Exception {
        registry = newRegistry(100, 100, 100, 10, 100, 100, 8);
        UUID projectId = Uuid7.generate(), deviceId = Uuid7.generate(), modelId = Uuid7.generate();
        BlockingConnection connection = new BlockingConnection("ordered-pending", principal(projectId));
        registry.register(connection);
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("temperature")));
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 1L, "1"));
        assertThat(connection.sendEntered.await(1, TimeUnit.SECONDS)).isTrue();
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 3L, "3"));
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 2L, "2"));
        connection.releaseSend.countDown();
        assertThat(connection.awaitMessage()).contains("\"temperature\":1");
        assertThat(connection.awaitMessage()).contains("\"temperature\":3");
        assertThat(connection.pollMessage()).isNull();
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("humidity")));
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 4L, "4"));
        assertThat(connection.pollMessage()).isNull();
    }

    /** 首帧已交底层不能撤回，但换订阅必须清除尚在队列中的旧属性帧。 */
    @Test
    void replacingSubscriptionDiscardsPendingButDoesNotClaimToRecallInFlightFrame() throws Exception {
        registry = newRegistry(100, 100, 100, 10, 100, 100, 8);
        UUID projectId = Uuid7.generate(), deviceId = Uuid7.generate(), modelId = Uuid7.generate();
        BlockingConnection connection = new BlockingConnection("replacement-pending", principal(projectId));
        registry.register(connection);
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("temperature")));
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 1L, "1"));
        assertThat(connection.sendEntered.await(1, TimeUnit.SECONDS)).isTrue();
        registry.fanout(orderedBatch(projectId, deviceId, modelId, 2L, "2"));
        registry.replaceSubscriptions(connection.id(), Map.of(deviceId, Set.of("humidity")));
        connection.releaseSend.countDown();
        assertThat(connection.awaitMessage()).contains("\"temperature\":1");
        assertThat(connection.pollMessage()).isNull();
        registry.fanout(batch(projectId, deviceId, Map.of("humidity", json("30"))));
        assertThat(connection.awaitMessage()).contains("humidity");
    }

    /** 真实生产字段的定序测试增量，保持时间和desired版本相同。 */
    private RealtimePropertyBatch orderedBatch(UUID projectId, UUID deviceId, UUID modelId, long revision,
                                               String value) throws Exception {
        return new RealtimePropertyBatch(projectId, deviceId, Instant.parse("2026-09-12T00:00:00Z"), 0,
                modelId, "1.0.0", Map.of("temperature", "NUMBER"), Map.of("temperature", json(value)),
                Map.of("temperature", Long.toString(revision)));
    }

    /** 读取无标签 Counter，简化队列类指标断言。 */
    private static double counter(SimpleMeterRegistry meters, String name) {
        return meters.get(name).counter().count();
    }

    /** 读取固定 scope Counter，证明指标不会携带业务标识。 */
    private static double counter(SimpleMeterRegistry meters, String name, String scope) {
        return meters.get(name).tag("scope", scope).counter().count();
    }

    /** 读取固定 result 标签 Counter，租约判定不得携带任何业务 ID。 */
    private static double resultCounter(SimpleMeterRegistry meters, String name, String result) {
        return meters.get(name).tag("result", result).counter().count();
    }

    /** 读取本实例 Gauge 当前值。 */
    private static double gauge(SimpleMeterRegistry meters, String name) {
        return meters.get(name).gauge().value();
    }

    /** 创建与设备当前值提交等价的一组属性更新。 */
    private static RealtimePropertyBatch batch(UUID projectId, UUID deviceId, Map<String, JsonNode> properties) {
        Map<String, String> types = properties.keySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(key -> key, key -> "NUMBER"));
        return new RealtimePropertyBatch(projectId, deviceId, Instant.parse("2026-08-09T08:00:00Z"), 1,
                Uuid7.generate(), "1.0.0", types, properties);
    }

    /** 把数值 JSON 文本转为可写入 PROPERTY_BATCH 的节点。 */
    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    /** 记录异步发送结果的普通连接替身。 */
    private static class TestConnection implements RealtimeConnection {

        /** 本机会话 ID。 */
        private final String id;
        /** 已认证身份。 */
        private final RealtimePrincipal principal;
        /** sender 虚拟线程写入的协议帧。 */
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        /** 关闭码，未关闭时为 null。 */
        protected volatile Integer closeCode;
        /** 关闭原因，验证同为 1008 的认证、物理限额与租户额度不会被控制台混淆。 */
        protected volatile String closeReason;

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
        }

        /** @return 两秒内到达的协议帧 */
        protected String awaitMessage() throws InterruptedException {
            return messages.poll(2, TimeUnit.SECONDS);
        }

        /** @return 短暂等待内的协议帧，用于断言未订阅属性没有泄漏 */
        protected String pollMessage() throws InterruptedException {
            return messages.poll(200, TimeUnit.MILLISECONDS);
        }
    }

    /** 首帧发送时阻塞，稳定复现队列积压而不依赖线程调度偶然性。 */
    private static final class BlockingConnection extends TestConnection {

        /** sender 已开始底层写入的同步点。 */
        private final CountDownLatch sendEntered = new CountDownLatch(1);
        /** 测试主动解除底层写入阻塞的同步点。 */
        private final CountDownLatch releaseSend = new CountDownLatch(1);

        /** @param id 会话 ID @param principal 已认证身份 */
        private BlockingConnection(String id, RealtimePrincipal principal) {
            super(id, principal);
        }

        /** {@inheritDoc} */
        @Override
        public void sendText(String payload) {
            sendEntered.countDown();
            try {
                releaseSend.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            super.sendText(payload);
        }
    }

    /** 发送即抛错的连接替身，用于验证单会话故障隔离和失败指标。 */
    private static final class FailingConnection extends TestConnection {

        /** 注册表已按 1011 执行关闭的同步点。 */
        private final CountDownLatch failedClose = new CountDownLatch(1);

        /** @param id 会话 ID @param principal 已认证身份 */
        private FailingConnection(String id, RealtimePrincipal principal) {
            super(id, principal);
        }

        /** {@inheritDoc} */
        @Override
        public void sendText(String payload) {
            throw new IllegalStateException("test send failure");
        }

        /** {@inheritDoc} */
        @Override
        public void close(int statusCode, String reason) {
            super.close(statusCode, reason);
            failedClose.countDown();
        }
    }

    /** 可单调推进的UTC时钟，避免授权期测试依赖sleep。 */
    private static final class MutableClock extends Clock {
        /** 当前测试时刻。 */
        private Instant now;

        /** @param initial 初始UTC时刻 */
        private MutableClock(Instant initial) {
            now = initial;
        }

        /** 推进测试时间，不允许回拨掩盖截止。 */
        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        /** {@inheritDoc} */
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        /** {@inheritDoc} */
        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(getZone()) ? this : Clock.fixed(now, zone);
        }

        /** {@inheritDoc} */
        @Override
        public Instant instant() {
            return now;
        }
    }
}
