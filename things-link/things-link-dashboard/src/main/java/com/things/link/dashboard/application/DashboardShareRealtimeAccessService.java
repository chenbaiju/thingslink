package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 分享合同§4实时握手、订阅及发送前权威复验；不读取属性值，也不借用App绑定或线程主体。 */
@Service
public class DashboardShareRealtimeAccessService {
    /** 加入本次事务恢复可信RLS并复验token、资源、代次及完整精确版本。 */
    private final DashboardShareRuntimeService runtime;
    /** 保留原变量binding，避免同模型属性并集扩大能力。 */
    private final DashboardSharePlanValidator plans;
    /** 普通RLS下查询设备与模型属性定义，不通过当前值接口执行授权。 */
    private final DeviceRuntimeDataService devices;

    /** 明确跨域公开端口依赖，不访问其他模块私表。 */
    public DashboardShareRealtimeAccessService(DashboardShareRuntimeService runtime,
            DashboardSharePlanValidator plans, DeviceRuntimeDataService devices) {
        this.runtime = runtime;
        this.plans = plans;
        this.devices = devices;
    }

    /** 握手或静默连接巡检仍须新事务复验，不能延长filter曾建立身份的有效期。 */
    @DataPlaneDatabase
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DashboardSharePrincipal requireIdentity(DashboardSharePrincipal principal) {
        return guarded(() -> {
            runtime.read(principal);
            return principal;
        });
    }

    /** 订阅与实际发送共用完整证明；任何设备或键失权都拒绝整包，返回值不携带属性事实。 */
    @DataPlaneDatabase
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DashboardSharePrincipal requireSubscriptions(DashboardSharePrincipal principal,
            Map<UUID, Set<String>> subscribed) {
        return guarded(() -> {
            DashboardShareReadContext context = runtime.read(principal);
            if (subscribed == null || subscribed.isEmpty() || subscribed.size() > 20) throw invalid();
            Map<UUID, UUID> modelByDevice = new HashMap<>();
            context.scopes().forEach(scope -> scope.deviceIds().forEach(device -> {
                UUID previous = modelByDevice.putIfAbsent(device, scope.modelVersionId());
                if (previous != null && !previous.equals(scope.modelVersionId())) throw drift();
            }));
            List<RuntimeDeviceQuery> requested = new ArrayList<>();
            for (Map.Entry<UUID, Set<String>> entry : subscribed.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().stream().anyMatch(java.util.Objects::isNull)) throw invalid();
                UUID model = modelByDevice.get(entry.getKey());
                if (model == null) throw forbidden();
                requested.add(new RuntimeDeviceQuery(entry.getKey(), model, List.copyOf(entry.getValue())));
            }
            plans.requireCurrent(context, requested);
            Map<UUID, RuntimeModelReference> references = new LinkedHashMap<>();
            for (JsonNode node : context.version().schema().path("models")) {
                UUID id = UUID.fromString(node.path("versionId").asString());
                if (requested.stream().anyMatch(query -> query.expectedModelVersionId().equals(id))) {
                    references.put(id, new RuntimeModelReference(id, node.path("digestAlgorithm").asString(),
                            node.path("digest").asString(), node.path("profile").asString()));
                }
            }
            if (!references.keySet().containsAll(requested.stream().map(RuntimeDeviceQuery::expectedModelVersionId).toList())) throw drift();
            com.things.link.device.application.RuntimeDeviceSnapshotResult snapshot;
            try {
                snapshot = devices.querySnapshots(principal.projectId(), requested, List.copyOf(references.values()));
            } catch (BusinessException failure) {
                if (failure.errorCode() != CommonErrorCode.INVALID_PARAMETER) throw failure;
                BusinessException mapped = forbidden();
                mapped.initCause(failure);
                throw mapped;
            }
            if (!snapshot.devices().stream().map(RuntimeDeviceAvailability::deviceId).toList()
                    .equals(requested.stream().map(RuntimeDeviceQuery::deviceId).toList())) throw drift();
            if (snapshot.devices().stream().anyMatch(device -> device.status() == RuntimeDeviceAvailability.Status.NOT_AVAILABLE)) {
                throw new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND);
            }
            if (snapshot.devices().stream().anyMatch(device -> device.status() != RuntimeDeviceAvailability.Status.AVAILABLE)) throw forbidden();
            Map<UUID, Set<String>> expectedKeys = new HashMap<>();
            requested.forEach(query -> expectedKeys.computeIfAbsent(query.expectedModelVersionId(), ignored -> new HashSet<>())
                    .addAll(query.propertyKeys()));
            Set<UUID> seen = new HashSet<>();
            for (var model : snapshot.models()) {
                RuntimeModelReference expected = references.get(model.versionId());
                if (!seen.add(model.versionId()) || expected == null || !expected.digest().equals(model.digest())
                        || !expected.digestAlgorithm().equals(model.digestAlgorithm()) || !expected.profile().equals(model.profile())
                        || !new HashSet<>(model.properties().stream().map(property -> property.propertyKey()).toList())
                        .equals(expectedKeys.get(model.versionId()))) throw drift();
            }
            if (!seen.equals(references.keySet())) throw drift();
            return principal;
        });
    }

    /** WS内部参数格式错误保持10001；设备模型或属性在权威快照失配属于能力失权。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 失去任意有限范围即清理整连接，不返回部分成功订阅。 */
    private static BusinessException forbidden() { return new BusinessException(DashboardErrorCode.SHARE_SCOPE_FORBIDDEN); }
    /** 仓储/模型投影不完整不能被当成仍有权，按依赖异常关闭连接。 */
    private static IllegalStateException drift() { return new IllegalStateException("分享实时权威模型或设备投影漂移"); }
    /** 保留确权失效分类，数据库与持久完整性故障统一60055且不回显原文。 */
    private static <T> T guarded(Supplier<T> action) {
        try { return action.get(); }
        catch (BusinessException failure) {
            if (Set.of(10001, 60053, 60054, 60055).contains(failure.errorCode().code())) throw failure;
            BusinessException mapped = new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            mapped.initCause(failure);
            throw mapped;
        }
        catch (RuntimeException failure) {
            BusinessException mapped = new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            mapped.initCause(failure);
            throw mapped;
        }
    }
}
