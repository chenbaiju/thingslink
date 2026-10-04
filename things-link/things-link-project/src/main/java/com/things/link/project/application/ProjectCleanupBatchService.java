package com.things.link.project.application;

import com.things.link.project.domain.ProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** ADR0076：一片领域工作与项目进度使用五秒独立事务，无自动调度入口。 */
@Service
public class ProjectCleanupBatchService {

    /** 先锁项目并验证完整身份，不能只依赖RLS保护归属。 */
    private final ProjectCleanupAdmissionService admission;
    /** 项目进度只由project仓储写入。 */
    private final ProjectCleanupRepository repository;
    /** 在清理批次当前事务连接上建立完整 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 每阶段最多一个贡献器，避免装配顺序决定行为。 */
    private final Map<ProjectCleanupStage, ProjectCleanupContributor> contributors;

    /**
     * 创建生产清理批次服务。
     *
     * @param admission 围栏服务
     * @param repository 进度仓储
     * @param transactionLocalRlsScope 事务局部 RLS 完整范围组件
     * @param contributors 已交付领域步骤
     */
    @Autowired
    public ProjectCleanupBatchService(ProjectCleanupAdmissionService admission, ProjectCleanupRepository repository,
                                      TransactionLocalRlsScope transactionLocalRlsScope,
                                      List<ProjectCleanupContributor> contributors) {
        this.admission = admission;
        this.repository = repository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        EnumMap<ProjectCleanupStage, ProjectCleanupContributor> steps = new EnumMap<>(ProjectCleanupStage.class);
        for (ProjectCleanupContributor contributor : contributors) {
            if (steps.put(Objects.requireNonNull(contributor.stage()), contributor) != null) {
                throw new IllegalArgumentException("同一项目清理阶段不能重复装配");
            }
        }
        this.contributors = Map.copyOf(steps);
    }

    /** ADR0092：只有显式开启worker时要求完整装配，部分模块测试不能因此冒充生产可调度。 */
    public void requireCompleteConfiguration() {
        java.util.EnumSet<ProjectCleanupStage> missing = java.util.EnumSet.allOf(ProjectCleanupStage.class);
        missing.removeAll(contributors.keySet());
        if (!missing.isEmpty()) throw new IllegalStateException("项目自动清理缺少步骤：" + missing);
    }

    /**
     * 每次调用都建立独立五秒事务，不能继承调用方更长的事务预算。
     * @param claim 当前完整领取身份
     * @return 空表示围栏拒绝；领域故障或提交时失权抛错并回滚
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Optional<ProjectCleanupBatchResult> execute(ProjectCleanupClaim claim) {
        if (!admission.lockCurrent(claim)) {
            return Optional.empty();
        }
        ProjectCleanupStage stage = ProjectCleanupStage.valueOf(claim.stage());
        ProjectCleanupContributor contributor = contributors.get(stage);
        if (contributor == null) {
            throw new IllegalStateException("项目清理阶段尚未接齐：" + stage);
        }
        // REQUIRES_NEW的事务局部范围在提交/回滚时自动还原，不污染外层请求连接。
        transactionLocalRlsScope.establish(claim.tenantId(), claim.projectId());
        ProjectCleanupBatchResult result = Objects.requireNonNull(contributor.clean(claim), "清理结果不得为空");
        // ADR0091：FINALIZE贡献器已在同事务中完成最后的时钟CAS，DONE没有后继普通阶段。
        if (stage == ProjectCleanupStage.FINALIZE && result.complete()) {
            if (result.deletedRows() != 0) throw new IllegalStateException("墓碑封存不能隐含业务删除");
            return Optional.of(result);
        }
        String next = result.complete() ? stage.next().name() : stage.name();
        if (!repository.completeBatch(claim, next, result.deletedRows(), result.blockedReason())) {
            throw new IllegalStateException("项目清理批次提交前租约失效");
        }
        return Optional.of(result);
    }
}
