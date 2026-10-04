package com.things.link.telemetry.api.support;

import com.things.link.device.application.DeviceService;
import com.things.link.project.application.ProjectService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 消息日志 HTTP 入口授权守卫；应用服务仍执行第二次同等资源校验。 */
@Component
public class MessageLogApiAuthorization {
    /** 项目成员授权端口。 */
    private final ProjectService projectService;
    /** 设备域公开的控制面详情端口，统一使用设备不存在错误码。 */
    private final DeviceService deviceService;

    /**
     * 创建消息日志入口授权守卫。
     *
     * @param projectService 项目成员授权端口
     * @param deviceService 设备控制面详情端口
     */
    public MessageLogApiAuthorization(ProjectService projectService,
                                      DeviceService deviceService) {
        this.projectService = projectService;
        this.deviceService = deviceService;
    }

    /** @param projectId 项目 ID */
    public void requireProjectRead(UUID projectId) {
        projectService.requireRoleInProject(projectId);
    }

    /**
     * 校验项目成员和设备归属，跨项目设备不得通过日志查询泄露存在性。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     */
    public void requireDeviceRead(UUID projectId, UUID deviceId) {
        requireProjectRead(projectId);
        deviceService.detail(projectId, deviceId);
    }
}
