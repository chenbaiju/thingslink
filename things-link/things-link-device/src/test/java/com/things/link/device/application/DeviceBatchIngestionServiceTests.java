package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceTopology;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.infrastructure.metrics.DeviceBatchIngestionMetrics;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.SubDeviceReport;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/** 网关批量属性上报归属复核：按 dev_topo 校验「subDeviceKey 是否绑定到上报网关」并部分处理。 */
@ExtendWith(MockitoExtension.class)
class DeviceBatchIngestionServiceTests {

    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceTypeRepository typeRepository;
    @Mock private DeviceTopologyRepository topologyRepository;
    @Mock private TransactionLocalRlsScope transactionLocalRlsScope;

    private DeviceBatchIngestionService service;
    private final UUID tenantId = Uuid7.generate();
    private final UUID projectId = Uuid7.generate();
    private final UUID gatewayId = Uuid7.generate();

    @BeforeEach
    void setUp() {
        service = new DeviceBatchIngestionService(deviceRepository, typeRepository, topologyRepository,
                transactionLocalRlsScope, new DeviceBatchIngestionMetrics(new SimpleMeterRegistry()));
    }

    /** 已绑定到上报网关的条目应解析出子设备 ID 并接受。 */
    @Test
    void acceptsEntryBoundToReportingGateway() {
        UUID subId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        stubSubDevice("sub_01", subId, typeId);
        when(topologyRepository.findActiveBySubDevice(projectId, subId))
                .thenReturn(Optional.of(binding(subId, gatewayId)));

        List<ResolvedSubDeviceReport> resolved = service.resolve(message(
                new GatewayBatchMessage.Valid(report("sub_01"), 50)));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).deviceId()).isEqualTo(subId);
        assertThat(resolved.get(0).rawBytes()).isEqualTo(50);
        var order = inOrder(transactionLocalRlsScope, deviceRepository);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(deviceRepository).findByDeviceKey(projectId, "sub_01");
    }

    /** 不存在的子设备按 SUB_DEVICE_NOT_FOUND 拒绝。 */
    @Test
    void rejectsUnknownSubDevice() {
        when(deviceRepository.findByDeviceKey(projectId, "sub_missing")).thenReturn(Optional.empty());

        List<ResolvedSubDeviceReport> resolved = service.resolve(message(
                new GatewayBatchMessage.Valid(report("sub_missing"), 40)));

        assertThat(resolved).isEmpty();
    }

    /** 非 SUB_DEVICE 类型的设备按 TYPE_MISMATCH 拒绝。 */
    @Test
    void rejectsNonSubDeviceKind() {
        UUID subId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        Device device = new Device(subId, tenantId, projectId, typeId, gatewayId, "sub_01", "sub", null,
                Device.Status.OFFLINE, null, null, Instant.now());
        when(deviceRepository.findByDeviceKey(projectId, "sub_01")).thenReturn(Optional.of(device));
        when(typeRepository.findById(projectId, typeId)).thenReturn(Optional.of(
                new DeviceType(typeId, tenantId, projectId, "direct", "direct", DeviceType.DeviceKind.DIRECT,
                        DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1,
                        DeviceType.Status.PUBLISHED, null, null, Instant.now())));

        List<ResolvedSubDeviceReport> resolved = service.resolve(message(
                new GatewayBatchMessage.Valid(report("sub_01"), 40)));

        assertThat(resolved).isEmpty();
    }

    /** 存在但无有效绑定按 NOT_BOUND 拒绝。 */
    @Test
    void rejectsSubDeviceWithoutBinding() {
        UUID subId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        stubSubDevice("sub_01", subId, typeId);
        when(topologyRepository.findActiveBySubDevice(projectId, subId)).thenReturn(Optional.empty());

        List<ResolvedSubDeviceReport> resolved = service.resolve(message(
                new GatewayBatchMessage.Valid(report("sub_01"), 40)));

        assertThat(resolved).isEmpty();
    }

    /** 绑定到其他网关按 GATEWAY_MISMATCH 拒绝，不得抢占他人子设备。 */
    @Test
    void rejectsSubDeviceBoundToOtherGateway() {
        UUID subId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        UUID otherGateway = Uuid7.generate();
        stubSubDevice("sub_01", subId, typeId);
        when(topologyRepository.findActiveBySubDevice(projectId, subId))
                .thenReturn(Optional.of(binding(subId, otherGateway)));

        List<ResolvedSubDeviceReport> resolved = service.resolve(message(
                new GatewayBatchMessage.Valid(report("sub_01"), 40)));

        assertThat(resolved).isEmpty();
    }

    /** 结构层拒绝条目由信封携带，本服务只补记指标，不进入 accepted。 */
    @Test
    void passesThroughStructuralRejectionWithoutAccepting() {
        List<ResolvedSubDeviceReport> resolved = service.resolve(message(
                new GatewayBatchMessage.Rejected(GatewayBatchMessage.RejectReason.DUPLICATE_MESSAGE_ID, 30)));

        assertThat(resolved).isEmpty();
    }

    /** 预置「存在且为 SUB_DEVICE 类型」的子设备。 */
    private void stubSubDevice(String deviceKey, UUID subId, UUID typeId) {
        Device device = new Device(subId, tenantId, projectId, typeId, gatewayId, deviceKey, deviceKey, null,
                Device.Status.OFFLINE, null, null, Instant.now());
        when(deviceRepository.findByDeviceKey(projectId, deviceKey)).thenReturn(Optional.of(device));
        when(typeRepository.findById(projectId, typeId)).thenReturn(Optional.of(
                new DeviceType(typeId, tenantId, projectId, "subtype", "subtype", DeviceType.DeviceKind.SUB_DEVICE,
                        DeviceType.PayloadProtocol.STANDARD_GATEWAY, DeviceType.NetworkType.RS485, 1,
                        DeviceType.Status.PUBLISHED, null, null, Instant.now())));
    }

    /** @return 指向指定网关的有效拓扑绑定 */
    private DeviceTopology binding(UUID subId, UUID boundGateway) {
        return new DeviceTopology(Uuid7.generate(), tenantId, projectId, boundGateway, subId,
                DeviceTopology.BindSource.GATEWAY_REPORTED, DeviceTopology.OnlineStatus.ONLINE, null, null,
                null, Instant.now(), null, null, 1, Instant.now());
    }

    /** @return 单条合法子设备上报 */
    private SubDeviceReport report(String deviceKey) {
        return new SubDeviceReport(Uuid7.generate(), deviceKey, Instant.parse("2026-08-14T08:00:00Z"),
                "1.0.0", Map.of("temperature", 23.5));
    }

    /** @return 只包含指定条目的批量消息信封 */
    private GatewayBatchMessage message(GatewayBatchMessage.Entry entry) {
        return new GatewayBatchMessage(tenantId, projectId, gatewayId, 100,
                Instant.parse("2026-08-14T08:00:01Z"), "0123456789abcdef0123456789abcdef", List.of(entry));
    }
}
