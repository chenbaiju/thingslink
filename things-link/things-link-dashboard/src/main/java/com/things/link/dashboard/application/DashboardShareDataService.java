package com.things.link.dashboard.application;

import com.things.link.alarm.application.AlarmDeviceQueryService;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.device.application.DeviceRuntimeCatalogService;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceCurrentResult;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.telemetry.application.AppTelemetryDataPlaneService;
import com.things.link.telemetry.application.AppVersionedPropertyHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 分享合同第4节五路匿名数据读取；同一只读事务重新确权、恢复RLS、校验变量绑定后调用公开领域端口。
 * 不依赖Console成员/App设备绑定，不以同模型属性并集扩大冻结候选的可读范围。
 */
@Service
public class DashboardShareDataService {
    /** 游标签名用途与App/Console及告警严格隔离。 */
    private static final String CATALOG_PURPOSE = "SHARE_DEVICE_CATALOG";
    /** 告警和目录不能互换游标或移植其他分享的过滤条件。 */
    private static final String ALARM_PURPOSE = "SHARE_ALARM_QUERY";
    /** 数组序列化形成无歧义身份绑定，签名载荷只持有其指纹。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 在本次事务重新验证能力、精确Schema及有限scope。 */
    private final DashboardShareRuntimeService runtime;
    /** 逐变量保留原始Schema绑定，不能消费丢失变量关联的App模型合并计划。 */
    private final DashboardSharePlanValidator plans;
    /** 指定设备的PG事实与模型状态端口。 */
    private final DeviceRuntimeDataService devices;
    /** 在候选限制和模型过滤之后分页的设备域公开目录。 */
    private final DeviceRuntimeCatalogService catalog;
    /** 复用领域版本化历史，不调用App身份接口或读取遥测私表。 */
    private final AppTelemetryDataPlaneService telemetry;
    /** 全部指定设备验证后才查询过滤后告警页。 */
    private final AlarmDeviceQueryService alarms;
    /** 服务端现有配置派生独立域游标签名，不把JWT或secret作为绑定。 */
    private final SignedQueryCursorCodec cursors;

    /** 明确声明所有领域依赖；未装配端口不得降为匿名无鉴权SQL。 */
    public DashboardShareDataService(DashboardShareRuntimeService runtime, DashboardSharePlanValidator plans,
            DeviceRuntimeDataService devices, DeviceRuntimeCatalogService catalog,
            AppTelemetryDataPlaneService telemetry, AlarmDeviceQueryService alarms, SignedQueryCursorCodec cursors) {
        this.runtime = runtime;
        this.plans = plans;
        this.devices = devices;
        this.catalog = catalog;
        this.telemetry = telemetry;
        this.alarms = alarms;
        this.cursors = cursors;
    }

