package com.things.link.dashboard.application;

import com.things.link.alarm.application.AlarmDeviceQueryService;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.device.application.DeviceRuntimeCatalogService;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceCatalogItem;
import com.things.link.device.application.RuntimeDeviceCurrentResult;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.telemetry.application.AppTelemetryDataPlaneService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 匿名编排最低层反例：持续确权先于数据、跨分享游标拒绝、模型漂移和原始依赖失败。 */
class DashboardShareDataServiceTests {
    /** 固定数据库时刻，不能用机器时间掩盖历史锚点边界。 */
    private final Instant now = Instant.parse("2026-09-07T12:00:00Z");
    /** 正常能力身份；不伪造Console账号。 */
    private final DashboardSharePrincipal principal = principal(UUID.randomUUID());
    /** 有限模型和候选的单设备身份。 */
    private final UUID model = UUID.randomUUID();
    /** 请求只能选择此有限候选。 */
    private final UUID device = UUID.randomUUID();
    /** 同事务复验端口独立mock，计划校验另有真实Schema反例测试。 */
    private final DashboardShareRuntimeService runtime = mock(DashboardShareRuntimeService.class);
    /** 编排只验证调用顺序，不复制计划实现。 */
    private final DashboardSharePlanValidator plans = mock(DashboardSharePlanValidator.class);
    /** 数据端口不得在确权/计划拒绝后触发。 */
    private final DeviceRuntimeDataService devices = mock(DeviceRuntimeDataService.class);
    /** 有限候选目录端口。 */
    private final DeviceRuntimeCatalogService catalog = mock(DeviceRuntimeCatalogService.class);
    /** 历史端口需在当前模型可读后才触发。 */
    private final AppTelemetryDataPlaneService telemetry = mock(AppTelemetryDataPlaneService.class);
    /** 告警端口与历史同样受完整设备前置约束。 */
    private final AlarmDeviceQueryService alarms = mock(AlarmDeviceQueryService.class);
    /** 签名使用真实codec，测试不能靠mock默认空游标“验证”身份隔离。 */
    private final DashboardShareDataService service = new DashboardShareDataService(runtime, plans, devices, catalog,
            telemetry, alarms, new SignedQueryCursorCodec("share-query-unit-only-secret-0123456789"));

