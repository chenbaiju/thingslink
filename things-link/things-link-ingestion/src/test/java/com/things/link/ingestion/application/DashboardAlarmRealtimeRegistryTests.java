package com.things.link.ingestion.application;

import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR0109：纯告警、双域闭集、配额和发送前撤权共享同一真实发送循环。 */
class DashboardAlarmRealtimeRegistryTests {
    /** 严格字段断言使用树读取实际发送帧。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 本测试租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 本测试项目。 */ private final UUID project = UUID.randomUUID();
    /** 唯一合法设备。 */ private final UUID device = UUID.randomUUID();
    /** 冻结模型。 */ private final UUID model = UUID.randomUUID();
    /** 可在ACK后模拟权限撤销的权威端口。 */ private final DashboardRealtimeAccessService access = mock(DashboardRealtimeAccessService.class);
    /** 测试后必须释放所有后台资源。 */ private DashboardRealtimeRegistry registry;

    /** 配额显式不限租户租约，但本机连接和双域200单位仍约束。 */
    @BeforeEach
    void setup() {
        when(access.requireDashboardRuntime(any(), any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        when(access.requireDevices(any(), any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        EffectiveQuotaPolicyProvider policies = mock(EffectiveQuotaPolicyProvider.class);
        EffectiveQuotaPolicy policy = mock(EffectiveQuotaPolicy.class);
        when(policy.tenantId()).thenReturn(tenant);
        when(policy.websocketConnectionLimit()).thenReturn(null);
        when(policies.resolveTrustedDeviceProject(tenant, project)).thenReturn(policy);
        registry = new DashboardRealtimeRegistry(access, new RealtimeWebSocketProperties(8, 8, 8, 200, 400, 400, 16, 32768, 3000),
                mock(TenantConnectionLease.class), policies, new RealtimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(DashboardShareConnectionLease.class));
    }

    /** 不让测试遗留虚拟线程和会话影响其他切片。 */
    @AfterEach
    void shutdown() { registry.shutdown(); }

    /** 纯告警计入额度但ACK属性count为0，跨项目和迟到回调均不得发送。 */
    @Test
    void alarmOnlyReceivesCorrelatedAckAndScopedKeysWithoutValues() throws Exception {
        Connection connection = connect(true);
        registry.receive(connection.id(), frame(List.of(), List.of(alarm("a0", List.of(device)))));
        JsonNode ack = connection.next();
        assertThat(ack.propertyNames()).containsExactlyInAnyOrder("type", "requestId", "subscriptionId", "count", "alarmCount");
        assertThat(ack.get("count").intValue()).isZero(); assertThat(ack.get("alarmCount").intValue()).isEqualTo(1);
        registry.invalidateAlarms(UUID.randomUUID(), device);
        registry.invalidateAlarms(project, UUID.randomUUID());
        assertThat(connection.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
        registry.invalidateAlarms(project, device);
        JsonNode hint = connection.next();
        assertThat(hint.propertyNames()).containsExactlyInAnyOrder("type", "subscriptionId", "devices", "alarmQueryKeys");
        assertThat(hint.get("devices").isEmpty()).isTrue(); assertThat(hint.get("alarmQueryKeys").get(0).stringValue()).isEqualTo("a0");
        registry.unregister(connection.id()); assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1000);
        registry.invalidateAlarms(project, device); assertThat(connection.frames).isEmpty();
    }

    /** 两域在同一一秒提示窗口合并，不能让属性或告警独占另一套发送节流。 */
    @Test
    void mergesTwoDomainsAndReauthorizesBeforeSending() throws Exception {
        Connection connection = connect(true);
        registry.receive(connection.id(), frame(List.of(Map.of("deviceId", device, "propertyKeys", List.of("phase-a"))), List.of(alarm("a0", List.of(device)))));
        assertThat(connection.next().get("count").intValue()).isEqualTo(1);
        registry.invalidateAlarms(project, device); connection.next();
        registry.invalidate(project, device, Set.of("phase-a")); registry.invalidateAlarms(project, device);
        JsonNode merged = connection.next();
        assertThat(merged.get("devices").size()).isEqualTo(1); assertThat(merged.get("alarmQueryKeys").size()).isEqualTo(1);
        when(access.requireDashboardRuntime(any(), any(), any(), any())).thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        registry.invalidateAlarms(project, device); assertThat(connection.closed.poll(3, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(connection.frames).isEmpty();
    }

    /** 旧连接不能借新字段升级；空双域、重复组和重复设备不会被静默规范化。 */
    @Test
    void rejectsOldProtocolEmptyDomainsAndDuplicateSelections() throws Exception {
        List<String> invalid = List.of(frame(List.of(), List.of()), frame(List.of(), List.of(alarm("a", List.of(device)), alarm("a", List.of(device)))),
                frame(List.of(), List.of(alarm("a", List.of(device, device)))));
        for (String payload : invalid) {
            Connection connection = connect(true); registry.receive(connection.id(), payload);
            assertThat(connection.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
        }
        Connection old = connect(false); registry.receive(old.id(), frame(List.of(), List.of(alarm("a", List.of(device)))));
        assertThat(old.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    /** 共享设备依然按每组设备计费；合并设备上限也包含两域并集。 */
    @Test
    void rejectsCombinedCostAndUnionDeviceOverflow() throws Exception {
        List<UUID> devices = IntStream.range(0, 20).mapToObj(index -> UUID.randomUUID()).toList();
        List<Map<String, Object>> groups = IntStream.range(0, 10).mapToObj(index -> alarm("a" + index, devices)).toList();
        Connection boundary = connect(true); registry.receive(boundary.id(), frame(List.of(), groups));
        assertThat(boundary.next().get("alarmCount").intValue()).isEqualTo(10); registry.unregister(boundary.id());
        List<Map<String, Object>> over = new ArrayList<>(groups); over.add(alarm("extra", List.of(devices.getFirst())));
        Connection cost = connect(true); registry.receive(cost.id(), frame(List.of(), over)); assertThat(cost.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
        Connection union = connect(true); registry.receive(union.id(), frame(List.of(Map.of("deviceId", device, "propertyKeys", List.of("x"))), List.of(alarm("a", devices))));
        assertThat(union.closed.poll(2, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    /** 精确上下文独立于测试模型，实际资格由mock权威端口控制。 */
    private String frame(List<Map<String, Object>> devices, List<Map<String, Object>> alarms) {
        return JSON.writeValueAsString(Map.of("type", "SUBSCRIBE", "requestId", "request", "devices", devices, "alarms", alarms,
                "runtimeContext", Map.of("appKey", "app_" + "a".repeat(32), "applicationVersionId", UUID.randomUUID(), "publicationRevision", "1", "dashboardVersionId", UUID.randomUUID())));
    }

    /** 告警组沿REST三过滤与明确模型，不带游标或任意正文。 */
    private Map<String, Object> alarm(String key, List<UUID> devices) {
        return Map.of("queryKey", key, "devices", devices.stream().map(id -> Map.of("deviceId", id, "expectedModelVersionId", model)).toList(),
                "conditionStates", List.of("ACTIVE"), "ackStates", List.of("UNACKNOWLEDGED"), "severities", List.of("MAJOR"));
    }

    /** 端点标识由服务端构造，不读取客户端字段。 */
    private Connection connect(boolean v2) {
        Connection connection = new Connection(new DashboardRealtimePrincipal(true, UUID.randomUUID(), tenant, project, 0, Instant.now().plusSeconds(60)), v2);
        registry.register(connection); return connection;
    }

    /** 真实发送循环的有限队列观察器。 */
    private static final class Connection implements DashboardRealtimeConnection {
        /** 本地连接ID。 */ private final String id = UUID.randomUUID().toString();
        /** 握手身份。 */ private final DashboardRealtimePrincipal principal;
        /** 由端点冻结的版本。 */ private final boolean v2;
        /** 有界输出。 */ private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>(16);
        /** 有界关闭事件。 */ private final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>(4);
        /** 保存唯一身份与协议版本。 */ Connection(DashboardRealtimePrincipal principal, boolean v2) { this.principal = principal; this.v2 = v2; }
        /** {@inheritDoc} */ @Override public String id() { return id; }
        /** {@inheritDoc} */ @Override public DashboardRealtimePrincipal principal() { return principal; }
        /** {@inheritDoc} */ @Override public boolean dashboardV2() { return v2; }
        /** {@inheritDoc} */ @Override public void sendText(String payload) { assertThat(frames.offer(JSON.readTree(payload))).isTrue(); }
        /** {@inheritDoc} */ @Override public void close(int code, String reason) { closed.offer(code); }
        /** 不用固定睡眠推测发送者完成。 */ JsonNode next() throws InterruptedException {
            JsonNode value = frames.poll(3, TimeUnit.SECONDS); assertThat(value).isNotNull(); return value;
        }
    }
}
