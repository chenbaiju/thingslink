package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceTopology;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 拓扑绑定应用服务：把子设备绑定到网关、解绑、换绑，并同事务维护 {@code gateway_id} 物化投影。
 *
 * <p>{@code dev_topo} 是唯一权威事实，{@code dev_device.gateway_id} 是投影；两者同事务双写，
 * 提交前由 DEFERRABLE 约束触发器校验一致性（见迁移 V20260814_0340）。绑定与解绑都要求
 * OWNER/ADMIN，因此「跨网关换绑仅 OWNER/ADMIN」天然满足。</p>
 */
@Service
public class DeviceTopologyService {
    /** 拓扑仓储。 */ private final DeviceTopologyRepository topologyRepository;
    /** 设备仓储，投影写与存在性校验用。 */ private final DeviceRepository deviceRepository;
    /** 项目成员服务。 */ private final ProjectService projectService;
    /** ADR0057：可变类型共享锁与当前关系角色校验。 */
    private final DeviceTopologyRoleGuard roleGuard;
    private final DevicePresenceWebhookSource webhookSource;

    /** 共享同一设备域事务与仓储，控制面授权仍由项目公开端口校验。 */
    public DeviceTopologyService(DeviceTopologyRepository topologyRepository, DeviceRepository deviceRepository,
                                 ProjectService projectService, DeviceTopologyRoleGuard roleGuard,DevicePresenceWebhookSource webhookSource) {
        this.topologyRepository = topologyRepository;
        this.deviceRepository = deviceRepository;
        this.projectService = projectService;
        this.roleGuard = roleGuard;
        this.webhookSource = webhookSource;
    }

