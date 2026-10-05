package com.things.link.enduser.application;

import com.things.link.alarm.application.AlarmDeviceQueryPage;
import com.things.link.alarm.application.AlarmDeviceQueryService;
import com.things.link.dashboard.application.DashboardRuntimeDeviceRequest;
import com.things.link.dashboard.application.DashboardRuntimeModelRequest;
import com.things.link.dashboard.application.DashboardRuntimePlanValidator;
import com.things.link.dashboard.application.RuntimeDashboardSchema;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceCurrentResult;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.enduser.domain.AppRuntimeDeviceCatalogItem;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.telemetry.application.AppTelemetryDataPlaneService;
import com.things.link.telemetry.application.AppVersionedPropertyHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 五条App REST数据入口的统一确权与跨域编排。
 *
 * <p>每个公开方法在同一只读事务内重新核验App身份、当前应用/看板Schema和处于启用状态的读取授权，
 * 再以客户端明确给出的至多20个设备执行本域绑定查询。只有仍有绑定的设备会进入device、telemetry或alarm
 * 公开端口；隐藏和模型失配分支不会触发物模型或属性键数据库探测。</p>
 */
@Service
public class WebAppRuntimeDataService {
    /** 设备目录游标用途，禁止与告警游标互换。 */
    private static final String CATALOG_CURSOR_PURPOSE = "APP_DEVICE_CATALOG";
    /** 告警游标用途，禁止与目录游标互换。 */
    private static final String ALARM_CURSOR_PURPOSE = "APP_ALARM_QUERY";
    /** 固定JSON实现只用于构造无歧义游标绑定，不解析客户端输入。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 单看板Schema及grant逐请求确权。 */ private final WebAppDashboardSchemaService schemas;
    /** 统一Schema运行计划闭集验证。 */ private final DashboardRuntimePlanValidator plans;
    /** 本域指定设备绑定与目录联查。 */ private final AppUserDeviceRepository bindings;
    /** device域有界快照、当前值与模型状态端口。 */ private final DeviceRuntimeDataService devices;
    /** telemetry域完整版本化历史端口。 */ private final AppTelemetryDataPlaneService telemetry;
    /** alarm域按设备过滤后分页端口。 */ private final AlarmDeviceQueryService alarms;
    /** 身份与过滤绑定的签名keyset游标。 */ private final SignedQueryCursorCodec cursors;

    /** 创建完整App运行数据编排。 */
    public WebAppRuntimeDataService(WebAppDashboardSchemaService schemas, DashboardRuntimePlanValidator plans,
            AppUserDeviceRepository bindings, DeviceRuntimeDataService devices,
            AppTelemetryDataPlaneService telemetry, AlarmDeviceQueryService alarms,
            SignedQueryCursorCodec cursors) {
        this.schemas = schemas;
        this.plans = plans;
        this.bindings = bindings;
        this.devices = devices;
        this.telemetry = telemetry;
        this.alarms = alarms;
        this.cursors = cursors;
    }

