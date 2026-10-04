package com.things.link.ingestion.application;

import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR0099同核实时提示的有限队列、完整确权、关联ACK与恢复边界反例。 */
class DashboardRealtimeRegistryTests {
    /** 帧仅在内存解析，不接入外部传输或缓存。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 确权租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 本次明确选择的项目。 */ private final UUID project = UUID.randomUUID();
    /** 有效订阅设备。 */ private final UUID device = UUID.randomUUID();
    /** 独立授权依赖。 */ private final DashboardRealtimeAccessService access = mock(DashboardRealtimeAccessService.class);
    /** 有限跨实例租约。 */ private final TenantConnectionLease leases = mock(TenantConnectionLease.class);
    /** 已认证项目对应的策略。 */ private final EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
    /** 每个测试均释放后台线程。 */ private DashboardRealtimeRegistry registry;

    /** 默认确权返回相同身份，Console首次选择项目时恢复到该项目。 */
    @BeforeEach
    void setup() {
        when(access.requireDevices(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(com.things.link.enduser.application.WebAppRuntimeContext.class))).thenAnswer(invocation -> {
            DashboardRealtimePrincipal principal = invocation.getArgument(0);
            return new DashboardRealtimePrincipal(principal.app(), principal.subjectId(), tenant,
                    invocation.getArgument(1), principal.projectGeneration(), principal.expiresAt());
        });
        EffectiveQuotaPolicy policy = mock(EffectiveQuotaPolicy.class);
        when(policy.tenantId()).thenReturn(tenant);
        when(policy.websocketConnectionLimit()).thenReturn(10L);
        when(policies.resolveTrustedDeviceProject(tenant, project)).thenReturn(policy);
        when(leases.create(eq(tenant), anyString())).thenAnswer(invocation ->
                new TenantConnectionLease.ConnectionLease(tenant, invocation.getArgument(1)));
        when(leases.acquire(any(), eq(10L))).thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
        registry = new DashboardRealtimeRegistry(access,
                new RealtimeWebSocketProperties(2, 2, 2, 200, 400, 400, 16, 32768, 3000), leases, policies, new RealtimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(DashboardShareConnectionLease.class));
    }

    /** 清理本测试注册项和执行器，避免不同测试间共享发送线程。 */
    @AfterEach
    void cleanup() {
        registry.shutdown();
    }

    /** App与Console共享关联ACK核心，但Console必须明确传入项目。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void appAndConsoleReceiveCorrelatedAcknowledgment(boolean app) throws Exception {
        Connection connection = connect(app);
        registry.receive(connection.id(), subscribe(app, Map.of(device, List.of("temperature", "humidity"))));
        JsonNode ack = connection.next().json();
        assertThat(ack.get("type").stringValue()).isEqualTo("SUBSCRIBED");
        assertThat(ack.get("requestId").stringValue()).isEqualTo("request-one");
        assertThat(ack.get("count").intValue()).isEqualTo(2);
        assertThat(ack.get("subscriptionId").stringValue()).isNotBlank();
        registry.unregister(connection.id());
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1000);
    }

    /** 正式顶层属性键允许连字符和数字开头，旧双身份也必须保持与REST同一语法。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void appAndConsoleAcceptCanonicalPropertyKeysAndInvalidateThem(boolean app) throws Exception {
        Connection connection = connect(app);
        registry.receive(connection.id(), subscribe(app, Map.of(device, List.of("phase-a", "1st"))));
        assertThat(connection.next().json().get("count").intValue()).isEqualTo(2);
        registry.invalidate(project, device, Set.of("phase-a", "1st"));
        JsonNode hint = connection.next().json();
        assertThat(hint.get("type").stringValue()).isEqualTo("INVALIDATE");
        List<String> keys = new java.util.ArrayList<>();
        hint.get("devices").get(0).get("propertyKeys").forEach(key -> keys.add(key.stringValue()));
        assertThat(keys).containsExactlyInAnyOrder("phase-a", "1st");
        registry.unregister(connection.id());
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1000);
    }

    /** 重复订阅不得替换旧代次，已发送ACK之后仍立即关闭。 */
    @Test
    void repeatedSubscriptionClosesWithoutSecondAck() throws Exception {
        Connection connection = connect(true);
        String request = subscribe(true, Map.of(device, List.of("temperature")));
        registry.receive(connection.id(), request);
        connection.next();
        registry.receive(connection.id(), request);
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(connection.frames).isEmpty();
    }

    /** 独立协议反例每次重建夹具，不把前例关闭码误当物理槽位已归还。 */
    @ParameterizedTest
    @EnumSource(InvalidSubscription.class)
    void invalidBudgetsAndDuplicateKeysNeverAcknowledge(InvalidSubscription scenario) throws Exception {
        Map<UUID, List<String>> devices = new LinkedHashMap<>();
        switch (scenario) {
            case TOTAL_KEYS -> {
                for (int index = 0; index < 4; index++) devices.put(UUID.randomUUID(), keys(50));
                devices.put(UUID.randomUUID(), List.of("extra"));
            }
            case DEVICE_COUNT -> {
                for (int index = 0; index < 21; index++) devices.put(UUID.randomUUID(), List.of("key"));
            }
            case DEVICE_KEYS -> devices.put(device, keys(51));
            case DUPLICATE_KEYS -> devices.put(device, List.of("key", "key"));
        }
        Connection connection = connect(true);
        registry.receive(connection.id(), subscribe(true, devices));
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(connection.frames).isEmpty();
    }

