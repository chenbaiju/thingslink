package com.things.link.telemetry.domain;

import com.things.link.shared.error.ErrorCode;

/** ADR0235：事件发生事实的固定失败分类，不携带参数正文。 */
public enum EventErrorCode implements ErrorCode {
    /** 报文或原快照不符合事件合同；与设备同码同义，遥测不越过应用端口依赖设备内部枚举。 */
    EVENT_INVALID(30070, "设备事件上报不符合已发布定义", 400),
    /** 首次发生事实的上行额度明确不允许新增。 */
    EVENT_QUOTA_REJECTED(30071, "设备事件上行额度已用尽", 429),
    /** 已授权设备范围及当前历史窗口内不可见的发生事实。 */
    EVENT_NOT_FOUND(30072, "设备事件发生记录不存在", 404);

    /** 业务码。 */ private final int code;
    /** 固定公开说明。 */ private final String message;
    /** HTTP状态。 */ private final int status;
    EventErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public int code() { return code; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public String defaultMessage() { return message; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public int httpStatus() { return status; }
}
