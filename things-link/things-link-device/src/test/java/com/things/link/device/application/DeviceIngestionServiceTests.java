package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DevicePropertyDefinitionRepository;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.DeviceShadowRepository;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.domain.DeviceCommandDefinitionRepository;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 数据面设备应用端口测试，固定跨模块重构后的校验与影子 CAS 语义。 */
@ExtendWith(MockitoExtension.class)
class DeviceIngestionServiceTests {
    /** 设备仓储替身。 */
    @Mock private DeviceRepository deviceRepository;
    /** 设备类型仓储替身。 */
    @Mock private DeviceTypeRepository typeRepository;
    /** 属性定义仓储替身。 */
    @Mock private DevicePropertyDefinitionRepository propertyRepository;
    /** 影子仓储替身。 */
    @Mock private DeviceShadowRepository shadowRepository;
    /** Redis 热影子替身。 */
    @Mock private DeviceCurrentValueCache currentValueCache;
    /** 命令定义仓储替身。 */ @Mock private DeviceCommandDefinitionRepository commandRepository;
    /** 命令 Schema 校验替身。 */ @Mock private ThingModelSchemaValidator schemaValidator;
    /** 项目路由替身。 */ @Mock private ProjectService projectService;
    /** 提交后实时分发事件发布器替身。 */ @Mock private ApplicationEventPublisher eventPublisher;
    /** 版本绑定裁决替身。 */ @Mock private ThingModelVersionBindingService versionBindingService;
    /** 事务局部RLS集中入口替身。 */ @Mock private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 被测服务。 */
    private DeviceIngestionService service;
    /** 租户 ID。 */
    private UUID tenantId;
    /** 项目 ID。 */
    private UUID projectId;
    /** 设备 ID。 */
    private UUID deviceId;
    /** 设备类型 ID。 */
    private UUID deviceTypeId;

