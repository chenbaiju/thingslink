package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 读取独立小时统计样本；不从模拟样本反向写入业务事实。 */
public interface OverviewTrendRepository {
    /** @param tenantId 项目真实租户 @param projectId 受权项目 @param from 包含起点 @param to 不包含终点
     * @return 当前窗口的单一来源，优先实测；没有样本返回 NONE，不混算模拟与实测 */
    String source(UUID tenantId, UUID projectId, Instant from, Instant to);
    /** @param tenantId 项目真实租户 @param projectId 受权项目 @param from 包含起点 @param to 不包含终点
     * @param stepHours 桶小时数 @param source 已选单一来源 @return 按桶与指标聚合的样本 */
    List<Bucket> aggregate(UUID tenantId, UUID projectId, Instant from, Instant to, int stepHours, String source);
    /** @param metric 指标键 @param index 时间桶索引 @param value 累计或桶末值 @param samples 已到达的小时样本数 */
    record Bucket(String metric, int index, long value, int samples) { }
}
