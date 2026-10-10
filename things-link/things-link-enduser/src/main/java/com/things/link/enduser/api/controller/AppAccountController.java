package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.AppAccountResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppAccountService;
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

/** App本人账户资料入口，不接受其他用户标识。 */
@RestController
@RequestMapping(value = "/api/v1/app/account", produces = "application/json")
@Tag(name = "App 账户设置", description = "终端用户本人的账户公开资料与设置")
@SecurityRequirement(name = "appAccessBearer")
public class AppAccountController {
    private final AppAccountService accounts;
    /** @param accounts 本人资料用例 */
    public AppAccountController(AppAccountService accounts) { this.accounts = accounts; }
    /**
     * 读取令牌本人的账户信息与当前项目改密资格。
     * @param jwt 已通过专用App签名与项目生命周期检查的身份
     * @return 公开资料；用户锁定或角色失效统一401，不返回口令哈希
     */
    @GetMapping
    @Operation(operationId = "getAppAccount", summary = "读取本人账户资料",
            description = "沿当前App身份复验用户与项目角色；项目归档可读但不可改密，不接受前端指定其他用户。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "本人账户资料"),
            @ApiResponse(responseCode = "401", description = "App身份或角色失效",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AppAccountResponse read(@AuthenticationPrincipal Jwt jwt) {
        return AppAccountResponse.from(accounts.read(AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt)));
    }
}
