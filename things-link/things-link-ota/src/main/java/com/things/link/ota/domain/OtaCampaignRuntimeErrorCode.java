package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** ADR0126活动运行控制面的稳定失败分类。 */
public enum OtaCampaignRuntimeErrorCode implements ErrorCode {
    /** 运行控制必须当前OWNER/ADMIN。 */ FORBIDDEN(70039, "当前角色无权运行OTA活动", 403),
    /** 当前状态、修订或恢复条件不满足。 */ STATE_CONFLICT(70040, "OTA活动运行状态冲突", 409),
    /** 冻结排程时间尚未到达。 */ NOT_DUE(70041, "OTA活动尚未到排程时间", 409);
    /** 稳定业务码。 */ private final int code;
    /** 安全公开文案。 */ private final String message;
    /** HTTP分类。 */ private final int status;
    /** 冻结公开错误映射。 */
    OtaCampaignRuntimeErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回业务码。 */ @Override public int code() { return code; }
    /** 返回安全文案。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP分类。 */ @Override public int httpStatus() { return status; }
}
