package com.things.link.rule.api.support;
import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Component;
import java.util.UUID;
/** 管理面第一层角色校验；应用层仍须重复鉴权。 */
@Component
public class AutomationApiAuthorization {
    private final ProjectService projects;
    public AutomationApiAuthorization(ProjectService projects){this.projects=projects;}
    public void manage(UUID project){var role=projects.requireRoleInProject(project);
        if(role!=ProjectRole.OWNER&&role!=ProjectRole.ADMIN)throw new BusinessException(RuleErrorCode.AUTOMATION_MANAGE_FORBIDDEN);}
}
