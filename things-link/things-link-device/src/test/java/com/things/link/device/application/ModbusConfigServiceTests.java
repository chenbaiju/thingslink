package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ModbusPointMapping;
import com.things.link.device.domain.ModbusPointMappingRepository;
import com.things.link.device.domain.ModbusPollRepository;
import com.things.link.device.infrastructure.metrics.ModbusConfigMetrics;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.DeviceConfigReply;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Modbus 配置可靠下发与版本诊断。 */
@ExtendWith(MockitoExtension.class)
class ModbusConfigServiceTests {

    @Mock private ModbusPointMappingRepository repository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceTypeRepository typeRepository;
    @Mock private ProjectService projectService;
    @Mock private ProjectLifecycleAccessService lifecycle;
    @Mock private TransactionLocalRlsScope transactionLocalRlsScope;
    @Mock private TransactionalOutboxRepository outboxRepository;
    @Mock private ModbusPollRepository pollRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModbusConfigService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID gatewayId = UUID.randomUUID();
    private final UUID subId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ModbusConfigService(repository, deviceRepository, typeRepository, projectService,
                outboxRepository, pollRepository, objectMapper, new ModbusConfigMetrics(new SimpleMeterRegistry()),
                lifecycle, transactionLocalRlsScope);
        org.mockito.Mockito.lenient().when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(true);
    }

    /** 有已发布点位时下发全量配置，点位用子设备 deviceKey 而非 UUID。 */
    @Test
    void pushesConfigWithSubDeviceKey() {
        when(repository.findPublished(projectId, gatewayId)).thenReturn(List.of(point()));
        when(deviceRepository.findById(projectId, gatewayId)).thenReturn(Optional.of(new Device(
                gatewayId, tenantId, projectId, UUID.randomUUID(), null, "gw_01", "网关", null,
                Device.Status.ONLINE, null, null, Instant.now())));
        when(deviceRepository.findById(projectId, subId)).thenReturn(Optional.of(new Device(
                subId, tenantId, projectId, UUID.randomUUID(), gatewayId, "sub_01", "子设备", null,
                Device.Status.OFFLINE, null, null, Instant.now())));
        when(projectService.requireRoutingContext(projectId))
                .thenReturn(new ProjectService.ProjectRoutingContext(tenantId, "project_1"));

        service.pushConfig(projectId, gatewayId);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).append(captor.capture());
        DeviceConfigPush push = objectMapper.readValue(captor.getValue().payload(), DeviceConfigPush.class);
        assertThat(push.version()).isEqualTo(1);
        assertThat(push.gatewayKey()).isEqualTo("gw_01");
        assertThat(push.points()).hasSize(1);
        assertThat(push.points().get(0).subDeviceKey()).isEqualTo("sub_01");
    }

    /** 无已发布点位时 no-op，不下发。 */
    @Test
    void noOpWhenNoPublishedPoints() {
        when(repository.findPublished(projectId, gatewayId)).thenReturn(List.of());

        service.pushConfig(projectId, gatewayId);

        verify(outboxRepository, never()).append(any());
    }

    /** 冻结项目的落后ACK正常吸收，不能继续追加配置意图或轮询事实。 */
    @Test
    void noOpWhenStaleReplyProjectIsFrozen() {
        when(repository.findPublished(projectId, gatewayId)).thenReturn(List.of(pointWithVersion(3)));
        when(lifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(false);

        service.processReply(reply(2, DeviceConfigReply.Status.APPLIED));

        verify(lifecycle).lockActiveForWrite(tenantId, projectId);
        verify(outboxRepository, never()).append(any());
        verify(pollRepository, never()).syncSchedule(any(), any(), any(), any(), any());
    }

    /** 等待项目锁后版本已追平时重新读取并吸收旧ACK，不能沿锁前快照重推。 */
    @Test
    void noOpWhenVersionCatchesUpWhileWaitingForProjectLock() {
        when(repository.findPublished(projectId, gatewayId))
                .thenReturn(List.of(pointWithVersion(3)), List.of(pointWithVersion(2)));

        service.processReply(reply(2, DeviceConfigReply.Status.APPLIED));

        verify(lifecycle).lockActiveForWrite(tenantId, projectId);
        verify(repository, org.mockito.Mockito.times(2)).findPublished(projectId, gatewayId);
        verify(outboxRepository, never()).append(any());
        verify(pollRepository, never()).syncSchedule(any(), any(), any(), any(), any());
    }

    /** REJECTED只恢复可信数据库范围并记录原诊断，不读取点位或取得项目锁。 */
    @Test
    void rejectedReplyDoesNotReadOrWriteConfiguration() {
        service.processReply(reply(2, DeviceConfigReply.Status.REJECTED));

        verify(transactionLocalRlsScope).establish(tenantId, projectId);
        verify(repository, never()).findPublished(any(), any());
        verify(lifecycle, never()).lockActiveForWrite(any(), any());
        verify(outboxRepository, never()).append(any());
    }

    /** 当前端口只接收Modbus点位配置回执，其他完整但异类的配置必须在SET LOCAL前拒绝。 */
    @Test
    void rejectsUnsupportedConfigTypeBeforeDatabaseAccess() {
        DeviceConfigReply unsupported = new DeviceConfigReply(Uuid7.generate(), tenantId, projectId, gatewayId,
                "OTHER_CONFIG", 2, DeviceConfigReply.Status.APPLIED, null, null,
                Instant.now(), Instant.now(), "config-reply-test");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.processReply(unsupported))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("配置回执类型不受支持");
        org.mockito.Mockito.verifyNoInteractions(transactionLocalRlsScope);
        verify(repository, never()).findPublished(any(), any());
    }

    /** 网关应用版本落后于当前发布版本时重推并记诊断。 */
    @Test
    void repushesWhenReplyVersionIsStale() {
        when(repository.findPublished(projectId, gatewayId)).thenReturn(List.of(pointWithVersion(3)));
        when(deviceRepository.findById(projectId, gatewayId)).thenReturn(Optional.of(new Device(
                gatewayId, tenantId, projectId, UUID.randomUUID(), null, "gw_01", "网关", null,
                Device.Status.ONLINE, null, null, Instant.now())));
        when(deviceRepository.findById(projectId, subId)).thenReturn(Optional.of(new Device(
                subId, tenantId, projectId, UUID.randomUUID(), gatewayId, "sub_01", "子设备", null,
                Device.Status.OFFLINE, null, null, Instant.now())));
        when(projectService.requireRoutingContext(projectId))
                .thenReturn(new ProjectService.ProjectRoutingContext(tenantId, "project_1"));

        service.processReply(reply(2, DeviceConfigReply.Status.APPLIED));

        var order = inOrder(transactionLocalRlsScope, repository);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(repository, org.mockito.Mockito.times(2)).findPublished(projectId, gatewayId);
        verify(outboxRepository).append(any());
    }

    /** 版本一致时 no-op，不重推。 */
    @Test
    void noOpWhenReplyVersionIsCurrent() {
        when(repository.findPublished(projectId, gatewayId)).thenReturn(List.of(pointWithVersion(2)));

        service.processReply(reply(2, DeviceConfigReply.Status.APPLIED));

        verify(outboxRepository, never()).append(any());
    }

    private ModbusPointMapping point() {
        return pointWithVersion(1);
    }

    private ModbusPointMapping pointWithVersion(int version) {
        return new ModbusPointMapping(UUID.randomUUID(), tenantId, projectId, gatewayId, subId, "temperature", 1,
                ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 100,
                ModbusPointMapping.DataType.FLOAT32, ModbusPointMapping.ByteOrder.BIG_ENDIAN,
                new BigDecimal("0.1"), BigDecimal.ZERO, 1000, version, ModbusPointMapping.Status.PUBLISHED,
                Instant.now(), Instant.now());
    }

    private DeviceConfigReply reply(int version, DeviceConfigReply.Status status) {
        return new DeviceConfigReply(Uuid7.generate(), tenantId, projectId, gatewayId,
                DeviceConfigReply.CONFIG_TYPE, version, status,
                status == DeviceConfigReply.Status.REJECTED ? "INVALID_CONFIG" : null,
                status == DeviceConfigReply.Status.REJECTED ? "网关拒绝配置" : null,
                Instant.parse("2026-08-15T09:00:00Z"), Instant.parse("2026-08-15T09:00:01Z"),
                "0123456789abcdef0123456789abcdef");
    }
}
