package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceSearchQuery;
import com.things.link.device.domain.DeviceType;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 设备实例应用服务，负责项目成员校验与事务边界。 */
@Service
public class DeviceService {
    /** 设备仓储。 */ private final DeviceRepository repository;
    /** 项目成员服务。 */ private final ProjectService projectService;
    /** 从当前已授权项目解析 owner tenant，跨租户协作者不能使用 JWT tenant 记账。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider;
    /** 复用 S7-2 的真实设备存量投影判定租户共享硬限。 */
    private final ProjectQuotaService projectQuotaService;
    /** ADR0056：软删先通过拓扑唯一写者原子关闭关系，禁止设备服务单独改gateway_id。 */
    private final DeviceTopologyService topologyService;
    /** ADR0057：设备换型与类型变更/拓扑建立共享同一角色保护。 */
    private final DeviceTopologyRoleGuard roleGuard;
    /** 配置/删除沿同一原子接入控制边界。 */
    private final DeviceAccessControlService accessControl;
    private final DeviceAccessTypeGuard accessTypes;

    /** @param repository 设备仓储 @param projectService 项目成员服务 */
    public DeviceService(DeviceRepository repository, ProjectService projectService,
                         EffectiveQuotaPolicyProvider quotaPolicyProvider,
                         ProjectQuotaService projectQuotaService,
                         DeviceTopologyService topologyService, DeviceTopologyRoleGuard roleGuard,
                         DeviceAccessControlService accessControl, DeviceAccessTypeGuard accessTypes) {
        this.repository = repository;
        this.projectService = projectService;
        this.quotaPolicyProvider = quotaPolicyProvider;
        this.projectQuotaService = projectQuotaService;
        this.topologyService = topologyService;
        this.roleGuard = roleGuard;
        this.accessControl = accessControl; this.accessTypes = accessTypes;
    }

    /** @param projectId 项目 ID @return 设备列表 */
    @Transactional(readOnly = true, timeout = 3)
    public List<Device> list(UUID projectId) {
        requireMember(projectId);
        var page = repository.search(new DeviceSearchQuery(projectId, null, Set.of(), Set.of(),
                null, null, null, null, 200), null);
        if (page.hasMore()) {
            throw new BusinessException(DeviceErrorCode.LEGACY_LIST_LIMIT_EXCEEDED);
        }
        return page.items();
    }

    /** @param projectId 项目 ID @param id 设备 ID @return 设备详情 */
    @Transactional(readOnly = true)
    public Device detail(UUID projectId, UUID id) {
        requireMember(projectId);
        return repository.findById(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
    }

    /** 创建设备。 */
    @Transactional
    public Device create(UUID projectId, UUID deviceTypeId, String deviceKey, String name,
                         String description, String location) {
        requireManager(projectId);
        requireType(projectId, deviceTypeId);
        TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        UUID ownerTenantId = quotaPolicyProvider.resolveTrustedProject(projectId).tenantId();
        repository.lockTenantDeviceQuota(ownerTenantId);
        QuotaStatus quotaStatus = projectQuotaService.deviceQuotaStatus(projectId);
        if (quotaStatus == QuotaStatus.HARD_LIMIT || quotaStatus == QuotaStatus.DEGRADED) {
            throw new BusinessException(DeviceErrorCode.DEVICE_QUOTA_EXCEEDED);
        }
        Device device = new Device(Uuid7.generate(), ownerTenantId, projectId, deviceTypeId, null,
                deviceKey.strip(), name.strip(), blankToNull(description), Device.Status.INACTIVE,
                blankToNull(location), null, java.time.Instant.now());
        try { repository.create(device); } catch (DuplicateKeyException e) { throw conflict(); }
        return device;
    }

    /** 修改设备基础信息与绑定的设备类型。 */
    @Transactional
    public Device update(UUID projectId, UUID id, UUID deviceTypeId, String name, String description, String location) {
        requireManager(projectId);
        Device current = repository.findByIdForUpdate(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        DeviceType candidate = requireType(projectId, deviceTypeId);
        accessTypes.requireDevice(current.tenantId(), projectId, id, candidate);
        roleGuard.requireDeviceRole(projectId, id, candidate == null ? null : candidate.deviceKind());
        // ADR0059：设备锁后重查绑定事实；同分类换型也不能把旧固件的版本解释成另一类型。
        boolean changingType = !Objects.equals(current.deviceTypeId(), deviceTypeId);
        if (changingType && repository.hasModelVersionBinding(projectId, id))
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_VERSION_CONFLICT);
        // ADR0059的数据库复合FK/行守卫另保护直接写入；此处先返回明确的领域冲突并保留整笔回滚。
        Device updated = new Device(current.id(), current.tenantId(), current.projectId(),
                deviceTypeId, current.gatewayId(), current.deviceKey(),
                name.strip(), blankToNull(description), current.status(),
                blankToNull(location), current.lastOnlineAt(), current.createdAt());
        if (!DeviceTopologyRoleGuard.controlWrite(() -> repository.update(updated)))
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        // 仅明确换到已发布类型才首次绑定；同type编辑不得悄悄修复存量空指针。
        // type共享锁保持至事务结束，发布拿排他锁；缺版本或绑定失败必须回滚刚写的全部基本信息。
        if (changingType && candidate != null && candidate.status() == DeviceType.Status.PUBLISHED
                && !repository.bindInitialModelVersion(projectId, id, deviceTypeId))
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_VERSION_CONFLICT);
        return updated;
    }

    /** 软删除设备。 */
    @Transactional
    public void delete(UUID projectId, UUID id) {
        requireManager(projectId);
        // ADR0056：同事务先锁定目标并关闭拓扑；后续软删失败时关系与状态也必须回滚。
        accessControl.closeForDeletion(projectId, id);
        topologyService.detachForDeletion(projectId, id);
        if (!DeviceTopologyRoleGuard.controlWrite(() -> repository.softDelete(projectId, id)))
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
    }

    /** @return 唯一冲突业务异常 */
    private static BusinessException conflict() { return new BusinessException(DeviceErrorCode.DEVICE_KEY_CONFLICT); }
    /** @param value 可空文本 @return 去空格值 */
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    /** 可空类型存在时必须属于当前项目，否则会形成跨租户物模型引用。 */
    private DeviceType requireType(UUID projectId, UUID deviceTypeId) {
        if (deviceTypeId == null) return null;
        return roleGuard.findTypeForControlPlane(projectId, deviceTypeId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }
    /** @param projectId 项目 ID @return 成员角色 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.requireRoleInProject(projectId);
    }
    /** @param projectId 项目 ID */
    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
    }
}
