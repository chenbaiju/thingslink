package com.things.link.telemetry.domain;

import com.things.link.shared.error.ErrorCode;

/** S4-3 命令事实与控制权限错误码，对应 docs/ERROR_CODES.md 的 30028–30031。 */
public enum DeviceCommandErrorCode implements ErrorCode {
    /** 命令不存在或不属于路径项目/设备。 */ COMMAND_NOT_FOUND(30028, "设备命令不存在", 404),
    /** VIEWER 或非项目成员不可控制设备。 */ COMMAND_CONTROL_FORBIDDEN(30029, "当前角色无权控制设备", 403),
    /** 请求载荷未通过输入 Schema。 */ COMMAND_INPUT_INVALID(30030, "命令请求参数不符合定义", 400),
    /** 当前状态不允许操作。 */ COMMAND_STATE_CONFLICT(30031, "命令状态不允许当前操作", 409);

    /** 业务码。 */ private final int code;
    /** 中文消息。 */ private final String message;
    /** HTTP 状态。 */ private final int httpStatus;
    DeviceCommandErrorCode(int code, String message, int httpStatus) {
        this.code = code; this.message = message; this.httpStatus = httpStatus;
    }
    /** {@inheritDoc} */ @Override public int code() { return code; }
    /** {@inheritDoc} */ @Override public String defaultMessage() { return message; }
    /** {@inheritDoc} */ @Override public int httpStatus() { return httpStatus; }
}
