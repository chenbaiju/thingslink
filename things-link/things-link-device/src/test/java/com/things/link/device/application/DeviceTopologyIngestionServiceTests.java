package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceTopology;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.infrastructure.metrics.DeviceTopologyIngestionMetrics;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 拓扑在线状态机的网关回执（down/topo/reply）与部分拒绝语义。 */
@ExtendWith(MockitoExtension.class)
class DeviceTopologyIngestionServiceTests {

    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceTypeRepository typeRepository;
    @Mock private DeviceTopologyRepository topologyRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TransactionLocalRlsScope transactionLocalRlsScope;
    @Mock private EffectiveQuotaPolicyProvider quotaPolicyProvider;
    @Mock private ProjectQuotaService quotaService;
    @Mock private ProjectService projectService;
    @Mock private TransactionalOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceTopologyIngestionService service;

    private final UUID tenantId = Uuid7.generate();
    private final UUID projectId = Uuid7.generate();
    private final UUID gatewayId = Uuid7.generate();
    private final UUID gatewayTypeId = Uuid7.generate();
    private final UUID subId = Uuid7.generate();
    private final UUID subTypeId = Uuid7.generate();

    @BeforeEach
    void setUp() {
        service = new DeviceTopologyIngestionService(deviceRepository, typeRepository, topologyRepository,
                jdbcTemplate, transactionLocalRlsScope, quotaPolicyProvider, quotaService,
                new DeviceTopologyIngestionMetrics(new SimpleMeterRegistry()), projectService,
                outboxRepository, objectMapper, new DeviceTopologyRoleGuard(typeRepository, topologyRepository),org.mockito.Mockito.mock(DevicePresenceWebhookSource.class));
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        lenient().when(projectService.requireRoutingContext(projectId))
                .thenReturn(new ProjectService.ProjectRoutingContext(tenantId, "project_1"));
        lenient().when(quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId))
                .thenReturn(EffectiveQuotaPolicy.safeDefault(tenantId));
        lenient().when(quotaService.deviceQuotaStatus(tenantId, projectId)).thenReturn(QuotaStatus.NORMAL);
        lenient().when(deviceRepository.setGatewayId(any(), any(), any())).thenReturn(true);
        lenient().when(deviceRepository.findByIdForKeyShare(projectId, gatewayId)).thenReturn(Optional.of(gatewayDevice()));
        lenient().when(typeRepository.findByIdForShareNowait(projectId, gatewayTypeId)).thenReturn(Optional.of(gatewayType()));
    }

    /** topo/add 成功绑定应回执 SUCCESS，且不携带 errorCode。 */
    @Test
    void topoAddSuccessEmitsSuccessReply() {
        stubSubDevice("sub_01");
        when(topologyRepository.findActiveBySubDevice(projectId, subId)).thenReturn(Optional.empty());

        service.ingest(topoMessage(DeviceTopologyMessage.Type.TOPO_ADD, "sub_01"));

        var order = inOrder(transactionLocalRlsScope, jdbcTemplate);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(jdbcTemplate).update(anyString(), any(Object[].class));
        assertReply(TopologyReplyMessage.Status.SUCCESS, null, "sub_01");
    }

    /** topo/add 目标子设备不存在应回执 FAILED 并携带稳定 errorCode。 */
    @Test
    void topoAddRejectsUnknownSubDeviceWithFailedReply() {
        when(deviceRepository.findByDeviceKey(projectId, "sub_missing")).thenReturn(Optional.empty());

        service.ingest(topoMessage(DeviceTopologyMessage.Type.TOPO_ADD, "sub_missing"));

        assertReply(TopologyReplyMessage.Status.FAILED, "sub_device_invalid", "sub_missing");
    }

    /** 网关身份非法（不存在或非网关类型）时无从回执，不得 append Outbox。 */
    @Test
    void gatewayInvalidDoesNotEmitReply() {
        when(deviceRepository.findByIdForKeyShare(projectId, gatewayId)).thenReturn(Optional.empty());

        service.ingest(topoMessage(DeviceTopologyMessage.Type.TOPO_ADD, "sub_01"));

        verify(outboxRepository, never()).append(any());
    }

    /** 网关掉线级联必须先建立可信双轴范围，再读取网关权威事实。 */
    @Test
    void cascadeGatewayOfflineEstablishesScopeBeforeRepositoryAccess() {
        when(deviceRepository.findByIdForKeyShare(projectId, gatewayId)).thenReturn(Optional.empty());

        service.cascadeGatewayOffline(tenantId, projectId, gatewayId, Instant.now());

        var order = inOrder(transactionLocalRlsScope, deviceRepository);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(deviceRepository).findByIdForKeyShare(projectId, gatewayId);
    }

    /** topo/delete 无有效绑定时按幂等成功回执 SUCCESS。 */
    @Test
    void topoDeleteWithoutBindingEmitsSuccessReply() {
        stubSubDevice("sub_01");
        when(topologyRepository.findActiveBySubDevice(projectId, subId)).thenReturn(Optional.empty());

        service.ingest(topoMessage(DeviceTopologyMessage.Type.TOPO_DELETE, "sub_01"));

        assertReply(TopologyReplyMessage.Status.SUCCESS, null, "sub_01");
    }

    /** 原网关已漂移为 DIRECT 时，即使旧关系仍指向它，也不能用 LOGIN 写入子设备在线证明。 */
    @Test
    void loginFromGatewayWhoseTypeDriftedToDirectDoesNotUpdateOnlineState() {
        when(typeRepository.findByIdForShareNowait(projectId, gatewayTypeId))
                .thenReturn(Optional.of(directType(gatewayTypeId, "gwtype")));
        // 保留可达的旧关系及可成功的 CAS：若入口漏掉网关当前分类校验，必须真的写在线状态并触发失败。
        lenient().when(deviceRepository.findByDeviceKey(projectId, "sub_01"))
                .thenReturn(Optional.of(subDevice("sub_01")));
        lenient().when(deviceRepository.findByIdForUpdate(projectId, subId))
                .thenReturn(Optional.of(subDevice("sub_01")));
        lenient().when(typeRepository.findByIdForShareNowait(projectId, subTypeId))
                .thenReturn(Optional.of(subType()));
        lenient().when(topologyRepository.findActiveBySubDevice(projectId, subId))
                .thenReturn(Optional.of(bindingToGateway()));
        lenient().when(topologyRepository.updateOnlineStatus(eq(projectId), eq(subId), any(), any()))
                .thenReturn(true);

        service.ingest(topoMessage(DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_01"));

        verify(topologyRepository, never()).updateOnlineStatus(any(), any(), any(), any());
        verify(deviceRepository, never()).setStatus(any(), any(), any(), any());
        verify(outboxRepository, never()).append(any());
    }

    /** 注册请求的合法子类型不能替代已有设备的实际 DIRECT 类型，否则旧关系会被误认作成功幂等。 */
    @Test
    void registerRejectsExistingDirectDeviceDespiteValidRequestedSubTypeAndOwnBinding() {
        UUID existingTypeId = Uuid7.generate();
        Device existing = new Device(subId, tenantId, projectId, existingTypeId, gatewayId,
                "sub_existing", "旧设备", null, Device.Status.OFFLINE, null, null, Instant.now());
        when(typeRepository.findByTypeKey(projectId, "subtype")).thenReturn(Optional.of(subType()));
        when(typeRepository.findByIdForShareNowait(projectId, subTypeId)).thenReturn(Optional.of(subType()));
        when(deviceRepository.findByDeviceKey(projectId, "sub_existing")).thenReturn(Optional.of(existing));
        when(deviceRepository.findByIdForUpdate(projectId, subId)).thenReturn(Optional.of(existing));
        when(typeRepository.findByIdForShareNowait(projectId, existingTypeId))
                .thenReturn(Optional.of(directType(existingTypeId, "actual_direct")));
        // 新实现应在读取旧绑定前拒绝；旧实现跳过实际分类检查时会命中该绑定并错误回执 SUCCESS。
        lenient().when(topologyRepository.findActiveBySubDevice(projectId, subId))
                .thenReturn(Optional.of(bindingToGateway()));
        DeviceTopologyMessage register = new DeviceTopologyMessage(Uuid7.generate(), tenantId, projectId, gatewayId,
                DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER, "sub_existing", "子设备", "subtype",
                Instant.parse("2026-08-14T09:00:00Z"), "0123456789abcdef0123456789abcdef");

        service.ingest(register);

        assertReply(TopologyReplyMessage.Status.FAILED, "sub_device_invalid", "sub_existing");
        verify(deviceRepository, never()).create(any());
        verify(deviceRepository, never()).setGatewayId(any(), any(), any());
        verify(deviceRepository, never()).setStatus(any(), any(), any(), any());
        verify(topologyRepository, never()).create(any());
    }

    /** 网关注册在硬限及更高降级水位均拒绝新增，并使用稳定的配额失败回执。 */
    @ParameterizedTest
    @EnumSource(value = QuotaStatus.class, names = {"HARD_LIMIT", "DEGRADED"})
    void registerRejectsHardAndDegradedDeviceQuota(QuotaStatus quotaStatus) {
        when(typeRepository.findByTypeKey(projectId, "subtype")).thenReturn(Optional.of(subType()));
        when(typeRepository.findByIdForShareNowait(projectId, subTypeId)).thenReturn(Optional.of(subType()));
        when(deviceRepository.findByDeviceKey(projectId, "sub_quota")).thenReturn(Optional.empty());
        when(quotaService.deviceQuotaStatus(tenantId, projectId)).thenReturn(quotaStatus);
        DeviceTopologyMessage register = new DeviceTopologyMessage(Uuid7.generate(), tenantId, projectId, gatewayId,
                DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER, "sub_quota", "子设备", "subtype",
                Instant.parse("2026-08-14T09:00:00Z"), "0123456789abcdef0123456789abcdef");

        service.ingest(register);

        assertReply(TopologyReplyMessage.Status.FAILED, "quota_exceeded", "sub_quota");
        verify(deviceRepository, never()).create(any());
        verify(topologyRepository, never()).create(any());
    }

    /** 模拟旧版本允许产生的分类漂移；保留合法 DIRECT 协议组合，避免错误由不相关协议配置触发。 */
    private DeviceType directType(UUID typeId, String typeKey) {
        return new DeviceType(typeId, tenantId, projectId, typeKey, "漂移直连类型", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1,
                DeviceType.Status.DRAFT, null, null, Instant.now());
    }

    /** 旧绑定仍属于上报网关，使回归专门验证当前设备分类而不是网关归属不匹配。 */
    private DeviceTopology bindingToGateway() {
        Instant boundAt = Instant.parse("2026-08-14T08:00:00Z");
        return new DeviceTopology(Uuid7.generate(), tenantId, projectId, gatewayId, subId,
                DeviceTopology.BindSource.GATEWAY_REPORTED, DeviceTopology.OnlineStatus.OFFLINE,
                null, null, null, boundAt, null, null, 1, boundAt);
    }

    private void stubSubDevice(String deviceKey) {
        when(deviceRepository.findByDeviceKey(projectId, deviceKey)).thenReturn(Optional.of(subDevice(deviceKey)));
        lenient().when(deviceRepository.findByIdForUpdate(projectId, subId)).thenReturn(Optional.of(subDevice(deviceKey)));
        lenient().when(typeRepository.findByIdForShareNowait(projectId, subTypeId)).thenReturn(Optional.of(subType()));
    }

    private void assertReply(TopologyReplyMessage.Status status, String errorCode, String subDeviceKey) {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).append(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(TopologyReplyMessage.EVENT_TYPE);
        assertThat(event.partitionKey()).isEqualTo(gatewayId.toString());
        TopologyReplyMessage reply = objectMapper.readValue(event.payload(), TopologyReplyMessage.class);
        assertThat(reply.status()).isEqualTo(status);
        assertThat(reply.errorCode()).isEqualTo(errorCode);
        assertThat(reply.subDeviceKey()).isEqualTo(subDeviceKey);
        assertThat(reply.gatewayKey()).isEqualTo("gw_01");
        assertThat(reply.projectKey()).isEqualTo("project_1");
    }

    private DeviceTopologyMessage topoMessage(DeviceTopologyMessage.Type type, String subDeviceKey) {
        return new DeviceTopologyMessage(Uuid7.generate(), tenantId, projectId, gatewayId, type, subDeviceKey,
                null, null, Instant.parse("2026-08-14T09:00:00Z"), "0123456789abcdef0123456789abcdef");
    }

    private Device gatewayDevice() {
        return new Device(gatewayId, tenantId, projectId, gatewayTypeId, null, "gw_01", "网关", null,
                Device.Status.ONLINE, null, null, Instant.now());
    }

    private Device subDevice(String deviceKey) {
        return new Device(subId, tenantId, projectId, subTypeId, gatewayId, deviceKey, "子设备", null,
                Device.Status.OFFLINE, null, null, Instant.now());
    }

    private DeviceType gatewayType() {
        return new DeviceType(gatewayTypeId, tenantId, projectId, "gwtype", "网关类型", DeviceType.DeviceKind.GATEWAY,
                DeviceType.PayloadProtocol.STANDARD_GATEWAY, DeviceType.NetworkType.ETHERNET, 1,
                DeviceType.Status.PUBLISHED, null, null, Instant.now());
    }

    private DeviceType subType() {
        return new DeviceType(subTypeId, tenantId, projectId, "subtype", "子设备类型", DeviceType.DeviceKind.SUB_DEVICE,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.ZIGBEE, 1,
                DeviceType.Status.PUBLISHED, null, null, Instant.now());
    }
}
