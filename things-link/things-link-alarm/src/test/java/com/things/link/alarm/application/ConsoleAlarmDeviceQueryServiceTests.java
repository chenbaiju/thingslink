package com.things.link.alarm.application;

import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Console独立成员授权、设备模型复核与真实HMAC游标绑定，不复用App权限。 */
class ConsoleAlarmDeviceQueryServiceTests {
    /** Console实际租户。 */
    private final UUID tenant = UUID.randomUUID();
    /** Console实际项目。 */
    private final UUID project = UUID.randomUUID();
    /** Console实际账号。 */
    private final UUID actor = UUID.randomUUID();
    /** 请求设备。 */
    private final UUID device = UUID.randomUUID();
    /** 请求当前模型预期。 */
    private final UUID model = UUID.randomUUID();
    /** 游标微秒时间锚点。 */
    private final Instant time = Instant.parse("2026-09-01T00:00:00Z");
    /** 第二层实际成员查询。 */
    private ProjectService projects;
    /** 集中RLS范围。 */
    private TransactionLocalRlsScope scope;
    /** 有界设备检查端口。 */
    private DeviceRuntimeDataService devices;
    /** 过滤前分页的内部窄端口。 */
    private AlarmDeviceQueryService alarms;
    /** 被测Console编排。 */
    private ConsoleAlarmDeviceQueryService service;

    /** VIEWER有只读权限，设备均可见；签名codec使用真实实现。 */
    @BeforeEach
    void setup() {
        projects = mock(ProjectService.class);
        scope = mock(TransactionLocalRlsScope.class);
        devices = mock(DeviceRuntimeDataService.class);
        alarms = mock(AlarmDeviceQueryService.class);
        service = new ConsoleAlarmDeviceQueryService(projects, scope, devices, alarms,
                new SignedQueryCursorCodec("console-alarm-test-only-signing-secret-32-bytes"));
        TenantContext.set(new TenantScope(tenant, project, actor));
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.VIEWER);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        when(devices.inspect(any(), any())).thenReturn(List.of(available(device, model)));
        doCallRealMethod().when(devices).requireAllVisible(any());
        when(alarms.query(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new AlarmDeviceQueryPage(List.of(), null, null, false));
    }

    /** ThreadLocal不能泄漏到其他模块测试。 */
    @AfterEach
    void cleanup() { TenantContext.clear(); }

    /** 实际项目成员→可信RLS→设备模型→告警窄查询，未引入App绑定或ACTIVE写许可。 */
    @Test
    void authorizesViewerAndReadsInsideTrustedScope() {
        assertThat(query(null, null).items()).isEmpty();
        var order = inOrder(projects, scope, devices, alarms);
        order.verify(projects).requireRoleInProject(project);
        order.verify(projects).requireProjectTenant(project);
        order.verify(scope).establish(tenant, project);
        order.verify(devices).inspect(any(), any());
        order.verify(devices).requireAllVisible(any());
        order.verify(alarms).query(any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(20));
    }

    /** 未匹配模型10001；不可见设备30020优先于另一设备的配置失配。 */
    @Test
    void rejectsAllUnavailableDevicesBeforeModelMismatch() {
        when(devices.inspect(any(), any())).thenReturn(List.of(new RuntimeDeviceAvailability(device,
                RuntimeDeviceAvailability.Status.MODEL_MISMATCH, UUID.randomUUID(), null, null, null)));
        assertCode(() -> query(null, 20), 10001);
        UUID second = UUID.randomUUID();
        when(devices.inspect(any(), any())).thenReturn(List.of(new RuntimeDeviceAvailability(device,
                        RuntimeDeviceAvailability.Status.MODEL_MISMATCH, UUID.randomUUID(), null, null, null),
                new RuntimeDeviceAvailability(second, RuntimeDeviceAvailability.Status.NOT_AVAILABLE, null, null, null, null)));
        assertCode(() -> service.query(project, List.of(new AlarmDeviceExpectation(device, model), new AlarmDeviceExpectation(second, model)),
                List.of("ACTIVE"), List.of("UNACKNOWLEDGED"), List.of("MAJOR"), null, 20), 30020);
        verifyNoInteractions(alarms);
    }

