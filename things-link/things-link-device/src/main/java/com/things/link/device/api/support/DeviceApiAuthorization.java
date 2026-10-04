package com.things.link.device.api.support;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 设备 Controller 的第一层项目授权守卫。
 *
 * <p>应用服务仍保留同样的角色检查，确保内部调用绕过 HTTP 层时也不会越权；本守卫负责让每个 HTTP
 * 入口显式声明读、创建或修改意图。</p>
 */
@Component
public class DeviceApiAuthorization {
    /** 项目公开应用服务。 */ private final ProjectService projectService;

    /** @param projectService 项目成员关系查询服务 */
    public DeviceApiAuthorization(ProjectService projectService) { this.projectService = projectService; }

    /** @param projectId 项目 ID */
    public void requireRead(UUID projectId) { requireMember(projectId); }

    /** @param projectId 项目 ID */
    public void requireCreate(UUID projectId) {
        if (!isManager(requireMember(projectId)))
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_CREATE_FORBIDDEN);
    }

    /** @param projectId 项目 ID */
    public void requireWrite(UUID projectId) {
        if (!isManager(requireMember(projectId)))
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN);
    }

    /** 设备实例、设备组与标签共用配置写权限，但不能返回设备类型专属错误码。 */
    public void requireDeviceWrite(UUID projectId) {
        if (!isManager(requireMember(projectId))) {
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
        }
    }

    /** @param projectId 项目 ID @return 当前项目角色 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.roleInProject(projectId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }

    /** @param role 项目角色 @return 是否可修改物模型 */
    private static boolean isManager(ProjectRole role) {
        return role == ProjectRole.OWNER || role == ProjectRole.ADMIN;
    }
}
