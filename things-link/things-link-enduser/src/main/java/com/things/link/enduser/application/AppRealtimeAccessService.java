package com.things.link.enduser.application;

import com.things.link.dashboard.application.DashboardRuntimeDeviceRequest;
import com.things.link.dashboard.application.DashboardRuntimePlanValidator;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.AppDevice;
import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADR0099及数据运行合同§6的App实时身份与有界设备授权公开端口。
 *
 * <p>握手、订阅和异步发送分别开启独立只读事务；可信JWT身份恢复事务局部RLS，
 * 不依赖WebSocket工作线程上的HTTP上下文。失权整体拒绝，不缩小集合后继续发送。</p>
 */
@Service
public class AppRealtimeAccessService {
    /** 合同§6固定单连接最多20台设备，不能由调用方扩容。 */
    private static final int MAX_DEVICES = 20;
    /** 共用用户、角色及项目生命周期代次门禁，并建立当前事务RLS。 */
    private final AppRuntimeIdentityService identities;
    /** 仅查询指定设备的当前有效绑定，禁止扫描用户全量关系。 */
    private final AppUserDeviceRepository bindings;
    /** device拥有设备软删和项目归属事实，enduser不得跨表读取。 */
    private final AppDeviceDataPlaneService devices;
    /** ADR0100要求实时提示同样复验当前入口、发布版本与READ grant。 */
    private final WebAppDashboardSchemaService schemas;
    /** 设备域提供当前模型，不能由客户端自行声明。 */
    private final DeviceRuntimeDataService runtimeDevices;
    /** 复用Schema模型、键及设备数计划验证，不复制解析器。 */
    private final DashboardRuntimePlanValidator plans;

    /** 创建实时确权端口，保留各领域既有公开查询边界。 */
    public AppRealtimeAccessService(AppRuntimeIdentityService identities, AppUserDeviceRepository bindings,
            AppDeviceDataPlaneService devices, WebAppDashboardSchemaService schemas,
            DeviceRuntimeDataService runtimeDevices, DashboardRuntimePlanValidator plans) {
        this.identities = identities;
        this.bindings = bindings;
        this.devices = devices;
        this.schemas = schemas;
        this.runtimeDevices = runtimeDevices;
        this.plans = plans;
    }

