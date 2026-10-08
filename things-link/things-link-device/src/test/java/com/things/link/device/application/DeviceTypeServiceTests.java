package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 设备类型应用服务单元测试，覆盖成功、越权与非成员路径。 */
@ExtendWith(MockitoExtension.class)
class DeviceTypeServiceTests {
    /** 仓储替身。 */ @Mock private DeviceTypeRepository repository;
    /** 项目服务替身。 */ @Mock private ProjectService projectService;
    /** 初始版本与绑定仓储替身。 */ @Mock private ThingModelVersionRepository thingModelVersionRepository;
    /** 角色冲突由真实拓扑 API 测试覆盖，现有类型服务用例隔离新增应用依赖。 */
    @Mock private DeviceTopologyRoleGuard roleGuard;
    /** 接入能力真库守卫由组合测试覆盖。 */ @Mock private DeviceAccessTypeGuard accessTypes;
    /** 被测服务。 */ private DeviceTypeService service;
    /** 当前项目。 */ private UUID projectId;

    /** 为每个用例绑定独立的租户上下文。 */
    @BeforeEach
    void setUp() {
        service = new DeviceTypeService(repository, projectService, thingModelVersionRepository, roleGuard, accessTypes);
        projectId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
    }

    /** 防止测试线程复用时把上下文泄露给下一个测试。 */
    @AfterEach
    void clearContext() { TenantContext.clear(); }

