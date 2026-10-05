package com.things.link.task.domain;

import com.things.link.shared.error.ErrorCode;

/** 任务调度域错误码，使用规则/执行域预留的 4xxxx 段。 */
public enum TaskErrorCode implements ErrorCode {
    /** 当前项目角色不能管理任务定义。 */
    TASK_MANAGE_FORBIDDEN(40020, "当前角色无权管理任务", 403),
    /** 当前项目角色不能手工触发任务。 */
    TASK_RUN_FORBIDDEN(40021, "当前角色无权执行任务", 403),
    /** 租户共享下行日额度达到硬限后暂停产生新的批量执行。 */
    TASK_DAILY_QUOTA_EXCEEDED(40022, "租户共享下行日额度已用尽", 429);
    /** 业务码。 */ private final int code;
    /** 默认中文消息。 */ private final String message;
    /** HTTP 状态。 */ private final int status;
    /** 创建错误码。 */ TaskErrorCode(int code, String message, int status) { this.code = code; this.message = message; this.status = status; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public int code() { return code; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public String defaultMessage() { return message; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public int httpStatus() { return status; }
}
