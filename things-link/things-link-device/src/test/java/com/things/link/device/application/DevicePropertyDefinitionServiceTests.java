package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DevicePropertyDefinitionRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 属性定义服务测试，覆盖类型专属约束、授权与发布冻结。 */
@ExtendWith(MockitoExtension.class)
class DevicePropertyDefinitionServiceTests {
    /** 属性仓储替身。 */ @Mock private DevicePropertyDefinitionRepository repository;
    /** 类型仓储替身。 */ @Mock private DeviceTypeRepository typeRepository;
    /** 项目服务替身。 */ @Mock private ProjectService projectService;
    /** Schema 校验端口替身。 */ @Mock private ThingModelSchemaValidator schemaValidator;
    /** 被测服务。 */ private DevicePropertyDefinitionService service;
    /** 项目 ID。 */ private UUID projectId;
    /** 类型 ID。 */ private UUID typeId;

    /** 建立租户上下文和草稿类型。 */
    @BeforeEach void setUp() {
        service = new DevicePropertyDefinitionService(repository, typeRepository, projectService, schemaValidator);
        projectId = UUID.randomUUID(); typeId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
    }
    /** 清理线程上下文。 */ @AfterEach void clear() { TenantContext.clear(); }

    /** Number 属性保留单位、精度和量程。 */
    @Test void createsNumberProperty() {
        allow(DeviceType.Status.DRAFT);
        DevicePropertyDefinition value = service.create(projectId, typeId, "temperature", "温度",
                DevicePropertyDefinition.AccessType.REPORT, DevicePropertyDefinition.DataType.NUMBER, "℃", 2,
                new BigDecimal("-40"), new BigDecimal("125"), null, null, null, 0);
        assertThat(value.unit()).isEqualTo("℃");
        assertThat(value.minimumValue()).isEqualByComparingTo("-40");
        verify(repository).create(value);
    }

    /** Enum 必须至少提供一个非空且去重后的可选值。 */
    @Test void rejectsEmptyEnumOptions() {
        allow(DeviceType.Status.DRAFT);
        assertThatThrownBy(() -> service.create(projectId, typeId, "mode", "模式",
                DevicePropertyDefinition.AccessType.SHARED, DevicePropertyDefinition.DataType.ENUM, null, null,
                null, null, List.of(" "), null, null, 0))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.PROPERTY_DEFINITION_CONSTRAINT_INVALID));
    }

    /** 最小值大于最大值时应在写数据库前拒绝。 */
    @Test void rejectsReversedNumberRange() {
        allow(DeviceType.Status.DRAFT);
        assertThatThrownBy(() -> service.create(projectId, typeId, "level", "液位",
                DevicePropertyDefinition.AccessType.REPORT, DevicePropertyDefinition.DataType.NUMBER, "m", 1,
                BigDecimal.TEN, BigDecimal.ONE, null, null, null, 0))
                .isInstanceOf(BusinessException.class);
    }

    /** 已发布类型的属性契约不可原地修改。 */
    @Test void publishedTypeRejectsPropertyCreation() {
        allow(DeviceType.Status.PUBLISHED);
        assertThatThrownBy(() -> service.create(projectId, typeId, "switch", "开关",
                DevicePropertyDefinition.AccessType.SHARED, DevicePropertyDefinition.DataType.SWITCH, null, null,
                null, null, null, "开", "关", 0))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE));
    }

    /** @param status 类型状态 */
    private void allow(DeviceType.Status status) {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        TenantScope scope = TenantContext.current().orElseThrow();
        when(typeRepository.findByIdForUpdate(projectId, typeId)).thenReturn(Optional.of(new DeviceType(typeId,
                scope.tenantId(), projectId, "sensor", "传感器", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1, status, null, null, Instant.now())));
    }
}
