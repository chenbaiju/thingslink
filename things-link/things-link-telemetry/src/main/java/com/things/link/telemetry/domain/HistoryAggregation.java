package com.things.link.telemetry.domain;

/** 数值历史聚合函数；连续聚合保存的中间量足以稳定计算这五种结果。 */
public enum HistoryAggregation {
    /** 平均值，由 sum/count 计算，避免分层平均造成权重错误。 */
    AVG,
    /** 最小值。 */
    MIN,
    /** 最大值。 */
    MAX,
    /** 总和。 */
    SUM,
    /** 有效数值点数量。 */
    COUNT
}
