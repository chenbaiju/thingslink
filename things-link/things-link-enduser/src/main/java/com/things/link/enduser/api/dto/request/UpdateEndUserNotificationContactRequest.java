package com.things.link.enduser.api.dto.request;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前项目接收配置的封闭请求。
 * @param voiceNumber 电话号码，空表示清除
 * @param smsNumber 短信号码，空表示清除
 * @param expectedRevision 已读取的版本字符串
 */
@Schema(description = "项目终端用户接收号码更新")
public record UpdateEndUserNotificationContactRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string","null"}, description = "电话接收号码，+开头及7至15位数字；null清除") String voiceNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string","null"}, description = "短信接收号码，+开头及7至15位数字；null清除") String smsNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "当前版本字符串", example = "0") String expectedRevision) { }
