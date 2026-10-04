package com.things.link.telemetry.domain;

import com.things.link.shared.page.CursorPage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 属性时序点仓储端口。
 *
 * <p>查询使用基于 {@code (ts, message_id, property_key)} 的键集游标，避免持续写入时
 * offset 分页产生重复或遗漏，依据架构文档第 11.1 节。</p>
 */
public interface PropertyPointRepository {
    /**
     * 保存一个已经通过物模型校验的属性点。
     *
     * @param point 属性点
     */
    void save(PropertyPoint point);

    /**
     * 按设备、属性和时间窗口查询历史点。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 可选属性标识符
     * @param from 可选起始时刻，包含
     * @param to 可选结束时刻，不包含
     * @param cursor 可选键集游标
     * @param limit 每页数量
     * @return 一页历史点
     */
    CursorPage<PropertyPoint> findByDevice(UUID projectId, UUID deviceId, String propertyKey,
                                           Instant from, Instant to, String cursor, int limit);

    /**
     * 查询单个数值属性的原始点或连续聚合桶。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 属性标识符
     * @param from 开始时刻，包含
     * @param to 结束时刻，不包含
     * @param granularity 实际查询粒度
     * @param aggregation 聚合函数
     * @param limit 最大点数
     * @return 按时间正序排列的点
     */
    List<PropertyHistoryPoint> findHistory(UUID projectId, UUID deviceId, String propertyKey,
                                           Instant from, Instant to, HistoryGranularity granularity,
                                           HistoryAggregation aggregation, int limit);

    /**
     * 检查窗口内是否存在不可数值聚合的版本化属性点。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 属性标识符
     * @param from 开始时刻，包含
     * @param to 结束时刻，不包含
     * @return true 表示 OBJECT/LIST/TEXT/SWITCH/ENUM 不得进入数值聚合
     */
    boolean hasNonNumericData(UUID projectId, UUID deviceId, String propertyKey, Instant from, Instant to);
}
