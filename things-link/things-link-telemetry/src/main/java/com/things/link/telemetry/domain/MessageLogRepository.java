package com.things.link.telemetry.domain;

import com.things.link.shared.page.CursorPage;

import java.time.Instant;
import java.util.UUID;

/** 设备消息日志仓储端口。 */
public interface MessageLogRepository {
    /**
     * 原子登记 messageId；只有首次调用成功者可以继续写日志。
     *
     * @param entry 消息日志
     * @return 是否取得本消息的写入权
     */
    boolean tryAcquire(DeviceMessageLog entry);

    /**
     * 保存已取得幂等写入权的日志。
     *
     * @param entry 消息日志
     */
    void save(DeviceMessageLog entry);

    /**
     * 按筛选条件执行键集分页查询。
     *
     * @param query 查询条件
     * @return 一页消息日志
     */
    CursorPage<DeviceMessageLog> find(MessageLogQuery query);

    /**
     * 按日志 ID 读取一行（限定项目与设备，避免跨项目存在性探测）。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param logId 日志 ID
     * @return 命中的日志；不存在时为空
     */
    java.util.Optional<DeviceMessageLog> findByLogId(java.util.UUID projectId, java.util.UUID deviceId,
                                                     java.util.UUID logId);

    /**
     * 按平台接收时刻聚合半开时间窗内的消息量与流量。
     *
     * <p>使用 {@code received_at} 而不是设备提供的 {@code ts}：前者由平台生成并与落库、保留生命周期
     * 一致，后者可能因为设备时钟漂移或伪造落到任意时间，不能用于运营概要口径。</p>
     *
     * @param projectId 项目 ID
     * @param from 平台接收窗口起点（包含）
     * @param to 平台接收窗口终点（不包含）
     * @return 消息统计
     */
    MessageLogStatistics summarize(UUID projectId, Instant from, Instant to);
}
