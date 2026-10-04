package com.things.link.rule.api.support;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 执行记录只读 Controller 的第一层项目授权；应用服务必须保留同一规则以保护内部调用。 */
@Component
public class RuleExecutionApiAuthorization {
    /** 项目成员公开端口。 */ private final ProjectService projectService;
    /** @param projectService 项目成员与角色端口 */
    public RuleExecutionApiAuthorization(ProjectService projectService) { this.projectService = projectService; }
    /** 执行记录是只读可观测数据，任何项目成员（VIEWER 及以上）都可读；路径项目必须等于当前已选项目。 */
    public void requireProjectRead(UUID projectId) {
        TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        if (projectId == null || !projectId.equals(TenantContext.requireProjectId())) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        projectService.requireRoleInProject(projectId);
    }
}
