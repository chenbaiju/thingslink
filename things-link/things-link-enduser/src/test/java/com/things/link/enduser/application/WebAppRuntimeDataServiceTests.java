package com.things.link.enduser.application;

import com.things.link.alarm.application.AlarmDeviceQueryService;
import com.things.link.dashboard.application.DashboardRuntimePlanValidator;
import com.things.link.dashboard.application.RuntimeDashboardSchema;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.enduser.domain.AppRuntimeDeviceCatalogItem;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.telemetry.application.AppTelemetryDataPlaneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 五路运行编排的指定绑定、失败优先级、游标代次绑定与跨域短路纯测试。 */
class WebAppRuntimeDataServiceTests {
    /** 可信租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 可信项目。 */ private final UUID project = UUID.randomUUID();
    /** App用户。 */ private final UUID user = UUID.randomUUID();
    /** 模型版本。 */ private final UUID model = UUID.randomUUID();
    /** 应用版本。 */ private final UUID applicationVersion = UUID.randomUUID();
    /** 看板版本。 */ private final UUID dashboardVersion = UUID.randomUUID();
    /** 固定运行上下文。 */ private final WebAppRuntimeContext context = new WebAppRuntimeContext(
            "app_" + "a".repeat(32), applicationVersion, 9, dashboardVersion);
    /** Schema确权服务。 */ private WebAppDashboardSchemaService schemas;
    /** 计划验证器。 */ private DashboardRuntimePlanValidator plans;
    /** 绑定仓储。 */ private AppUserDeviceRepository bindings;
    /** 设备端口。 */ private DeviceRuntimeDataService devices;
    /** 遥测端口。 */ private AppTelemetryDataPlaneService telemetry;
    /** 告警端口。 */ private AlarmDeviceQueryService alarms;
    /** 已授权Schema。 */ private RuntimeDashboardSchema schema;
    /** 被测编排。 */ private WebAppRuntimeDataService service;

    /** 建立全部可观察依赖；真实游标签名器用于验证身份绑定。 */
    @BeforeEach
    void setup() {
        schemas = mock(WebAppDashboardSchemaService.class);
        plans = mock(DashboardRuntimePlanValidator.class);
        bindings = mock(AppUserDeviceRepository.class);
        devices = mock(DeviceRuntimeDataService.class);
        telemetry = mock(AppTelemetryDataPlaneService.class);
        alarms = mock(AlarmDeviceQueryService.class);
        schema = mock(RuntimeDashboardSchema.class);
        service = new WebAppRuntimeDataService(schemas, plans, bindings, devices, telemetry, alarms,
                new SignedQueryCursorCodec("test-runtime-cursor-secret-32-bytes-long"));
        when(schemas.schema(eq(tenant), eq(project), eq(user), anyLong(), eq(context.appKey()),
                eq(applicationVersion), eq(9L), eq(dashboardVersion))).thenReturn(schema);
    }

    /** 快照先Schema计划后指定绑定，只把有效绑定设备传给device并按原位置补不可见。 */
    @Test
    void snapshotsNeverSendUnboundDeviceToDevicePort() {
        UUID bound = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        RuntimeDeviceQuery first = new RuntimeDeviceQuery(bound, model, List.of("temperature"));
        RuntimeDeviceQuery second = new RuntimeDeviceQuery(hidden, model, List.of("temperature"));
        RuntimeModelReference reference = new RuntimeModelReference(
                model, "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64), "TC_PROPERTY_COMPOSITE_V1");
        when(bindings.findActiveDeviceIds(tenant, project, user, Set.of(bound, hidden))).thenReturn(Set.of(bound));
        RuntimeDeviceAvailability available = new RuntimeDeviceAvailability(
                bound, RuntimeDeviceAvailability.Status.AVAILABLE, model, "泵站", "ONLINE", null);
        when(devices.querySnapshots(project, List.of(first), List.of(reference)))
                .thenReturn(new RuntimeDeviceSnapshotResult(List.of(available), List.of()));

        RuntimeDeviceSnapshotResult result = service.snapshots(identity(3), context,
                List.of(reference), List.of(first, second));

        assertThat(result.devices()).extracting(RuntimeDeviceAvailability::status)
                .containsExactly(RuntimeDeviceAvailability.Status.AVAILABLE,
                        RuntimeDeviceAvailability.Status.NOT_AVAILABLE);
        var order = inOrder(schemas, plans, bindings, devices);
        order.verify(schemas).schema(tenant, project, user, 3, context.appKey(), applicationVersion, 9, dashboardVersion);
        order.verify(plans).requireSnapshot(eq(schema), any(), any());
        order.verify(bindings).findActiveDeviceIds(tenant, project, user, Set.of(bound, hidden));
        order.verify(devices).querySnapshots(project, List.of(first), List.of(reference));
    }

    /** 告警先检查全部不可见项，较早MODEL_MISMATCH不得遮住稍后的60010。 */
    @Test
    void alarmNotAvailableTakesPriorityOverAnyModelMismatch() {
        RuntimeDeviceQuery mismatch = new RuntimeDeviceQuery(UUID.randomUUID(), model, List.of());
        RuntimeDeviceQuery hidden = new RuntimeDeviceQuery(UUID.randomUUID(), model, List.of());
        when(bindings.findActiveDeviceIds(tenant, project, user, Set.of(mismatch.deviceId(), hidden.deviceId())))
                .thenReturn(Set.of(mismatch.deviceId(), hidden.deviceId()));
        when(devices.inspect(project, List.of(mismatch, hidden))).thenReturn(List.of(
                new RuntimeDeviceAvailability(mismatch.deviceId(), RuntimeDeviceAvailability.Status.MODEL_MISMATCH,
                        UUID.randomUUID(), null, null, null),
                new RuntimeDeviceAvailability(hidden.deviceId(), RuntimeDeviceAvailability.Status.NOT_AVAILABLE,
                        null, null, null, null)));

        assertCode(() -> service.alarms(identity(3), context, List.of(mismatch, hidden),
                Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"), null, 20), 60010);
        verifyNoInteractions(alarms);
    }

    /** 项目恢复代次进入游标绑定，新代次不能复用旧代次目录游标。 */
    @Test
    void catalogCursorCannotCrossProjectGeneration() {
        AppRuntimeDeviceCatalogItem first = new AppRuntimeDeviceCatalogItem(
                UUID.randomUUID(), "设备甲", "ONLINE", model, Instant.parse("2026-09-07T00:00:00Z"));
        AppRuntimeDeviceCatalogItem probe = new AppRuntimeDeviceCatalogItem(
                UUID.randomUUID(), "设备乙", "OFFLINE", model, Instant.parse("2026-09-06T00:00:00Z"));
        when(bindings.findRuntimeCatalog(eq(tenant), eq(project), eq(user), eq(model),
                nullable(Instant.class), nullable(UUID.class), eq(2))).thenReturn(List.of(first, probe));

        WebAppDeviceCatalogPage page = service.catalog(identity(3), context, model, null, 1);
        assertThat(page.nextCursor()).isNotBlank();
        assertCode(() -> service.catalog(identity(4), context, model, page.nextCursor(), 1), 10001);
        verify(bindings, never()).findRuntimeCatalog(eq(tenant), eq(project), eq(user), eq(model),
                eq(first.createdAt()), eq(first.deviceId()), anyInt());
    }

    /** 构造指定项目代次身份。 */
    private AppAuthenticatedPrincipal identity(long generation) {
        return new AppAuthenticatedPrincipal(tenant, project, user, generation);
    }

    /** 断言业务错误码。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode().code()).isEqualTo(code);
    }
}
