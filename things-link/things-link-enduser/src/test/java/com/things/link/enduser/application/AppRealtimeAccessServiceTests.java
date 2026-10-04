package com.things.link.enduser.application;

import com.things.link.dashboard.application.DashboardRuntimePlanValidator;
import com.things.link.dashboard.application.DashboardRuntimeDeviceRequest;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.AppDevice;
import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 实时握手身份、发送前完整确权、有界查询与首因保留的独立反例。 */
class AppRealtimeAccessServiceTests {
    /** 可信会话，异步调用完全由显式值传递。 */
    private final AppAuthenticatedPrincipal identity = new AppAuthenticatedPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7);
    /** 当前身份复验入口。 */
    private final AppRuntimeIdentityService identities = mock(AppRuntimeIdentityService.class);
    /** 有界绑定仓储。 */
    private final AppUserDeviceRepository bindings = mock(AppUserDeviceRepository.class);
    /** 设备归属公开端口。 */
    private final AppDeviceDataPlaneService devices = mock(AppDeviceDataPlaneService.class);
    /** 完整入口与grant公开确权。 */
    private final WebAppDashboardSchemaService schemas = mock(WebAppDashboardSchemaService.class);
    /** 指定设备的当前模型事实。 */
    private final DeviceRuntimeDataService runtimeDevices = mock(DeviceRuntimeDataService.class);
    /** Schema闭集计划。 */
    private final DashboardRuntimePlanValidator plans = mock(DashboardRuntimePlanValidator.class);
    /** 被测授权编排。 */
    private final AppRealtimeAccessService service = new AppRealtimeAccessService(identities, bindings, devices, schemas, runtimeDevices, plans);

    /** 无设备握手只校可信用户、项目和代次，不猜测任何设备权限。 */
    @Test
    void handshakeValidatesIdentityWithoutDeviceRead() {
        service.validateIdentity(identity);
        verify(identities).requireActive(identity.tenantId(), identity.projectId(), identity.appUserId(), 7);
        verifyNoInteractions(bindings, devices);
    }

    /** 绑定有效仍需device确认当前归属，且同一发送前先复验身份。 */
    @Test
    void completeSetChecksIdentityThenBindingsThenOwnership() {
        Set<UUID> ids = IntStream.range(0, 20).mapToObj(index -> UUID.randomUUID()).collect(Collectors.toSet());
        when(bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids))
                .thenReturn(ids);
        when(devices.list(identity.projectId(), ids, null, 20))
                .thenReturn(CursorPage.last(ids.stream().map(AppRealtimeAccessServiceTests::device).toList()));
        service.requireDevices(identity, ids);
        var order = inOrder(identities, bindings, devices);
        order.verify(identities).requireActive(identity.tenantId(), identity.projectId(), identity.appUserId(), 7);
        order.verify(bindings).findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids);
        order.verify(devices).list(identity.projectId(), ids, null, 20);
    }

    /** 发送前身份被撤销时整体拒绝，不继续查询资源。 */
    @Test
    void invalidIdentityStopsBeforeResourceReads() {
        BusinessException failure = new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        doThrow(failure).when(identities).requireActive(
                identity.tenantId(), identity.projectId(), identity.appUserId(), 7);
        assertThatThrownBy(() -> service.requireDevices(identity, Set.of(UUID.randomUUID()))).isSameAs(failure);
        verifyNoInteractions(bindings, devices);
    }

    /** 任一设备解绑即关闭整条订阅授权，不将剩余子集假装完整。 */
    @Test
    void revokedBindingRejectsWholeSet() {
        UUID first = UUID.randomUUID();
        Set<UUID> ids = Set.of(first, UUID.randomUUID());
        when(bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids))
                .thenReturn(Set.of(first));
        assertThatThrownBy(() -> service.requireDevices(identity, ids)).isInstanceOfSatisfying(
                BusinessException.class, failure -> org.assertj.core.api.Assertions.assertThat(failure.errorCode())
                        .isEqualTo(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND));
        verifyNoInteractions(devices);
    }

    /** ACTIVE绑定不能绕过设备已软删或归属已变化。 */
    @Test
    void staleBindingDoesNotAuthorizeDeletedDevice() {
        Set<UUID> ids = Set.of(UUID.randomUUID());
        when(bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids))
                .thenReturn(ids);
        when(devices.list(identity.projectId(), ids, null, 1)).thenReturn(CursorPage.last(List.of()));
        assertThatThrownBy(() -> service.requireDevices(identity, ids)).isInstanceOfSatisfying(
                BusinessException.class, failure -> org.assertj.core.api.Assertions.assertThat(failure.errorCode())
                        .isEqualTo(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND));
    }

    /** 参数边界在任何设备SQL之前拒绝，不截断或全量扫描。 */
    @Test
    void invalidDeviceSetsStopBeforeQueries() {
        Set<UUID> tooMany = IntStream.range(0, 21).mapToObj(index -> UUID.randomUUID()).collect(Collectors.toSet());
        Set<UUID> containsNull = new HashSet<>();
        containsNull.add(null);
        for (Set<UUID> ids : List.of(Set.<UUID>of(), tooMany, containsNull)) {
            assertThatThrownBy(() -> service.requireDevices(identity, ids)).isInstanceOfSatisfying(
                    BusinessException.class, failure -> org.assertj.core.api.Assertions.assertThat(failure.errorCode())
                            .isEqualTo(CommonErrorCode.INVALID_PARAMETER));
        }
        verifyNoInteractions(bindings, devices);
    }

    /** 仓储故障不得伪装成失权，供上层保留基础设施首因。 */
    @Test
    void databaseFailurePropagatesUnchanged() {
        Set<UUID> ids = Set.of(UUID.randomUUID());
        var failure = new DataAccessResourceFailureException("测试连接故障");
        when(bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids))
                .thenThrow(failure);
        assertThatThrownBy(() -> service.requireDevices(identity, ids)).isSameAs(failure);
        verifyNoInteractions(devices);
    }

    /** 仓储返回范围外设备属于内部合同漂移，不能当成有效授权。 */
    @Test
    void unexpectedBindingFailsClosed() {
        Set<UUID> ids = Set.of(UUID.randomUUID());
        when(bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids))
                .thenReturn(Set.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.requireDevices(identity, ids)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(devices);
    }

    /** grant撤销或发布版本失效必须先拒绝，不能只凭有效设备绑定继续发提示。 */
    @Test
    void runtimeGrantFailureStopsBeforeDeviceBindings() {
        WebAppRuntimeContext context = new WebAppRuntimeContext("app_" + "a".repeat(32),
                UUID.randomUUID(), 9, UUID.randomUUID());
        BusinessException failure = new BusinessException(EndUserErrorCode.APPLICATION_RUNTIME_UNAVAILABLE);
        when(schemas.schema(identity.tenantId(), identity.projectId(), identity.appUserId(), 7,
                context.appKey(), context.applicationVersionId(), 9, context.dashboardVersionId())).thenThrow(failure);
        assertThatThrownBy(() -> service.requireRuntime(identity, context, Map.of(UUID.randomUUID(), Set.of("temperature"))))
                .isSameAs(failure);
        verifyNoInteractions(bindings, devices, identities);
    }

    /** Schema端口加入当前事务完成身份复验，设备授权随后执行且不另走身份事务。 */
    @Test
    void runtimeSchemaPrecedesBoundedDeviceAuthorization() {
        WebAppRuntimeContext context = new WebAppRuntimeContext("app_" + "a".repeat(32),
                UUID.randomUUID(), 9, UUID.randomUUID());
        Set<UUID> ids = Set.of(UUID.randomUUID());
        when(bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids))
                .thenReturn(ids);
        when(devices.list(identity.projectId(), ids, null, 1))
                .thenReturn(CursorPage.last(ids.stream().map(AppRealtimeAccessServiceTests::device).toList()));
        UUID selected = ids.iterator().next();
        when(runtimeDevices.currentModelVersions(identity.projectId(), ids)).thenReturn(Map.of(selected, UUID.randomUUID()));
        service.requireRuntime(identity, context, Map.of(selected, Set.of("temperature")));
        var order = inOrder(schemas, bindings, devices);
        order.verify(schemas).schema(identity.tenantId(), identity.projectId(), identity.appUserId(), 7,
                context.appKey(), context.applicationVersionId(), 9, context.dashboardVersionId());
        order.verify(bindings).findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), ids);
        order.verify(devices).list(identity.projectId(), ids, null, 1);
        verifyNoInteractions(identities);
    }

    /** 纯告警也一次合并绑定/模型核验，不伪造CURRENT_VALUE键。 */
    @Test
    void alarmOnlyRuntimeChecksCompleteAuthorizationAndSchema() {
        UUID device = UUID.randomUUID(); UUID model = UUID.randomUUID();
        var context = alarmContext(); var alarm = alarm(device, model);
        allowRuntimeDevice(device, model);
        service.requireDashboardRuntime(identity, context, Map.of(), List.of(alarm));
        verify(plans).requireAlarms(null, alarm.devices(), alarm.conditionStates(), alarm.ackStates(), alarm.severities());
        verify(plans, org.mockito.Mockito.never()).requireCurrentValues(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var order = inOrder(schemas, bindings, runtimeDevices, plans);
        order.verify(schemas).schema(identity.tenantId(), identity.projectId(), identity.appUserId(), 7,
                context.appKey(), context.applicationVersionId(), context.publicationRevision(), context.dashboardVersionId());
        order.verify(bindings).findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), Set.of(device));
        order.verify(runtimeDevices).currentModelVersions(identity.projectId(), Set.of(device));
        order.verify(plans).requireAlarms(null, alarm.devices(), alarm.conditionStates(), alarm.ackStates(), alarm.severities());
    }

    /** 两域同设备只查询一次绑定，但分别核验Schema消费资格。 */
    @Test
    void mixedRuntimeUsesOneBoundedUnion() {
        UUID device = UUID.randomUUID(); UUID model = UUID.randomUUID();
        var alarm = alarm(device, model); allowRuntimeDevice(device, model);
        service.requireDashboardRuntime(identity, alarmContext(), Map.of(device, Set.of("temperature")), List.of(alarm));
        verify(runtimeDevices).currentModelVersions(identity.projectId(), Set.of(device));
        verify(plans).requireCurrentValues(null, List.of(new DashboardRuntimeDeviceRequest(device, model, List.of("temperature"))));
        verify(plans).requireAlarms(null, alarm.devices(), alarm.conditionStates(), alarm.ackStates(), alarm.severities());
    }

    /** 纯告警grant失败也在设备查找前终止。 */
    @Test
    void alarmGrantFailureStopsBeforeResourceQueries() {
        var context = alarmContext();
        BusinessException failure = new BusinessException(EndUserErrorCode.APPLICATION_RUNTIME_UNAVAILABLE);
        when(schemas.schema(identity.tenantId(), identity.projectId(), identity.appUserId(), 7,
                context.appKey(), context.applicationVersionId(), context.publicationRevision(), context.dashboardVersionId())).thenThrow(failure);
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, context, Map.of(),
                List.of(alarm(UUID.randomUUID(), UUID.randomUUID())))).isSameAs(failure);
        verifyNoInteractions(bindings, devices, runtimeDevices, plans);
    }

    /** 元信息变化必须关闭整个订阅，不能用旧模型继续发送提示。 */
    @Test
    void alarmModelDriftRejectsEntireSubscription() {
        UUID device = UUID.randomUUID(); allowRuntimeDevice(device, UUID.randomUUID());
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, alarmContext(), Map.of(),
                List.of(alarm(device, UUID.randomUUID())))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(plans);
    }

    /** 组数、重复短键和两域总槽位边界不能靠重复设备绕过。 */
    @Test
    void alarmBoundsRejectBeforeDatabaseReads() {
        var alarm = alarm(UUID.randomUUID(), UUID.randomUUID());
        var context = alarmContext();
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, context, Map.of(), List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, context, Map.of(), List.of(alarm, alarm))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, context, Map.of(), java.util.Collections.nCopies(21, alarm))).isInstanceOf(BusinessException.class);
        var twenty = IntStream.range(0, 20).mapToObj(index -> new DashboardRuntimeDeviceRequest(UUID.randomUUID(), UUID.randomUUID(), List.<String>of())).toList();
        var groups = IntStream.range(0, 11).mapToObj(index -> new AppRealtimeAlarmSubscription("alarm_" + index, twenty,
                Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"))).toList();
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, context, Map.of(), groups)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(schemas, bindings, devices, runtimeDevices);
    }

    /** 非法过滤值和告警伪属性在领域入口独立拒绝，不能依赖WS parser先校过。 */
    @Test
    void alarmDomainRejectsInvalidFiltersAndPropertyKeys() {
        var valid = alarm(UUID.randomUUID(), UUID.randomUUID());
        var invalid = new AppRealtimeAlarmSubscription("alarm_0", valid.devices(), Set.of("UNKNOWN"), valid.ackStates(), valid.severities());
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, alarmContext(), Map.of(), List.of(invalid))).isInstanceOf(BusinessException.class);
        var device = valid.devices().getFirst();
        var keys = new AppRealtimeAlarmSubscription("alarm_0", List.of(new DashboardRuntimeDeviceRequest(device.deviceId(), device.expectedModelVersionId(), List.of("temperature"))), valid.conditionStates(), valid.ackStates(), valid.severities());
        assertThatThrownBy(() -> service.requireDashboardRuntime(identity, alarmContext(), Map.of(), List.of(keys))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(schemas, bindings, devices, runtimeDevices);
    }

    /** 设置一个真实身份范围内的完整设备投影，不替代Schema/grant服务验证。 */
    private void allowRuntimeDevice(UUID device, UUID model) {
        when(bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(), identity.appUserId(), Set.of(device))).thenReturn(Set.of(device));
        when(devices.list(identity.projectId(), Set.of(device), null, 1)).thenReturn(CursorPage.last(List.of(device(device))));
        when(runtimeDevices.currentModelVersions(identity.projectId(), Set.of(device))).thenReturn(Map.of(device, model));
    }

    /** 明确短查询键与空属性域。 */
    private static AppRealtimeAlarmSubscription alarm(UUID device, UUID model) {
        return new AppRealtimeAlarmSubscription("alarm_0", List.of(new DashboardRuntimeDeviceRequest(device, model, List.of())),
                Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"));
    }

    /** 不伪造模型来源，运行上下文与生产入口类型一致。 */
    private static WebAppRuntimeContext alarmContext() {
        return new WebAppRuntimeContext("app_" + "a".repeat(32), UUID.randomUUID(), 9, UUID.randomUUID());
    }

    /** 构造无需生产domain依赖的设备投影。 */
    private static AppDevice device(UUID id) {
        return new AppDevice(id, "device", "测试设备", null, "ONLINE", null, null, Instant.EPOCH);
    }
}
