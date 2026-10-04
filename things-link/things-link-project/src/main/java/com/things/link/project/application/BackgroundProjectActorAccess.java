package com.things.link.project.application;

import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.Project;
import com.things.link.shared.authz.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 已认证内部事件/持久资源的后台入口；不借用HTTP线程身份，不自行创建业务事务。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class BackgroundProjectActorAccess {
    private final ProjectRepository projects;
    private final AccountDirectory accounts;
    public BackgroundProjectActorAccess(ProjectRepository projects,AccountDirectory accounts) {
        this.projects=projects; this.accounts=accounts;
    }
    /** 项目共享锁保持到原事务结束，成员写入/归档使用既有项目排他锁。 */
    public State lockProject(UUID tenant,UUID project) {
        if(tenant==null||project==null)throw new IllegalArgumentException("后台范围缺失");
        return projects.lockLiveForBackground(tenant,project)
                .map(p->p.status()==Project.Status.ACTIVE?State.ACTIVE:State.READ_ONLY).orElse(State.MISSING);
    }
    /** 必须先持有项目许可；账号锁由IAM持有，锁后查询成员，不信任创建时角色。 */
    public boolean lockManager(UUID project,UUID account) {
        if(project==null||account==null)return false;
        if(!accounts.lockActive(account))return false;
        return projects.findRole(project,account).map(r->r==ProjectRole.OWNER||r==ProjectRole.ADMIN).orElse(false);
    }
    public enum State { ACTIVE, READ_ONLY, MISSING }
}
