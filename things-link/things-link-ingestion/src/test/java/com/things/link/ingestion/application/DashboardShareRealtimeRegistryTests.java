package com.things.link.ingestion.application;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 分享同核隔离与失败关闭反例，真实Upgrade/Redis由Bootstrap独立证明。 */
class DashboardShareRealtimeRegistryTests {
    /** 测试真实租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 测试固定项目。 */ private final UUID project = UUID.randomUUID();
    /** 已签发能力。 */ private final UUID share = UUID.randomUUID();
    /** 有限设备。 */ private final UUID device = UUID.randomUUID();
    /** 可控制的权威端口。 */ private final DashboardRealtimeAccessService access = mock(DashboardRealtimeAccessService.class);
    /** 分享不得跳过的租约。 */ private final DashboardShareConnectionLease shareLeases = mock(DashboardShareConnectionLease.class);
    /** 原有租户池。 */ private final TenantConnectionLease leases = mock(TenantConnectionLease.class);
    /** 套餐服务。 */ private final EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
    /** 可显式不限的策略。 */ private final EffectiveQuotaPolicy policy = mock(EffectiveQuotaPolicy.class);
    /** 每测试唯一发送核心。 */ private DashboardRealtimeRegistry registry;

    /** 最多2物理连接，租户默认限额10，分享续租正常。 */
    @BeforeEach
    void setup() {
        when(access.requireShareDevices(any(), any(), any()))
                .thenAnswer(call -> call.getArgument(0));
        when(policy.tenantId()).thenReturn(tenant);
        when(policy.websocketConnectionLimit()).thenReturn(10L);
        when(policies.resolveTrustedDeviceProject(tenant, project)).thenReturn(policy);
        when(leases.create(eq(tenant), anyString())).thenAnswer(call -> new TenantConnectionLease.ConnectionLease(tenant, call.getArgument(1)));
        when(leases.acquire(any(), eq(10L))).thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
        when(leases.renew(any())).thenReturn(TenantConnectionLease.RenewDecision.RENEWED);
        when(shareLeases.renew(any())).thenReturn(true);
        registry = new DashboardRealtimeRegistry(access,
                new RealtimeWebSocketProperties(2, 1, 2, 200, 1, 400, 16, 32768, 2000),
                leases, policies, new RealtimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), shareLeases);
    }

    /** 释放有限执行器和物理会话。 */
    @AfterEach
    void cleanup() { registry.shutdown(); }

    /** 首个订阅不接受客户端项目轴，能力不占账号字段。 */
    @Test
    void derivesProjectWithoutImpersonatingAccountAndSendsAck() throws Exception {
        Connection connection = connect();
        assertThat(connection.principal().subjectId()).isNull();
        registry.receive(connection.id(), subscribe());
        assertThat(connection.frames.poll(2, TimeUnit.SECONDS)).contains("SUBSCRIBED");
        verify(shareLeases, timeout(2000).atLeastOnce()).renew(connection.shareLease());
    }

    /** 分享采用与Schema/REST相同的正式属性键，不拒绝已签发的连字符或数字开头键。 */
    @Test
    void shareAcceptsCanonicalPropertyKeysAndInvalidatesThem() throws Exception {
        Connection connection = connect();
        registry.receive(connection.id(), JsonMapper.builder().build().writeValueAsString(Map.of(
                "type", "SUBSCRIBE", "requestId", "canonical-keys", "devices", List.of(Map.of(
                "deviceId", device.toString(), "propertyKeys", List.of("phase-a", "1st"))))));
        assertThat(connection.frames.poll(2, TimeUnit.SECONDS)).contains("SUBSCRIBED");
        registry.invalidate(project, device, java.util.Set.of("phase-a", "1st"));
        var hint = JsonMapper.builder().build().readTree(connection.frames.poll(2, TimeUnit.SECONDS));
        assertThat(hint.get("type").stringValue()).isEqualTo("INVALIDATE");
        List<String> keys = new java.util.ArrayList<>();
        hint.get("devices").get(0).get("propertyKeys").forEach(key -> keys.add(key.stringValue()));
        assertThat(keys).containsExactlyInAnyOrder("phase-a", "1st");
    }

    /** 分享即使尚未发送SUBSCRIBE也先占租户名额，故障不能退回本机额度。 */
    @Test
    void unavailableTenantLeaseClosesBeforeSubscription() throws Exception {
        when(leases.acquire(any(), eq(10L))).thenReturn(TenantConnectionLease.LeaseDecision.UNAVAILABLE);
        Connection connection = connect();
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1011);
        assertThat(connection.frames).isEmpty();
        verify(shareLeases, timeout(2000)).release(connection.shareLease());
    }

    /** 租户显式不限也仍需分享租约，续期丢失不能发送ACK。 */
    @Test
    void unlimitedTenantCannotBypassShareLeaseFailure() throws Exception {
        when(policy.websocketConnectionLimit()).thenReturn(null);
        when(shareLeases.renew(any())).thenReturn(false);
        Connection connection = connect();
        registry.receive(connection.id(), subscribe());
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1011);
        assertThat(connection.frames).isEmpty();
    }

    /** 单帧超限使用分享规定1008，旧App/Console仍由已有测试守护。 */
    @Test
    void oversizedShareFrameClosesWithPolicyViolation() throws Exception {
        Connection connection = connect();
        registry.receive(connection.id(), "x".repeat(32769));
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    /** 分享两连接与每会话键预算独立于账号上限，不能把能力当作虚拟账号扣额。 */
    @Test
    void shareUsesShareAndProjectBudgetsInsteadOfAccountBudgets() throws Exception {
        Connection first = connect(); Connection second = connect();
        registry.receive(first.id(), subscribe().replace("\"temperature\"", "\"temperature\",\"humidity\""));
        registry.receive(second.id(), subscribe());
        assertThat(first.frames.poll(2, TimeUnit.SECONDS)).contains("SUBSCRIBED");
        assertThat(second.frames.poll(2, TimeUnit.SECONDS)).contains("SUBSCRIBED");
    }

    /** 静默复验挂起时，独立看门狗按最近成功授权十秒关闭而不是等待查库返回。 */
    @Test
    void stalledPeriodicAuthorizationCannotExtendTenSecondFreshness() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(access.requireShareDevices(any(), any(), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() >= 3) {
                entered.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return call.getArgument(0);
        });
        Connection connection = connect();
        try {
            registry.receive(connection.id(), subscribe());
            assertThat(connection.frames.poll(2, TimeUnit.SECONDS)).contains("SUBSCRIBED");
            assertThat(entered.await(7, TimeUnit.SECONDS)).isTrue();
            assertThat(connection.closed.poll(7, TimeUnit.SECONDS)).isEqualTo(1011);
            assertThat(connection.frames).isEmpty();
        } finally { release.countDown(); }
    }

    /** 本机准入拒绝也归还握手先取得的分享租约，避免占满直到TTL。 */
    @Test
    void rejectedRegistrationReleasesHandshakeLease() throws Exception {
        connect(); connect(); Connection rejected = connect();
        assertThat(rejected.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1013);
        verify(shareLeases, timeout(2000)).release(rejected.shareLease());
    }

    /** 使用实际能力类型，禁止以随机账号模拟匿名身份。 */
    private Connection connect() {
        Connection connection = new Connection(DashboardRealtimePrincipal.share(new DashboardSharePrincipal(
                share, tenant, project, UUID.randomUUID(), UUID.randomUUID(), 0,
                Instant.now().plusSeconds(60), "NONE", "a".repeat(64))));
        registry.register(connection); return connection;
    }

    /** 分享闭合控制帧只有三个字段，项目及精确版本都不由调用者指定。 */
    private String subscribe() {
        return JsonMapper.builder().build().writeValueAsString(Map.of("type", "SUBSCRIBE", "requestId", "share-test",
                "devices", List.of(Map.of("deviceId", device.toString(), "propertyKeys", List.of("temperature")))));
    }

    /** 有界传输替身，只有真实发送方法执行才写入帧队列。 */
    private static final class Connection implements DashboardRealtimeConnection {
        /** 容器生成标识。 */ private final String id = UUID.randomUUID().toString();
        /** 能力身份。 */ private final DashboardRealtimePrincipal principal;
        /** 握手所有的单租约。 */ private final DashboardShareConnectionLease.Lease lease;
        /** 有限接收帧。 */ private final BlockingQueue<String> frames = new LinkedBlockingQueue<>(4);
        /** 有限关闭回执。 */ private final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>(4);
        /** 固定可信分享与测试租约身份。 */
        Connection(DashboardRealtimePrincipal principal) {
            this.principal = principal;
            lease = new DashboardShareConnectionLease.Lease(principal.sharePrincipal().shareId(), id);
        }
        /** {@inheritDoc} */ @Override public String id() { return id; }
        /** {@inheritDoc} */ @Override public DashboardRealtimePrincipal principal() { return principal; }
        /** {@inheritDoc} */ @Override public DashboardShareConnectionLease.Lease shareLease() { return lease; }
        /** {@inheritDoc} */ @Override public void sendText(String payload) { frames.offer(payload); }
        /** {@inheritDoc} */ @Override public void close(int code, String reason) { closed.offer(code); }
    }
}
