package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 本人通知偏好封闭请求，严格类型在原始信封解析阶段检查。
 * @param appPushEnabled 目标App告警偏好
 * @param expectedRevision 十进制字符串版本，避免客户端整数精度丢失
 */
@Schema(description = "本人App告警偏好更新")
public record UpdateAppNotificationPreferenceRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "目标App告警偏好") boolean appPushEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "当前版本，非负十进制字符串", example = "0") String expectedRevision) { }
