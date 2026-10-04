package com.things.link.ingestion.application;

import com.things.link.enduser.application.WebAppRuntimeContext;
import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 慢外部依赖不能靠关闭后重连绕过工作槽；时钟和JSON边界独立于正常提示旅程。 */
class DashboardRealtimeResourceBoundaryTests {
    /** 只负责生成有界合法Console订阅，不依赖App运行上下文夹具。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 授权阻塞的会话虽已关闭，原工作未返回时仍占唯一槽位。 */
    @Test
    void timedOutAuthorizationRetainsWorkerSlotUntilItReturns() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            doAnswer(invocation -> {
                        if (calls.incrementAndGet() == 3) {
                            entered.countDown();
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        }
                        return fixture.authorized(invocation.getArgument(0));
                    }).when(fixture.access).requireDevices(any(), any(), any(), nullable(WebAppRuntimeContext.class));
            try {
                Connection initial = fixture.connect(Instant.now().plusSeconds(60), null, null);
                fixture.registry.receive(initial.id(), fixture.subscribe());
                assertThat(initial.frames.poll(2, TimeUnit.SECONDS)).contains("SUBSCRIBED");
                fixture.registry.invalidate(fixture.project, fixture.device, Set.of("temperature"));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(initial.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1011);
                for (int index = 0; index < 4; index++) {
                    Connection reconnect = fixture.connect(Instant.now().plusSeconds(60), null, null);
                    fixture.registry.receive(reconnect.id(), fixture.subscribe());
                    assertThat(reconnect.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1013);
                    assertThat(reconnect.frames).isEmpty();
                }
                assertThat(calls).hasValue(3);
                assertThat(initial.frames).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    /** 即使底层发送不响应close，超时连接的工作槽也不能被下一条连接重新占用。 */
    @Test
    void timedOutTransportRetainsWorkerSlotUntilItReturns() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                Connection initial = fixture.connect(Instant.now().plusSeconds(60), entered, release);
                fixture.registry.receive(initial.id(), fixture.subscribe());
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(initial.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1011);
                Connection reconnect = fixture.connect(Instant.now().plusSeconds(60), null, null);
                fixture.registry.receive(reconnect.id(), fixture.subscribe());
                assertThat(reconnect.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1013);
                assertThat(reconnect.frames).isEmpty();
                assertThat(initial.frames).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    /** 物理关闭阻塞仍占总连接配额，新准入直接1013且不再查身份数据库。 */
    @Test
    void blockedPhysicalCloseRetainsAdmissionPermitBeforeIdentityLookup() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            CountDownLatch closeEntered = new CountDownLatch(1);
            CountDownLatch closeRelease = new CountDownLatch(1);
            Connection initial = fixture.connect(Instant.now().plusSeconds(60), null, null);
            try {
                initial.closeEntered = closeEntered;
                initial.closeRelease = closeRelease;
                fixture.registry.receive(initial.id(), fixture.subscribe());
                assertThat(initial.frames.poll(2, TimeUnit.SECONDS)).contains("SUBSCRIBED");
                fixture.registry.unregister(initial.id());
                assertThat(closeEntered.await(2, TimeUnit.SECONDS)).isTrue();
                for (int index = 0; index < 4; index++) {
                    Connection reconnect = fixture.connect(Instant.now().plusSeconds(60), null, null);
                    assertThat(reconnect.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1013);
                    verify(fixture.access, never()).requireIdentity(reconnect.principal());
                    assertThat(reconnect.frames).isEmpty();
                }
            } finally {
                closeRelease.countDown();
                // 测试方针§4.1：countDown只发出放行信号，不证明工作线程已退出await。
                // 先取得物理关闭回执，再由Fixture.shutdown中断发送池；否则成功测试仍会泄漏线程异常。
                assertThat(initial.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1000);
            }
        }
    }

    /** 语法破损、合法JSON后尾随另一个值和重复字段都必须在授权前拒绝。 */
    @Test
    void malformedJsonAndTrailingValuesNeverReachDeviceAuthorization() throws Exception {
        try (Fixture fixture = new Fixture(8)) {
            String valid = fixture.subscribe();
            for (String invalid : List.of("{", valid + " {}", valid + " null", valid + " true",
                    valid.substring(0, valid.length() - 1) + ",\"type\":\"SUBSCRIBE\"}")) {
                Connection connection = fixture.connect(Instant.now().plusSeconds(60), null, null);
                fixture.registry.receive(connection.id(), invalid);
                assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
                assertThat(connection.frames).isEmpty();
            }
            verify(fixture.access, never()).requireDevices(any(), any(), any(), nullable(WebAppRuntimeContext.class));
        }
    }

    /** 无SUBSCRIBE且没有事件的连接仍由截止看门狗关闭，不依赖下一条提示才发现过期。 */
    @Test
    void idleTokenExpiresWithoutAnyHintOrSubscription() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            Connection connection = fixture.connect(Instant.now().plusMillis(400), null, null);
            assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(connection.frames).isEmpty();
            verify(fixture.access, never()).requireDevices(any(), any(), any(), nullable(WebAppRuntimeContext.class));
        }
    }

    /** 每个测试独立装配核心和执行器，关闭时不把后台任务泄漏到其他测试。 */
    private static final class Fixture implements AutoCloseable {
        /** 项目真实租户。 */ private final UUID tenant = UUID.randomUUID();
        /** Console本次选中项目。 */ private final UUID project = UUID.randomUUID();
        /** 唯一请求设备。 */ private final UUID device = UUID.randomUUID();
        /** 可被门闩阻塞的授权端口。 */ private final DashboardRealtimeAccessService access = mock(DashboardRealtimeAccessService.class);
        /** 被测有限资源核心。 */ private final DashboardRealtimeRegistry registry;

        /** 150ms操作预算只用于稳定制造慢依赖，测试不修改生产配置。 */
        Fixture(int workers) {
            TenantConnectionLease leases = mock(TenantConnectionLease.class);
            EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
            EffectiveQuotaPolicy policy = mock(EffectiveQuotaPolicy.class);
            when(policy.tenantId()).thenReturn(tenant);
            when(policy.websocketConnectionLimit()).thenReturn(10L);
            when(policies.resolveTrustedDeviceProject(tenant, project)).thenReturn(policy);
            when(leases.create(eq(tenant), anyString())).thenAnswer(invocation ->
                    new TenantConnectionLease.ConnectionLease(tenant, invocation.getArgument(1)));
            when(leases.acquire(any(), eq(10L))).thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
            when(access.requireDevices(any(), any(), any(), nullable(WebAppRuntimeContext.class)))
                    .thenAnswer(invocation -> authorized(invocation.getArgument(0)));
            registry = new DashboardRealtimeRegistry(access,
                    new RealtimeWebSocketProperties(workers, workers, workers, 200, 2000, 2000, 16, 32768, 150),
                    leases, policies, new RealtimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(DashboardShareConnectionLease.class));
        }

        /** 返回已确定项目的身份，保留JWT截止。 */
        DashboardRealtimePrincipal authorized(DashboardRealtimePrincipal principal) {
            return new DashboardRealtimePrincipal(false, principal.subjectId(), tenant, project,
                    0L, principal.expiresAt());
        }

        /** 可选择阻塞物理发送，并让close保持独立可观察。 */
        Connection connect(Instant expiresAt, CountDownLatch entered, CountDownLatch release) {
            Connection connection = new Connection(new DashboardRealtimePrincipal(false, UUID.randomUUID(),
                    tenant, null, 0L, expiresAt), entered, release);
            registry.register(connection);
            return connection;
        }

        /** 合法订阅作为反例变化的固定基准。 */
        String subscribe() {
            return JSON.writeValueAsString(Map.of("type", "SUBSCRIBE", "requestId", "bounded",
                    "projectId", project.toString(), "devices",
                    List.of(Map.of("deviceId", device.toString(), "propertyKeys", List.of("temperature")))));
        }

        /** 测试finally先释放门闩，再销毁核心线程。 */
        @Override
        public void close() {
            registry.shutdown();
        }
    }

    /** 受控传输可模拟close无法立即中断已阻塞写入的现实边界。 */
    private static final class Connection implements DashboardRealtimeConnection {
        /** 稳定物理连接标识。 */ private final String id = UUID.randomUUID().toString();
        /** 握手声明。 */ private final DashboardRealtimePrincipal principal;
        /** 发送进入信号，仅慢发送测试设置。 */ private final CountDownLatch entered;
        /** 放行发送信号。 */ private final CountDownLatch release;
        /** 可选物理关闭进入信号，模拟发送线程已退出但close仍阻塞。 */
        private volatile CountDownLatch closeEntered;
        /** 只由测试finally释放，避免夹具永久阻塞。 */
        private volatile CountDownLatch closeRelease;
        /** 最多观察四个帧，避免测试夹具本身无界。 */ private final BlockingQueue<String> frames = new LinkedBlockingQueue<>(4);
        /** 允许观察重复关闭回调但不能无界堆积。 */ private final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>(4);

        /** 绑定身份与可选受控阻塞。 */
        Connection(DashboardRealtimePrincipal principal, CountDownLatch entered, CountDownLatch release) {
            this.principal = principal;
            this.entered = entered;
            this.release = release;
        }

        /** 物理连接标识供注册与取消使用。 */
        @Override public String id() { return id; }
        /** 已冻结身份供核心二次认证。 */
        @Override public DashboardRealtimePrincipal principal() { return principal; }

        /** 写入直到测试释放才结束，不能被关闭索引自动当成已回收线程。 */
        @Override
        public void sendText(String payload) throws Exception {
            if (entered != null) {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            frames.offer(payload);
        }

        /** 关闭只记录传输结果，故意不替底层写入释放门闩。 */
        @Override
        public void close(int code, String reason) {
            if (closeEntered != null) {
                closeEntered.countDown();
                try {
                    assertThat(closeRelease.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试物理关闭被意外中断", interrupted);
                }
            }
            closed.offer(code);
        }
    }
}
