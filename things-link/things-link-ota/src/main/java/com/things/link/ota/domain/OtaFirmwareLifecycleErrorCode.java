package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 固件软终态的稳定失败分类，不泄露其他项目资源。 */
public enum OtaFirmwareLifecycleErrorCode implements ErrorCode {
    /** 闭集正文、修订或原因不满足合同。 */ INVALID(70025, "固件生命周期参数不合法", 400),
    /** 状态方向或期望修订与当前事实冲突。 */ CONFLICT(70026, "固件生命周期状态冲突", 409),
    /** 软终态管理限当前项目管理者。 */ FORBIDDEN(70027, "当前角色无权管理固件生命周期", 403);
    /** 固定业务码。 */ private final int code;
    /** 安全消息。 */ private final String message;
    /** 对应HTTP状态。 */ private final int status;
    /** 绑定稳定分类。 */
    OtaFirmwareLifecycleErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回已登记业务码。 */ @Override public int code() { return code; }
    /** 返回安全消息。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP映射。 */ @Override public int httpStatus() { return status; }
}
