package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 只读回退准备不构成执行权，配置缺失与当前授权分别归类。 */
public enum OtaRollbackPreflightErrorCode implements ErrorCode {
    /** 独立受控扩展与当前原基线必须同时满足。 */ UNAVAILABLE(70042, "OTA回退能力未配置或不可用", 503),
    /** 管理读取只允许当前项目管理者。 */ FORBIDDEN(70043, "当前角色无权查询OTA回退预检", 403),
    /** 精确范围下没有当前查询报告，不泄露其他项目身份。 */ NOT_FOUND(70044, "OTA回退预检结果不存在", 404);
    /** 稳定业务编号。 */ private final int code;
    /** 不含配置正文的公开消息。 */ private final String message;
    /** 固定HTTP分类。 */ private final int status;
    /** 不允许调用者把供应商错误拼入响应。 */
    OtaRollbackPreflightErrorCode(int code, String message, int status) { this.code=code; this.message=message; this.status=status; }
    /** 返回已登记编号。 */ @Override public int code() { return code; }
    /** 返回固定安全消息。 */ @Override public String defaultMessage() { return message; }
    /** 返回固定HTTP状态。 */ @Override public int httpStatus() { return status; }
}
