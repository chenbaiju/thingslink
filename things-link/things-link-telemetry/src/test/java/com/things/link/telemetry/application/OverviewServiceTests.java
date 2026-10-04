package com.things.link.telemetry.application;

import com.things.link.alarm.application.ProjectAlarmStatistics;
import com.things.link.alarm.application.ProjectAlarmStatisticsService;
import com.things.link.device.application.ProjectDeviceStatistics;
import com.things.link.device.application.ProjectDeviceStatisticsService;
import com.things.link.project.application.ProjectService;
import com.things.link.telemetry.domain.MessageLogRepository;
import com.things.link.telemetry.domain.MessageLogStatistics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 概要 read-through 必须把全部缓存异常降级为 PostgreSQL 事实查询。 */
@ExtendWith(MockitoExtension.class)
class OverviewServiceTests {
    /** device 统计端口替身。 */
    @Mock private ProjectDeviceStatisticsService deviceStatistics;
    /** telemetry 事实仓储替身。 */
    @Mock private MessageLogRepository messages;
    /** alarm 域事实统计端口替身。 */
    @Mock private ProjectAlarmStatisticsService alarms;
    /** 项目授权替身。 */
    @Mock private ProjectService projects;
    /** Redis 缓存端口替身。 */
    @Mock private OverviewCache cache;
    /** 指标注册表。 */
    private SimpleMeterRegistry meters;
    /** 被测服务。 */
    private OverviewService service;
    /** 测试项目。 */
    private UUID projectId;

    /** 创建独立指标注册表，防止测试之间累计计数。 */
    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        service = new OverviewService(deviceStatistics, messages, alarms, projects, cache,
                new OverviewCacheMetrics(meters));
        projectId = UUID.randomUUID();
    }

    /** 有效命中不允许进入任何 PostgreSQL 统计端口。 */
    @Test
    void cacheHitSkipsFactQueries() {
        OverviewSnapshot cached = snapshot();
        when(cache.find(projectId)).thenReturn(Optional.of(cached));

        assertThat(service.get(projectId)).isSameAs(cached);

        verify(projects).requireRoleInProject(projectId);
        verify(deviceStatistics, never()).snapshot(any(), any());
        verify(messages, never()).summarize(any(), any(), any());
        verify(alarms, never()).snapshot(any());
        assertThat(counter("hit")).isEqualTo(1D);
    }

    /** miss 按同一个半开 24 小时窗口聚合两个领域，并尽力回填缓存。 */
    @Test
    void cacheMissReadsFactsAndUsesReceivedAtWindow() {
        when(cache.find(projectId)).thenReturn(Optional.empty());
        when(deviceStatistics.snapshot(any(), any())).thenReturn(new ProjectDeviceStatistics(4, 2, 3));
        when(messages.summarize(any(), any(), any())).thenReturn(new MessageLogStatistics(9, 1024));
        when(alarms.snapshot(projectId)).thenReturn(new ProjectAlarmStatistics(1, 1, 0, 0, 0));

        OverviewSnapshot result = service.get(projectId);

        assertThat(result.devices().onlineRate()).isEqualTo(0.5D);
        assertThat(result.devices().active24hRate()).isEqualTo(0.75D);
        assertThat(result.messages24h()).isEqualTo(new OverviewSnapshot.MessageSummary(9, 1024));
        assertThat(result.alarmRate()).isEqualTo(new OverviewSnapshot.AvailabilityRate(true, 0.5D));
        assertThat(Duration.between(result.window().from(), result.window().to())).isEqualTo(Duration.ofHours(24));
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(messages).summarize(org.mockito.ArgumentMatchers.eq(projectId), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(result.window().from());
        assertThat(to.getValue()).isEqualTo(result.window().to());
        verify(cache).put(projectId, result);
        assertThat(counter("miss")).isEqualTo(1D);
    }

    /** 设备刚软删、ACTIVE 事故尚未清除时，告警率仍须保持 API 契约的 [0,1] 范围。 */
    @Test
    void inconsistentAlarmPopulationMustNotBePublishedAsAValidSnapshot() {
        when(cache.find(projectId)).thenReturn(Optional.empty());
        when(deviceStatistics.snapshot(any(), any())).thenReturn(new ProjectDeviceStatistics(1, 0, 0));
        when(messages.summarize(any(), any(), any())).thenReturn(new MessageLogStatistics(0, 0));
        when(alarms.snapshot(projectId)).thenReturn(new ProjectAlarmStatistics(1, 1, 0, 0, 0));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(projectId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 损坏值必须单独计为 invalid，再回源；不能把 JSON 契约错误误报成正常 miss。 */
    @Test
    void invalidCacheValueFallsBackToFacts() {
        when(cache.find(projectId)).thenThrow(new IllegalArgumentException("bad json"));
        prepareEmptyFacts();

        OverviewSnapshot result = service.get(projectId);

        assertThat(result.devices().total()).isZero();
        assertThat(result.devices().onlineRate()).isZero();
        assertThat(counter("invalid")).isEqualTo(1D);
        verify(deviceStatistics).snapshot(any(), any());
    }

    /** Redis 连接异常只影响命中率，仍返回 PostgreSQL 事实。 */
    @Test
    void redisReadFailureFallsBackToFacts() {
        when(cache.find(projectId)).thenThrow(new IllegalStateException("redis unavailable"));
        prepareEmptyFacts();

        assertThat(service.get(projectId).messages24h().count()).isZero();

        assertThat(counter("error")).isEqualTo(1D);
        verify(messages).summarize(any(), any(), any());
    }

    /** 回填失败发生在事实查询后，不能把成功响应改成 5xx。 */
    @Test
    void redisWriteFailureDoesNotChangeFactResponse() {
        when(cache.find(projectId)).thenReturn(Optional.empty());
        prepareEmptyFacts();
        doThrow(new IllegalStateException("redis unavailable")).when(cache).put(any(), any());

        assertThat(service.get(projectId).devices().total()).isZero();

        assertThat(meters.get("thingslink.overview.cache.write.errors").counter().count()).isEqualTo(1D);
    }

    /** 为回源路径准备空项目事实。 */
    private void prepareEmptyFacts() {
        when(deviceStatistics.snapshot(any(), any())).thenReturn(new ProjectDeviceStatistics(0, 0, 0));
        when(messages.summarize(any(), any(), any())).thenReturn(new MessageLogStatistics(0, 0));
        when(alarms.snapshot(any())).thenReturn(new ProjectAlarmStatistics(0, 0, 0, 0, 0));
    }

    /** @return 固定有效缓存快照 */
    private static OverviewSnapshot snapshot() {
        Instant to = Instant.parse("2026-08-09T12:00:00Z");
        return new OverviewSnapshot(to, new OverviewSnapshot.Window(to.minus(Duration.ofHours(24)), to),
                new OverviewSnapshot.DeviceSummary(2, 1, 0.5D, 1, 0.5D),
                new OverviewSnapshot.MessageSummary(3, 128),
                new OverviewSnapshot.AvailabilityRate(true, 0.5D),
                new OverviewSnapshot.AlarmSeverityDeviceCounts(1, 0, 1, 0, 0, 0));
    }

    /** @param result 缓存结果标签 @return 指标计数 */
    private double counter(String result) {
        return meters.get("thingslink.overview.cache.requests").tag("result", result).counter().count();
    }
}
