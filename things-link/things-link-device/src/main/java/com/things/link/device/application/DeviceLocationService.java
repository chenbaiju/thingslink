package com.things.link.device.application;

import com.things.link.device.domain.*;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

/** PS-026a：当前点写入与生命周期、设备锁、CAS和审计同事务。 */
@Service
public class DeviceLocationService {
    private final DeviceLocationRepository locations;
    private final DeviceRepository devices;
    private final ProjectService projects;
    private final ProjectLifecycleAccessService lifecycle;
    private final AuditLogService audit;
    public DeviceLocationService(DeviceLocationRepository locations, DeviceRepository devices,
            ProjectService projects, ProjectLifecycleAccessService lifecycle, AuditLogService audit) {
        this.locations=locations; this.devices=devices; this.projects=projects;
        this.lifecycle=lifecycle; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public DeviceLocationPoint get(UUID project, UUID device) {
        projects.requireRoleInProject(project);
        return locations.find(project, device).orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
    }
    @Transactional
    public DeviceLocationPoint put(UUID project, UUID device, Double longitude, Double latitude, String versionText) {
        requireManager(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        requireManager(project);
        devices.findByIdForUpdate(project, device)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        long version;
        try { version = Long.parseLong(versionText); }
        catch (NumberFormatException e) { throw new BusinessException(DeviceErrorCode.LOCATION_POINT_INVALID); }
        if ((longitude == null) != (latitude == null) || version < 0
                || (longitude != null && (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90))) {
            throw new BusinessException(DeviceErrorCode.LOCATION_POINT_INVALID);
        }
        if (!locations.update(project, device, longitude, latitude, version)) {
            throw new BusinessException(DeviceErrorCode.LOCATION_POINT_VERSION_CONFLICT);
        }
        // 不在通用审计复制敏感精确位置；记录操作者、版本和是否清除足以追踪配置动作。
        audit.record(new AuditLogEntry(tenant, project, TenantContext.require().accountId(), "device", device,
                "DEVICE_LOCATION_POINT_CHANGED", Map.of("previousVersion", version,
                        "version", version+1, "cleared", longitude == null)));
        return locations.find(project, device).orElseThrow();
    }
    private void requireManager(UUID project) {
        ProjectRole role=projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
    }
}
