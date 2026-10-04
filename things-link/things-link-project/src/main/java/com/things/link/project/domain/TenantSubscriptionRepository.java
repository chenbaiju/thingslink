package com.things.link.project.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 租户订阅事实的持久化端口（S14-2a，架构文档 §3.2）。
 *
 * <p>写入端只有「读取 FREE 冻结快照」与「幂等建一条 ACTIVE 免费订阅」两个动作，没有修改或删除：
 * 订阅状态机、升降级与到期属 S14-3，它们必须新增显式端口而不是让本端口长出 UPDATE 语义。
 */
public interface TenantSubscriptionRepository {

    /**
     * 读取 {@code product-revision-1} 的 FREE 冻结快照。
     *
     * <p>按产品修订版标识与档位编码连接 {@code sys_plan} / {@code sys_plan_revision} /
     * {@code sys_quota_policy}，不接收任何调用方传入的价格或模板 ID。
     *
     * @return FREE 修订版与 {@code PLAN_R1_FREE} 模板都存在时的快照；目录缺失时为空
     */
    Optional<FreeSubscriptionSnapshot> findFreeSubscriptionSnapshot();

    /**
     * 幂等写入一条 FREE 基础订阅：租户已有 ACTIVE 订阅时不插入、不修改。
     *
     * <p>幂等键是「该租户是否已有 ACTIVE 订阅」，与 {@code sys_tenant_subscription_active_tenant_uk}
     * 表达同一条不变式：重放同一注册/补建步骤既不会产生第二行，也不会覆盖既有订阅。
     *
     * @param tenantId 刚创建的租户 ID
     * @param snapshot FREE 冻结价目快照
     * @return 本次确实插入了新订阅行时返回 {@code true}；已存在 ACTIVE 订阅时返回 {@code false}
     */
    boolean createFreeSubscriptionIfAbsent(UUID tenantId, FreeSubscriptionSnapshot snapshot);

    /**
     * 读取 {@code PLAN_R1_FREE} 冻结模板的自由项目数上限（S14-3c）。
     *
     * <p>切换到 {@code RESTRICTED_FREE} 时要按「FREE 档允许几个自有项目」决定保留几个可写，
     * 因此该值必须从 FREE 模板读，不能把付费档或 P4 的默认值写死在业务代码里。
     *
     * @return FREE 模板的 {@code projects_max}；模板缺失或该列为空时为空
     */
    java.util.OptionalLong findFreeProjectsMax();
}
