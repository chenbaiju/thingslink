package com.things.link.project.application;

import java.time.Instant;

/** 同一数据库语句冻结的UTC有效历史区间，右端排他。 */
public record PlanHistoryWindow(Instant from, Instant to) {
    /** 请求起点缺省取套餐起点，否则取两者较晚者。 */
    public Instant clipFrom(Instant requested) { return requested == null || requested.isBefore(from) ? from : requested; }
    /** 请求终点缺省取数据库时刻，否则取两者较早者。 */
    public Instant clipTo(Instant requested) { return requested == null || requested.isAfter(to) ? to : requested; }
}
