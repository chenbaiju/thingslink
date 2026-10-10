package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.AppNotificationPreferenceService.PreferenceView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 本人偏好和当前入口资格，不把开启等同于厂商送达或系统权限。
 * @param appPushEnabled 同一账号全部已授权项目的App告警偏好
 * @param revision 当前版本，字符串避免长整数精度丢失
 * @param editable 当前项目允许写入，服务端仍在提交时复验
 */
@Schema(description = "本人App告警偏好与当前可编辑性")
public record AppNotificationPreferenceResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "同账号全部已授权项目App告警偏好") boolean appPushEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "当前版本字符串", example = "0") String revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "当前入口是否允许编辑") boolean editable) {
    /** @param view 权威偏好和入口资格 @return 无账号标识及敏感材料的响应 */
    public static AppNotificationPreferenceResponse from(PreferenceView view) {
        return new AppNotificationPreferenceResponse(view.preference().appPushEnabled(),
                Long.toString(view.preference().revision()), view.editable());
    }
}
