package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 持久发布失败只公开稳定分类，绝不暴露签名服务或对象地址。 */
public enum OtaPublicationErrorCode implements ErrorCode {
    /** 缺受控适配器、容量或外部依赖。 */ UNAVAILABLE(70016, "固件签名发布尚不可用", 503),
    /** 完整manifest与权威身份或签名不一致。 */ INVALID(70017, "固件发布内容校验失败", 422),
    /** 修订、状态、租约或最终资格发生变化。 */ CONFLICT(70018, "固件发布状态冲突", 409),
    /** 隐藏其他项目内的尝试。 */ NOT_FOUND(70019, "固件发布记录不存在或不可见", 404),
    /** 普通读取成员不具有管理发布权限。 */ FORBIDDEN(70020, "当前角色无权发布固件", 403);
    /** 固定业务码。 */ private final int code;
    /** 安全消息。 */ private final String message;
    /** 对应HTTP状态。 */ private final int status;
    /** 绑定稳定分类。 */
    OtaPublicationErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回已登记业务码。 */ @Override public int code() { return code; }
    /** 返回安全消息。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP映射。 */ @Override public int httpStatus() { return status; }
}
