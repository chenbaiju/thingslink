package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRepository;
import com.things.link.device.domain.DeviceGroupRule;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceSearchQuery;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 旧设备组数组接口的 201 条兼容边界测试。 */
@ExtendWith(MockitoExtension.class)
class DeviceGroupPagingTests {
    /** 组仓储替身。 */ @Mock private DeviceGroupRepository groups;
    /** 设备键集仓储替身。 */ @Mock private DeviceRepository devices;
    /** 项目授权替身。 */ @Mock private ProjectService projects;
    /** 被测服务。 */ private DeviceGroupService service;
    /** 测试项目。 */ private UUID projectId;
    /** 动态组。 */ private DeviceGroup group;

    /** 建立一个合法动态组；成员查询必须走统一设备键集 SQL。 */
    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        group = new DeviceGroup(UUID.randomUUID(), UUID.randomUUID(), projectId, "在线设备", null,
                DeviceGroup.Type.DYNAMIC,
                new DeviceGroupRule(Set.of(), Set.of(Device.Status.ONLINE), Map.of(), null), Instant.now());
        service = new DeviceGroupService(groups, projects, devices);
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(groups.findGroup(projectId, group.id())).thenReturn(Optional.of(group));
    }

    /** 兼容接口最多返回 200 条，并把组条件交给统一键集查询。 */
    @Test
    void legacyMembersUseBoundedDeviceSearch() {
        when(devices.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(group)))
                .thenReturn(CursorPage.last(java.util.List.of()));

        assertThat(service.members(projectId, group.id())).isEmpty();

        ArgumentCaptor<DeviceSearchQuery> query = ArgumentCaptor.forClass(DeviceSearchQuery.class);
        verify(devices).search(query.capture(), org.mockito.ArgumentMatchers.eq(group));
        assertThat(query.getValue().groupId()).isEqualTo(group.id());
        assertThat(query.getValue().limit()).isEqualTo(200);
    }

    /** 第 201 条存在时返回明确 409，不允许缓存或数组响应静默截断。 */
    @Test
    void legacyMembersRejectMoreThanTwoHundredDevices() {
        when(devices.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(group)))
                .thenReturn(CursorPage.of(java.util.List.of(), "next"));

        assertThatThrownBy(() -> service.members(projectId, group.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(
                                DeviceErrorCode.LEGACY_LIST_LIMIT_EXCEEDED));
    }
}
