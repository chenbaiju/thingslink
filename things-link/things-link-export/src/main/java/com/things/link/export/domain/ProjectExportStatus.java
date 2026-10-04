package com.things.link.export.domain;

/** ADR0075冻结的项目导出任务状态。 */
public enum ProjectExportStatus {
    /** 等待首次或重试领取。 */
    QUEUED,
    /** 已领取并正在生成或上传。 */
    RUNNING,
    /** 对象与摘要已经原子采用。 */
    SUCCEEDED,
    /** 永久失败或三次尝试耗尽。 */
    FAILED,
    /** 成功对象已经过期并完成删除。 */
    EXPIRED
}
