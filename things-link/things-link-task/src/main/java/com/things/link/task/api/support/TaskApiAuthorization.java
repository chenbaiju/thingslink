package com.things.link.task.api.support;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.task.domain.TaskErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 任务 HTTP 入口的第一层项目角色守卫；应用服务保留相同二层校验。 */
@Component
public class TaskApiAuthorization {
    /** 项目成员授权服务。 */ private final ProjectService projectService;
    /** @param projectService 项目成员授权服务 */ public TaskApiAuthorization(ProjectService projectService) { this.projectService = projectService; }
    /** 全体成员可读取任务和执行日志。 */ public void requireRead(UUID projectId) { projectService.requireRoleInProject(projectId); }
    /** OWNER/ADMIN 可修改定义。 */ public void requireManage(UUID projectId) { ProjectRole role = projectService.requireRoleInProject(projectId); if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) throw new BusinessException(TaskErrorCode.TASK_MANAGE_FORBIDDEN); }
    /** OWNER/ADMIN/OPERATOR 可手工触发。 */ public void requireRun(UUID projectId) { if (projectService.requireRoleInProject(projectId) == ProjectRole.VIEWER) throw new BusinessException(TaskErrorCode.TASK_RUN_FORBIDDEN); }
}
