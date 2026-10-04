package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 受控发布信任失败不等同于固件已发布或设备已经确认密钥。 */
public enum OtaTrustErrorCode implements ErrorCode {
    /** 无受控根或配置已变，不能猜测默认可信键。 */ UNAVAILABLE(70010, "发布信任尚未配置或不可用", 503),
    /** 签名、字段、根身份或持久字节校验不通过。 */ INVALID(70011, "信任配置或签名bundle不合法", 422),
    /** 修订、版本或密钥状态不允许该变更。 */ CONFLICT(70012, "信任修订或密钥状态冲突", 409),
    /** 隐藏不同项目内登记存在性。 */ NOT_FOUND(70013, "信任域尚未登记或不可见", 404),
    /** 类型未获授权或ACTIVE发布键不在有效期内。 */ INELIGIBLE(70014, "发布密钥当前不具备资格", 409),
    /** 成员读取资格不代表管理签名信任资格。 */ FORBIDDEN(70015, "当前角色无权管理发布信任", 403);
    /** 已登记业务码。 */ private final int code;
    /** 安全公开消息。 */ private final String message;
    /** HTTP对应状态。 */ private final int status;
    /** 绑定固定分类，禁止供应商内容进入异常。 */
    OtaTrustErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回稳定业务码。 */ @Override public int code() { return code; }
    /** 返回安全消息。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP分类。 */ @Override public int httpStatus() { return status; }
}
