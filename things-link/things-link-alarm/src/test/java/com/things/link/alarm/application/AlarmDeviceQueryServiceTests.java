package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 按设备告警窄端口的闭集、范围、候选上限和内部游标锚点验证。 */
class AlarmDeviceQueryServiceTests {
    /** 权威租户轴。 */
    private final UUID tenant = UUID.randomUUID();
    /** 权威项目轴。 */
    private final UUID project = UUID.randomUUID();
    /** 已由调用方验证可见且模型匹配的设备。 */
    private final UUID device = UUID.randomUUID();
    /** 稳定微秒可表示游标时间。 */
    private final Instant timestamp = Instant.parse("2026-09-01T00:00:00Z");
    /** 只负责本域事实的仓储替身。 */
    private AlarmInstanceRepository repository;
    /** 被测共享查询编排。 */
    private AlarmDeviceQueryService service;

    /** 每例独立仓储，非法参数必须零访问。 */
    @BeforeEach
    void setup() {
        repository = mock(AlarmInstanceRepository.class);
        service = new AlarmDeviceQueryService(repository);
    }

    /** 所有过滤及limit+1完整传入SQL仓储，页外候选不会进入公开items。 */
    @Test
    void passesAllFiltersBeforePaginationAndReturnsInternalAnchor() {
        AlarmInstance first = instance(tenant, project, device, timestamp);
        AlarmInstance next = instance(tenant, project, device, timestamp.minusSeconds(1));
        stub(List.of(first, next));
        var result = query(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().id()).isEqualTo(first.id());
        assertThat(result.items().getFirst().deviceId()).isEqualTo(device);
        assertThat(result.items().getFirst().version()).isEqualTo(3);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextId()).isEqualTo(first.id());
        assertThat(result.nextUpdatedAt()).isEqualTo(timestamp);
        verify(repository).findByDevices(tenant, project, List.of(device), Set.of(AlarmInstance.ConditionState.ACTIVE),
                Set.of(AlarmInstance.AckState.UNACKNOWLEDGED), Set.of(AlarmRule.Severity.MAJOR), null, null, 2);
    }

    /** 无候选是合法过滤结果，未满页不制造下一游标。 */
    @Test
    void emptyPageIsACompleteValidResult() {
        stub(List.of());
        var result = query(20);
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextUpdatedAt()).isNull();
        assertThat(result.nextId()).isNull();
    }

    /** 任何仓储跨域、跨设备或过滤漂移都保持内部错误，不掩饰成空列表。 */
    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "PROJECT", "DEVICE", "FILTER", "TOO_MANY"})
    void rejectsRepositoryScopeAndBudgetDrift(String change) {
        AlarmInstance value = instance("TENANT".equals(change) ? UUID.randomUUID() : tenant,
                "PROJECT".equals(change) ? UUID.randomUUID() : project,
                "DEVICE".equals(change) ? UUID.randomUUID() : device, timestamp);
        if ("FILTER".equals(change)) {
            value = new AlarmInstance(value.id(), tenant, project, value.ruleId(), value.originatorType(), device,
                    "告警", AlarmRule.Severity.INFO, value.conditionState(), value.ackState(), null,
                    timestamp, null, timestamp, null, null, null, timestamp, timestamp, 1, 3, timestamp, timestamp);
        }
        stub("TOO_MANY".equals(change) ? Collections.nCopies(3, value) : List.of(value));
        assertThatThrownBy(() -> query(1)).isInstanceOf(IllegalStateException.class);
    }

    /** 设备、过滤、页大小与锚点必须同时规范，不能让空IN或半个锚点到SQL。 */
    @Test
    void rejectsInvalidParametersBeforePersistence() {
        assertInvalid(() -> service.query(tenant, project, List.of(), conditions(), acknowledgements(), severities(), null, null, 20));
        assertInvalid(() -> service.query(tenant, project, Collections.nCopies(21, device), conditions(), acknowledgements(), severities(), null, null, 20));
        assertInvalid(() -> service.query(tenant, project, List.of(device, device), conditions(), acknowledgements(), severities(), null, null, 20));
        assertInvalid(() -> service.query(tenant, project, List.of(device), Set.of(), acknowledgements(), severities(), null, null, 20));
        assertInvalid(() -> service.query(tenant, project, List.of(device), Set.of("UNKNOWN"), acknowledgements(), severities(), null, null, 20));
        assertInvalid(() -> service.query(tenant, project, List.of(device), conditions(), Set.of("UNKNOWN"), severities(), null, null, 20));
        assertInvalid(() -> service.query(tenant, project, List.of(device), conditions(), acknowledgements(), Set.of("UNKNOWN"), null, null, 20));
        assertInvalid(() -> service.query(tenant, project, List.of(device), conditions(), acknowledgements(), severities(), timestamp, null, 20));
        assertInvalid(() -> query(0));
        assertInvalid(() -> query(51));
        verifyNoInteractions(repository);
    }

    /** 数据库故障不折叠成无告警。 */
    @Test
    void preservesDatabaseFirstCause() {
        var failure = new DataAccessResourceFailureException("告警数据库不可用");
        when(repository.findByDevices(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenThrow(failure);
        assertThatThrownBy(() -> query(20)).isSameAs(failure);
    }

    /** 基准过滤只读取指定设备ACTIVE未确认的MAJOR事故。 */
    private AlarmDeviceQueryPage query(int limit) {
        return service.query(tenant, project, List.of(device), conditions(), acknowledgements(), severities(), null, null, limit);
    }
    /** 条件过滤闭集示例。 */
    private Set<String> conditions() { return Set.of("ACTIVE"); }
    /** 确认过滤闭集示例。 */
    private Set<String> acknowledgements() { return Set.of("UNACKNOWLEDGED"); }
    /** 严重程度过滤闭集示例。 */
    private Set<String> severities() { return Set.of("MAJOR"); }
    /** 提供过滤后真实候选，不在测试替身内模拟Java二次裁剪。 */
    private void stub(List<AlarmInstance> values) {
        when(repository.findByDevices(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(values);
    }
    /** 创建含敏感内部字段的领域实例，公开item必须只投影冻结字段。 */
    private AlarmInstance instance(UUID ownerTenant, UUID ownerProject, UUID originator, Instant updated) {
        return new AlarmInstance(UUID.randomUUID(), ownerTenant, ownerProject, UUID.randomUUID(), AlarmRule.OriginatorType.DEVICE,
                originator, "温度告警", AlarmRule.Severity.MAJOR, AlarmInstance.ConditionState.ACTIVE,
                AlarmInstance.AckState.UNACKNOWLEDGED, null, timestamp, null, timestamp, null, null, null,
                timestamp, timestamp, 25, 3, timestamp, updated);
    }
    /** 非法输入统一10001。 */
    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(10001));
    }
}
