package com.things.link.rule.application.queue;

/**
 * 一次规则执行提交所携带的权威队列限制快照。
 *
 * <p>架构文档 7.1 要求租户共享并发与排队额度，并给项目保留独立保护上限。{@code null} 只表示
 * 当前策略未配置业务额度，调度器的实例级物理上限仍然生效，不能借此形成无界队列。</p>
 *
 * @param tenantConcurrencyLimit 租户共享运行并发上限，{@code null} 表示仅受工作线程数约束
 * @param tenantQueueCapacity 租户等待项上限，{@code null} 表示仅受实例总等待上限约束
 * @param projectQueueCapacity 项目等待项上限，{@code null} 表示仅受实例总等待上限约束
 */
public record RuleQueueLimits(
        Integer tenantConcurrencyLimit,
        Integer tenantQueueCapacity,
        Integer projectQueueCapacity
) {

    /** 校验显式额度；零容量允许项目只接受可立即执行的工作。 */
    public RuleQueueLimits {
        requireNonNegative(tenantConcurrencyLimit, "tenantConcurrencyLimit");
        requireNonNegative(tenantQueueCapacity, "tenantQueueCapacity");
        requireNonNegative(projectQueueCapacity, "projectQueueCapacity");
    }

    /**
     * 拒绝负数，避免配置错误被误解释为不限额。
     *
     * @param value 待校验值
     * @param name 参数名
     */
    private static void requireNonNegative(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " 不能小于零");
        }
    }
}
