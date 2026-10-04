package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** ADR0114登记的固件草稿稳定错误码。 */
public enum OtaFirmwareErrorCode implements ErrorCode {
    /** 不存在与错项目统一隐藏。 */
    NOT_FOUND(70001, "固件不存在或不可见", 404),
    /** 仅OWNER和ADMIN可写。 */
    FORBIDDEN(70002, "当前角色无权管理固件", 403),
    /** 精确已发布类型和模型资格不符。 */
    MODEL_CONFLICT(70003, "固件模型资格不符", 409),
    /** 状态或期望修订不符。 */
    STATE_CONFLICT(70004, "固件状态或修订冲突", 409);
    /** 稳定业务错误码。 */
    private final int code;
    /** 安全中文消息。 */
    private final String message;
    /** HTTP状态码。 */
    private final int status;
    /** 保存冻结错误元数据。 */
    OtaFirmwareErrorCode(int code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }
    /** 返回业务错误码。 */
    @Override public int code() { return code; }
    /** 返回安全默认消息。 */
    @Override public String defaultMessage() { return message; }
    /** 返回HTTP状态。 */
    @Override public int httpStatus() { return status; }
}