    /**
     * 绑定（或换绑）子设备到网关。
     *
     * <p>同一子设备重复绑定到同一网关是幂等的（直接返回现有绑定）；绑定到不同网关则先关闭
     * 旧绑定再新建，形成换绑轨迹。并发绑定由部分唯一索引仲裁，撞约束统一返回 30039。</p>
     *
     * @param projectId 项目 ID @param subDeviceId 子设备 ID @param gatewayId 网关 ID @return 有效绑定
     */
    @Transactional
    public DeviceTopology bind(UUID projectId, UUID subDeviceId, UUID gatewayId) {
        requireManager(projectId);
        var presenceGeneration=webhookSource.captureForConsole(projectId);
        if (subDeviceId.equals(gatewayId)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_SELF_BIND);
        }
        Device gateway = requireKind(projectId, deviceRepository.findByIdForKeyShare(projectId, gatewayId).orElse(null),
                DeviceType.DeviceKind.GATEWAY,
                DeviceErrorCode.DEVICE_TOPOLOGY_GATEWAY_INVALID);
        Device subDevice = requireKind(projectId, deviceRepository.findByIdForUpdate(projectId, subDeviceId).orElse(null),
                DeviceType.DeviceKind.SUB_DEVICE,
                DeviceErrorCode.DEVICE_TOPOLOGY_SUB_DEVICE_INVALID);
        TenantScope scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));

        DeviceTopology existing = topologyRepository.findActiveBySubDevice(projectId, subDeviceId).orElse(null);
        if (existing != null && existing.gatewayDeviceId().equals(gatewayId)) {
            return existing;
        }
        if (existing != null) {
            // 换绑：先关闭旧绑定，释放部分唯一索引槽位，再建新绑定。
            topologyRepository.closeActive(projectId, subDeviceId, scope.accountId());
        }

        DeviceTopology topology = new DeviceTopology(Uuid7.generate(), subDevice.tenantId(), projectId,
                gatewayId, subDeviceId, DeviceTopology.BindSource.CONTROL_PLANE,
                DeviceTopology.OnlineStatus.UNKNOWN, null, null, scope.accountId(), Instant.now(),
                null, null, 1, Instant.now());
        try {
            DeviceTopologyRoleGuard.controlWrite(() -> {
                topologyRepository.create(topology);
                return null;
            });
        } catch (DuplicateKeyException exception) {
            // 两个请求并发为同一子设备绑定：部分唯一索引保证至多一条有效绑定，后提交者撞约束。
            throw new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_CONFLICT);
        }
        if (!deviceRepository.setGatewayId(projectId, subDeviceId, gatewayId)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_SUB_DEVICE_INVALID);
        }
        deviceRepository.setStatus(projectId, subDeviceId, subDevice.statusWithoutGatewayReachability(), null);
        webhookSource.append(subDevice.tenantId(),projectId,subDeviceId,presenceGeneration,subDevice.status().name(),
                subDevice.statusWithoutGatewayReachability().name(),"TOPOLOGY","TOPOLOGY_REBOUND",null,
                existing==null?null:existing.gatewayDeviceId(),null);
        return topology;
    }

    /** 解绑子设备；幂等，无有效绑定时为 no-op。 */
    @Transactional
    public void unbind(UUID projectId, UUID subDeviceId) {
        requireManager(projectId);
        var presenceGeneration=webhookSource.captureForConsole(projectId);
        TenantScope scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        Device subDevice = deviceRepository.findByIdForUpdate(projectId, subDeviceId).orElse(null);
        if (subDevice != null) {
            closeBinding(projectId, subDevice, scope.accountId(),presenceGeneration,"TOPOLOGY_UNBOUND");
        }
    }

    /**
     * ADR0056：在设备软删的同一事务关闭双向拓扑事实，保留子设备与历史身份。
     * 目标排他锁阻止新绑定越过存在性检查；多子按UUID文本排序，与PostgreSQL字节顺序一致。
     * ADR0057先拒绝分类漂移，不能在多设备锁之后才发现角色冲突；正常关系仍按权威事实关闭。
     */
    @Transactional
    public void detachForDeletion(UUID projectId, UUID deviceId) {
        requireManager(projectId);
        var presenceGeneration=webhookSource.captureForConsole(projectId);
        Device target = deviceRepository.findByIdForUpdate(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        DeviceType currentType = roleGuard.findTypeForControlPlane(projectId, target.deviceTypeId()).orElse(null);
        roleGuard.requireDeviceRole(projectId, target.id(), currentType == null ? null : currentType.deviceKind());
        roleGuard.requireGatewayComponent(projectId, target.id());
        UUID actor = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文")).accountId();
        closeBinding(projectId, target, actor,presenceGeneration,"DEVICE_DELETED");
        var bindings = topologyRepository.findActiveByGateway(projectId, deviceId).stream()
                .sorted(Comparator.comparing(binding -> binding.subDeviceId().toString())).toList();
        for (DeviceTopology snapshot : bindings) {
            Device child = deviceRepository.findByIdForUpdate(projectId, snapshot.subDeviceId()).orElse(null);
            if (child == null) {
                // 历史已软删子设备的脏关系由ADR0056存量迁移修复，不冒充本次账号刚完成的解绑。
                continue;
            }
            var current = topologyRepository.findActiveBySubDevice(projectId, child.id()).orElse(null);
            if (current != null && current.gatewayDeviceId().equals(deviceId)) {
                closeBinding(projectId, child, actor,presenceGeneration,"GATEWAY_DELETED");
            }
        }
    }

    /** 子设备行锁内关闭当前关系；无绑定的幂等解绑不得改变直连设备状态。 */
    private void closeBinding(UUID projectId, Device device, UUID actor,java.util.OptionalLong presenceGeneration,String reason) {
        var existing=topologyRepository.findActiveBySubDevice(projectId,device.id()).orElse(null);
        if (topologyRepository.closeActive(projectId, device.id(), actor)) {
            deviceRepository.setGatewayId(projectId, device.id(), null);
            deviceRepository.setStatus(projectId, device.id(), device.statusWithoutGatewayReachability(), null);
            webhookSource.append(device.tenantId(),projectId,device.id(),presenceGeneration,device.status().name(),
                    device.statusWithoutGatewayReachability().name(),"TOPOLOGY",reason,null,
                    existing==null?null:existing.gatewayDeviceId(),null);
        }
    }

    /** @param projectId 项目 ID @param gatewayId 网关 ID @return 网关当前挂载的有效子设备绑定 */
    @Transactional(readOnly = true)
    public List<DeviceTopology> listByGateway(UUID projectId, UUID gatewayId) {
        requireMember(projectId);
        return topologyRepository.findActiveByGateway(projectId, gatewayId);
    }

    /** @param projectId 项目 ID @return 项目内全部有效绑定，供控制台拓扑树一次拉取 */
    @Transactional(readOnly = true)
    public List<DeviceTopology> listAll(UUID projectId) {
        requireMember(projectId);
        return topologyRepository.findActiveByProject(projectId);
    }

    /** 校验设备存在且类型匹配指定分类，非法时抛统一业务码，避免泄露跨项目设备存在性。 */
    private Device requireKind(UUID projectId, Device device, DeviceType.DeviceKind expected,
                               DeviceErrorCode errorCode) {
        if (device == null || device.deviceTypeId() == null) {
            throw new BusinessException(errorCode);
        }
        DeviceType type = roleGuard.findTypeForControlPlane(projectId, device.deviceTypeId())
                .orElseThrow(() -> new BusinessException(errorCode));
        if (type.deviceKind() != expected) {
            throw new BusinessException(errorCode);
        }
        roleGuard.requireDeviceRole(projectId, device.id(), type.deviceKind());
        return device;
    }

    /** @param projectId 项目 ID @return 成员角色 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.requireRoleInProject(projectId);
    }

    /** @param projectId 项目 ID */
    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
        }
    }
}
