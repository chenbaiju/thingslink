package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 上传会话稳定失败分类，不把对象验真当成签名发布。 */
public enum OtaUploadErrorCode implements ErrorCode {
    /** 隐藏错项目与不存在的差异。 */ NOT_FOUND(70005, "上传会话不存在或不可见", 404),
    /** 一次消费、修订和未决占用冲突。 */ CONFLICT(70006, "上传会话状态或修订冲突", 409),
    /** 缺配置和存储不可用必须失败关闭。 */ UNAVAILABLE(70007, "固件存储暂不可用", 503),
    /** 长度或正文摘要与承诺不同。 */ CONTENT_MISMATCH(70008, "固件内容与上传承诺不符", 422),
    /** 接收取消、断连或截止，客户端通过GET查询最终事实。 */ RECEIVE_FAILED(70009, "固件接收未完成，请查询上传会话", 400);
    /** 稳定业务码。 */ private final int code;
    /** 不包含供应商数据的消息。 */ private final String message;
    /** 对应HTTP状态。 */ private final int status;
    /** 构造已登记错误。 */
    OtaUploadErrorCode(int code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }
    /** 返回业务码。 */ @Override public int code() { return code; }
    /** 返回安全消息。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP状态。 */ @Override public int httpStatus() { return status; }
}