    /** 四种协议拒绝各自验收，连接总量上界由独立资源边界测试负责。 */
    private enum InvalidSubscription {
        /** 总键数超过200。 */ TOTAL_KEYS,
        /** 设备数超过20。 */ DEVICE_COUNT,
        /** 单设备键数超过50。 */ DEVICE_KEYS,
        /** 同设备重复键必须拒绝。 */ DUPLICATE_KEYS
    }

    /** App不能通过控制帧覆盖JWT项目，未知字段也不能成为隐式协议扩展。 */
    @Test
    void appCannotOverrideProjectOrAddFields() throws Exception {
        Connection connection = connect(true);
        registry.receive(connection.id(), subscribe(false, Map.of(device, List.of("temperature"))));
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(connection.frames).isEmpty();
    }

    /** 只有订阅的同项目键可见，提示不包含值、时间或shadowVersion。 */
    @Test
    void hintsContainOnlySubscribedKeysAndIgnoreOtherProjects() throws Exception {
        Connection connection = connect(true);
        registry.receive(connection.id(), subscribe(true, Map.of(device, List.of("temperature"))));
        JsonNode ack = connection.next().json();
        registry.invalidate(UUID.randomUUID(), device, Set.of("temperature"));
        registry.invalidate(project, UUID.randomUUID(), Set.of("temperature"));
        registry.invalidate(project, device, Set.of("secret"));
        assertThat(connection.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
        registry.invalidate(project, device, Set.of("temperature", "secret"));
        JsonNode hint = connection.next().json();
        assertThat(hint.propertyNames()).containsExactlyInAnyOrder("type", "subscriptionId", "devices");
        assertThat(hint.get("type").stringValue()).isEqualTo("INVALIDATE");
        assertThat(hint.get("subscriptionId")).isEqualTo(ack.get("subscriptionId"));
        JsonNode item = hint.get("devices").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("deviceId", "propertyKeys");
        assertThat(item.get("deviceId").stringValue()).isEqualTo(device.toString());
        assertThat(item.get("propertyKeys").size()).isEqualTo(1);
        assertThat(item.get("propertyKeys").get(0).stringValue()).isEqualTo("temperature");
    }

    /** 首帧之后同一秒的重复提示合并，不能靠热点回调突破发送频率。 */
    @Test
    void repeatedInvalidationsMergeAtOneSecondBoundary() throws Exception {
        Connection connection = connect(true);
        registry.receive(connection.id(), subscribe(true, Map.of(device, List.of("temperature", "humidity"))));
        connection.next();
        registry.invalidate(project, device, Set.of("temperature"));
        Frame first = connection.next();
        for (int index = 0; index < 50; index++) registry.invalidate(project, device, Set.of("temperature", "humidity"));
        Frame second = connection.next();
        assertThat(second.sentNanos() - first.sentNanos()).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
        assertThat(second.json().get("devices").get(0).get("propertyKeys").size()).isEqualTo(2);
        assertThat(connection.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
    }

    /** ACK后失权在实际发送前检测，不发送任何提示，也不静默删除单台设备。 */
    @Test
    void revocationBeforePhysicalSendClosesWithoutHint() throws Exception {
        AtomicBoolean revoked = new AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (revoked.get()) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            return invocation.getArgument(0);
        }).when(access).requireDevices(any(), any(), any(),
                org.mockito.ArgumentMatchers.nullable(com.things.link.enduser.application.WebAppRuntimeContext.class));
        Connection connection = connect(true);
        registry.receive(connection.id(), subscribe(true, Map.of(device, List.of("temperature"))));
        connection.next();
        revoked.set(true);
        registry.invalidate(project, device, Set.of("temperature"));
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(connection.frames).isEmpty();
        verify(leases, timeout(2000)).release(new TenantConnectionLease.ConnectionLease(tenant, "dashboard:" + connection.id()));
    }

