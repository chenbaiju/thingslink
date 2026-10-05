package com.things.link.assistant.domain;

import com.things.link.shared.error.ErrorCode;

/** 项目成员使用共享模型凭据的许可；先登记于docs/ERROR_CODES.md。 */
public enum AssistantErrorCode implements ErrorCode {
    MODEL_ANALYSIS_FORBIDDEN(50060, "当前角色无权使用项目模型分析", 403),
    CREDENTIAL_PROTECTION_UNAVAILABLE(50061, "服务器模型凭据保护不可用，请联系管理员检查主密钥配置", 503);
    private final int code;
    private final String message;
    private final int status;
    AssistantErrorCode(int code, String message, int status) { this.code = code; this.message = message; this.status = status; }
    @Override public int code() { return code; }
    @Override public String defaultMessage() { return message; }
    @Override public int httpStatus() { return status; }
}
