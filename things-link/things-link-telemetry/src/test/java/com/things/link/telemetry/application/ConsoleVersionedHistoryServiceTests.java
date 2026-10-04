package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.project.application.ProjectService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyHistoryResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Console严格历史验证授权顺序、精确模型和未掩盖首因，严格2001点由VersionedPropertyHistoryTests验证。 */
class ConsoleVersionedHistoryServiceTests {
    /** 每例随机项目。 */
    private final UUID project = UUID.randomUUID();
    /** 项目真实tenant而非调用账号tenant。 */
    private final UUID tenant = UUID.randomUUID();
    /** 精确目标设备。 */
    private final UUID device = UUID.randomUUID();
    /** 精确模型。 */
    private final UUID model = UUID.randomUUID();
    /** 统一窗口起点。 */
    private final Instant from = Instant.parse("2026-09-06T11:00:00Z");
    /** 窗口不含终点。 */
    private final Instant to = from.plusSeconds(3600);
    /** 项目公开端口。 */
    private final ProjectService projects = mock(ProjectService.class);
    /** 普通RLS范围端口。 */
    private final TransactionLocalRlsScope scope = mock(TransactionLocalRlsScope.class);
    /** 设备精确模型端口。 */
    private final DeviceRuntimeDataService devices = mock(DeviceRuntimeDataService.class);
    /** 历史严格核心。 */
    private final PropertyHistoryService history = mock(PropertyHistoryService.class);
    /** 生产Console编排。 */
    private final ConsoleVersionedHistoryService service = new ConsoleVersionedHistoryService(projects, scope, devices, history);

    /** 成员→真实tenant→普通RLS→精确模型→严格历史，绝不调用旧截断入口。 */
    @Test void establishesAuthoritativeScopeBeforeStrictHistory() {
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        var expected = new PropertyHistoryResult(HistoryGranularity.RAW, HistoryGranularity.RAW, HistoryAggregation.AVG, List.of());
        when(history.queryVersionedTrusted(project, device, "temperature", from, to, HistoryGranularity.RAW, HistoryAggregation.AVG))
                .thenReturn(expected);
        assertThat(query()).isSameAs(expected);
        var order = inOrder(projects, scope, devices, history);
        order.verify(projects).requireRoleInProject(project);
        order.verify(projects).requireProjectTenant(project);
        order.verify(scope).establish(tenant, project);
        order.verify(devices).requireAllAvailable(project, List.of(new RuntimeDeviceQuery(device, model, List.of())));
        order.verify(history).queryVersionedTrusted(project, device, "temperature", from, to, HistoryGranularity.RAW, HistoryAggregation.AVG);
        verifyNoMoreInteractions(history);
    }

    /** 成员拒绝不触碰设备/历史，保留同一异常对象。 */
    @Test void membershipFailureNeverReadsFacts() {
        RuntimeException first = new IllegalStateException("membership storage unavailable");
        doThrow(first).when(projects).requireRoleInProject(project);
        assertThatThrownBy(this::query).isSameAs(first);
        verifyNoInteractions(scope, devices, history);
    }

    /** RLS或模型复核失败不能降级旧历史或伪造空点。 */
    @Test void modelFailureNeverReadsHistory() {
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        RuntimeException first = new IllegalStateException("model unavailable");
        when(devices.requireAllAvailable(project, List.of(new RuntimeDeviceQuery(device, model, List.of())))).thenThrow(first);
        assertThatThrownBy(this::query).isSameAs(first);
        verifyNoInteractions(history);
    }

    /** @return 本例固定请求对应真实生产编排结果 */
    private PropertyHistoryResult query() {
        return service.query(project, device, "temperature", model, from, to, HistoryGranularity.RAW, HistoryAggregation.AVG);
    }
}
