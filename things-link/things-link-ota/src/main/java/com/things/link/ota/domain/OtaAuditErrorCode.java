package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** ADR0125 OTA审计读取控制面的稳定失败分类。 */
public enum OtaAuditErrorCode implements ErrorCode {
    /** 仅当前OWNER/ADMIN可查询OTA审计。 */ FORBIDDEN(70049, "当前角色无权查询OTA审计", 403);
    /** 稳定业务码。 */ private final int code;
    /** 安全公开文案。 */ private final String message;
    /** HTTP分类。 */ private final int status;
    /** 冻结公开错误映射。 */
    OtaAuditErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回业务码。 */ @Override public int code() { return code; }
    /** 返回安全文案。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP分类。 */ @Override public int httpStatus() { return status; }
}
