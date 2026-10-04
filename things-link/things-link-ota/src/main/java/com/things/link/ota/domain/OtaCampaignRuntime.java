package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 当前运行投影，不代表通知已到设备或下载已授权。
 * @param campaign 活动不可变快照和当前状态
 * @param startedAt 首次启动时间
 * @param currentBatch 当前批次
 * @param pauseKind 暂停类型
 * @param pauseReason 稳定暂停原因
 * @param pausedAt 最近暂停时间
 * @param pauseActorId 管理账号，系统暂停为空
 * @param pauseJobId 安全暂停触发作业，可空
 * @param pendingCount 待准入数量
 * @param dispatchedCount 已持久通知意图数量
 * @param skippedCount 明确不合格跳过数量
 * @param batchProgress 当前批次策略与完成事实
 * @param runtimeCancellation 运行取消原事实与当前责任投影，可空
 */
public record OtaCampaignRuntime(OtaCampaign campaign, Instant startedAt, Integer currentBatch,
        String pauseKind, String pauseReason, Instant pausedAt, UUID pauseActorId, UUID pauseJobId,
        long pendingCount, long dispatchedCount, long skippedCount, OtaCampaignRuntimeCancellation runtimeCancellation,
        OtaCampaignBatchProgress batchProgress) {
    /** 原取消投影构造兼容，完整仓储读取必须提供批次事实。 */
    public OtaCampaignRuntime(OtaCampaign campaign, Instant startedAt, Integer currentBatch,
            String pauseKind, String pauseReason, Instant pausedAt, UUID pauseActorId, UUID pauseJobId,
            long pendingCount, long dispatchedCount, long skippedCount, OtaCampaignRuntimeCancellation runtimeCancellation) {
        this(campaign, startedAt, currentBatch, pauseKind, pauseReason, pausedAt, pauseActorId, pauseJobId,
                pendingCount, dispatchedCount, skippedCount, runtimeCancellation, null);
    }
    /** 原运行投影构造保持兼容，未提供运行取消事实时为空。 */
    public OtaCampaignRuntime(OtaCampaign campaign, Instant startedAt, Integer currentBatch,
            String pauseKind, String pauseReason, Instant pausedAt, UUID pauseActorId, UUID pauseJobId,
            long pendingCount, long dispatchedCount, long skippedCount) {
        this(campaign, startedAt, currentBatch, pauseKind, pauseReason, pausedAt, pauseActorId, pauseJobId,
                pendingCount, dispatchedCount, skippedCount, null);
    }
}
