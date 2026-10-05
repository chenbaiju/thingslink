package com.things.link.assistant.application;

import com.things.link.assistant.domain.EvidenceRetentionRepository;
import com.things.link.assistant.domain.EvidenceRetentionRepository.Scope;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** 独立短事务回收已到期个人事实；项目生命周期许可不授予任何用户访问权。 */
@Service
public class EvidenceRetentionService {
    private final EvidenceRetentionRepository repository;
    private final ProjectLifecycleAccessService projects;
    private final TransactionLocalRlsScope rls;
    public EvidenceRetentionService(EvidenceRetentionRepository repository, ProjectLifecycleAccessService projects,
            TransactionLocalRlsScope rls) { this.repository=repository;this.projects=projects;this.rls=rls; }

    /** @param afterProject 仅项目标识的公平扫描游标 @return 固定最多100个到期候选，不返回私有正文 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public List<Scope> candidates(UUID afterProject) { return repository.candidates(afterProject); }

    /**
     * 在当前数据库年龄及项目锁下删除，删除中项目由原租约回收而非本维护任务处理。
     * @param scope 候选的真实租户和项目，必须完整
     * @param limit 全轮剩余预算，1至500
     * @return 本事务实际删除行数；项目不再可读时为0
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=5)
    public int purge(Scope scope, int limit) {
        if (scope==null || scope.tenantId()==null || scope.projectId()==null || limit<1 || limit>500)
            throw new IllegalArgumentException("事实回收身份或预算缺失");
        rls.establish(scope.tenantId(),scope.projectId());
        if (projects.lockReadableGeneration(scope.tenantId(),scope.projectId()).isEmpty()) return 0;
        return repository.deleteExpired(scope,limit);
    }
}
