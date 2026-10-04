package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DevicePropertyDefinitionRepository;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceTopology;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ModbusPointMapping;
import com.things.link.device.domain.ModbusPointMappingRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Modbus 点位映射控制面（S10-4a）。
 *
 * <p>点位挂在<b>网关设备</b>上，把 Modbus 寄存器绑定到某具体子设备的属性。写端口限定网关类型为
 * {@code STANDARD_GATEWAY} / {@code MODBUS_RTU_CLOUD_GATEWAY}（ADR 0034）；点位只在 {@code DRAFT} 状态可编辑，
 * {@code publish} 冻结为不可变版本（对齐设备类型发布语义）。</p>
 */
@Service
public class ModbusPointMappingService {
    /** 点位仓储。 */ private final ModbusPointMappingRepository repository;
    /** 设备仓储。 */ private final DeviceRepository deviceRepository;
    /** 设备类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 拓扑仓储。 */ private final DeviceTopologyRepository topologyRepository;
    /** 属性定义仓储。 */ private final DevicePropertyDefinitionRepository propertyRepository;
    /** 项目成员服务。 */ private final ProjectService projectService;
    /** 配置下发端口，发布后触发下发。 */ private final ModbusConfigService configService;

    /**
     * @param repository 点位仓储
     * @param deviceRepository 设备仓储
     * @param typeRepository 类型仓储
     * @param topologyRepository 拓扑仓储
     * @param propertyRepository 属性定义仓储
     * @param projectService 项目服务
     * @param configService 配置下发端口
     */
    public ModbusPointMappingService(ModbusPointMappingRepository repository, DeviceRepository deviceRepository,
                                     DeviceTypeRepository typeRepository,
                                     DeviceTopologyRepository topologyRepository,
                                     DevicePropertyDefinitionRepository propertyRepository,
                                     ProjectService projectService,
                                     ModbusConfigService configService) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.typeRepository = typeRepository;
        this.topologyRepository = topologyRepository;
        this.propertyRepository = propertyRepository;
        this.projectService = projectService;
        this.configService = configService;
    }

    /** @param projectId 项目 ID @param deviceId 网关设备 ID @return 网关全部点位（草稿与已发布） */
    @Transactional(readOnly = true)
    public List<ModbusPointMapping> list(UUID projectId, UUID deviceId) {
        requireMember(projectId);
        requireModbusGateway(projectId, deviceId);
        return repository.findByDevice(projectId, deviceId);
    }

    /** 创建草稿点位。 */
    @Transactional
    public ModbusPointMapping create(UUID projectId, UUID deviceId, UUID subDeviceId, String propertyKey,
                                     int slaveAddress, ModbusPointMapping.FunctionCode functionCode,
                                     int registerAddress, ModbusPointMapping.DataType dataType,
                                     ModbusPointMapping.ByteOrder byteOrder, BigDecimal scale, BigDecimal offset,
                                     int pollingIntervalMs) {
        requireManager(projectId);
        requireModbusGateway(projectId, deviceId);
        validatePoint(projectId, deviceId, subDeviceId, propertyKey, slaveAddress, functionCode, registerAddress,
                dataType, null);
        TenantScope scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        Instant now = Instant.now();
        ModbusPointMapping point = new ModbusPointMapping(Uuid7.generate(), scope.tenantId(), projectId, deviceId,
                subDeviceId, propertyKey.strip(), slaveAddress, functionCode, registerAddress, dataType, byteOrder,
                scale, offset, pollingIntervalMs, 1, ModbusPointMapping.Status.DRAFT, now, now);
        repository.create(point);
        return point;
    }

    /** 修改草稿点位。 */
    @Transactional
    public ModbusPointMapping update(UUID projectId, UUID id, UUID subDeviceId, String propertyKey,
                                     int slaveAddress, ModbusPointMapping.FunctionCode functionCode,
                                     int registerAddress, ModbusPointMapping.DataType dataType,
                                     ModbusPointMapping.ByteOrder byteOrder, BigDecimal scale, BigDecimal offset,
                                     int pollingIntervalMs) {
        requireManager(projectId);
        ModbusPointMapping current = repository.findById(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.MODBUS_POINT_NOT_FOUND));
        requireDraft(current);
        requireModbusGateway(projectId, current.deviceId());
        validatePoint(projectId, current.deviceId(), subDeviceId, propertyKey, slaveAddress, functionCode,
                registerAddress, dataType, id);
        ModbusPointMapping updated = new ModbusPointMapping(current.id(), current.tenantId(), current.projectId(),
                current.deviceId(), subDeviceId, propertyKey.strip(), slaveAddress, functionCode, registerAddress,
                dataType, byteOrder, scale, offset, pollingIntervalMs, current.version(), current.status(),
                current.createdAt(), Instant.now());
        if (!repository.update(updated)) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_NOT_FOUND);
        }
        return updated;
    }

    /** 删除草稿点位。 */
    @Transactional
    public void delete(UUID projectId, UUID id) {
        requireManager(projectId);
        ModbusPointMapping current = repository.findById(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.MODBUS_POINT_NOT_FOUND));
        requireDraft(current);
        if (!repository.delete(projectId, id)) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_NOT_FOUND);
        }
    }

    /** 发布冻结：网关全部草稿点位变为已发布，版本取现有最大已发布版本 + 1；随后下发配置。 */
    @Transactional
    public void publish(UUID projectId, UUID deviceId) {
        requireManager(projectId);
        requireModbusGateway(projectId, deviceId);
        repository.publish(projectId, deviceId);
        configService.pushConfig(projectId, deviceId);
    }

    /** 校验点位到子设备属性的完整约束；excludeId 用于更新时排除自身参与重叠比较。 */
    private void validatePoint(UUID projectId, UUID deviceId, UUID subDeviceId, String propertyKey,
                               int slaveAddress, ModbusPointMapping.FunctionCode functionCode,
                               int registerAddress, ModbusPointMapping.DataType dataType, UUID excludeId) {
        requireSlaveAddress(slaveAddress);
        requireFunctionDataType(functionCode, dataType);
        DeviceType subType = requireBoundSubDevice(projectId, deviceId, subDeviceId);
        requireProperty(projectId, subType.id(), propertyKey, dataType);
        requireNoOverlap(projectId, deviceId, excludeId, slaveAddress, functionCode, registerAddress, dataType);
    }

    /** 校验网关存在且类型为 STANDARD_GATEWAY / MODBUS_RTU_CLOUD_GATEWAY。 */
    private Device requireModbusGateway(UUID projectId, UUID deviceId) {
        Device device = deviceRepository.findById(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        DeviceType type = typeRepository.findById(projectId, device.deviceTypeId())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
        if (type.payloadProtocol() != DeviceType.PayloadProtocol.STANDARD_GATEWAY
                && type.payloadProtocol() != DeviceType.PayloadProtocol.MODBUS_RTU_CLOUD_GATEWAY) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_DEVICE_TYPE_INVALID);
        }
        return device;
    }

    /** 校验子设备存在、是 SUB_DEVICE 类型且有效绑定到该网关；返回子设备类型。 */
    private DeviceType requireBoundSubDevice(UUID projectId, UUID deviceId, UUID subDeviceId) {
        Device subDevice = deviceRepository.findById(projectId, subDeviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.MODBUS_POINT_SUB_DEVICE_INVALID));
        DeviceType subType = typeRepository.findById(projectId, subDevice.deviceTypeId())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.MODBUS_POINT_SUB_DEVICE_INVALID));
        if (subType.deviceKind() != DeviceType.DeviceKind.SUB_DEVICE) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_SUB_DEVICE_INVALID);
        }
        DeviceTopology binding = topologyRepository.findActiveBySubDevice(projectId, subDeviceId).orElse(null);
        if (binding == null || !binding.gatewayDeviceId().equals(deviceId)) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_SUB_DEVICE_INVALID);
        }
        return subType;
    }

    /** 校验属性存在、可上报且数据类型与 Modbus 数据类型匹配。 */
    private void requireProperty(UUID projectId, UUID subTypeId, String propertyKey,
                                 ModbusPointMapping.DataType dataType) {
        DevicePropertyDefinition definition = propertyRepository.findByPropertyKey(projectId, subTypeId, propertyKey)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.MODBUS_POINT_PROPERTY_INVALID));
        if (definition.accessType() != DevicePropertyDefinition.AccessType.REPORT
                && definition.accessType() != DevicePropertyDefinition.AccessType.SHARED) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_PROPERTY_INVALID);
        }
        if (!propertyTypeMatches(dataType, definition.dataType())) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_PROPERTY_INVALID);
        }
    }

    /** 校验同一网关内「同从站 + 功能码」的寄存器区间不重叠。 */
    private void requireNoOverlap(UUID projectId, UUID deviceId, UUID excludeId, int slaveAddress,
                                  ModbusPointMapping.FunctionCode functionCode, int registerAddress,
                                  ModbusPointMapping.DataType dataType) {
        int from = registerAddress;
        int to = registerAddress + dataType.width();
        for (ModbusPointMapping existing : repository.findByDevice(projectId, deviceId)) {
            if (existing.id().equals(excludeId)) {
                continue;
            }
            if (existing.slaveAddress() != slaveAddress || existing.functionCode() != functionCode) {
                continue;
            }
            int existingFrom = existing.registerAddress();
            int existingTo = existing.registerAddress() + existing.dataType().width();
            if (from < existingTo && existingFrom < to) {
                throw new BusinessException(DeviceErrorCode.MODBUS_POINT_REGISTER_OVERLAP);
            }
        }
    }

    /** @param dataType Modbus 数据类型 @param propertyType 属性数据类型 @return 是否兼容 */
    private static boolean propertyTypeMatches(ModbusPointMapping.DataType dataType,
                                               DevicePropertyDefinition.DataType propertyType) {
        return switch (dataType) {
            case BIT -> propertyType == DevicePropertyDefinition.DataType.SWITCH;
            case INT16, UINT16, INT32, UINT32, FLOAT32 -> propertyType == DevicePropertyDefinition.DataType.NUMBER;
        };
    }

    /** @param slaveAddress 从站地址 */
    private static void requireSlaveAddress(int slaveAddress) {
        if (slaveAddress < 1 || slaveAddress > 247) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_CONSTRAINT_INVALID, "从站地址必须在 1..247");
        }
    }

    /** @param functionCode 功能码 @param dataType 数据类型 */
    private static void requireFunctionDataType(ModbusPointMapping.FunctionCode functionCode,
                                                ModbusPointMapping.DataType dataType) {
        boolean coil = functionCode == ModbusPointMapping.FunctionCode.READ_COILS
                || functionCode == ModbusPointMapping.FunctionCode.READ_DISCRETE_INPUTS;
        if (coil && dataType != ModbusPointMapping.DataType.BIT) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_CONSTRAINT_INVALID,
                    "线圈/离散输入功能码只支持 BIT 数据类型");
        }
        if (!coil && dataType == ModbusPointMapping.DataType.BIT) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_CONSTRAINT_INVALID,
                    "寄存器功能码不支持 BIT 数据类型");
        }
    }

    /** @param point 点位 */
    private static void requireDraft(ModbusPointMapping point) {
        if (point.status() != ModbusPointMapping.Status.DRAFT) {
            throw new BusinessException(DeviceErrorCode.MODBUS_POINT_PUBLISHED_IMMUTABLE);
        }
    }

    /** @param projectId 项目 ID @return 成员角色 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.roleInProject(projectId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }

    /** @param projectId 项目 ID */
    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN);
        }
    }
}