    /** 被撤销的能力在共享HTTP外层之后仍须逐用例复验，不能沿用之前principal。 */
    @Test
    void revokedCapabilityNeverReachesPlanOrData() {
        when(runtime.read(principal)).thenThrow(new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND));
        assertCode(() -> service.currentValues(principal, requested()), 60053);
        verifyNoInteractions(plans, devices, catalog, telemetry, alarms);
    }

    /** 变量绑定范围拒绝发生在真实数据探测之前，不返回部分空数据。 */
    @Test
    void forbiddenBindingNeverReachesDeviceLookup() {
        var context = context(principal);
        when(runtime.read(principal)).thenReturn(context);
        doThrow(new BusinessException(DashboardErrorCode.SHARE_SCOPE_FORBIDDEN)).when(plans).requireCurrent(context, requested());
        assertCode(() -> service.currentValues(principal, requested()), 60054);
        verifyNoInteractions(devices, catalog, telemetry, alarms);
    }

    /** 返回范围外身份是内部合同损坏，不能转成合法匿名结果或截掉后声称成功。 */
    @Test
    void unexpectedReturnedDeviceFailsClosed() {
        when(runtime.read(principal)).thenReturn(context(principal));
        when(devices.queryCurrentValues(principal.projectId(), requested())).thenReturn(new RuntimeDeviceCurrentResult(List.of(
                new RuntimeDeviceCurrentResult.DeviceValues(UUID.randomUUID(), RuntimeDeviceAvailability.Status.NOT_AVAILABLE, List.of()))));
        assertCode(() -> service.currentValues(principal, requested()), 60055);
    }

    /** 数据库异常保留首因并503，不能伪装成scope外或无当前值。 */
    @Test
    void dependencyFailureRetainsItsCause() {
        var cause = new IllegalStateException("private-driver-cause");
        when(runtime.read(principal)).thenReturn(context(principal));
        when(devices.queryCurrentValues(principal.projectId(), requested())).thenThrow(cause);
        assertThatThrownBy(() -> service.currentValues(principal, requested())).isInstanceOfSatisfying(BusinessException.class,
                failure -> {
                    assertThat(failure.errorCode().code()).isEqualTo(60055);
                    assertThat(failure.getCause()).isSameAs(cause);
                    assertThat(failure.getMessage()).doesNotContain("private-driver-cause");
                });
    }

    /** 当前模型失配或设备删除时整段历史不进入旧版本数据，不能按Schema曾声明就继续读取。 */
    @Test
    void historyRequiresCurrentlyAvailableMatchingDevice() {
        var context = context(principal);
        var query = List.of(new RuntimeDeviceQuery(device, model, List.of()));
        when(runtime.read(principal)).thenReturn(context);
        when(plans.requireHistory(context, device, model, "temperature", "LAST_1_HOUR", now, "RAW", "AVG"))
                .thenReturn(now.minusSeconds(3600));
        when(devices.inspect(principal.projectId(), query)).thenReturn(List.of(new RuntimeDeviceAvailability(
                device, RuntimeDeviceAvailability.Status.NOT_AVAILABLE, null, null, null, null)));
        assertCode(() -> service.history(principal, device, "temperature", model, "LAST_1_HOUR", now, "RAW", "AVG"), 60053);
        when(devices.inspect(principal.projectId(), query)).thenReturn(List.of(new RuntimeDeviceAvailability(
                device, RuntimeDeviceAvailability.Status.MODEL_MISMATCH, UUID.randomUUID(), null, null, null)));
        assertCode(() -> service.history(principal, device, "temperature", model, "LAST_1_HOUR", now, "RAW", "AVG"), 10001);
        verifyNoInteractions(telemetry);
    }

    /** 初验成功后设备并发失效，遥测再次检查的通用未找到仍应变为匿名60053。 */
    @Test
    void historySecondOwnershipCheckKeepsShareUnavailableClassification() {
        var context = context(principal);
        when(runtime.read(principal)).thenReturn(context);
        when(plans.requireHistory(context, device, model, "temperature", "LAST_1_HOUR", now, "RAW", "AVG"))
                .thenReturn(now.minusSeconds(3600));
        when(devices.inspect(principal.projectId(), List.of(new RuntimeDeviceQuery(device, model, List.of()))))
                .thenReturn(List.of(new RuntimeDeviceAvailability(device, RuntimeDeviceAvailability.Status.AVAILABLE,
                        model, "设备", "ONLINE", now)));
        var firstCause = new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        when(telemetry.historyVersioned(principal.projectId(), device, "temperature", now.minusSeconds(3600), now, "RAW", "AVG"))
                .thenThrow(firstCause);
        assertThatThrownBy(() -> service.history(principal, device, "temperature", model, "LAST_1_HOUR", now, "RAW", "AVG"))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode().code()).isEqualTo(60053);
                    assertThat(failure.getCause()).isSameAs(firstCause);
                });
    }

    /** 游标绑定真实share及精确版本；换分享时在有限目录SQL前拒绝。 */
    @Test
    void catalogCursorCannotMoveToAnotherShare() {
        UUID secondDevice = UUID.randomUUID();
        var scope = new DashboardShareVariableScope("devices", model, List.of(device, secondDevice));
        var context = context(principal);
        when(runtime.read(principal)).thenReturn(context);
        when(plans.requireCatalog(context, "devices")).thenReturn(scope);
        when(catalog.find(eq(principal.projectId()), eq(model), eq(scope.deviceIds()), isNull(), isNull(), eq(2)))
                .thenReturn(List.of(new RuntimeDeviceCatalogItem(device, "甲", "ONLINE", model, now),
                        new RuntimeDeviceCatalogItem(secondDevice, "乙", "ONLINE", model, now.minusSeconds(1))));
        var first = service.catalog(principal, "devices", null, 1);
        assertThat(first.items()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        var other = new DashboardSharePrincipal(UUID.randomUUID(), principal.tenantId(), principal.projectId(), principal.dashboardId(),
                principal.dashboardVersionId(), principal.projectGeneration(), principal.expiresAt(), "NONE", "0".repeat(64));
        var otherContext = context(other);
        when(runtime.read(other)).thenReturn(otherContext);
        when(plans.requireCatalog(otherContext, "devices")).thenReturn(scope);
        assertCode(() -> service.catalog(other, "devices", first.nextCursor(), 1), 10001);
        verify(catalog).find(eq(principal.projectId()), eq(model), eq(scope.deviceIds()), isNull(), isNull(), eq(2));
        org.mockito.Mockito.verifyNoMoreInteractions(catalog);
    }

    /** 模拟同一数据库事务已经校验的观察，完整Schema解释由计划专测负责。 */
    private DashboardShareReadContext context(DashboardSharePrincipal identity) {
        var schema = new DashboardShareSchema(identity.dashboardId(), identity.dashboardVersionId(), 1, "tc.dashboard/v1",
                "PG_JSONB_TEXT_V1_SHA256", "1".repeat(64), List.of(), List.of(), JsonMapper.builder().build().createObjectNode());
        return new DashboardShareReadContext(identity, schema,
                List.of(new DashboardShareVariableScope("devices", model, List.of(device))), now);
    }

    /** 当前值请求保留准确模型及顶层键，不从模型并集推导授权。 */
    private List<RuntimeDeviceQuery> requested() { return List.of(new RuntimeDeviceQuery(device, model, List.of("temperature"))); }

    /** 固定能力事实无需任何actor account。 */
    private static DashboardSharePrincipal principal(UUID shareId) {
        return new DashboardSharePrincipal(shareId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, Instant.parse("2026-09-08T12:00:00Z"), "NONE", "0".repeat(64));
    }

    /** 只断言机器码与异常类型，不用易漂移文本掩盖权限分类。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