    /**
     * 读取设备描述快照；无绑定设备按原请求位置返回NOT_AVAILABLE。
     *
     * @param identity 已验签JWT身份
     * @param context 四字段精确运行上下文
     * @param requestedModels 请求模型引用
     * @param requestedDevices 请求设备
     * @return 原顺序设备状态与仅成功设备实际使用的模型描述
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceSnapshotResult snapshots(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            List<RuntimeModelReference> requestedModels, List<RuntimeDeviceQuery> requestedDevices) {
        RuntimeDashboardSchema schema = schema(identity, context);
        plans.requireSnapshot(schema, requestedModels.stream().map(WebAppRuntimeDataService::modelPlan).toList(),
                requestedDevices.stream().map(WebAppRuntimeDataService::devicePlan).toList());
        Set<UUID> active = activeBindings(identity, requestedDevices);
        List<RuntimeDeviceQuery> authorized = requestedDevices.stream()
                .filter(item -> active.contains(item.deviceId())).toList();
        RuntimeDeviceSnapshotResult result = authorized.isEmpty()
                ? new RuntimeDeviceSnapshotResult(List.of(), List.of())
                : devices.querySnapshots(identity.projectId(), authorized, requestedModels);
        return new RuntimeDeviceSnapshotResult(mergeAvailability(requestedDevices, active, result.devices()), result.models());
    }

    /**
     * 读取稀疏PG当前值；无绑定设备返回NOT_AVAILABLE且空values。
     *
     * @param identity 已验签JWT身份
     * @param context 精确运行上下文
     * @param requestedDevices 请求设备与键
     * @return 与请求设备及键顺序一致的确定状态
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceCurrentResult currentValues(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            List<RuntimeDeviceQuery> requestedDevices) {
        RuntimeDashboardSchema schema = schema(identity, context);
        plans.requireCurrentValues(schema, requestedDevices.stream().map(WebAppRuntimeDataService::devicePlan).toList());
        Set<UUID> active = activeBindings(identity, requestedDevices);
        List<RuntimeDeviceQuery> authorized = requestedDevices.stream()
                .filter(item -> active.contains(item.deviceId())).toList();
        RuntimeDeviceCurrentResult result = authorized.isEmpty()
                ? new RuntimeDeviceCurrentResult(List.of())
                : devices.queryCurrentValues(identity.projectId(), authorized);
        Map<UUID, RuntimeDeviceCurrentResult.DeviceValues> returned = uniqueValues(result.devices(), authorized);
        List<RuntimeDeviceCurrentResult.DeviceValues> merged = requestedDevices.stream().map(request ->
                active.contains(request.deviceId()) ? returned.get(request.deviceId())
                        : new RuntimeDeviceCurrentResult.DeviceValues(request.deviceId(),
                                RuntimeDeviceAvailability.Status.NOT_AVAILABLE, List.of())).toList();
        if (merged.stream().anyMatch(java.util.Objects::isNull)) throw drift("当前值端口遗漏请求设备");
        return new RuntimeDeviceCurrentResult(merged);
    }

    /**
     * 按精确模型列已授权设备目录，SQL在授权和模型过滤后才应用limit+1。
     *
     * @param identity 已验签JWT身份
     * @param context 精确运行上下文
     * @param modelVersionId Schema目录声明模型
     * @param cursor 可选签名游标
     * @param limit 1..50页大小
     * @return 目录页
     */
    @Transactional(readOnly = true)
    public WebAppDeviceCatalogPage catalog(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            UUID modelVersionId, String cursor, int limit) {
        RuntimeDashboardSchema schema = schema(identity, context);
        plans.requireCatalog(schema, modelVersionId);
        if (limit < 1 || limit > 50) throw invalid();
        String binding = cursorBinding(identity, context, List.of("sort:createdAt,id:DESC",
                modelVersionId.toString(), Integer.toString(limit)));
        var anchor = cursors.decode(cursor, CATALOG_CURSOR_PURPOSE, binding);
        List<AppRuntimeDeviceCatalogItem> rows = bindings.findRuntimeCatalog(identity.tenantId(), identity.projectId(),
                identity.appUserId(), modelVersionId, anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit + 1);
        if (rows.size() > limit + 1) throw drift("目录仓储越过有界读取上限");
        boolean more = rows.size() > limit;
        List<AppRuntimeDeviceCatalogItem> visible = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String next = more ? cursors.encode(CATALOG_CURSOR_PURPOSE, binding,
                visible.getLast().createdAt(), visible.getLast().deviceId()) : null;
        return new WebAppDeviceCatalogPage(visible, next, more);
    }

    /**
     * 读取单设备属性完整版本化历史。
     *
     * @param identity 已验签JWT身份
     * @param context 精确运行上下文
     * @param deviceId 设备ID
     * @param propertyKey 顶层属性键
     * @param expectedModelVersionId 当前模型预期
     * @param from 窗口起点
     * @param to 窗口终点
     * @param granularity 粒度
     * @param aggregation 聚合
     * @param now 服务端同请求捕获时刻
     * @return 完整有界版本化历史
     */
    @Transactional(readOnly = true)
    public AppVersionedPropertyHistory history(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            UUID deviceId, String propertyKey, UUID expectedModelVersionId, Instant from, Instant to,
            String granularity, String aggregation, Instant now) {
        RuntimeDashboardSchema schema = schema(identity, context);
        plans.requireHistory(schema, expectedModelVersionId, propertyKey, from, to, granularity, aggregation, now);
        RuntimeDeviceQuery query = new RuntimeDeviceQuery(deviceId, expectedModelVersionId, List.of());
        if (!activeBindings(identity, List.of(query)).contains(deviceId)) throw invalid();
        RuntimeDeviceAvailability availability = onlyAvailability(
                devices.inspect(identity.projectId(), List.of(query)), deviceId);
        if (availability.status() != RuntimeDeviceAvailability.Status.AVAILABLE) throw invalid();
        return telemetry.historyVersioned(identity.projectId(), deviceId, propertyKey,
                from, to, granularity, aggregation);
    }

