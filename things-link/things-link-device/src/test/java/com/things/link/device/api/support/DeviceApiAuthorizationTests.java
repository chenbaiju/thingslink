package com.things.link.device.api.support;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HTTP 设备接口授权守卫测试；应用服务另有独立授权测试，形成双层保护。 */
class DeviceApiAuthorizationTests {
    /** VIEWER 可读但写操作在进入服务前即被拒绝。 */
    @Test void viewerCanReadButCannotWrite() {
        UUID projectId = UUID.randomUUID();
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.roleInProject(projectId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        DeviceApiAuthorization authorization = new DeviceApiAuthorization(projectService);

        assertThatCode(() -> authorization.requireRead(projectId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorization.requireWrite(projectId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN));
    }

    /** 非项目成员统一表现为资源不存在，避免泄露项目存在性。 */
    @Test void nonMemberCannotReadProjectResources() {
        UUID projectId = UUID.randomUUID();
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.roleInProject(projectId)).thenReturn(Optional.empty());
        DeviceApiAuthorization authorization = new DeviceApiAuthorization(projectService);

        assertThatThrownBy(() -> authorization.requireRead(projectId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }
}
