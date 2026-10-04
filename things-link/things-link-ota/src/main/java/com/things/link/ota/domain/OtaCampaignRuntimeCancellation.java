package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 运行取消原请求与当前未决责任投影，未决归零前不宣称设备已停止。
 * @param requestedRevision 原取消请求修订
 * @param requestedFromStatus 原运行或暂停状态
 * @param requestedAt 首次取消请求数据库时间
 * @param requestedBy 首次真实管理账号
 * @param reason 首次固定原因
 * @param cancelledPendingCount 本次取消的未准入作业数量
 * @param unresolvedCount 当前仍未终态的作业数量
 * @param completedAt 活动真正进入取消完成的时间，可空
 */
public record OtaCampaignRuntimeCancellation(long requestedRevision, String requestedFromStatus,
        Instant requestedAt, UUID requestedBy, String reason, long cancelledPendingCount,
        long unresolvedCount, Instant completedAt) { }