    /** 关闭索引之后的迟到Redis及容器回调无效，租约只释放一次。 */
    @Test
    void closedConnectionIgnoresLateCallbacksAndReleasesLease() throws Exception {
        Connection connection = connect(true);
        registry.receive(connection.id(), subscribe(true, Map.of(device, List.of("temperature"))));
        connection.next();
        registry.unregister(connection.id());
        assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1000);
        registry.invalidate(project, device, Set.of("temperature"));
        registry.receive(connection.id(), subscribe(true, Map.of(device, List.of("temperature"))));
        registry.unregister(connection.id());
        assertThat(connection.frames).isEmpty();
        verify(leases, timeout(2000)).release(new TenantConnectionLease.ConnectionLease(tenant, "dashboard:" + connection.id()));
    }

    /** Redis明确拒绝配额时关闭连接，不能发ACK。 */
    @Test
    void rejectedLeaseDoesNotAcknowledgeSubscription() throws Exception {
        when(leases.acquire(any(), eq(10L))).thenReturn(TenantConnectionLease.LeaseDecision.REJECTED);
        Connection rejected = connect(true);
        registry.receive(rejected.id(), subscribe(true, Map.of(device, List.of("temperature"))));
        assertThat(rejected.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1013);
        assertThat(rejected.frames).isEmpty();
    }

    /** Redis不可用时仍有本机上界；独立夹具不依赖另一场景的异步关闭归还槽位。 */
    @Test
    void unavailableLeaseKeepsLocalConnectionBound() throws Exception {
        when(leases.acquire(any(), eq(10L))).thenReturn(TenantConnectionLease.LeaseDecision.UNAVAILABLE);
        for (int index = 0; index < 2; index++) {
            Connection connection = connect(true);
            registry.receive(connection.id(), subscribe(true, Map.of(device, List.of("temperature"))));
            assertThat(connection.next().json().get("type").stringValue()).isEqualTo("SUBSCRIBED");
        }
        Connection excess = connect(true);
        assertThat(excess.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1013);
        assertThat(excess.frames).isEmpty();
    }

    /** 显式不限不建立或续租Redis占位，仍必须完成身份和设备确权。 */
    @Test
    void unlimitedPolicyDoesNotCreateRedisLease() throws Exception {
        when(policies.resolveTrustedDeviceProject(tenant, project).websocketConnectionLimit()).thenReturn(null);
        Connection connection = connect(true);
        registry.receive(connection.id(), subscribe(true, Map.of(device, List.of("temperature"))));
        assertThat(connection.next().json().path("type").asString()).isEqualTo("SUBSCRIBED");
        org.mockito.Mockito.verifyNoInteractions(leases);
    }

    /** 注册有明确过期时间的独立连接，Console握手暂不指定项目。 */
    private Connection connect(boolean app) {
        Connection connection = new Connection(new DashboardRealtimePrincipal(app, UUID.randomUUID(), tenant,
                app ? project : null, 0, Instant.now().plusSeconds(60)));
        registry.register(connection);
        return connection;
    }

    /** 由类型化字段生成精确协议正文，不把测试转义错误混入安全反例。 */
    private String subscribe(boolean app, Map<UUID, List<String>> devices) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "SUBSCRIBE");
        frame.put("requestId", "request-one");
        if (!app) frame.put("projectId", project.toString());
        else frame.put("runtimeContext", Map.of("appKey", "app_" + "a".repeat(32),
                "applicationVersionId", UUID.randomUUID().toString(), "publicationRevision", "1",
                "dashboardVersionId", UUID.randomUUID().toString()));
        List<Map<String, Object>> items = new ArrayList<>();
        devices.forEach((id, keys) -> items.add(Map.of("deviceId", id.toString(), "propertyKeys", keys)));
        frame.put("devices", items);
        return JSON.writeValueAsString(frame);
    }

    /** 生成合法且互异的属性键，专门触发数量而非语法边界。 */
    private static List<String> keys(int count) {
        return IntStream.range(0, count).mapToObj(index -> "key_" + index).toList();
    }

    /** @param json 实际发送帧 @param sentNanos 单调时间，仅测试发送间隔 */
    private record Frame(JsonNode json, long sentNanos) { }

    /** 使用有限等待队列观察真实后台发送，不用固定sleep猜执行顺序。 */
    private static final class Connection implements DashboardRealtimeConnection {
        /** 容器生成会话身份。 */ private final String id = UUID.randomUUID().toString();
        /** 握手冻结身份。 */ private final DashboardRealtimePrincipal principal;
        /** 实际到达的帧，测试数量本身有界。 */ private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>(16);
        /** 实际到达的关闭码。 */ private final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>(4);
        /** 固定握手身份供连接生命周期使用。 */
        Connection(DashboardRealtimePrincipal principal) { this.principal = principal; }
        /** {@inheritDoc} */
        @Override public String id() { return id; }
        /** {@inheritDoc} */
        @Override public DashboardRealtimePrincipal principal() { return principal; }
        /** {@inheritDoc} */
        @Override public void sendText(String payload) {
            assertThat(frames.offer(new Frame(JSON.readTree(payload), System.nanoTime()))).isTrue();
        }
        /** {@inheritDoc} */
        @Override public void close(int code, String reason) { closed.offer(code); }
        /** 最多等待两秒，后台线程失效时明确失败。 */
        Frame next() throws InterruptedException {
            Frame frame = frames.poll(2, TimeUnit.SECONDS);
            assertThat(frame).as("后台应发送当前连接的有限帧").isNotNull();
            return frame;
        }
    }
}
