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
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Modbus 点位控制面：设备类型、子设备绑定、属性类型、寄存器重叠与发布冻结校验。 */
@ExtendWith(MockitoExtension.class)
class ModbusPointMappingServiceTests {

    @Mock private ModbusPointMappingRepository repository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceTypeRepository typeRepository;
    @Mock private DeviceTopologyRepository topologyRepository;
    @Mock private DevicePropertyDefinitionRepository propertyRepository;
    @Mock private ProjectService projectService;
    @Mock private ModbusConfigService configService;

    private ModbusPointMappingService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID gatewayId = UUID.randomUUID();
    private final UUID gatewayTypeId = UUID.randomUUID();
    private final UUID subId = UUID.randomUUID();
    private final UUID subTypeId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ModbusPointMappingService(repository, deviceRepository, typeRepository, topologyRepository,
                propertyRepository, projectService, configService);
        TenantContext.set(new TenantScope(tenantId, projectId, UUID.randomUUID()));
        lenient().when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.ADMIN));
        // 网关：STANDARD_GATEWAY 类型
        lenient().when(deviceRepository.findById(projectId, gatewayId)).thenReturn(Optional.of(new Device(
                gatewayId, tenantId, projectId, gatewayTypeId, null, "gw_01", "网关", null,
                Device.Status.ONLINE, null, null, Instant.now())));
        lenient().when(typeRepository.findById(projectId, gatewayTypeId)).thenReturn(Optional.of(new DeviceType(
                gatewayTypeId, tenantId, projectId, "gwtype", "网关类型", DeviceType.DeviceKind.GATEWAY,
                DeviceType.PayloadProtocol.STANDARD_GATEWAY, DeviceType.NetworkType.ETHERNET, 1,
                DeviceType.Status.PUBLISHED, null, null, Instant.now())));
        // 子设备：SUB_DEVICE，绑定到网关
        lenient().when(deviceRepository.findById(projectId, subId)).thenReturn(Optional.of(new Device(
                subId, tenantId, projectId, subTypeId, gatewayId, "sub_01", "子设备", null,
                Device.Status.OFFLINE, null, null, Instant.now())));
        lenient().when(typeRepository.findById(projectId, subTypeId)).thenReturn(Optional.of(new DeviceType(
                subTypeId, tenantId, projectId, "subtype", "子设备类型", DeviceType.DeviceKind.SUB_DEVICE,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.ZIGBEE, 1,
                DeviceType.Status.PUBLISHED, null, null, Instant.now())));
        lenient().when(topologyRepository.findActiveBySubDevice(projectId, subId)).thenReturn(Optional.of(
                new DeviceTopology(UUID.randomUUID(), tenantId, projectId, gatewayId, subId,
                        DeviceTopology.BindSource.CONTROL_PLANE, DeviceTopology.OnlineStatus.ONLINE, null, null,
                        null, Instant.now(), null, null, 1, Instant.now())));
        // 子设备 NUMBER 属性，可上报
        lenient().when(propertyRepository.findByPropertyKey(projectId, subTypeId, "temperature"))
                .thenReturn(Optional.of(new DevicePropertyDefinition(propertyId, tenantId, projectId, subTypeId,
                        "temperature", "温度", DevicePropertyDefinition.AccessType.REPORT,
                        DevicePropertyDefinition.DataType.NUMBER, "℃", null, null, null, null, null, null, 0,
                        Instant.now())));
        lenient().when(repository.findByDevice(projectId, gatewayId)).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 合法数值点位应创建为草稿。 */
    @Test
    void createsValidPointAsDraft() {
        ModbusPointMapping point = service.create(projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 100,
                ModbusPointMapping.DataType.FLOAT32, ModbusPointMapping.ByteOrder.BIG_ENDIAN,
                new BigDecimal("0.1"), BigDecimal.ZERO, 1000);

        assertThat(point.status()).isEqualTo(ModbusPointMapping.Status.DRAFT);
        assertThat(point.subDeviceId()).isEqualTo(subId);
        verify(repository).create(any());
    }

    /** 非 Modbus 网关类型禁止创建点位。 */
    @Test
    void rejectsNonModbusGateway() {
        when(typeRepository.findById(projectId, gatewayTypeId)).thenReturn(Optional.of(new DeviceType(
                gatewayTypeId, tenantId, projectId, "directtype", "直连类型", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1,
                DeviceType.Status.PUBLISHED, null, null, Instant.now())));

        assertThatThrownBy(() -> service.create(projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 100,
                ModbusPointMapping.DataType.FLOAT32, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, 1000))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.MODBUS_POINT_DEVICE_TYPE_INVALID));
    }

    /** 子设备未绑定到该网关时拒绝。 */
    @Test
    void rejectsUnboundSubDevice() {
        when(topologyRepository.findActiveBySubDevice(projectId, subId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 100,
                ModbusPointMapping.DataType.FLOAT32, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, 1000))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.MODBUS_POINT_SUB_DEVICE_INVALID));
    }

    /** BIT 数据类型绑定到 NUMBER 属性时拒绝。 */
    @Test
    void rejectsBitToNumberProperty() {
        assertThatThrownBy(() -> service.create(projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_COILS, 0,
                ModbusPointMapping.DataType.BIT, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, 1000))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.MODBUS_POINT_PROPERTY_INVALID));
    }

    /** 线圈功能码 + 非 BIT 数据类型组合非法。 */
    @Test
    void rejectsCoilWithRegisterDataType() {
        assertThatThrownBy(() -> service.create(projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_COILS, 0,
                ModbusPointMapping.DataType.INT16, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, 1000))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.MODBUS_POINT_CONSTRAINT_INVALID));
    }

    /** 寄存器区间重叠时拒绝。 */
    @Test
    void rejectsOverlappingRegisters() {
        when(repository.findByDevice(projectId, gatewayId)).thenReturn(List.of(new ModbusPointMapping(
                UUID.randomUUID(), tenantId, projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 100,
                ModbusPointMapping.DataType.FLOAT32, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, 1000,
                1, ModbusPointMapping.Status.DRAFT, Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.create(projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 101,
                ModbusPointMapping.DataType.FLOAT32, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, 1000))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.MODBUS_POINT_REGISTER_OVERLAP));
    }

    /** 已发布点位不可删除。 */
    @Test
    void rejectsDeleteOfPublishedPoint() {
        when(repository.findById(projectId, propertyId)).thenReturn(Optional.of(new ModbusPointMapping(
                propertyId, tenantId, projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 100,
                ModbusPointMapping.DataType.FLOAT32, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, 1000,
                1, ModbusPointMapping.Status.PUBLISHED, Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.delete(projectId, propertyId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.MODBUS_POINT_PUBLISHED_IMMUTABLE));
        verify(repository, never()).delete(any(), any());
    }
}
