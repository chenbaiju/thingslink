package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import com.things.link.device.application.RuntimeModelDescription;
import com.things.link.device.application.RuntimePropertyDescription;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 分享实时独立确权：先binding后设备模型定义、零属性值读取及撤权首因分类。 */
class DashboardShareRealtimeAccessServiceTests {
    /** 同一个冻结设备，所有失败只改变一项事实。 */
    private final UUID device = UUID.randomUUID();
    /** 冻结模型由服务端scope派生而非匿名消息提交。 */
    private final UUID model = UUID.randomUUID();
    /** 固定能力不承载Console/App账号。 */
    private final DashboardSharePrincipal principal = new DashboardSharePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.parse("2026-09-08T00:00:00Z"), "ANY", "a".repeat(64));
    /** 权威复验在此隔离，真实事务传播由Bootstrap验证。 */
    private final DashboardShareRuntimeService runtime = mock(DashboardShareRuntimeService.class);
    /** 模型公开端口不提供属性值给实时服务。 */
    private final DeviceRuntimeDataService devices = mock(DeviceRuntimeDataService.class);
    /** 使用真实逐变量计划，避免mock吞掉授权边界。 */
    private final DashboardShareRealtimeAccessService service = new DashboardShareRealtimeAccessService(runtime,
            new DashboardSharePlanValidator(), devices);

    /** 握手只复验精确版本，不能偷读设备当前属性来认定身份。 */
    @Test
    void handshakeRevalidatesVersionWithoutDeviceReads() {
        when(runtime.read(principal)).thenReturn(context());
        assertThat(service.requireIdentity(principal)).isSameAs(principal);
        verify(runtime).read(principal);
        verifyNoInteractions(devices);
    }

    /** 订阅查询属性定义即可完整证明，返回原身份且不提供属性值。 */
    @Test
    void validSubscriptionUsesSnapshotDefinitionsOnly() {
        when(runtime.read(principal)).thenReturn(context());
        when(devices.querySnapshots(eq(principal.projectId()), any(), any())).thenReturn(snapshot(RuntimeDeviceAvailability.Status.AVAILABLE));
        assertThat(service.requireSubscriptions(principal, Map.of(device, Set.of("temperature")))).isSameAs(principal);
        verify(devices, never()).queryCurrentValues(any(), any());
    }

    /** token撤销优先于任何范围推导与设备探测。 */
    @Test
    void revokedIdentityStopsBeforeDeviceLookup() {
        when(runtime.read(principal)).thenThrow(new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND));
        code(() -> service.requireSubscriptions(principal, Map.of(device, Set.of("temperature"))), 60053);
        verifyNoInteractions(devices);
    }

    /** 候选外设备或未绑定键不能通过模型相同绕过。 */
    @Test
    void scopeAndBindingDenialNeverProbeDevices() {
        when(runtime.read(principal)).thenReturn(context());
        code(() -> service.requireSubscriptions(principal, Map.of(UUID.randomUUID(), Set.of("temperature"))), 60054);
        code(() -> service.requireSubscriptions(principal, Map.of(device, Set.of("private"))), 60054);
        verifyNoInteractions(devices);
    }

    /** 属性值为空不参与授权；真实设备丢失和模型变更分别导致能力不可用与范围失权。 */
    @Test
    void currentDeviceAvailabilityIsMandatory() {
        when(runtime.read(principal)).thenReturn(context());
        when(devices.querySnapshots(eq(principal.projectId()), any(), any())).thenReturn(snapshot(RuntimeDeviceAvailability.Status.NOT_AVAILABLE));
        code(() -> service.requireSubscriptions(principal, Map.of(device, Set.of("temperature"))), 60053);
        when(devices.querySnapshots(eq(principal.projectId()), any(), any())).thenReturn(snapshot(RuntimeDeviceAvailability.Status.MODEL_MISMATCH));
        code(() -> service.requireSubscriptions(principal, Map.of(device, Set.of("temperature"))), 60054);
    }

    /** Schema声明但权威模型不存在的键不能继续发hint；异常保留作内部排查。 */
    @Test
    void missingAuthoritativePropertyIsDenied() {
        when(runtime.read(principal)).thenReturn(context());
        var cause = new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        when(devices.querySnapshots(eq(principal.projectId()), any(), any())).thenThrow(cause);
        assertThatThrownBy(() -> service.requireSubscriptions(principal, Map.of(device, Set.of("temperature"))))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_SCOPE_FORBIDDEN);
                    assertThat(failure.getCause()).isSameAs(cause);
                });
    }

    /** 数据库故障不能伪装为撤销，必须保留首因且固定公开消息。 */
    @Test
    void infrastructureFailurePreservesCauseWithoutMessageEcho() {
        when(runtime.read(principal)).thenReturn(context());
        var cause = new IllegalStateException("private-sql-driver");
        when(devices.querySnapshots(eq(principal.projectId()), any(), any())).thenThrow(cause);
        assertThatThrownBy(() -> service.requireSubscriptions(principal, Map.of(device, Set.of("temperature"))))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode().code()).isEqualTo(60055);
                    assertThat(failure.getCause()).isSameAs(cause);
                    assertThat(failure.getMessage()).doesNotContain("private-sql-driver");
                });
    }

    /** 缺失模型描述不能被设备AVAILABLE掩盖。 */
    @Test
    void incompleteSnapshotFailsClosed() {
        when(runtime.read(principal)).thenReturn(context());
        var good = snapshot(RuntimeDeviceAvailability.Status.AVAILABLE);
        when(devices.querySnapshots(eq(principal.projectId()), any(), any()))
                .thenReturn(new RuntimeDeviceSnapshotResult(good.devices(), List.of()));
        code(() -> service.requireSubscriptions(principal, Map.of(device, Set.of("temperature"))), 60055);
    }

    /** 独立纯Schema夹具固定一个实际CURRENT_VALUE声明。 */
    private DashboardShareReadContext context() {
        var schema = JsonMapper.builder().build().readTree("""
                {"models":[{"key":"m","versionId":"%s","digestAlgorithm":"PG","digest":"digest","profile":"profile"}],
                 "variables":[{"key":"sensor","type":"DEVICE_SINGLE","modelKey":"m"}],
                 "pages":[{"components":[{"bindings":{"value":{"source":"CURRENT_VALUE",
                    "device":{"variableKey":"sensor"},"propertyKey":"temperature"}}}]}]}
                """.formatted(model));
        return new DashboardShareReadContext(principal, new DashboardShareSchema(principal.dashboardId(),
                principal.dashboardVersionId(), 1, "1", "PG", "digest", List.of(), List.of(), schema),
                List.of(new DashboardShareVariableScope("sensor", model, List.of(device))), Instant.parse("2026-09-07T12:00:00Z"));
    }

    /** 只有AVAILABLE才提供模型定义，避免失败夹具凭空携带隐藏模型。 */
    private RuntimeDeviceSnapshotResult snapshot(RuntimeDeviceAvailability.Status status) {
        return new RuntimeDeviceSnapshotResult(List.of(new RuntimeDeviceAvailability(device, status,
                status == RuntimeDeviceAvailability.Status.NOT_AVAILABLE ? null : model,
                status == RuntimeDeviceAvailability.Status.AVAILABLE ? "温度设备" : null,
                status == RuntimeDeviceAvailability.Status.AVAILABLE ? "ONLINE" : null, null)),
                status != RuntimeDeviceAvailability.Status.AVAILABLE ? List.of() : List.of(new RuntimeModelDescription(
                        model, "PG", "digest", "profile", List.of(new RuntimePropertyDescription(
                        "temperature", "NUMBER", null, null, null, null, null, null)))));
    }

    /** 每项失权反例校验机器码，不能仅断言有异常。 */
    private static void code(Runnable action, int expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(expected));
    }
}
