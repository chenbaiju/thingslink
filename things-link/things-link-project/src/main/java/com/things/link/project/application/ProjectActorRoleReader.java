package com.things.link.project.application;

import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** ADR0171：显式可信账号/项目的当前成员读取，不创建或冒充Console线程上下文。 */
@Service
public class ProjectActorRoleReader {
    private final ProjectRepository repository;
    public ProjectActorRoleReader(ProjectRepository repository){this.repository=repository;}
    /** 调用方已证明持久身份并建立项目隔离；快照不等于写事务许可。 */
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Optional<ProjectRole> current(UUID project,UUID account){
        if(project==null||account==null)throw new IllegalArgumentException("当前成员读取缺少可信身份");
        return repository.findRole(project,account);
    }
    /** 已确权出站事务持有成员行锁，成员删除/降权不能越过本帧接管。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<ProjectRole> lockCurrent(UUID project,UUID account){
        if(project==null||account==null)throw new IllegalArgumentException("缺少可信身份");
        return repository.lockMemberRole(project,account);
    }
}
