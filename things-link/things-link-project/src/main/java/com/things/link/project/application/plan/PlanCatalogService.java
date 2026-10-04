package com.things.link.project.application.plan;

import com.things.link.project.domain.plan.PlanCatalogEntry;
import com.things.link.project.domain.plan.PlanCatalogRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 平台套餐目录只读用例。
 *
 * <p>返回某时刻生效的修订版：每个套餐取有效区间内修订序号最高的一版。不返回已失效修订版，
 * 也不做价格/权益的运行时合成 —— 有效权益由 S14-3 订阅服务从生效订阅派生（架构文档 §3.3）。
 */
@Service
public class PlanCatalogService {

    /** 稳定套餐编码的形状，与 {@code sys_plan_code_ck} 一致；不匹配的编码不可能存在。 */
    private static final String PLAN_CODE_PATTERN = "^[A-Z][A-Z0-9_]{1,31}$";

    /** 目录持久化端口。 */
    private final PlanCatalogRepository planCatalogRepository;

    /**
     * @param planCatalogRepository 目录持久化端口
     */
    public PlanCatalogService(PlanCatalogRepository planCatalogRepository) {
        this.planCatalogRepository = planCatalogRepository;
    }

    /**
     * 读取当前生效的平台目录，按定价页展示顺序。
     *
     * @return 四档套餐快照；不含租户、订单或密钥信息
     */
    @Transactional(readOnly = true)
    public List<PlanCatalogEntry> catalog() {
        return planCatalogRepository.findEffectiveCatalog(Instant.now());
    }

    /**
     * 读取单个套餐当前生效的修订版快照。
     *
     * <p>未知或不存在的编码一律返回 {@code RESOURCE_NOT_FOUND}（404），与仓库既有的
     * 「资源不存在」口径一致：目录是平台全局公开事实，隐藏具体的编码格式错误没有意义，
     * 但也不能把「不存在」当成 200 空对象返回。形状不合法的编码直接判定不存在，不做回显。
     *
     * @param planCode 稳定套餐编码，如 {@code STANDARD}
     * @return 该套餐当前生效快照；不存在的编码抛 {@link BusinessException}
     * @throws BusinessException 编码不存在或形状不合法（404）
     */
    @Transactional(readOnly = true)
    public PlanCatalogEntry plan(String planCode) {
        if (planCode == null || !planCode.matches(PLAN_CODE_PATTERN)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "套餐不存在");
        }
        return planCatalogRepository.findEffectiveByPlanCode(planCode, Instant.now())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "套餐不存在"));
    }
}
