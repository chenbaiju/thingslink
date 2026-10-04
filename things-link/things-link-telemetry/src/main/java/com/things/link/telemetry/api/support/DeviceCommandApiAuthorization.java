package com.things.link.telemetry.api.support;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.telemetry.domain.DeviceCommandErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 命令 Controller 第一层授权守卫；应用服务保留同样检查。 */
@Component
public class DeviceCommandApiAuthorization {
    /** 项目角色服务。 */ private final ProjectService projectService;
    /** @param projectService 项目角色服务 */
    public DeviceCommandApiAuthorization(ProjectService projectService) { this.projectService = projectService; }
    /** OWNER/ADMIN/OPERATOR 可控制设备，VIEWER 明确拒绝。 */
    public void requireControl(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role == ProjectRole.VIEWER)
            throw new BusinessException(DeviceCommandErrorCode.COMMAND_CONTROL_FORBIDDEN);
    }
}
