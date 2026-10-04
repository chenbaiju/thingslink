package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 七天常规连续聚合窗口之外的迟到点回补端口。
 *
 * <p>请求与属性点在同一事务持久化；扫描器只领取 UTC 单日窗口，并在 raw/1m/1h/1d
 * 对账一致后完成。接口不暴露 TimescaleDB 内部物化表名。</p>
 */
public interface PropertyAggregateBackfillRepository {

    /**
     * 在当前属性摄入事务中登记可能需要的迟到窗口。
     *
     * @param tenantId 已确权租户 ID
     * @param projectId 已确权项目 ID
     * @param occurredAt 设备发生时间
     * @param receivedAt 平台接收时间
     * @return true 表示超过七天常规刷新窗并已登记，false 表示无需显式回补
     */
    boolean request(UUID tenantId, UUID projectId, Instant occurredAt, Instant receivedAt);

    /**
     * 领取到期窗口；数据库把批量夹在 1..20 并设置五分钟租约。
     *
     * @param maximumRows 期望最大领取数
     * @return 最小回补窗口投影
     */
    List<BackfillWindow> claimDue(int maximumRows);

    /**
     * 按 1m→1h→1d 顺序刷新窗口。
     *
     * @param window 已领取窗口
     */
    void refresh(BackfillWindow window);

    /**
     * 从 raw 独立重算三个粒度并统计不一致桶数。
     *
     * @param window 已刷新窗口
     * @return 不一致桶数，零才允许完成
     */
    long mismatchCount(BackfillWindow window);

    /**
     * 仅在 revision 未变化时完成；在途新增迟到点会保留请求供下一轮刷新。
     *
     * @param window 已对账窗口
     * @return true 表示请求已删除，false 表示 revision 已变化并重新排队
     */
    boolean complete(BackfillWindow window);

    /**
     * 释放失败租约并登记有界退避。
     *
     * @param window 失败窗口
     * @param failureSummary 不含业务载荷的限长异常类型摘要
     */
    void fail(BackfillWindow window, String failureSummary);

    /**
     * 扫描器可见的单日窗口。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param windowStart UTC 窗口起点（含）
     * @param windowEnd UTC 窗口终点（不含）
     * @param revision 领取时的请求修订号
     */
    record BackfillWindow(UUID tenantId, UUID projectId, Instant windowStart,
                          Instant windowEnd, long revision) {
    }
}
