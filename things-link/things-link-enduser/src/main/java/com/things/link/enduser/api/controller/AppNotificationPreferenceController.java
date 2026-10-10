package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.UpdateAppNotificationPreferenceRequest;
import com.things.link.enduser.api.dto.response.AppNotificationPreferenceResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.api.support.AppNotificationPreferenceRequestParser;
import com.things.link.enduser.application.AppNotificationPreferenceService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立App本人通知偏好，不提供Console账号或他人身份代选。 */
@RestController
@RequestMapping(value = "/api/v1/app/account/notification-preferences", produces = "application/json")
@Tag(name = "App 账户设置", description = "终端用户本人的账户公开资料与设置")
@SecurityRequirement(name = "appAccessBearer")
public class AppNotificationPreferenceController {
    private final AppNotificationPreferenceService service;
    private final AppNotificationPreferenceRequestParser parser;
    /**
     * @param service 本人偏好用例
     * @param parser 封闭请求解析器
     */
    public AppNotificationPreferenceController(AppNotificationPreferenceService service, AppNotificationPreferenceRequestParser parser) {
        this.service = service;
        this.parser = parser;
    }
    /**
     * 读取本人跨已授权项目共用的通知偏好，不表示系统权限已授予。
     * @param jwt 已确权App身份
     * @return 本人偏好及当前入口可编辑性，角色或身份失效统一401
     */
    @GetMapping
    @Operation(operationId = "getAppNotificationPreferences", summary = "读取本人通知偏好",
            description = "偏好覆盖同一终端账号全部已授权项目；缺行默认App告警开启，项目只读仍可查询。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "本人通知偏好"),
        @ApiResponse(responseCode = "401", description = "App身份或角色失效", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AppNotificationPreferenceResponse read(@AuthenticationPrincipal Jwt jwt) {
        return AppNotificationPreferenceResponse.from(service.read(AppJwtIdentity.tenantId(jwt),
                AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt)));
    }
    /**
     * 以读取版本更新本人偏好；不受当前页面设备范围限制，不写电话或系统权限。
     * @param jwt 已确权App身份
     * @param body 原始封闭偏好信封
     * @return 更新后权威状态；旧版本409，项目只读403，不自动覆盖
     */
    @PutMapping(consumes = "application/json")
    @Operation(operationId = "updateAppNotificationPreferences", summary = "更新本人通知偏好",
            description = "以expectedRevision进行账号级CAS，项目许可和用户锁内复验；关闭影响后续受众与发送前授权，不撤回已发送消息。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = UpdateAppNotificationPreferenceRequest.class)))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "偏好已更新或确认未变化"),
        @ApiResponse(responseCode = "400", description = "参数不合法", content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = "App身份或角色失效", content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "403", description = "项目只读", content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "409", description = "偏好版本已变化（60061）", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AppNotificationPreferenceResponse update(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] body) {
        var request = parser.parse(body);
        return AppNotificationPreferenceResponse.from(service.update(AppJwtIdentity.tenantId(jwt),
                AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), request.appPushEnabled(), Long.parseLong(request.expectedRevision())));
    }
}
