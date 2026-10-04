package com.things.link.project.domain;

import com.things.link.project.domain.plan.PlanDimension;
import com.things.link.project.domain.plan.PlanEntitlement;
import com.things.link.project.domain.plan.PlanIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 租户套餐摘要（S14-2c）：只回答「这个租户订阅的是什么、服务期到何时、冻结额度与权益是什么」。
 *
 * <p><b>命名口径必须诚实。</b>{@code subscribedPlan} 是租户当前 ACTIVE 订阅快照锁定的修订版
 * （成交即冻结，后续目录调价不改写存量）；{@code effectivePlan} 是运行时指针
 * {@code sys_tenant.quota_policy_id} 当前实际绑定的套餐档位。两者在中途迁移、绑定滞后或目录
 * 只调价不改额度时可能不一致，因此分别暴露、不互相冒充。{@code effectivePlan} 为 {@code null}
 * 表示运行时绑定不是可售套餐模板（例如 S7 遗留 FREE 基线），<b>不是「不限」</b>。
 *
 * <p>{@code quotaDimensions} 与 {@code capabilities} 恒来自 {@code subscribedPlan} 锁定的修订版：
 * 那是租户与平台之间的冻结契约。它们不含成交价、订单或支付渠道；{@code referencePriceCents}
 * 只是商业架构 §2 的参考价快照，不是成交价，也不参与任何销售判定。
 *
 * <p>{@code serviceEndsAt} 为 {@code null} 表示服务期没有终点（FREE 长期有效档，数据库约束
 * {@code sys_tenant_subscription_perpetual_ck} 已把它钉死为「零价且无计费周期」）；这是服务期
 * 语义，不是「不限额度」。
 *
 * @param subscribedPlan ACTIVE 订阅锁定的档位身份
 * @param effectivePlan 运行时指针实际绑定的档位身份；{@code null} 表示绑定不是可售套餐模板
 * @param subscriptionStatus 订阅状态：{@code ACTIVE}/{@code GRACE}/{@code RESTRICTED_FREE} 均为活状态
 *        （S14-6b：宽限与受限免费期仍需向租户展示档位与有效额度）
 * @param serviceStartsAt 服务期起始时刻（UTC，含）
 * @param serviceEndsAt 服务期结束时刻（UTC，不含）；{@code null} 表示长期有效
 * @param billingPeriod 计费周期快照：{@code NONE}/{@code MONTH}/{@code YEAR}
 * @param renewalMode 续费方式：{@code NONE}/{@code AUTO}/{@code MANUAL}
 * @param referencePriceCents 参考价快照（人民币分）；未记录时为空。它不是成交价
 * @param referencePriceCurrency 参考价币种；与参考价同生共死
 * <p>{@code effectiveQuotaDimensions}（S14-4c）与 {@code quotaDimensions} <b>不是同一个表示</b>：
 * 后者是修订版冻结的目录表示（例如对象存储写成 {@code 100 MB}），前者是运行时实际生效的
 * <b>运行时单位</b>表示（对象存储写成 {@code BYTE}），数值已含此刻有效的资源包与人工调整。
 * 两者并列暴露是为了让租户既看到「买的是什么」也看到「此刻真正生效多少」，且谁也不冒充谁。
 * 运行时绑定不是可售套餐模板时它是空列表：那表示没有运行时额度投影，<b>不是额度为零</b>。
 *
 * <p>{@code additions} 是扩容与人工调整的只读溯源行（含生效中与待生效），可以为空；
 * 它只暴露租户该看的事实，操作人账号不在其中。
 *
 * @param quotaDimensions 冻结数值维度（目录表示），按编码排序
 * @param capabilities 功能权益，含 {@code DISABLED}，按编码排序
 * @param effectiveQuotaDimensions 运行时生效维度（运行时单位，含有效扩容与调整），按编码排序
 * @param additions 扩容与人工调整溯源行，按起点升序；没有时为空列表
 */
public record TenantPlanSummary(
        PlanIdentity subscribedPlan,
        PlanIdentity effectivePlan,
        String subscriptionStatus,
        Instant serviceStartsAt,
        Instant serviceEndsAt,
        String billingPeriod,
        String renewalMode,
        Long referencePriceCents,
        String referencePriceCurrency,
        List<PlanDimension> quotaDimensions,
        List<PlanEntitlement> capabilities,
        List<PlanDimension> effectiveQuotaDimensions,
        List<PlanQuotaAddition> additions) {

    /** 摘要必须来自真实订阅事实：档位、状态与起始时刻都不能缺失，列表必须是只读副本。 */
    public TenantPlanSummary {
        Objects.requireNonNull(subscribedPlan, "订阅锁定的档位身份不得为空");
        Objects.requireNonNull(subscriptionStatus, "订阅状态不得为空");
        Objects.requireNonNull(serviceStartsAt, "服务期起始时刻不得为空");
        Objects.requireNonNull(billingPeriod, "计费周期不得为空");
        Objects.requireNonNull(renewalMode, "续费方式不得为空");
        if ((referencePriceCents == null) != (referencePriceCurrency == null)) {
            throw new IllegalArgumentException("参考价与参考价币种必须同时存在或同时缺失");
        }
        if (referencePriceCents != null && referencePriceCents < 0) {
            throw new IllegalArgumentException("参考价不得为负数");
        }
        if (serviceEndsAt != null && !serviceEndsAt.isAfter(serviceStartsAt)) {
            throw new IllegalArgumentException("服务期必须是非空区间");
        }
        quotaDimensions = List.copyOf(quotaDimensions);
        capabilities = List.copyOf(capabilities);
        effectiveQuotaDimensions = List.copyOf(effectiveQuotaDimensions);
        additions = List.copyOf(additions);
    }

    /**
     * 运行时是否存在有效额度投影（运行时绑定是可售套餐模板）。
     *
     * <p>为 {@code false} 时 {@code effectiveQuotaDimensions} 为空，读取方必须显示「运行时绑定
     * 不是可售套餐模板」，不得把它当成额度为零，也不得回退展示目录冻结值冒充运行时值。
     *
     * @return 存在运行时有效额度投影时为 {@code true}
     */
    public boolean hasEffectiveQuotaDimensions() {
        return !effectiveQuotaDimensions.isEmpty();
    }

    /**
     * 运行时绑定的档位是否与订阅锁定的修订版一致。
     *
     * <p>显式暴露这条比较，避免读取方只看一个字段就默认「订阅额度已在运行时生效」。
     *
     * @return 两者都解析到同一档位与同一修订版时为 {@code true}
     */
    public boolean effectiveMatchesSubscribed() {
        return effectivePlan != null && effectivePlan.equals(subscribedPlan);
    }

    /**
     * 服务期是否没有终点。
     *
     * @return {@code serviceEndsAt} 为空时为 {@code true}
     */
    public boolean perpetual() {
        return serviceEndsAt == null;
    }
}
