package com.things.link.device.application;

import com.things.link.device.domain.DeviceDataStream;
import com.things.link.device.domain.DeviceDataStreamRepository;
import com.things.link.device.domain.DeviceErrorCode;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 自定义数据流服务测试，覆盖 Topic 规范、权限和发布冻结。 */
@ExtendWith(MockitoExtension.class)
class DeviceDataStreamServiceTests {
    /** 数据流仓储。 */ @Mock private DeviceDataStreamRepository repository;
    /** 类型仓储。 */ @Mock private DeviceTypeRepository typeRepository;
    /** 项目服务。 */ @Mock private ProjectService projectService;
    /** 被测服务。 */ private DeviceDataStreamService service;
    /** 项目 ID。 */ private UUID projectId;
    /** 类型 ID。 */ private UUID typeId;

    /** 初始化租户上下文与服务。 */
    @BeforeEach void setUp() {
        projectId = UUID.randomUUID(); typeId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
        service = new DeviceDataStreamService(repository, typeRepository, projectService);
    }
    /** 清除线程上下文。 */ @AfterEach void clear() { TenantContext.clear(); }

    /** OWNER 可创建默认 Topic 数据流，关闭高级模式会清空外部传入的幽灵 Topic。 */
    @Test void ownerCreatesDefaultTopicStream() {
        allow(ProjectRole.OWNER, DeviceType.Status.DRAFT);
        DeviceDataStream stream = service.create(projectId, typeId, "stream", "原始流",
                DeviceDataStream.Format.HEX, false, "ignored/up", "ignored/down");
        assertThat(stream.publishTopic()).isNull(); assertThat(stream.subscribeTopic()).isNull();
        verify(repository).create(stream);
    }

    /** 高级模式必须成对配置上下行 Topic。 */
    @Test void advancedTopicRequiresBothDirections() {
        allow(ProjectRole.ADMIN, DeviceType.Status.DRAFT);
        assertThatThrownBy(() -> service.create(projectId, typeId, "stream", "原始流",
                DeviceDataStream.Format.JSON, true, "custom/up", ""))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.DATA_STREAM_TOPIC_INVALID));
        verify(repository, never()).create(org.mockito.ArgumentMatchers.any());
    }

    /** VIEWER 不能修改数据流。 */
    @Test void viewerCannotCreateStream() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        assertThatThrownBy(() -> service.create(projectId, typeId, "stream", "原始流",
                DeviceDataStream.Format.TEXT, false, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN));
    }

    /** 已发布类型的数据流配置同样冻结。 */
    @Test void publishedTypeRejectsStreamChanges() {
        allow(ProjectRole.OWNER, DeviceType.Status.PUBLISHED);
        assertThatThrownBy(() -> service.create(projectId, typeId, "stream", "原始流",
                DeviceDataStream.Format.MODBUS_RTU, false, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE));
    }

    /** 准备角色和设备类型状态。 */
    private void allow(ProjectRole role, DeviceType.Status status) {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(role));
        TenantScope scope = TenantContext.current().orElseThrow();
        when(typeRepository.findByIdForUpdate(projectId, typeId)).thenReturn(Optional.of(new DeviceType(typeId,
                scope.tenantId(), projectId, "sensor", "传感器", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1, status, null, null, Instant.now())));
    }
}
