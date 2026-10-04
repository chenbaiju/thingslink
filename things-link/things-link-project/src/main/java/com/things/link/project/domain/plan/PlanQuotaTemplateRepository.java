package com.things.link.project.domain.plan;

import java.util.Map;
import java.util.UUID;

/**
 * 产品修订版配额模板的持久化端口（S14-1b）。
 *
 * <p>写入端只有「幂等物化」与「一次性绑定」，没有任何 UPDATE 额度值的语义：
 * 调整冻结值必须新建产品修订版，再物化一份新的模板行。绑定只允许把
 * {@code sys_plan_revision.quota_policy_id} 从空补成一个具体模板，且仅在确实为空时生效。
 */
public interface PlanQuotaTemplateRepository {

    /**
     * 取得配额模板物化的事务级排他锁，串行化同一修订版的并发播种。
     *
     * @param revision 产品修订版标识
     */
    void lockSeed(String revision);

    /**
     * 按产品修订版读回已绑定的全部冻结模板（播种后的漂移校验用）。
     *
     * @param revision 产品修订版标识
     * @return 档位编码到冻结模板的映射，按档位展示顺序；没有绑定时返回空映射
     */
    Map<String, PlanQuotaTemplate> findByRevision(String revision);

    /**
     * 幂等物化一份冻结模板：编码已存在时不改写任何值，只返回既有模板 ID。
     *
     * @param planCode 档位编码，用于记录来源与失败信息
     * @param policyCode 配额模板编码，全局唯一
     * @param template 冻结数值
     * @return 该编码对应的模板 ID，无论本次是否新建
     */
    UUID ensureTemplate(String planCode, String policyCode, PlanQuotaTemplate template);

    /**
     * 把修订版绑定到模板；只在 {@code quota_policy_id} 仍为空时写入。
     *
     * <p>已经绑定时不改写：若既有绑定与期望模板不一致，抛出异常显式暴露漂移，
     * 而不是把一个已被历史订单引用的修订版改绑到另一份模板。
     *
     * @param revision 产品修订版标识
     * @param planCode 档位编码
     * @param policyId 期望的模板 ID
     * @throws IllegalStateException 修订版不存在，或既有绑定与期望不一致
     */
    void linkIfAbsent(String revision, String planCode, UUID policyId);
}