    /** OWNER 创建时应固定生成版本 1 的草稿。 */
    @Test
    void ownerCreatesDraftDeviceType() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        DeviceType created = service.create(projectId, "temperature_sensor", "温度传感器",
                DeviceType.DeviceKind.DIRECT, DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI);
        ArgumentCaptor<DeviceType> captor = ArgumentCaptor.forClass(DeviceType.class);
        verify(repository).create(captor.capture());
        assertThat(created).isEqualTo(captor.getValue());
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.status()).isEqualTo(DeviceType.Status.DRAFT);
    }

    /** VIEWER 只能读取，不能通过直接调用服务绕过 Controller 创建设备类型。 */
    @Test
    void viewerCannotCreateDeviceType() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        assertThatThrownBy(() -> service.create(projectId, "sensor", "传感器",
                DeviceType.DeviceKind.DIRECT, DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.OTHER))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_TYPE_CREATE_FORBIDDEN));
        verify(repository, never()).create(org.mockito.ArgumentMatchers.any());
    }

    /** 非成员统一按资源不存在处理，不泄露项目是否真实存在。 */
    @Test
    void nonMemberCannotListDeviceTypes() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(projectId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }

    /** 四种成员角色均可读取列表。 */
    @Test
    void viewerCanListDeviceTypes() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        when(repository.search(projectId, null, 200)).thenReturn(CursorPage.last(List.of()));
        assertThat(service.list(projectId)).isEmpty();
        verify(repository).search(projectId, null, 200);
    }

    /** 新接口直接透传键集页，避免选择器为了找第 201 条重新加载整个项目。 */
    @Test
    void viewerCanSearchDeviceTypesByCursor() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        CursorPage<DeviceType> expected = CursorPage.of(List.of(type(UUID.randomUUID(), DeviceType.Status.DRAFT)),
                "next");
        when(repository.search(projectId, "cursor", 50)).thenReturn(expected);

        assertThat(service.search(projectId, "cursor", 50)).isEqualTo(expected);
    }

    /** ADMIN 可修改草稿，仓储收到的是保留 ID 与版本的新状态。 */
    @Test
    void adminUpdatesDraftDeviceType() {
        UUID id = UUID.randomUUID();
        DeviceType current = type(id, DeviceType.Status.DRAFT);
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.ADMIN));
        when(repository.findByIdForUpdate(projectId, id)).thenReturn(Optional.of(current));
        when(repository.update(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        DeviceType updated = service.update(projectId, id, "new_sensor", "新传感器",
                DeviceType.DeviceKind.GATEWAY, DeviceType.PayloadProtocol.STANDARD_GATEWAY,
                DeviceType.NetworkType.ETHERNET);
        assertThat(updated.id()).isEqualTo(id);
        assertThat(updated.typeKey()).isEqualTo("new_sensor");
        assertThat(updated.version()).isEqualTo(1);
        verify(repository).update(updated);
    }

    /** VIEWER 直接调用服务也不能绕过写授权。 */
    @Test
    void viewerCannotDeleteDeviceType() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        assertThatThrownBy(() -> service.delete(projectId, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN));
        verify(repository, never()).softDelete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** 发布版本不可原地修改，否则已接入设备的数据契约会静默漂移。 */
    @Test
    void publishedDeviceTypeIsImmutable() {
        UUID id = UUID.randomUUID();
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.findByIdForUpdate(projectId, id)).thenReturn(Optional.of(type(id, DeviceType.Status.PUBLISHED)));
        assertThatThrownBy(() -> service.update(projectId, id, "sensor", "传感器",
                DeviceType.DeviceKind.DIRECT, DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.OTHER))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE));
        verify(repository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    /** OWNER 可发布草稿，发布后状态为 PUBLISHED 且版本号不变。 */
    @Test
    void ownerPublishesDraft() {
        UUID id = UUID.randomUUID();
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.findByIdForUpdate(projectId, id)).thenReturn(Optional.of(type(id, DeviceType.Status.DRAFT)));
        when(repository.publish(projectId, id)).thenReturn(true);
        when(thingModelVersionRepository.createInitialFromDefinitions(TenantContext.current().orElseThrow().tenantId(), projectId, id))
                .thenReturn(initialVersion(id, "{\"properties\":{},\"events\":{},\"commands\":{}}"));
        DeviceType published = service.publish(projectId, id);
        assertThat(published.status()).isEqualTo(DeviceType.Status.PUBLISHED);
        assertThat(published.version()).isEqualTo(1);
        verify(repository).publish(projectId, id);
        verify(thingModelVersionRepository).createInitialFromDefinitions(
                published.tenantId(), projectId, id);
    }

    @Test void firstPublishRejectsLegacyCompositeEventSnapshot() {
        UUID id = UUID.randomUUID();
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.findByIdForUpdate(projectId, id)).thenReturn(Optional.of(type(id, DeviceType.Status.DRAFT)));
        when(repository.publish(projectId, id)).thenReturn(true);
        when(thingModelVersionRepository.createInitialFromDefinitions(TenantContext.current().orElseThrow().tenantId(), projectId, id))
                .thenReturn(initialVersion(id, """
                        {"properties":{},"events":{"fault":{"level":"ERROR","parameters":{
                          "value":{"dataType":"OBJECT","required":true}}}},"commands":{}}
                        """));
        assertThatThrownBy(() -> service.publish(projectId, id)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.EVENT_REPORT_INVALID));
        // 这里只证明应用层拒绝，事务回滚由组合测试证明。
    }

    private ThingModelVersion initialVersion(UUID typeId, String snapshot) {
        return new ThingModelVersion(UUID.randomUUID(), TenantContext.current().orElseThrow().tenantId(), projectId,
                typeId, "1.0.0", ThingModelVersion.ChangeLevel.MAJOR, snapshot, "a".repeat(64),
                "PG_JSONB_TEXT_V1_SHA256", Instant.now());
    }

    /** 已发布类型不可重复发布。 */
    @Test
    void alreadyPublishedRejectsPublishAgain() {
        UUID id = UUID.randomUUID();
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.findByIdForUpdate(projectId, id)).thenReturn(Optional.of(type(id, DeviceType.Status.PUBLISHED)));
        assertThatThrownBy(() -> service.publish(projectId, id))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE));
        verify(repository, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** VIEWER 不可发布。 */
    @Test
    void viewerCannotPublish() {
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        assertThatThrownBy(() -> service.publish(projectId, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN));
        verify(repository, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** 构造指定状态的领域对象。 @param id 类型 ID @param status 状态 @return 测试对象 */
    private DeviceType type(UUID id, DeviceType.Status status) {
        TenantScope scope = TenantContext.current().orElseThrow();
        return new DeviceType(id, scope.tenantId(), projectId, "sensor", "传感器",
                DeviceType.DeviceKind.DIRECT, DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.OTHER,
                1, status, null, null, Instant.now());
    }
}
