package com.things.link.project.application.plan;

import com.things.link.project.domain.plan.PlanCatalogEntry;
import com.things.link.project.domain.plan.PlanCatalogRepository;
import com.things.link.project.domain.plan.PlanDefinition;
import com.things.link.project.domain.plan.PlanQuotaTemplate;
import com.things.link.project.domain.plan.PlanQuotaTemplateRepository;
import com.things.link.project.domain.plan.ProductRevision1;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code product-revision-1} 的幂等播种用例（S14-0 decisionVersion 1，S14-1b 扩展到配额模板）。
 *
 * <p>契约有四条，缺一不可：
 * <ol>
 *   <li><b>可重复执行。</b>目录与配额模板通常在迁移里已落库；本用例只补缺失行，可以在测试或
 *       运维中反复调用而不产生副本。</li>
 *   <li><b>绝不静默改值。</b>已存在的套餐、修订版、维度行、权益行与配额模板一律不更新；播种后
 *       读回并与冻结定义逐值比对，任何漂移都以异常失败，而不是把旧值改写成新值。</li>
 *   <li><b>绑定只补空。</b>{@code sys_plan_revision.quota_policy_id} 只在为空时补成冻结模板；
 *       已绑定到另一模板时失败，绝不原地改绑。</li>
 *   <li><b>并发安全。</b>先取事务级排他锁，避免两个播种事务交错出半份快照。</li>
 * </ol>
 *
 * <p>调整价格或额度必须新建 {@code revisionNo}（架构文档 §3.1），本用例不提供修改入口。
 */
@Service
public class PlanCatalogSeedService {

    /** 目录持久化与幂等补齐端口。 */
    private final PlanCatalogRepository planCatalogRepository;
    /** 配额模板幂等物化与一次性绑定端口。 */
    private final PlanQuotaTemplateRepository planQuotaTemplateRepository;

    /**
     * @param planCatalogRepository 目录持久化与幂等补齐端口
     * @param planQuotaTemplateRepository 配额模板幂等物化与绑定端口
     */
    public PlanCatalogSeedService(PlanCatalogRepository planCatalogRepository,
                                  PlanQuotaTemplateRepository planQuotaTemplateRepository) {
        this.planCatalogRepository = planCatalogRepository;
        this.planQuotaTemplateRepository = planQuotaTemplateRepository;
    }

    /**
     * 补齐并校验 {@code product-revision-1} 四档冻结目录与配额模板。
     *
     * @return 本次实际新建的修订版数量；目录与模板已完整时为 0
     * @throws IllegalStateException 已存在目录或模板与冻结定义不一致（不修改任何既有行）
     */
    @Transactional
    public int ensureProductRevision1() {
        planCatalogRepository.lockSeed(ProductRevision1.CODE);
        planQuotaTemplateRepository.lockSeed(ProductRevision1.CODE);
        Instant validFrom = Instant.now();
        int inserted = 0;
        for (PlanDefinition definition : ProductRevision1.definitions()) {
            if (planCatalogRepository.insertIfAbsent(definition, validFrom)) {
                inserted++;
            }
        }
        List<PlanCatalogEntry> actualCatalog = planCatalogRepository.findByProductRevision(ProductRevision1.CODE);
        ProductRevision1.verify(actualCatalog);

        for (Map.Entry<String, PlanQuotaTemplate> entry : ProductRevision1.quotaTemplates().entrySet()) {
            String planCode = entry.getKey();
            UUID policyId = planQuotaTemplateRepository.ensureTemplate(
                    planCode, ProductRevision1.quotaPolicyCode(planCode), entry.getValue());
            planQuotaTemplateRepository.linkIfAbsent(ProductRevision1.CODE, planCode, policyId);
        }
        Map<String, PlanQuotaTemplate> actualTemplates =
                planQuotaTemplateRepository.findByRevision(ProductRevision1.CODE);
        ProductRevision1.verifyQuotaTemplates(actualTemplates);
        return inserted;
    }
}
