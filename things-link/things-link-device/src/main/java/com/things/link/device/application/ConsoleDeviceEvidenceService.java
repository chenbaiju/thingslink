package com.things.link.device.application;

import com.things.link.device.domain.DeviceCurrentValue;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Console成员授权、设备模型验证与当前值投影均留在所属域内。 */
@Service
public class ConsoleDeviceEvidenceService {
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final PublicDeviceReadService catalog;
    private final DeviceRuntimeDataService runtime;
    private final DeviceCurrentValueService values;
    private final Clock clock;

    public ConsoleDeviceEvidenceService(ProjectService projects, TransactionLocalRlsScope scope,
            PublicDeviceReadService catalog, DeviceRuntimeDataService runtime,
            DeviceCurrentValueService values, Clock clock) {
        this.projects = projects; this.scope = scope; this.catalog = catalog;
        this.runtime = runtime; this.values = values; this.clock = clock;
    }

    /** 自有短事务完成取证；不向调用者暴露领域类型或可信无鉴权查询。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public ConsoleDeviceEvidence read(UUID project, UUID device, UUID expected, List<String> keys) {
        authorize(project);
        if (keys == null || keys.isEmpty() || keys.size() > 10
                || keys.stream().anyMatch(k -> k == null || !k.matches("[A-Za-z0-9_-]{1,64}"))
                || keys.stream().distinct().count() != keys.size()) throw invalid();
        var detail = requireModel(project, device, expected);
        var model = catalog.model(project, expected);
        if (keys.stream().anyMatch(k -> !model.snapshot().path("properties").has(k))) throw invalid();
        Map<String, DeviceCurrentValue> found = values.findAll(project, List.of(device), keys).stream()
                .collect(Collectors.toMap(DeviceCurrentValue::propertyKey, Function.identity()));
        if (found.values().stream().anyMatch(v -> !device.equals(v.deviceId()) || !keys.contains(v.propertyKey())))
            throw new IllegalStateException("设备证据越过请求范围");
        var readAt = clock.instant();
        var properties = keys.stream().map(key -> {
            var value = found.get(key);
            var availability = value == null ? ConsoleDeviceEvidence.Availability.MISSING
                    : value.thingModelVersionId() == null ? ConsoleDeviceEvidence.Availability.SOURCE_UNKNOWN
                    : !expected.equals(value.thingModelVersionId()) ? ConsoleDeviceEvidence.Availability.MODEL_MISMATCH
                    : ConsoleDeviceEvidence.Availability.PRESENT;
            return new ConsoleDeviceEvidence.Property(key,
                    availability == ConsoleDeviceEvidence.Availability.PRESENT ? value.value().deepCopy() : null,
                    value == null ? null : value.occurredAt(), value == null ? null : value.reportedRevision(),
                    value == null ? null : value.thingModelVersionId(), readAt, availability);
        }).toList();
        return new ConsoleDeviceEvidence(device, expected, detail.deviceStatus(), detail.lastOnlineAt(), readAt, properties);
    }

    /** 独立新事务复核，不能复用采集事务的旧成员或模型快照。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public void revalidate(UUID project, UUID device, UUID expected) {
        authorize(project);
        requireModel(project, device, expected);
    }

    private void authorize(UUID project) {
        if (project == null || !Objects.equals(project, TenantContext.require().projectId()))
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        projects.requireRoleInProject(project);
        scope.establish(projects.requireProjectTenant(project), project);
    }

    private PublicDeviceReadService.Detail requireModel(UUID project, UUID device, UUID expected) {
        if (device == null || expected == null) throw invalid();
        var detail = catalog.detail(project, device);
        if (!expected.equals(detail.currentModelVersionId()))
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
        if (!expected.equals(runtime.validatedCurrentModelVersions(project, Set.of(device)).get(device)))
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
        return detail;
    }

    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
