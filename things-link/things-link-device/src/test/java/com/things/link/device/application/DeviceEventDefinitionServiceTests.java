package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceEventDefinition;
import com.things.link.device.domain.DeviceEventDefinitionRepository;
import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 事件定义服务测试，覆盖结构化参数归一化和设备类型发布冻结。 */
@ExtendWith(MockitoExtension.class)
class DeviceEventDefinitionServiceTests {
    /** 事件仓储替身。 */ @Mock private DeviceEventDefinitionRepository repository;
    /** 类型仓储替身。 */ @Mock private DeviceTypeRepository typeRepository;
    /** 项目服务替身。 */ @Mock private ProjectService projectService;
    /** 被测服务。 */ private DeviceEventDefinitionService service;
    /** 项目 ID。 */ private UUID projectId;
    /** 类型 ID。 */ private UUID typeId;

    /** 建立租户上下文和被测服务。 */
    @BeforeEach void setUp() {
        service = new DeviceEventDefinitionService(repository, typeRepository, projectService);
        projectId = UUID.randomUUID(); typeId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
    }

    /** 清理线程上下文，避免污染同一测试进程中的其他用例。 */
    @AfterEach void clear() { TenantContext.clear(); }

    /** 枚举参数去除空值和重复值后，与事件主体一并交给仓储持久化。 */
    @Test void createsEventWithNormalizedEnumParameter() {
        allow(DeviceType.Status.DRAFT);
        DeviceEventDefinition value = service.create(projectId, typeId, "overheat", "过热告警",
                DeviceEventDefinition.Level.WARNING, "温度超过安全阈值", 0,
                List.of(new DeviceEventDefinition.ParameterDraft("severity", "等级",
                        DevicePropertyDefinition.DataType.ENUM, true, List.of("high", " high ", "critical"), 0)));
        assertThat(value.parameters()).singleElement().satisfies(parameter ->
                assertThat(parameter.enumOptions()).containsExactly("high", "critical"));
        verify(repository).create(value);
    }

    /** 同一事件的参数键重复时必须在写数据库前返回稳定业务码。 */
    @Test void rejectsDuplicateParameterKeys() {
        allow(DeviceType.Status.DRAFT);
        List<DeviceEventDefinition.ParameterDraft> parameters = List.of(
                new DeviceEventDefinition.ParameterDraft("value", "当前值",
                        DevicePropertyDefinition.DataType.NUMBER, true, null, 0),
                new DeviceEventDefinition.ParameterDraft("value", "阈值",
                        DevicePropertyDefinition.DataType.NUMBER, true, null, 1));
        assertThatThrownBy(() -> service.create(projectId, typeId, "overheat", "过热告警",
                DeviceEventDefinition.Level.ERROR, null, 0, parameters))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID));
    }

    /** 已发布类型的事件契约不可原地新增，防止设备端解析契约静默漂移。 */
    @Test void publishedTypeRejectsEventCreation() {
        allow(DeviceType.Status.PUBLISHED);
        assertThatThrownBy(() -> service.create(projectId, typeId, "offline", "离线",
                DeviceEventDefinition.Level.WARNING, null, 0, List.of()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE));
    }

    @ParameterizedTest
    @EnumSource(value = DevicePropertyDefinition.DataType.class, names = {"OBJECT", "LIST"})
    void creationRejectsCompositeEventParametersBeforePersistence(DevicePropertyDefinition.DataType dataType) {
        allow(DeviceType.Status.DRAFT);
        assertThatThrownBy(() -> service.create(projectId, typeId, "fault", "故障",
                DeviceEventDefinition.Level.ERROR, null, 0, List.of(new DeviceEventDefinition.ParameterDraft(
                        "value", "值", dataType, true, null, 0))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = DevicePropertyDefinition.DataType.class, names = {"OBJECT", "LIST"})
    void updateRejectsCompositeEventParametersBeforePersistence(DevicePropertyDefinition.DataType dataType) {
        allow(DeviceType.Status.DRAFT);
        UUID id = UUID.randomUUID();
        when(repository.findById(projectId, typeId, id)).thenReturn(Optional.of(new DeviceEventDefinition(
                id, TenantContext.current().orElseThrow().tenantId(), projectId, typeId, "fault", "故障",
                DeviceEventDefinition.Level.ERROR, null, 0, List.of(), Instant.now())));
        assertThatThrownBy(() -> service.update(projectId, typeId, id, "fault", "故障",
                DeviceEventDefinition.Level.ERROR, null, 0, List.of(new DeviceEventDefinition.ParameterDraft(
                        "value", "值", dataType, true, null, 0))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID));
        verify(repository, org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test void scalarDefinitionsPreserveRequiredAndOptionalParameters() {
        allow(DeviceType.Status.DRAFT);
        DeviceEventDefinition value = service.create(projectId, typeId, "fault", "故障",
                DeviceEventDefinition.Level.ERROR, null, 0, List.of(
                        new DeviceEventDefinition.ParameterDraft("number", "数值", DevicePropertyDefinition.DataType.NUMBER, true, null, 0),
                        new DeviceEventDefinition.ParameterDraft("text", "文本", DevicePropertyDefinition.DataType.TEXT, false, null, 1),
                        new DeviceEventDefinition.ParameterDraft("switch", "开关", DevicePropertyDefinition.DataType.SWITCH, true, null, 2),
                        new DeviceEventDefinition.ParameterDraft("enum", "枚举", DevicePropertyDefinition.DataType.ENUM, false, List.of("one", "two"), 3)));
        assertThat(value.parameters()).extracting(DeviceEventDefinition.Parameter::dataType)
                .containsExactly(DevicePropertyDefinition.DataType.NUMBER, DevicePropertyDefinition.DataType.TEXT,
                        DevicePropertyDefinition.DataType.SWITCH, DevicePropertyDefinition.DataType.ENUM);
        assertThat(value.parameters()).extracting(DeviceEventDefinition.Parameter::required)
                .containsExactly(true, false, true, false);
        verify(repository).create(value);
    }

    @Test void rejectsUnstorableEnumOptionBeforeDefinitionPersistence() {
        allow(DeviceType.Status.DRAFT);
        assertThatThrownBy(() -> service.create(projectId, typeId, "fault", "故障", DeviceEventDefinition.Level.ERROR,
                null, 0, List.of(new DeviceEventDefinition.ParameterDraft("value", "值",
                        DevicePropertyDefinition.DataType.ENUM, true, List.of("synthetic-secret\0option"), 0))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID));
        verifyNoInteractions(repository);
    }

    /** @param status 当前设备类型状态 */
    private void allow(DeviceType.Status status) {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        TenantScope scope = TenantContext.current().orElseThrow();
        when(typeRepository.findByIdForUpdate(projectId, typeId)).thenReturn(Optional.of(new DeviceType(typeId,
                scope.tenantId(), projectId, "sensor", "传感器", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1, status, null, null, Instant.now())));
    }
}
