package com.things.link.ota.domain;

import java.time.Instant;

/** 冻结策略的当前批次投影，完成后数量来自不可变完成事实。
 * @param currentBatchStatus 当前批状态，尚未启动为空
 * @param requireManualBatchApproval 冻结的人工放行要求
 * @param awaitingManualApproval 当前成功批等待人工扩批
 * @param nextBatchNumber 冻结的下一批序号，无下一批为空
 * @param completedAt 活动完成数据库时间，尚未完成为空
 * @param outcome 活动完成业务结论，尚未完成为空
 * @param targetCount 全部冻结目标数
 * @param succeededCount 成功数量
 * @param rolledBackCount 回退数量
 * @param skippedCount 资格跳过数量
 * @param timedOutCount 明确失败且预算耗尽的作业数
 * @param cancelledCount 取消数量
 */
public record OtaCampaignBatchProgress(String currentBatchStatus, boolean requireManualBatchApproval,
        boolean awaitingManualApproval, Integer nextBatchNumber, Instant completedAt, String outcome,
        long targetCount, long succeededCount, long rolledBackCount, long skippedCount, long timedOutCount, long cancelledCount) { }