    /**
     * 读取全部指定设备均可见且模型匹配后的告警实例页。
     *
     * @param identity 已验签JWT身份
     * @param context 精确运行上下文
     * @param requestedDevices 1..20设备及模型
     * @param conditionStates 条件状态
     * @param ackStates 确认状态
     * @param severities 等级
     * @param cursor 可选签名游标
     * @param limit 1..50页大小
     * @return 告警页
     */
    @Transactional(readOnly = true)
    public WebAppAlarmPage alarms(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            List<RuntimeDeviceQuery> requestedDevices, Set<String> conditionStates, Set<String> ackStates,
            Set<String> severities, String cursor, int limit) {
        RuntimeDashboardSchema schema = schema(identity, context);
        plans.requireAlarms(schema, requestedDevices.stream().map(WebAppRuntimeDataService::devicePlan).toList(),
                conditionStates, ackStates, severities);
        if (limit < 1 || limit > 50) throw invalid();
        Set<UUID> active = activeBindings(identity, requestedDevices);
        if (active.size() != requestedDevices.size()) throw deviceNotFound();
        List<RuntimeDeviceAvailability> inspected = devices.inspect(identity.projectId(), requestedDevices);
        if (inspected.size() != requestedDevices.size()) throw drift("设备检查端口遗漏请求设备");
        Map<UUID, RuntimeDeviceAvailability> byId = uniqueAvailability(inspected, requestedDevices);
        List<RuntimeDeviceAvailability> ordered = requestedDevices.stream().map(request -> byId.get(request.deviceId())).toList();
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) throw drift("设备检查端口遗漏请求身份");
        // 先完成全体可见性分类，避免较早的模型失配遮住稍后的不可见设备并形成存在性探针。
        if (ordered.stream().anyMatch(item -> item.status() == RuntimeDeviceAvailability.Status.NOT_AVAILABLE)) {
            throw deviceNotFound();
        }
        if (ordered.stream().anyMatch(item -> item.status() != RuntimeDeviceAvailability.Status.AVAILABLE)) throw invalid();
        List<String> normalized = new ArrayList<>();
        requestedDevices.stream().map(item -> item.deviceId() + ":" + item.expectedModelVersionId())
                .sorted().forEach(normalized::add);
        conditionStates.stream().sorted().forEach(value -> normalized.add("condition:" + value));
        ackStates.stream().sorted().forEach(value -> normalized.add("ack:" + value));
        severities.stream().sorted().forEach(value -> normalized.add("severity:" + value));
        normalized.add("limit:" + limit);
        normalized.add("sort:updatedAt,id:DESC");
        String binding = cursorBinding(identity, context, normalized);
        var anchor = cursors.decode(cursor, ALARM_CURSOR_PURPOSE, binding);
        AlarmDeviceQueryPage page = alarms.query(identity.tenantId(), identity.projectId(),
                requestedDevices.stream().map(RuntimeDeviceQuery::deviceId).toList(), conditionStates, ackStates,
                severities, anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit);
        String next = page.hasMore() ? cursors.encode(ALARM_CURSOR_PURPOSE, binding,
                page.nextUpdatedAt(), page.nextId()) : null;
        return new WebAppAlarmPage(page.items(), next, page.hasMore());
    }

    /** 每次调用现有Schema服务，复用身份、当前版本、精确引用和grant顺序。 */
    private RuntimeDashboardSchema schema(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context) {
        if (identity == null || context == null) throw invalid();
        return schemas.schema(identity.tenantId(), identity.projectId(), identity.appUserId(),
                identity.projectGeneration(), context.appKey(), context.applicationVersionId(),
                context.publicationRevision(), context.dashboardVersionId());
    }

    /** 指定设备绑定查询最多20项，且返回范围外ID属于仓储漂移。 */
    private Set<UUID> activeBindings(AppAuthenticatedPrincipal identity, List<RuntimeDeviceQuery> requests) {
        Set<UUID> requested = requests.stream().map(RuntimeDeviceQuery::deviceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> active = bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(),
                identity.appUserId(), requested);
        if (active == null || !requested.containsAll(active)) throw drift("设备授权仓储返回范围外身份");
        return active;
    }

    /** 把无绑定项按原位置补成NOT_AVAILABLE，并验证device端只返回已授权请求。 */
    private static List<RuntimeDeviceAvailability> mergeAvailability(List<RuntimeDeviceQuery> requests,
            Set<UUID> active, List<RuntimeDeviceAvailability> returned) {
        Map<UUID, RuntimeDeviceAvailability> byId = uniqueAvailability(returned,
                requests.stream().filter(item -> active.contains(item.deviceId())).toList());
        List<RuntimeDeviceAvailability> merged = new ArrayList<>();
        for (RuntimeDeviceQuery request : requests) {
            RuntimeDeviceAvailability item = active.contains(request.deviceId()) ? byId.get(request.deviceId())
                    : new RuntimeDeviceAvailability(request.deviceId(),
                            RuntimeDeviceAvailability.Status.NOT_AVAILABLE, null, null, null, null);
            if (item == null) throw drift("设备快照端口遗漏请求设备");
            merged.add(item);
        }
        return List.copyOf(merged);
    }

    /** 建立无重复且不越过请求范围的设备状态索引。 */
    private static Map<UUID, RuntimeDeviceAvailability> uniqueAvailability(
            List<RuntimeDeviceAvailability> values, List<RuntimeDeviceQuery> expected) {
        Set<UUID> ids = expected.stream().map(RuntimeDeviceQuery::deviceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<UUID, RuntimeDeviceAvailability> result = new HashMap<>();
        for (RuntimeDeviceAvailability value : values) {
            if (!ids.contains(value.deviceId()) || result.putIfAbsent(value.deviceId(), value) != null) {
                throw drift("设备端口返回重复或范围外身份");
            }
        }
        return result;
    }

    /** 建立当前值设备索引并验证范围。 */
    private static Map<UUID, RuntimeDeviceCurrentResult.DeviceValues> uniqueValues(
            List<RuntimeDeviceCurrentResult.DeviceValues> values, List<RuntimeDeviceQuery> expected) {
        Set<UUID> ids = expected.stream().map(RuntimeDeviceQuery::deviceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<UUID, RuntimeDeviceCurrentResult.DeviceValues> result = new HashMap<>();
        for (RuntimeDeviceCurrentResult.DeviceValues value : values) {
            if (!ids.contains(value.deviceId()) || result.putIfAbsent(value.deviceId(), value) != null) {
                throw drift("当前值端口返回重复或范围外身份");
            }
        }
        return result;
    }

    /** 要求单设备检查返回精确一项且身份一致。 */
    private static RuntimeDeviceAvailability onlyAvailability(List<RuntimeDeviceAvailability> values, UUID deviceId) {
        if (values == null || values.size() != 1 || !deviceId.equals(values.getFirst().deviceId())) {
            throw drift("设备检查端口未返回精确单目标");
        }
        return values.getFirst();
    }

    /** 转换device模型引用到dashboard纯验证值。 */
    private static DashboardRuntimeModelRequest modelPlan(RuntimeModelReference source) {
        return new DashboardRuntimeModelRequest(source.versionId(), source.digestAlgorithm(), source.digest(), source.profile());
    }

    /** 转换device查询到dashboard纯验证值，保持原键顺序。 */
    private static DashboardRuntimeDeviceRequest devicePlan(RuntimeDeviceQuery source) {
        return new DashboardRuntimeDeviceRequest(source.deviceId(), source.expectedModelVersionId(), source.propertyKeys());
    }

    /** 构造不含JWT、但绑定可信身份、运行版本及规范化过滤的无歧义JSON数组。 */
    private static String cursorBinding(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            List<String> filters) {
        ArrayNode array = JSON.createArrayNode();
        array.add(identity.tenantId().toString()).add(identity.projectId().toString()).add("APP")
                .add(identity.appUserId().toString()).add(Long.toString(identity.projectGeneration())).add(context.appKey())
                .add(context.applicationVersionId().toString()).add(Long.toString(context.publicationRevision()))
                .add(context.dashboardVersionId().toString());
        filters.forEach(array::add);
        return JSON.writeValueAsString(array);
    }

    /** 客户端参数、声明或模型失配统一10001。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }

    /** 告警明确设备不可见按既有App 60010隐藏。 */
    private static BusinessException deviceNotFound() {
        return new BusinessException(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND);
    }

    /** 跨模块端口返回越界事实属于内部合同破坏。 */
    private static IllegalStateException drift(String message) {
        return new IllegalStateException(message);
    }
}
