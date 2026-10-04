package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 管理分发只公开稳定失败分类，绝不把临时地址作为异常详情。 */
public enum OtaReleaseDownloadErrorCode implements ErrorCode {
    /** 精确项目内不存在可读取发布物。 */ NOT_FOUND(70021, "固件发布物不存在或不可见", 404),
    /** 当前资格或固定发布事实已变化。 */ INELIGIBLE(70022, "固件发布物当前不可分发", 409),
    /** 存储、地址策略或技术预算不满足。 */ UNAVAILABLE(70023, "固件下载暂不可用", 503),
    /** 管理下载只允许当前项目管理者。 */ FORBIDDEN(70024, "当前角色无权下载固件发布物", 403);
    /** 固定业务码。 */ private final int code;
    /** 安全消息。 */ private final String message;
    /** 对应HTTP状态。 */ private final int status;
    /** 绑定稳定分类。 */
    OtaReleaseDownloadErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回已登记业务码。 */ @Override public int code() { return code; }
    /** 返回安全消息。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP映射。 */ @Override public int httpStatus() { return status; }
}
