package com.things.link.project.application;

/**
 * ADR0076：单轮显式删除事实，失败原因不承载异常正文。
 * @param deletedRows 实际直接删除行数，不包含状态整理
 * @param complete 是否已证明该领域无待清理事实
 * @param blockedReason 稳定等待分类；等待不能同时宣告删除或完成
 */
public record ProjectCleanupBatchResult(int deletedRows, boolean complete, String blockedReason) {

    /** ADR0076明确每批最多500行，任何贡献器超限均使原事务失败。 */
    public static final int LIMIT = 500;

    /** 验证领域结果不能将未知、超限或部分成功冒充完成。 */
    public ProjectCleanupBatchResult {
        if (deletedRows < 0 || deletedRows > LIMIT
                || (blockedReason != null && (!blockedReason.matches("[A-Z][A-Z0-9_]{0,63}")
                || deletedRows != 0 || complete))) {
            throw new IllegalArgumentException("无效项目清理批次结果");
        }
    }

    /** @param rows 真实删除行数 @return 保持当前阶段，下一轮继续查余量 */
    public static ProjectCleanupBatchResult deleted(int rows) {
        return new ProjectCleanupBatchResult(rows, false, null);
    }

    /** @return 已确认没有领域剩余事实 */
    public static ProjectCleanupBatchResult done() {
        return new ProjectCleanupBatchResult(0, true, null);
    }

    /** @param reason 稳定等待原因 @return 保持阶段并退避 */
    public static ProjectCleanupBatchResult blocked(String reason) {
        if (reason == null) {
            throw new IllegalArgumentException("等待原因不得为空");
        }
        return new ProjectCleanupBatchResult(0, false, reason);
    }
}