    /** 候选范围内设备不可见/模型变更按设备域状态返回，范围外整请求403且不探测设备。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RuntimeDeviceSnapshotResult snapshots(DashboardSharePrincipal principal,
            List<RuntimeModelReference> models, List<RuntimeDeviceQuery> requested) {
        return guarded(() -> {
            DashboardShareReadContext context = runtime.read(principal);
            plans.requireSnapshots(context, models, requested);
            RuntimeDeviceSnapshotResult result = devices.querySnapshots(principal.projectId(), requested, models);
            requireReturnedDevices(requested, result.devices().stream().map(RuntimeDeviceAvailability::deviceId).toList());
            return result;
        });
    }

    /** 当前值按真实来源版本解释，每个设备/键必须属于其变量的实际CURRENT_VALUE绑定。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RuntimeDeviceCurrentResult currentValues(DashboardSharePrincipal principal, List<RuntimeDeviceQuery> requested) {
        return guarded(() -> {
            DashboardShareReadContext context = runtime.read(principal);
            plans.requireCurrent(context, requested);
            RuntimeDeviceCurrentResult result = devices.queryCurrentValues(principal.projectId(), requested);
            requireReturnedDevices(requested, result.devices().stream().map(RuntimeDeviceCurrentResult.DeviceValues::deviceId).toList());
            for (int index = 0; index < requested.size(); index++) {
                var returned = result.devices().get(index);
                if (returned.status() == RuntimeDeviceAvailability.Status.AVAILABLE
                        && !returned.values().stream().map(RuntimeDeviceCurrentResult.PropertyValue::propertyKey).toList()
                        .equals(requested.get(index).propertyKeys())) throw drift();
            }
            return result;
        });
    }

    /** 一个变量的冻结候选先进入SQL限制，再过滤当前模型并分页；没有全项目目录兜底。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DashboardShareCatalogPage catalog(DashboardSharePrincipal principal, String variableKey, String cursor, int limit) {
        return guarded(() -> {
            DashboardShareReadContext context = runtime.read(principal);
            var scope = plans.requireCatalog(context, variableKey);
            requireLimit(limit);
            String binding = binding(principal, List.of(variableKey, scope.modelVersionId().toString(),
                    "limit:" + limit, "sort:createdAt,id:DESC"));
            var anchor = cursors.decode(cursor, CATALOG_PURPOSE, binding);
            var rows = catalog.find(principal.projectId(), scope.modelVersionId(), scope.deviceIds(),
                    anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                    anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit + 1);
            if (rows.size() > limit + 1 || rows.stream().anyMatch(row -> !scope.deviceIds().contains(row.deviceId())
                    || !scope.modelVersionId().equals(row.currentModelVersionId()))
                    || rows.stream().map(row -> row.deviceId()).distinct().count() != rows.size()) throw drift();
            boolean more = rows.size() > limit;
            var visible = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
            String next = more ? cursors.encode(CATALOG_PURPOSE, binding,
                    visible.getLast().createdAt(), visible.getLast().deviceId()) : null;
            return new DashboardShareCatalogPage(visible, next, more);
        });
    }

    /** 历史只允许声明的preset与本次DB时刻前60秒内锚点，不接受任意from/to平移。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AppVersionedPropertyHistory history(DashboardSharePrincipal principal, UUID deviceId, String propertyKey,
            UUID modelVersionId, String preset, Instant anchorAt, String granularity, String aggregation) {
        return guarded(() -> {
            DashboardShareReadContext context = runtime.read(principal);
            Instant from = plans.requireHistory(context, deviceId, modelVersionId, propertyKey,
                    preset, anchorAt, granularity, aggregation);
            var requested = List.of(new RuntimeDeviceQuery(deviceId, modelVersionId, List.of()));
            requireAvailable(principal, requested);
            try {
                return telemetry.historyVersioned(principal.projectId(), deviceId, propertyKey, from, anchorAt, granularity, aggregation);
            } catch (BusinessException failure) {
                // READ_COMMITTED下初验后设备可能被删除/移走；遥测二次归属复验失败保持分享不可用分类。
                if (failure.errorCode() != CommonErrorCode.RESOURCE_NOT_FOUND) throw failure;
                BusinessException unavailable = new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND);
                unavailable.initCause(failure);
                throw unavailable;
            }
        });
    }

    /** 告警整集合与三组过滤必须来自同一Schema绑定，不能拼接多个slot或返回部分不可见设备。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DashboardShareAlarmPage alarms(DashboardSharePrincipal principal, List<RuntimeDeviceQuery> requested,
            Set<String> conditionStates, Set<String> ackStates, Set<String> severities, String cursor, int limit) {
        return guarded(() -> {
            DashboardShareReadContext context = runtime.read(principal);
            plans.requireAlarms(context, requested, conditionStates, ackStates, severities);
            requireLimit(limit);
            requireAvailable(principal, requested);
            List<String> filters = new ArrayList<>();
            requested.stream().map(item -> item.deviceId() + ":" + item.expectedModelVersionId()).sorted().forEach(filters::add);
            conditionStates.stream().sorted().forEach(value -> filters.add("condition:" + value));
            ackStates.stream().sorted().forEach(value -> filters.add("ack:" + value));
            severities.stream().sorted().forEach(value -> filters.add("severity:" + value));
            filters.add("limit:" + limit);
            filters.add("sort:updatedAt,id:DESC");
            String binding = binding(principal, filters);
            var anchor = cursors.decode(cursor, ALARM_PURPOSE, binding);
            List<UUID> deviceIds = requested.stream().map(RuntimeDeviceQuery::deviceId).toList();
            var page = alarms.query(principal.tenantId(), principal.projectId(), deviceIds,
                    conditionStates, ackStates, severities, anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                    anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit);
            if (page.items().size() > limit || page.items().stream().anyMatch(item -> !deviceIds.contains(item.deviceId())
                    || !conditionStates.contains(item.conditionState()) || !ackStates.contains(item.ackState())
                    || !severities.contains(item.severity()))
                    || page.items().stream().map(item -> item.id()).distinct().count() != page.items().size()
                    || (page.hasMore() && (page.items().isEmpty() || page.nextUpdatedAt() == null || page.nextId() == null))) throw drift();
            String next = page.hasMore() ? cursors.encode(ALARM_PURPOSE, binding, page.nextUpdatedAt(), page.nextId()) : null;
            return new DashboardShareAlarmPage(page.items(), next, page.hasMore());
        });
    }

    /** 已在scope但真实设备不可用时历史/告警整请求拒绝，模型失配只返回输入错误，不读取旧事实。 */
    private void requireAvailable(DashboardSharePrincipal principal, List<RuntimeDeviceQuery> requested) {
        List<RuntimeDeviceAvailability> availability = devices.inspect(principal.projectId(), requested);
        requireReturnedDevices(requested, availability.stream().map(RuntimeDeviceAvailability::deviceId).toList());
        if (availability.stream().anyMatch(item -> item.status() == RuntimeDeviceAvailability.Status.NOT_AVAILABLE)) {
            throw new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND);
        }
        if (availability.stream().anyMatch(item -> item.status() != RuntimeDeviceAvailability.Status.AVAILABLE)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
    }

    /** 完整有序响应防止跨模块遗漏、重复或额外身份被当成正常空值。 */
    private static void requireReturnedDevices(List<RuntimeDeviceQuery> requested, List<UUID> returned) {
        if (!requested.stream().map(RuntimeDeviceQuery::deviceId).toList().equals(returned)) throw drift();
    }

    /** 游标只绑定可信内部身份与规范过滤，不包含凭据或原始请求正文。 */
    private static String binding(DashboardSharePrincipal principal, List<String> filters) {
        var values = JSON.createArrayNode().add("SHARE").add(principal.tenantId().toString())
                .add(principal.projectId().toString()).add(principal.shareId().toString())
                .add(principal.dashboardId().toString()).add(principal.dashboardVersionId().toString())
                .add(Long.toString(principal.projectGeneration()));
        filters.forEach(values::add);
        return JSON.writeValueAsString(values);
    }

    /** 共享合同页大小有限，不因候选少就接受无限客户端limit。 */
    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 50) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }

    /** 本域明确业务错误保持，领域基础设施或持久漂移保留cause并归一分享503。 */
    private static <T> T guarded(Supplier<T> action) {
        try { return action.get(); }
        catch (BusinessException failure) { throw failure; }
        catch (RuntimeException failure) {
            BusinessException mapped = new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            mapped.initCause(failure);
            throw mapped;
        }
    }

    /** 固定无敏感值的跨域合同漂移首因。 */
    private static IllegalStateException drift() { return new IllegalStateException("匿名数据端口返回身份或过滤范围漂移"); }
}
