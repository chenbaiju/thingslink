package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyHistoryPoint;
import com.things.link.telemetry.domain.PropertyPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 数据运行合同§3.4：版本化完整预算与旧接口兼容在同一底层反例上对照。 */
class VersionedPropertyHistoryTests {
    /** 可信项目。 */
    private final UUID project = UUID.randomUUID();
    /** 已确权设备。 */
    private final UUID device = UUID.randomUUID();
    /** 短窗不由估算自动升粒度，便于证明真实2001个多版本候选的决策。 */
    private final Instant from = Instant.parse("2026-01-01T00:00:00Z");
    /** 排他结束时刻。 */
    private final Instant to = from.plusSeconds(60);
    /** 真实聚合查询仓储的纯替身。 */
    private PropertyPointRepository repository;
    /** 新旧入口共享查询核心。 */
    private PropertyHistoryService history;
    /** 跨模块公开投影端口。 */
    private AppTelemetryDataPlaneService app;

    /** 设备归属默认通过，不引入Console账号。 */
    @BeforeEach
    void setup() {
        repository = mock(PropertyPointRepository.class);
        var devices = mock(DeviceIngestionService.class);
        var quota = mock(com.things.link.project.application.PlanCapacityService.class);
        when(devices.requireDeviceOwner(project, device)).thenReturn(new DeviceIngestionService.DeviceOwnerContext(project));
        when(quota.historyWindow(project, project)).thenReturn(new com.things.link.project.application.PlanHistoryWindow(
                from, from.plusSeconds(3000L * 86400)));
        history = new PropertyHistoryService(repository, mock(ProjectService.class), devices, quota);
        app = new AppTelemetryDataPlaneService(history, mock(DeviceCommandService.class), new ObjectMapper());
    }

    /** 同时间不同版本点及LEGACY完整保留，不以当前模型或ts去重重新解释旧事实。 */
    @Test
    void preservesMultipleVersionsAndLegacyAtSameTimestamp() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findHistory(project, device, "temperature", from, to, HistoryGranularity.RAW,
                HistoryAggregation.AVG, 2001)).thenReturn(List.of(point(first, "1"), point(second, "2"), point(null, "LEGACY_UNVERSIONED")));
        var result = app.historyVersioned(project, device, "temperature", from, to, "RAW", "AVG");
        assertThat(result.points()).hasSize(3);
        assertThat(result.points().stream().map(AppVersionedHistoryPoint::thingModelVersionId).toList()).containsExactly(first, second, null);
        assertThat(result.points().stream().map(AppVersionedHistoryPoint::modelVersion).toList()).containsExactly("1", "2", "LEGACY_UNVERSIONED");
        assertThat(result.points()).allSatisfy(value -> assertThat(value.ts()).isEqualTo(from));
    }

    /** 逐级真实2001候选推动升级，日粒度2000点仍完整接受。 */
    @Test
    void upgradesUntilActualCandidateCountFits() {
        PropertyHistoryPoint point = point(UUID.randomUUID(), "1");
        when(repository.findHistory(eq(project), eq(device), eq("temperature"), eq(from), eq(to), any(),
                eq(HistoryAggregation.AVG), eq(2001))).thenAnswer(invocation ->
                Collections.nCopies(invocation.getArgument(5) == HistoryGranularity.ONE_DAY ? 2000 : 2001, point));
        var result = app.historyVersioned(project, device, "temperature", from, to, "RAW", "AVG");
        assertThat(result.requestedGranularity()).isEqualTo("RAW");
        assertThat(result.actualGranularity()).isEqualTo("ONE_DAY");
        assertThat(result.points()).hasSize(2000);
        for (HistoryGranularity granularity : HistoryGranularity.values()) {
            verify(repository).findHistory(project, device, "temperature", from, to, granularity, HistoryAggregation.AVG, 2001);
        }
    }

    /** 同一日粒度2001反例：新接口10001无结果，旧接口维持既有2000截断兼容。 */
    @Test
    void rejectsFinalOverflowWithoutChangingLegacyContract() {
        when(repository.findHistory(project, device, "temperature", from, to, HistoryGranularity.ONE_DAY,
                HistoryAggregation.AVG, 2001)).thenReturn(Collections.nCopies(2001, point(UUID.randomUUID(), "1")));
        assertCode(() -> app.historyVersioned(project, device, "temperature", from, to, "ONE_DAY", "AVG"), 10001);
        assertThat(app.history(project, device, "temperature", from, to, "ONE_DAY", "AVG").points()).hasSize(2000);
    }

    /** 长窗先按已知桶宽升粒度，最终仍读取2001实际候选以覆盖多版本额外点。 */
    @Test
    void longWindowStillUsesActualFinalCandidateCount() {
        Instant end = from.plusSeconds(2500L * 86400);
        when(repository.findHistory(project, device, "temperature", from, end, HistoryGranularity.ONE_DAY,
                HistoryAggregation.AVG, 2001)).thenReturn(Collections.nCopies(2001, point(UUID.randomUUID(), "1")));
        assertCode(() -> app.historyVersioned(project, device, "temperature", from, end, "ONE_MINUTE", "AVG"), 10001);
        verify(repository, never()).findHistory(eq(project), eq(device), eq("temperature"), eq(from), eq(end),
                eq(HistoryGranularity.ONE_MINUTE), any(), anyInt());
    }

    /** 窗口存在非NUMBER时整序列30058，不偷偷过滤非数值段。 */
    @Test
    void rejectsNonNumericWindowBeforePointQuery() {
        when(repository.hasNonNumericData(project, device, "temperature", from, to)).thenReturn(true);
        assertCode(() -> app.historyVersioned(project, device, "temperature", from, to, "RAW", "AVG"), 30058);
        verify(repository, never()).findHistory(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    /** 粒度与窗口语法沿10001，数据库故障不伪装成空历史。 */
    @Test
    void preservesValidationAndDatabaseFailure() {
        assertCode(() -> app.historyVersioned(project, device, "temperature", from, to, "invalid", "AVG"), 10001);
        assertCode(() -> app.historyVersioned(project, device, "temperature", from, from, "RAW", "AVG"), 10001);
        var failure = new DataAccessResourceFailureException("时序数据库不可用");
        when(repository.hasNonNumericData(project, device, "temperature", from, to)).thenThrow(failure);
        assertThatThrownBy(() -> app.historyVersioned(project, device, "temperature", from, to, "RAW", "AVG")).isSameAs(failure);
    }

    /** 单个有版本或存量历史点，不携带伪造单位。 */
    private PropertyHistoryPoint point(UUID model, String version) {
        return new PropertyHistoryPoint(from, 12.5, 1, model, version);
    }

    /** 业务错误稳定校验码值，不依赖文案。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
