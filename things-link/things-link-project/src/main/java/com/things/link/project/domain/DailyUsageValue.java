package com.things.link.project.domain;

/**
 * 一个项目、UTC 日期和计量指标的绝对用量快照。
 *
 * <p>值不是增量。归并层只用数据库 {@code GREATEST} 推进它，因此 Kafka 重放、多实例并发与
 * 昨日迟到事件重算都不会重复计费。</p>
 *
 * @param metric 冻结日计量指标
 * @param usedValue 从幂等业务事实重算得到的非负绝对值
 */
public record DailyUsageValue(QuotaMetric metric, long usedValue) {

    /** 拒绝存量指标和负账单进入 UTC 日计数表。 */
    public DailyUsageValue {
        if (metric == null || !metric.dailyCounter()) {
            throw new IllegalArgumentException("日用量贡献必须使用日计量指标");
        }
        if (usedValue < 0) {
            throw new IllegalArgumentException("日用量绝对值不能为负数");
        }
    }
}
