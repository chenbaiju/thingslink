package com.things.link.enduser.api.dto.response;
import com.things.link.enduser.domain.AppNotificationContact;
import io.swagger.v3.oas.annotations.media.Schema;

/** 当前项目配置，号码可空，不公开共享身份的其他项目配置。 */
@Schema(description = "项目终端用户接收号码与版本")
public record EndUserNotificationContactResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string","null"}, description = "电话接收号码，未配置为空") String voiceNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string","null"}, description = "短信接收号码，未配置为空") String smsNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "当前版本字符串", example = "0") String revision) {
    /** @param contact 当前项目联系配置 @return 公开管理响应 */
    public static EndUserNotificationContactResponse from(AppNotificationContact contact) {
        return new EndUserNotificationContactResponse(contact.voiceNumber(),contact.smsNumber(),Long.toString(contact.revision()));
    }
}
