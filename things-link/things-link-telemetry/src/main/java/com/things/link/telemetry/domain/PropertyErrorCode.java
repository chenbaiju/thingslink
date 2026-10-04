package com.things.link.telemetry.domain;

import com.things.link.shared.error.ErrorCode;

/** 属性数据面错误码，对应 docs/ERROR_CODES.md 的 30058–30059。 */
public enum PropertyErrorCode implements ErrorCode {
    /** OBJECT/LIST/TEXT/SWITCH/ENUM 不得进入数值聚合，复合值应使用 RAW 游标历史。 */
    PROPERTY_AGGREGATION_UNSUPPORTED(30058, "该属性类型不支持聚合", 400),
    /** 同一 messageId 只能重放完全相同的物模型版本与载荷。 */
    UPLINK_REPLAY_CONFLICT(30059, "上行消息重放内容冲突", 409);

    /** 业务码。 */ private final int code;
    /** 中文消息。 */ private final String message;
    /** HTTP 状态。 */ private final int httpStatus;
    PropertyErrorCode(int code, String message, int httpStatus) {
        this.code = code; this.message = message; this.httpStatus = httpStatus;
    }
    /** {@inheritDoc} */ @Override public int code() { return code; }
    /** {@inheritDoc} */ @Override public String defaultMessage() { return message; }
    /** {@inheritDoc} */ @Override public int httpStatus() { return httpStatus; }
}
