package com.things.link.enduser.api.controller;
import com.things.link.enduser.api.dto.response.AppNotificationChannelResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.EndUserNotificationContactService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本人当前项目通知接收号码只读入口。 */
@RestController
@RequestMapping(value="/api/v1/app/account/notification-channels",produces="application/json")
@Tag(name="App 账户设置",description="终端用户本人的账户公开资料与设置")
@SecurityRequirement(name="appAccessBearer")
public class AppNotificationChannelController {
    private final EndUserNotificationContactService service;
    /** @param service 本人联系配置读取用例 */
    public AppNotificationChannelController(EndUserNotificationContactService service){this.service=service;}
    /**
     * 读取Console配置的本人当前项目号码和电话短信渠道状态。
     * @param jwt 已确权的App身份
     * @return 当前项目号码；渠道固定未接入，无可用额度承诺，不允许客户端改号
     */
    @GetMapping
    @Operation(operationId="getAppNotificationChannels",summary="读取本人通知渠道",description="只读本人当前项目接收配置，电话短信尚未接入，当前均不可用；没有客户端编辑入口或虚构额度。")
    @ApiResponses({
        @ApiResponse(responseCode="200",description="本人通知接收配置与不可用渠道状态"),
        @ApiResponse(responseCode="401",description="App身份或角色失效",content=@Content(schema=@Schema(implementation=ApiError.class)))
    })
    public AppNotificationChannelResponse read(@AuthenticationPrincipal Jwt jwt){return AppNotificationChannelResponse.from(service.readSelf(AppJwtIdentity.tenantId(jwt),AppJwtIdentity.projectId(jwt),AppJwtIdentity.appUserId(jwt)));}
}
