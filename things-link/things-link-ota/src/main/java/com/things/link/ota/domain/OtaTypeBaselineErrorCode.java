package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 类型基线只证明受控声明来源，不代表设备或硬件已完成资格验收。 */
public enum OtaTypeBaselineErrorCode implements ErrorCode {
    /** 缺受控来源或本进程配置与登记不符。 */ UNAVAILABLE(70028, "OTA类型基线尚未配置或不可用", 503),
    /** 规范字节、摘要或类型身份不完整。 */ INVALID(70029, "OTA类型基线校验失败", 422),
    /** 修订、版本或不可变制造身份冲突。 */ CONFLICT(70030, "OTA类型基线版本或身份冲突", 409),
    /** 按精确项目与类型隐藏不存在事实。 */ NOT_FOUND(70031, "OTA类型基线不存在或不可见", 404),
    /** 当前非项目管理者不允许登记或管理读取。 */ FORBIDDEN(70032, "当前角色无权管理OTA类型基线", 403);
    /** 已登记业务码。 */ private final int code;
    /** 安全公开消息。 */ private final String message;
    /** HTTP对应状态。 */ private final int status;
    /** 绑定固定分类，禁止供应商内容进入异常。 */
    OtaTypeBaselineErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回稳定业务码。 */ @Override public int code() { return code; }
    /** 返回安全消息。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP分类。 */ @Override public int httpStatus() { return status; }
}
