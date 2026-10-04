package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyErrorCode;
import com.things.link.telemetry.domain.PropertyHistoryPoint;
import com.things.link.telemetry.domain.PropertyHistoryResult;
import com.things.link.telemetry.domain.PropertyPointRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 按 ADR 0015 执行 2000 点受限的属性历史聚合查询。 */
@Service
public class PropertyHistoryService {
    /** 公共响应点数上限；多取一个点只用于判定是否继续升粒度。 */
    public static final int MAX_POINTS = 2000;
    /** 时序点仓储。 */
    private final PropertyPointRepository repository;
    /** 项目成员鉴权服务。 */
    private final ProjectService projectService;
    /** 可信设备所属租户的历史窗口。 */
    private final PlanCapacityService planCapacityService;
    /** 设备归属端口，避免 telemetry 跨域读取 dev_device。 */
    private final DeviceIngestionService deviceIngestionService;

    /** 创建历史查询服务。 */
    public PropertyHistoryService(PropertyPointRepository repository, ProjectService projectService,
                                  DeviceIngestionService deviceIngestionService, PlanCapacityService planCapacityService) {
        this.repository = repository;
        this.projectService = projectService;
        this.planCapacityService = planCapacityService;
        this.deviceIngestionService = deviceIngestionService;
    }

    /**
     * 查询历史并在超限时逐级提高粒度。
     *
     * @return 含请求粒度和实际粒度的结果
     */
    public PropertyHistoryResult query(UUID projectId, UUID deviceId, String propertyKey, Instant from, Instant to,
                                       HistoryGranularity requested, HistoryAggregation aggregation) {
        projectService.requireRoleInProject(projectId);
        return queryTrusted(projectId, deviceId, propertyKey, from, to, requested, aggregation);
    }

    /**
     * 无成员校验的历史查询核心（App 数据面，S11-2b）。
     *
     * <p>enduser 先经 {@code app_user_device} 校验绑定，再调本方法；因此这里不复用 {@link #query} 的
     * {@code requireRoleInProject}（App 请求没有控制台 {@code accountId}）。设备归属仍由
     * {@code requireDeviceOwner} 按 {@code project_id = ?} 显式校验，跨项目设备表现为不存在。</p>
     */
    public PropertyHistoryResult queryTrusted(UUID projectId, UUID deviceId, String propertyKey, Instant from, Instant to,
                                              HistoryGranularity requested, HistoryAggregation aggregation) {
        return queryWithBudget(projectId, deviceId, propertyKey, from, to, requested, aggregation, false);
    }

    /**
     * 数据运行合同§3.4的新版本化历史核心；最后粒度仍超2000点时明确拒绝，不裁掉版本段。
     * 调用方须先完成可信App运行上下文及设备绑定，或Console成员与真实双轴RLS上下文；
     * 两类入口均须复核当前精确模型，旧通用入口保留原行为。
     */
    public PropertyHistoryResult queryVersionedTrusted(UUID projectId, UUID deviceId, String propertyKey,
            Instant from, Instant to, HistoryGranularity requested, HistoryAggregation aggregation) {
        return queryWithBudget(projectId, deviceId, propertyKey, from, to, requested, aggregation, true);
    }

    /** 两类入口共享真实粒度升级与非NUMBER检测，只在最后一级的公开失败合同上区分。 */
    private PropertyHistoryResult queryWithBudget(UUID projectId, UUID deviceId, String propertyKey,
            Instant from, Instant to, HistoryGranularity requested, HistoryAggregation aggregation, boolean strictBudget) {
        var owner = deviceIngestionService.requireDeviceOwner(projectId, deviceId);
        validateWindow(from, to);
        var window = planCapacityService.historyWindow(owner.tenantId(), projectId);
        from = window.clipFrom(from);
        to = window.clipTo(to);
        if (!from.isBefore(to)) {
            return new PropertyHistoryResult(requested, requested, aggregation, List.of());
        }
        if (repository.hasNonNumericData(projectId, deviceId, propertyKey, from, to)) {
            throw new BusinessException(PropertyErrorCode.PROPERTY_AGGREGATION_UNSUPPORTED);
        }

        HistoryGranularity actual = requested;
        while (true) {
            // 聚合桶数量可在不扫描数据的情况下确定；先升粒度可避免对明显超限的窗口发起昂贵查询。
            if (estimatedBuckets(from, to, actual) > MAX_POINTS && actual != HistoryGranularity.ONE_DAY) {
                actual = next(actual);
                continue;
            }
            List<PropertyHistoryPoint> points = repository.findHistory(
                    projectId, deviceId, propertyKey, from, to, actual, aggregation, MAX_POINTS + 1);
            if (strictBudget && actual == HistoryGranularity.ONE_DAY && points.size() > MAX_POINTS) {
                throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "版本化历史在日粒度仍超过2000点预算");
            }
            if (points.size() <= MAX_POINTS || actual == HistoryGranularity.ONE_DAY) {
                return new PropertyHistoryResult(requested, actual, aggregation,
                        points.size() <= MAX_POINTS ? points : points.subList(0, MAX_POINTS));
            }
            actual = next(actual);
        }
    }

    /** 校验时间窗，禁止无界扫描和当前保留策略之外的歧义请求。 */
    private static void validateWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "开始时间必须早于结束时间");
        }
    }

    /** 估算固定宽度桶数；raw 无法预估，交给真实查询的第 2001 点判断。 */
    private static long estimatedBuckets(Instant from, Instant to, HistoryGranularity granularity) {
        if (!granularity.aggregated()) {
            return 0;
        }
        long millis = Duration.between(from, to).toMillis();
        return Math.ceilDiv(millis, granularity.bucketWidth().toMillis());
    }

    /** 返回 ADR 0015 冻结的下一档粒度。 */
    private static HistoryGranularity next(HistoryGranularity granularity) {
        return switch (granularity) {
            case RAW -> HistoryGranularity.ONE_MINUTE;
            case ONE_MINUTE -> HistoryGranularity.ONE_HOUR;
            case ONE_HOUR, ONE_DAY -> HistoryGranularity.ONE_DAY;
        };
    }
}