    /** 握手只复验身份；此时没有订阅设备，不宣称已校设备权限。 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void validateIdentity(AppAuthenticatedPrincipal identity) {
        requireIdentity(identity);
    }

    /**
     * 订阅和每次实际发送前重新校验完整设备集合；任一失权均60010。
     *
     * @param identity 已验签且尚未过期的App JWT身份
     * @param requestedDevices 明确请求的1..20个设备，不能为空或含null
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireDevices(AppAuthenticatedPrincipal identity, Set<UUID> requestedDevices) {
        requireIdentity(identity);
        requireBoundDevices(identity, requestedDevices);
    }

    /**
     * 订阅及每次发送复验四字段运行上下文与READ grant，再整体核当前设备绑定。
     *
     * @param identity 已验证App身份
     * @param context 应用公开键、应用版本、发布代次和看板版本
     * @param subscribed 当前固定订阅设备及属性键集合
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireRuntime(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            Map<UUID, Set<String>> subscribed) {
        if (identity == null) throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        if (context == null) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        // schema加入本事务并恢复可信RLS，同时复验身份、精确发布关系和grant，避免同类事务自调用。
        var schema = schemas.schema(identity.tenantId(), identity.projectId(), identity.appUserId(), identity.projectGeneration(),
                context.appKey(), context.applicationVersionId(), context.publicationRevision(), context.dashboardVersionId());
        if (subscribed == null || subscribed.isEmpty() || subscribed.size() > MAX_DEVICES
                || subscribed.values().stream().anyMatch(keys -> keys == null || keys.isEmpty()
                        || keys.size() > 50 || keys.stream().anyMatch(java.util.Objects::isNull))
                || subscribed.values().stream().mapToInt(Set::size).sum() > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        requireBoundDevices(identity, subscribed.keySet());
        Map<UUID, UUID> models = runtimeDevices.currentModelVersions(identity.projectId(), subscribed.keySet());
        if (!models.keySet().equals(subscribed.keySet()) || models.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("实时当前模型端口未返回完整设备范围");
        }
        plans.requireCurrentValues(schema, subscribed.entrySet().stream().map(entry ->
                new DashboardRuntimeDeviceRequest(entry.getKey(), models.get(entry.getKey()), List.copyOf(entry.getValue())))
                .toList());
    }

    /**
     * ADR0109：两订阅域共享一次Schema/grant和合并设备复验，纯告警不制造伪属性。
     *
     * @param identity 已校验且未过期的App主体
     * @param context 精确发布运行上下文
     * @param subscribed 当前值属性域，允许为空
     * @param alarms 独立告警域，允许为空但两域不能同时为空
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireDashboardRuntime(AppAuthenticatedPrincipal identity, WebAppRuntimeContext context,
            Map<UUID, Set<String>> subscribed, List<AppRealtimeAlarmSubscription> alarms) {
        if (identity == null) throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        if (context == null || subscribed == null || alarms == null || alarms.size() > 20
                || subscribed.size() > MAX_DEVICES || subscribed.isEmpty() && alarms.isEmpty()) throw invalid();
        Set<UUID> union = new HashSet<>();
        int slots = 0;
        for (var entry : subscribed.entrySet()) {
            Set<String> keys = entry.getValue();
            if (entry.getKey() == null || keys == null || keys.isEmpty() || keys.size() > 50
                    || keys.stream().anyMatch(key -> key == null || !key.matches("[A-Za-z0-9_-]{1,64}"))) throw invalid();
            union.add(entry.getKey());
            slots += keys.size();
        }
        Set<String> queryKeys = new HashSet<>();
        for (AppRealtimeAlarmSubscription alarm : alarms) {
            if (alarm == null || alarm.queryKey() == null || !alarm.queryKey().matches("[A-Za-z0-9_-]{1,64}")
                    || !queryKeys.add(alarm.queryKey()) || alarm.devices().isEmpty() || alarm.devices().size() > MAX_DEVICES
                    || alarm.conditionStates().isEmpty() || !Set.of("PENDING", "ACTIVE", "CLEARED").containsAll(alarm.conditionStates())
                    || alarm.ackStates().isEmpty() || !Set.of("ACKNOWLEDGED", "UNACKNOWLEDGED").containsAll(alarm.ackStates())
                    || alarm.severities().isEmpty() || !Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO").containsAll(alarm.severities())) throw invalid();
            Set<UUID> unique = new HashSet<>();
            for (DashboardRuntimeDeviceRequest device : alarm.devices()) {
                if (device == null || !device.propertyKeys().isEmpty() || !unique.add(device.deviceId())) throw invalid();
                union.add(device.deviceId());
            }
            slots += alarm.devices().size();
        }
        if (union.size() > MAX_DEVICES || slots > 200) throw invalid();
        var schema = schemas.schema(identity.tenantId(), identity.projectId(), identity.appUserId(), identity.projectGeneration(),
                context.appKey(), context.applicationVersionId(), context.publicationRevision(), context.dashboardVersionId());
        requireBoundDevices(identity, union);
        Map<UUID, UUID> models = runtimeDevices.currentModelVersions(identity.projectId(), union);
        if (!models.keySet().equals(union) || models.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("实时当前模型端口未返回完整设备范围");
        }
        if (!subscribed.isEmpty()) plans.requireCurrentValues(schema, subscribed.entrySet().stream().map(entry ->
                new DashboardRuntimeDeviceRequest(entry.getKey(), models.get(entry.getKey()), List.copyOf(entry.getValue()))).toList());
        for (AppRealtimeAlarmSubscription alarm : alarms) {
            if (alarm.devices().stream().anyMatch(device -> !device.expectedModelVersionId().equals(models.get(device.deviceId())))) throw invalid();
            plans.requireAlarms(schema, alarm.devices(), alarm.conditionStates(), alarm.ackStates(), alarm.severities());
        }
    }

    /** 协议/预算/模型变化失败关闭，不把非法域降级为部分订阅。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }

    /** 已在同一事务复验身份或完整Schema后执行有界资源核验，不额外开启事务。 */
    private void requireBoundDevices(AppAuthenticatedPrincipal identity, Set<UUID> requestedDevices) {
        if (requestedDevices == null || requestedDevices.isEmpty() || requestedDevices.size() > MAX_DEVICES
                || requestedDevices.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        Set<UUID> requested = Set.copyOf(requestedDevices);
        Set<UUID> active = bindings.findActiveDeviceIds(identity.tenantId(), identity.projectId(),
                identity.appUserId(), requested);
        if (!requested.containsAll(active)) {
            throw new IllegalStateException("实时设备绑定仓储返回请求范围外设备");
        }
        if (!active.equals(requested)) throw deviceNotFound();
        // 精确白名单一次查询归属和软删，防止残留ACTIVE绑定使已删除设备继续获得提示。
        var page = devices.list(identity.projectId(), requested, null, requested.size());
        Set<UUID> visible = page.items().stream().map(AppDevice::id).collect(Collectors.toSet());
        if (page.hasMore() || page.nextCursor() != null || visible.size() != page.items().size()
                || !requested.containsAll(visible)) {
            throw new IllegalStateException("实时设备归属端口返回重复或范围外设备");
        }
        if (!visible.equals(requested)) throw deviceNotFound();
    }

    /** 在本次独立事务建立可信RLS并回库复验，不包装数据库异常而丢失首因。 */
    private void requireIdentity(AppAuthenticatedPrincipal identity) {
        if (identity == null) throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        identities.requireActive(identity.tenantId(), identity.projectId(), identity.appUserId(),
                identity.projectGeneration());
    }

    /** 未绑定、解绑、软删和错误归属统一隐藏资源存在性。 */
    private static BusinessException deviceNotFound() {
        return new BusinessException(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND);
    }
}
