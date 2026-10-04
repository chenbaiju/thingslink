package com.things.link.telemetry.api.support;

import com.things.link.project.application.ProjectService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 概要 HTTP 入口项目成员守卫；OverviewService 保留第二层同等授权。 */
@Component
public class OverviewApiAuthorization {
    /** 项目成员授权端口。 */
    private final ProjectService projectService;

    /** @param projectService 项目成员授权端口 */
    public OverviewApiAuthorization(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** @param projectId 项目 ID */
    public void requireRead(UUID projectId) {
        projectService.requireRoleInProject(projectId);
    }
}
