package com.things.link.issuer.application;

import com.things.link.shared.error.ErrorCode;

/** 发行方自部署审核身份的独立业务拒绝码。 */
public enum ShcReviewerErrorCode implements ErrorCode {
    REVIEWER_REQUIRED(58001, "需要自部署审核权限", 403);

    private final int code;
    private final String message;
    private final int httpStatus;

    ShcReviewerErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override public int code() { return code; }
    @Override public String defaultMessage() { return message; }
    @Override public int httpStatus() { return httpStatus; }
}
