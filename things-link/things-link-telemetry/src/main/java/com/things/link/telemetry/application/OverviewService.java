package com.things.link.telemetry.application;

import com.things.link.alarm.application.ProjectAlarmStatisticsService;
import com.things.link.device.application.ProjectDeviceStatistics;
import com.things.link.device.application.ProjectDeviceStatisticsService;
import com.things.link.project.application.ProjectService;
import com.things.link.telemetry.domain.MessageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 编排 device 与 telemetry 各自事实端口，生成项目概要并提供 Redis read-through。 */
@Service
public class OverviewService {
    /** 统计窗口；与响应字段 messages24h/active24h 的语义一致。 */
    private static final Duration WINDOW = Duration.ofHours(24);
    /** 缓存故障日志不包含统计值，避免高频页面访问泄漏业务规模。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(OverviewService.class);
    /** device 域公开统计端口。 */
    private final ProjectDeviceStatisticsService deviceStatisticsService;
    /** telemetry 自有消息日志事实端口。 */
    private final MessageLogRepository messageLogRepository;
    /** alarm 域公开统计端口。 */
    private final ProjectAlarmStatisticsService alarmStatisticsService;
    /** 项目成员授权端口。 */
    private final ProjectService projectService;
    /** Redis 派生缓存端口。 */
    private final OverviewCache cache;
    /** 缓存命中与降级指标。 */
    private final OverviewCacheMetrics metrics;

    /**
     * 创建概要服务。
     *
     * @param deviceStatisticsService 设备统计公开端口
     * @param messageLogRepository 消息日志事实仓储
     * @param alarmStatisticsService 告警统计公开端口
     * @param projectService 项目成员授权端口
     * @param cache 概要派生缓存
     * @param metrics 缓存指标
     */
    public OverviewService(ProjectDeviceStatisticsService deviceStatisticsService,
                           MessageLogRepository messageLogRepository,
                           ProjectAlarmStatisticsService alarmStatisticsService,
                           ProjectService projectService,
                           OverviewCache cache,
                           OverviewCacheMetrics metrics) {
        this.deviceStatisticsService = deviceStatisticsService;
        this.messageLogRepository = messageLogRepository;
        this.alarmStatisticsService = alarmStatisticsService;
        this.projectService = projectService;
        this.cache = cache;
        this.metrics = metrics;
    }

    /**
     * 读取当前项目成员可见的概要。
     *
     * <p>先授权再访问以项目 ID 命名的缓存，避免非成员通过缓存时延探测项目存在性。缓存损坏与 Redis
     * 故障只改变性能，不改变 PostgreSQL 事实响应。</p>
     *
     * @param projectId 项目 ID
     * @return 项目概要快照
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public OverviewSnapshot get(UUID projectId) {
        projectService.requireRoleInProject(projectId);
        try {
            Optional<OverviewSnapshot> cached = cache.find(projectId);
            if (cached.isPresent()) {
                metrics.record(OverviewCacheMetrics.Result.HIT);
                return cached.orElseThrow();
            }
            metrics.record(OverviewCacheMetrics.Result.MISS);
        } catch (IllegalArgumentException exception) {
            metrics.record(OverviewCacheMetrics.Result.INVALID);
            LOGGER.warn("项目概要缓存值损坏，回源 PostgreSQL: projectId={}", projectId, exception);
        } catch (RuntimeException exception) {
            metrics.record(OverviewCacheMetrics.Result.ERROR);
            LOGGER.warn("项目概要缓存读取失败，回源 PostgreSQL: projectId={}", projectId, exception);
        }

        Instant to = Instant.now();
        Instant from = to.minus(WINDOW);
        ProjectDeviceStatistics devices = deviceStatisticsService.snapshot(projectId, from);
        var messages = messageLogRepository.summarize(projectId, from, to);
        var alarms = alarmStatisticsService.snapshot(projectId);
        OverviewSnapshot snapshot = new OverviewSnapshot(to, new OverviewSnapshot.Window(from, to),
                new OverviewSnapshot.DeviceSummary(devices.total(), devices.online(),
                        rate(devices.online(), devices.total()), devices.activeSince(),
                        rate(devices.activeSince(), devices.total())),
                new OverviewSnapshot.MessageSummary(messages.count(), messages.bytes()),
                new OverviewSnapshot.AvailabilityRate(true, rate(alarms.activeDevices(), devices.total())),
                new OverviewSnapshot.AlarmSeverityDeviceCounts(devices.total() - alarms.activeDevices(),
                        alarms.critical(), alarms.major(), alarms.minor(), alarms.warning(), alarms.info()));
        try {
            cache.put(projectId, snapshot);
        } catch (RuntimeException exception) {
            metrics.recordWriteError();
            LOGGER.warn("项目概要缓存回填失败，不影响 PostgreSQL 事实响应: projectId={}", projectId, exception);
        }
        return snapshot;
    }

    /**
     * 空项目固定返回 0，其他项目返回 [0,1] 比率。
     *
     * <p>在线和活跃比率保留合法边界；告警分类额外由快照构造器验证，不能用截断掩盖不一致。</p>
     */
    private static double rate(long numerator, long denominator) {
        if (denominator <= 0) return 0D;
        return Math.min(1D, Math.max(0D, (double) numerator / denominator));
    }
}
