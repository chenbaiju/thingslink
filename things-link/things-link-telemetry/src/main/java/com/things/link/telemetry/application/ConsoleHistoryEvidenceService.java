package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.PublicDeviceReadService;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 为只读诊断提供受权中性历史，不让调用模块引用内部领域或复制查询SQL。 */
@Service
public class ConsoleHistoryEvidenceService {
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final DeviceRuntimeDataService devices;
    private final PublicDeviceReadService catalog;
    private final PlanCapacityService capacity;
    private final PublicPropertyHistoryService history;
    private final Clock clock;

    /**
     * 装配已有身份、设备、权益与版本化历史端口，所有查询留在所属领域。
     * @param projects 当前成员及项目真实租户
     * @param scope 普通事务双轴范围
     * @param devices 精确当前模型验证
     * @param catalog 不可变模型属性目录
     * @param capacity 有效历史保留窗口
     * @param history 中性版本化历史读取
     * @param clock 采集时刻来源，不代替数据库权益时间
     */
    public ConsoleHistoryEvidenceService(ProjectService projects, TransactionLocalRlsScope scope,
            DeviceRuntimeDataService devices, PublicDeviceReadService catalog, PlanCapacityService capacity,
            PublicPropertyHistoryService history, Clock clock) {
        this.projects = projects; this.scope = scope; this.devices = devices; this.catalog = catalog;
        this.capacity = capacity; this.history = history; this.clock = clock;
    }

    /**
     * 新短事务取证；套餐窗口、来源版本和实际粒度不被空值或当前模型覆盖。
     * @param project 当前登录身份所选项目
     * @param device 项目内设备
     * @param model 期望精确当前物模型版本
     * @param property 单个顶层数值属性键
     * @param from 包含起点，非负时间且总窗不超过一天
     * @param to 排他终点，不得晚于数据库有效窗口终点
     * @return 不可变历史证据；不附单位推断或模型发送资格
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public ConsoleHistoryEvidence read(UUID project, UUID device, UUID model, String property, Instant from, Instant to) {
        if (project == null || !Objects.equals(project, TenantContext.require().projectId()))
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        projects.requireRoleInProject(project);
        UUID tenant = projects.requireProjectTenant(project);
        scope.establish(tenant, project);
        if (device == null || model == null || property == null || !property.matches("[A-Za-z0-9_-]{1,64}")
                || from == null || to == null || from.isBefore(Instant.EPOCH) || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(1)) > 0) throw invalid();
        devices.requireAllAvailable(project, List.of(new RuntimeDeviceQuery(device, model, List.of())));
        var definition = catalog.model(project, model).snapshot().path("properties").path(property);
        if (!definition.isObject() || !"NUMBER".equals(definition.path("dataType").asString())) throw invalid();
        var window = capacity.historyWindow(tenant, project);
        if (window == null || window.from() == null || window.to() == null || !window.from().isBefore(window.to()))
            throw new IllegalStateException("历史权益窗口无效");
        if (to.isAfter(window.to())) throw invalid();
        Instant start = window.clipFrom(from), end = window.clipTo(to);
        boolean clipped = !start.equals(from) || !end.equals(to);
        if (!start.isBefore(end)) {
            return new ConsoleHistoryEvidence(project, device, model, property, from, to, end, end, true,
                    "RAW", "RAW", "AVG", clock.instant(), ConsoleHistoryEvidence.State.OUTSIDE_RETENTION, List.of());
        }
        var result = history.query(project, device, model, property, start, end, "RAW", "AVG");
        if (result == null || !"RAW".equals(result.requestedGranularity()) || !"AVG".equals(result.aggregation())
                || !Set.of("RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY").contains(result.actualGranularity())
                || result.points().size() > 2000) throw new IllegalStateException("历史源合同无效");
        var points = result.points().stream().map(point -> {
            if (point == null || point.ts() == null || point.ts().isBefore(start) || !point.ts().isBefore(end)
                    || !Double.isFinite(point.value()) || point.sampleCount() == null
                    || !point.sampleCount().matches("[1-9][0-9]{0,18}")) throw new IllegalStateException("历史证据点无效");
            long seconds = switch (result.actualGranularity()) {
                case "ONE_MINUTE" -> 60;
                case "ONE_HOUR" -> 3600;
                case "ONE_DAY" -> 86400;
                default -> 0;
            };
            if (seconds > 0 && point.ts().plusSeconds(seconds).isAfter(end))
                throw new IllegalStateException("历史聚合桶越过可见窗口");
            try { Long.parseLong(point.sampleCount()); }
            catch (NumberFormatException ignored) { throw new IllegalStateException("历史样本数无效"); }
            var source = point.thingModelVersionId() == null ? ConsoleHistoryEvidence.Source.SOURCE_UNKNOWN
                    : model.equals(point.thingModelVersionId()) ? ConsoleHistoryEvidence.Source.CURRENT_MODEL
                    : ConsoleHistoryEvidence.Source.HISTORICAL_MODEL;
            return new ConsoleHistoryEvidence.Point(point.ts(), source == ConsoleHistoryEvidence.Source.SOURCE_UNKNOWN ? null : point.value(),
                    point.sampleCount(), point.thingModelVersionId(), source);
        }).toList();
        return new ConsoleHistoryEvidence(project, device, model, property, from, to, start, end, clipped,
                result.requestedGranularity(), result.actualGranularity(), result.aggregation(), clock.instant(),
                points.isEmpty() ? ConsoleHistoryEvidence.State.NO_POINTS : ConsoleHistoryEvidence.State.HAS_POINTS, points);
    }

    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