    /** 同一actor/过滤可继续游标；任何身份或过滤改变都不能复用旧页锚点。 */
    @ParameterizedTest
    @ValueSource(strings = {"ACTOR", "MODEL", "CONDITION", "LIMIT"})
    void rejectsCursorReuseAcrossBoundContext(String change) {
        when(alarms.query(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new AlarmDeviceQueryPage(List.of(), time, UUID.randomUUID(), true));
        String cursor = query(null, 20).nextCursor();
        clearInvocations(devices, alarms);
        if ("ACTOR".equals(change)) TenantContext.set(new TenantScope(tenant, project, UUID.randomUUID()));
        assertCode(() -> service.query(project, List.of(new AlarmDeviceExpectation(device, "MODEL".equals(change) ? UUID.randomUUID() : model)),
                List.of("CONDITION".equals(change) ? "CLEARED" : "ACTIVE"), List.of("UNACKNOWLEDGED"), List.of("MAJOR"),
                cursor, "LIMIT".equals(change) ? 21 : 20), 10001);
        verifyNoInteractions(devices, alarms);
    }

    /** 规范集合排序不把输入顺序差异错误当作新的过滤身份。 */
    @Test
    void sameFilterSetInDifferentOrderReusesCursor() {
        when(alarms.query(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new AlarmDeviceQueryPage(List.of(), time, UUID.randomUUID(), true));
        var first = service.query(project, List.of(new AlarmDeviceExpectation(device, model)),
                List.of("ACTIVE", "CLEARED"), List.of("ACKNOWLEDGED", "UNACKNOWLEDGED"), List.of("MINOR", "MAJOR"), null, 20);
        var second = service.query(project, List.of(new AlarmDeviceExpectation(device, model)),
                List.of("CLEARED", "ACTIVE"), List.of("UNACKNOWLEDGED", "ACKNOWLEDGED"), List.of("MAJOR", "MINOR"), first.nextCursor(), 20);
        assertThat(second.hasMore()).isTrue();
    }

    /** 独立内部入口仍拒绝重复设备/过滤和非法页大小，不依赖HTTP解析器。 */
    @Test
    void rejectsInvalidInternalRequestsBeforeDeviceQuery() {
        assertCode(() -> service.query(project, List.of(new AlarmDeviceExpectation(device, model), new AlarmDeviceExpectation(device, model)),
                List.of("ACTIVE"), List.of("UNACKNOWLEDGED"), List.of("MAJOR"), null, 20), 10001);
        assertCode(() -> service.query(project, List.of(new AlarmDeviceExpectation(device, model)),
                List.of("ACTIVE", "ACTIVE"), List.of("UNACKNOWLEDGED"), List.of("MAJOR"), null, 20), 10001);
        assertCode(() -> query(null, 51), 10001);
        verifyNoInteractions(devices, alarms);
    }

    /** 真实项目成员拒绝和设备数据库故障保留首因，不能当作合法空页。 */
    @Test
    void preservesAuthorizationAndDatabaseFailures() {
        var membership = new IllegalStateException("项目成员查询失败");
        when(projects.requireRoleInProject(project)).thenThrow(membership);
        assertThatThrownBy(() -> query(null, 20)).isSameAs(membership);
        verifyNoInteractions(devices, alarms);
        doReturn(ProjectRole.VIEWER).when(projects).requireRoleInProject(project);
        var failure = new DataAccessResourceFailureException("设备数据库不可用");
        when(devices.inspect(any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> query(null, 20)).isSameAs(failure);
    }

    /** 默认完整合法Console读取。 */
    private CursorPage<AlarmDeviceQueryItem> query(String cursor, Integer limit) {
        return service.query(project, List.of(new AlarmDeviceExpectation(device, model)), List.of("ACTIVE"),
                List.of("UNACKNOWLEDGED"), List.of("MAJOR"), cursor, limit);
    }
    /** 单个真实设备可见且模型匹配的端口投影。 */
    private RuntimeDeviceAvailability available(UUID id, UUID currentModel) {
        return new RuntimeDeviceAvailability(id, RuntimeDeviceAvailability.Status.AVAILABLE, currentModel, "测试设备", "ONLINE", null);
    }
    /** 校验稳定业务错误，不把隐藏资源差异写入响应。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
