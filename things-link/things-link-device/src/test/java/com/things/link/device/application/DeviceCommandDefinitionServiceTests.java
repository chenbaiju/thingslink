package com.things.link.device.application;

import com.things.link.device.domain.DeviceCommandDefinition;
import com.things.link.device.domain.DeviceCommandDefinitionRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.device.application.schema.InvalidThingModelSchemaException;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 命令定义服务测试，覆盖 JSON Schema 输入输出、超时校验和发布冻结。 */
@ExtendWith(MockitoExtension.class)
class DeviceCommandDefinitionServiceTests {
    /** 命令仓储替身。 */ @Mock private DeviceCommandDefinitionRepository repository;
    /** 类型仓储替身。 */ @Mock private DeviceTypeRepository typeRepository;
    /** 项目服务替身。 */ @Mock private ProjectService projectService;
    /** Schema 校验器替身。 */ @Mock private ThingModelSchemaValidator schemaValidator;
    /** 被测服务。 */ private DeviceCommandDefinitionService service;
    /** 项目 ID。 */ private UUID projectId;
    /** 类型 ID。 */ private UUID typeId;

    /** 建立租户上下文和被测服务。 */
    @BeforeEach void setUp() {
        service = new DeviceCommandDefinitionService(repository, typeRepository, projectService, schemaValidator);
        projectId = UUID.randomUUID(); typeId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
    }

    /** 清理线程上下文，避免污染同一测试进程中的其他用例。 */
    @AfterEach void clear() { TenantContext.clear(); }

    /** 携带输入输出 Schema 和超时创建命令，交付仓储持久化。 */
    @Test void createsCommandWithInputOutputSchema() {
        allow(DeviceType.Status.DRAFT);
        when(schemaValidator.validateDefinition("{\"type\":\"object\"}"))
                .thenReturn("{\"type\":\"object\"}");
        DeviceCommandDefinition value = service.create(projectId, typeId, "reboot", "重启设备",
                "远程重启设备", "{\"type\":\"object\"}", "{\"type\":\"object\"}", 30, 0);
        assertThat(value.commandKey()).isEqualTo("reboot");
        assertThat(value.timeoutSeconds()).isEqualTo(30);
        assertThat(value.inputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(value.outputSchema()).isEqualTo("{\"type\":\"object\"}");
        verify(repository).create(value);
    }

    /** 输入输出 Schema 均可为空，表示命令无需参数或无需响应结构。 */
    @Test void createsCommandWithNullSchemas() {
        allow(DeviceType.Status.DRAFT);
        DeviceCommandDefinition value = service.create(projectId, typeId, "ping", "连通性检测",
                null, null, null, 10, 1);
        assertThat(value.inputSchema()).isNull();
        assertThat(value.outputSchema()).isNull();
        assertThat(value.description()).isNull();
    }

    /** 非法 Schema 稳定映射为 30019。 */
    @Test void rejectsInvalidSchemaWithStableErrorCode() {
        allow(DeviceType.Status.DRAFT);
        when(schemaValidator.validateDefinition("{broken"))
                .thenThrow(new InvalidThingModelSchemaException("测试解析失败"));
        assertThatThrownBy(() -> service.create(projectId, typeId, "bad", "错误命令", null, "{broken", null, 10, 0))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.COMMAND_DEFINITION_SCHEMA_INVALID));
    }

    /** 已发布类型的命令契约不可原地新增，防止设备端解析契约静默漂移。 */
    @Test void publishedTypeRejectsCommandCreation() {
        allow(DeviceType.Status.PUBLISHED);
        assertThatThrownBy(() -> service.create(projectId, typeId, "restart", "重启",
                null, null, null, 30, 0))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE));
    }

    /** 非管理角色写入命令被拒绝。 */
    @Test void nonManagerRejectsCommandWrite() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OPERATOR));
        assertThatThrownBy(() -> service.create(projectId, typeId, "restart", "重启",
                null, null, null, 30, 0))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN));
    }

    /** @param status 当前设备类型状态 */
    private void allow(DeviceType.Status status) {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        TenantScope scope = TenantContext.current().orElseThrow();
        when(typeRepository.findByIdForUpdate(projectId, typeId)).thenReturn(Optional.of(new DeviceType(typeId,
                scope.tenantId(), projectId, "controller", "控制器", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1, status, null, null, Instant.now())));
    }
}
