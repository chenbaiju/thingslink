package com.things.link.project.application;

/**
 * 业务模块从幂等事实重算出的单指标绝对日用量。
 *
 * <p>这是跨模块 application 契约而非数据库增量；project 会把它映射到内部领域值并以
 * {@code GREATEST} 归并，重复投递与多实例重叠不会重复计费。</p>
 *
 * @param metric UTC 日计量指标
 * @param usedValue 非负绝对值
 */
public record DailyUsageValue(QuotaMetric metric, long usedValue) {

    /** 拒绝存量指标和负账单进入日用量边界。 */
    public DailyUsageValue {
        if (metric == null || !metric.dailyCounter()) {
            throw new IllegalArgumentException("日用量贡献必须使用日计量指标");
        }
        if (usedValue < 0) {
            throw new IllegalArgumentException("日用量绝对值不能为负数");
        }
    }
}
