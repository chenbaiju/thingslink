package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 管理资格查询错误与设备MQTT永久拒绝分类分离，不返回下载错误文案。 */
public enum OtaDeviceReportErrorCode implements ErrorCode {
    /** 当前管理成员不具备资格查看权限。 */
    FORBIDDEN(70033, "当前角色无权查询设备OTA资格", 403);

    /** 已登记业务码。 */ private final int code;
    /** 安全公开文案。 */ private final String message;
    /** HTTP状态。 */ private final int status;

    /** 绑定稳定公开分类。 */
    OtaDeviceReportErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回已登记业务码。 */ @Override public int code() { return code; }
    /** 返回安全文案。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP状态。 */ @Override public int httpStatus() { return status; }
}
