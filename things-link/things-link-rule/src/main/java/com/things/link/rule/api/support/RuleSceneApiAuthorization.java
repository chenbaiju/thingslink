package com.things.link.rule.api.support;

import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 手动场景 Controller 的第一层项目授权；应用服务必须保留同一规则以保护内部调用。 */
@Component
public class RuleSceneApiAuthorization {
    /** 项目成员公开端口。 */ private final ProjectService projectService;
    /** @param projectService 项目成员与角色端口 */
    public RuleSceneApiAuthorization(ProjectService projectService) { this.projectService = projectService; }
    /** 场景是控制面配置资产，读写与执行都只有 OWNER/ADMIN 允许。 */
    public void requireSceneManage(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(RuleErrorCode.SCENE_MANAGE_FORBIDDEN);
    }
}