    /** 建立有已发布物模型归属的设备。 */
    @BeforeEach
    void setUp() {
        service = new DeviceIngestionService(
                deviceRepository, typeRepository, propertyRepository, shadowRepository, new ObjectMapper(),
                currentValueCache, commandRepository, schemaValidator, projectService, eventPublisher,
                versionBindingService, transactionLocalRlsScope, org.mockito.Mockito.mock(DevicePropertyWebhookSource.class));
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        deviceTypeId = UUID.randomUUID();
        lenient().when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(new Device(
                deviceId, tenantId, projectId, deviceTypeId, null, "sensor", "传感器", null,
                Device.Status.ONLINE, null, Instant.now(), Instant.now())));
    }

    /** 合法数值属性返回可信租户并可进入影子 CAS。 */
    @Test
    void validatesAndMergesReportedProperty() {
        Instant occurredAt = Instant.parse("2026-08-07T08:00:00Z");
        Instant receivedAt = Instant.parse("2026-08-07T08:00:01Z");
        UUID versionId = UUID.randomUUID();
        when(versionBindingService.resolveForIngestion(projectId, deviceId, "1.0.0", receivedAt))
                .thenReturn(versionContext(versionId));
        when(shadowRepository.updateReportedPropertyIfNewer(
                projectId, deviceId, "temperature", "26.5", occurredAt, versionId)).thenReturn(java.util.OptionalLong.of(17));
        when(shadowRepository.findByDevice(projectId, deviceId)).thenReturn(Optional.of(new com.things.link.device.domain.DeviceShadow(
                deviceId, tenantId, projectId, null, "{\"temperature\":26.5}", 3, Instant.now())));
        DeviceIngestionContext context = service.validateReportedProperties(
                tenantId, projectId, deviceId, "1.0.0", receivedAt, Map.of("temperature", 26.5));
        service.mergeReportedProperties(context, projectId, deviceId, Map.of("temperature", 26.5), occurredAt);

        assertThat(context.tenantId()).isEqualTo(tenantId);
        var scopeBeforeDeviceLookup = inOrder(transactionLocalRlsScope, deviceRepository);
        scopeBeforeDeviceLookup.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        scopeBeforeDeviceLookup.verify(deviceRepository).findById(projectId, deviceId);
        verify(shadowRepository).createIfAbsent(any());
        verify(shadowRepository).updateReportedPropertyIfNewer(
                projectId, deviceId, "temperature", "26.5", occurredAt, versionId);
        verify(currentValueCache).merge(projectId, deviceId, Map.of("temperature",
                new com.things.link.device.domain.DeviceCurrentValueCache.ReportedValue("26.5", occurredAt, 3, "17", versionId)));
        ArgumentCaptor<DeviceReportedPropertiesCommitted> event =
                ArgumentCaptor.forClass(DeviceReportedPropertiesCommitted.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().update().projectId()).isEqualTo(projectId);
        assertThat(event.getValue().update().deviceId()).isEqualTo(deviceId);
        assertThat(event.getValue().update().propertiesJson()).containsExactlyEntriesOf(Map.of("temperature", "26.5"));
        assertThat(event.getValue().update().reportedRevisions()).containsExactlyEntriesOf(Map.of("temperature", "17"));
    }

    /** PostgreSQL 拒绝迟到属性时，即使 Redis 已淘汰也不得重新生成错误热值。 */
    @Test
    void doesNotCachePropertyRejectedByDatabaseCas() {
        Instant occurredAt = Instant.parse("2026-08-07T07:00:00Z");
        UUID versionId = UUID.randomUUID();
        DeviceIngestionContext context = versionContext(versionId).withPropertyDataTypes(Map.of("temperature", "NUMBER"));
        when(shadowRepository.updateReportedPropertyIfNewer(
                projectId, deviceId, "temperature", "20", occurredAt, versionId)).thenReturn(java.util.OptionalLong.empty());

        service.mergeReportedProperties(context, projectId, deviceId, Map.of("temperature", 20), occurredAt);

        verify(currentValueCache, never()).merge(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(shadowRepository, never()).findByDevice(projectId, deviceId);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** 超出物模型量程时必须在触碰影子前拒绝。 */
    @Test
    void rejectsValueOutsideThingModelRange() {
        when(propertyRepository.findByPropertyKey(projectId, deviceTypeId, "temperature"))
                .thenReturn(Optional.of(numberDefinition()));

        assertThatThrownBy(() -> service.validateReportedProperties(
                projectId, deviceId, Map.of("temperature", 200)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(CommonErrorCode.INVALID_PARAMETER));
        verify(shadowRepository, never()).createIfAbsent(any());
    }

    /** 未绑定网关的子设备下发必须 fail-fast，不能发往无订阅者的死 Topic。 */
    @Test
    void rejectsDownlinkToUnboundSubDevice() {
        when(typeRepository.findById(projectId, deviceTypeId)).thenReturn(Optional.of(
                new DeviceType(deviceTypeId, tenantId, projectId, "subtype", "子设备类型",
                        DeviceType.DeviceKind.SUB_DEVICE, DeviceType.PayloadProtocol.STANDARD,
                        DeviceType.NetworkType.ZIGBEE, 1, DeviceType.Status.PUBLISHED, null, null, Instant.now())));

        assertThatThrownBy(() -> service.resolveCommandRoute(
                projectId, deviceId, "reboot", new ObjectMapper().readTree("{}")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.SUB_DEVICE_UNBOUND));
    }

    /** 子设备已绑定但网关离线时下发必须 fail-fast（D-027）。 */
    @Test
    void rejectsDownlinkWhenGatewayIsOffline() {
        UUID gatewayId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        when(deviceRepository.findById(projectId, subId)).thenReturn(Optional.of(new Device(
                subId, tenantId, projectId, deviceTypeId, gatewayId, "sub_01", "子设备", null,
                Device.Status.OFFLINE, null, null, Instant.now())));
        when(deviceRepository.findById(projectId, gatewayId)).thenReturn(Optional.of(new Device(
                gatewayId, tenantId, projectId, UUID.randomUUID(), null, "gw_01", "网关", null,
                Device.Status.OFFLINE, null, null, Instant.now())));

        assertThatThrownBy(() -> service.resolveCommandRoute(
                projectId, subId, "reboot", new ObjectMapper().readTree("{}")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.GATEWAY_OFFLINE));
    }

    /** 构造 -40～125℃ 的只上报数值属性。 */
    private DevicePropertyDefinition numberDefinition() {
        return new DevicePropertyDefinition(UUID.randomUUID(), tenantId, projectId, deviceTypeId,
                "temperature", "温度", DevicePropertyDefinition.AccessType.REPORT,
                DevicePropertyDefinition.DataType.NUMBER, "℃", 1, new BigDecimal("-40"),
                new BigDecimal("125"), List.of(), null, null, 0, Instant.now());
    }

    /** @return 含单个 NUMBER 属性的当前版本摄入上下文 */
    private DeviceIngestionContext versionContext(UUID versionId) {
        String snapshot = """
                {"properties":{"temperature":{"accessType":"REPORT","dataType":"NUMBER",
                "minimum":-40,"maximum":125}}}
                """;
        return new DeviceIngestionContext(tenantId, versionId, "1.0.0", "a".repeat(64),
                "PG_JSONB_TEXT_V1_SHA256", snapshot, DeviceIngestionContext.Eligibility.CURRENT, Map.of(), false);
    }

    /** 存量标量省略 modelVersion 推断链路一旦携带 OBJECT/LIST 必须协议违约，不能静默按初始版本放行。 */
    @Test
    void rejectsCompositePropertyWhenVersionLegacyInferred() {
        Instant receivedAt = Instant.parse("2026-08-07T08:00:01Z");
        when(versionBindingService.resolveForIngestion(projectId, deviceId, null, receivedAt))
                .thenReturn(legacyInferredObjectContext());

        assertThatThrownBy(() -> service.validateReportedProperties(
                tenantId, projectId, deviceId, null, receivedAt, Map.of("labels", Map.of("a", "b"))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.THING_MODEL_VERSION_REQUIRED));
    }

    /** @return 标记为存量省略推断、且含 OBJECT 属性的版本上下文 */
    private DeviceIngestionContext legacyInferredObjectContext() {
        String snapshot = """
                {"properties":{"labels":{"accessType":"REPORT","dataType":"OBJECT",
                "schema":{"type":"object","properties":{"a":{"type":"string"}}}}}}
                """;
        return new DeviceIngestionContext(tenantId, UUID.randomUUID(), "1.0.0", "a".repeat(64),
                "PG_JSONB_TEXT_V1_SHA256", snapshot, DeviceIngestionContext.Eligibility.CURRENT, Map.of(), true);
    }
}
