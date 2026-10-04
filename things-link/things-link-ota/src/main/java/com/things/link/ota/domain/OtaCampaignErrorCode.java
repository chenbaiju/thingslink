package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** ADR0125管理活动控制面的稳定失败分类。 */
public enum OtaCampaignErrorCode implements ErrorCode {
    /** 不暴露其他项目的活动。 */ NOT_FOUND(70034, "OTA活动不存在", 404),
    /** 仅当前OWNER/ADMIN可操作。 */ FORBIDDEN(70035, "当前角色无权管理OTA活动", 403),
    /** CAS、状态或冻结计划冲突。 */ STATE_CONFLICT(70036, "OTA活动状态冲突", 409),
    /** 全部显式目标必须同类型且仍存在。 */ TARGET_INELIGIBLE(70037, "OTA活动目标不符合要求", 409),
    /** PENDING起即占设备互斥位。 */ TARGET_OCCUPIED(70038, "OTA活动设备已有未完成作业", 409);
    /** 稳定业务码。 */ private final int code;
    /** 安全公开文案。 */ private final String message;
    /** HTTP分类。 */ private final int status;
    /** 冻结公开错误映射。 */
    OtaCampaignErrorCode(int code, String message, int status) {
        this.code = code; this.message = message; this.status = status;
    }
    /** 返回业务码。 */ @Override public int code() { return code; }
    /** 返回安全文案。 */ @Override public String defaultMessage() { return message; }
    /** 返回HTTP分类。 */ @Override public int httpStatus() { return status; }
}
