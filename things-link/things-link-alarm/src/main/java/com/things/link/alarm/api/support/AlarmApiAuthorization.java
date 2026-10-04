package com.things.link.alarm.api.support;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 告警 Controller 的第一层项目授权；应用服务必须保留同一规则以保护内部调用。 */
@Component
public class AlarmApiAuthorization {
    /** 项目成员公开端口。 */ private final ProjectService projectService;
    /** @param projectService 项目成员与角色端口 */
    public AlarmApiAuthorization(ProjectService projectService) { this.projectService = projectService; }
    /** @param projectId 项目 ID */ public void requireRead(UUID projectId) { requireMember(projectId); }
    /** 规则是配置资产，只有 OWNER/ADMIN 可以改变。 */
    public void requireRuleManage(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) throw new BusinessException(AlarmErrorCode.MAINTAIN_FORBIDDEN);
    }
    /** 运行期 ACK/关闭允许 OPERATOR，VIEWER 保持只读。 */
    public void requireMaintain(UUID projectId) {
        if (requireMember(projectId) == ProjectRole.VIEWER) throw new BusinessException(AlarmErrorCode.MAINTAIN_FORBIDDEN);
    }
    /** 非成员由项目域统一转换为 50001/404，不能泄露项目存在性。 */
    private ProjectRole requireMember(UUID projectId) { return projectService.requireRoleInProject(projectId); }
}
