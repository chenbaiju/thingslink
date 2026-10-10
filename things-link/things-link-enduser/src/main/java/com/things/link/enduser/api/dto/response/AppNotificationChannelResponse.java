package com.things.link.enduser.api.dto.response;
import com.things.link.enduser.domain.AppNotificationContact;
import io.swagger.v3.oas.annotations.media.Schema;

/** 本人当前项目接收配置与实际未接入状态，不虚构额度。 */
@Schema(description = "本人当前项目电话短信配置与渠道状态")
public record AppNotificationChannelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string","null"}, description = "当前项目电话接收号码，未配置为空") String voiceNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string","null"}, description = "当前项目短信接收号码，未配置为空") String smsNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "电话渠道已接入；当前固定false") boolean voiceAvailable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "短信渠道已接入；当前固定false") boolean smsAvailable) {
    /** @param contact 当前项目本人联系配置 @return 未接入的电话短信渠道，不将保存号码当作可发送 */
    public static AppNotificationChannelResponse from(AppNotificationContact contact) {
        return new AppNotificationChannelResponse(contact.voiceNumber(),contact.smsNumber(),false,false);
    }
}
