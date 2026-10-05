package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.AppChangePasswordRequest;
import com.things.link.enduser.api.dto.request.AppLoginRequest;
import com.things.link.enduser.api.dto.request.AppRefreshRequest;
import com.things.link.enduser.api.dto.response.AppSessionResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppAuthenticationService;
import com.things.link.enduser.application.AppPasswordService;
import com.things.link.enduser.application.AppSessionService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 终端用户认证接口（S11-2a）。
 *
 * <p>与控制台 {@code AuthController} 平行但独立：主体是 {@code app_user_id}，路径前缀
 * {@code /api/v1/app/auth}，走 App 安全链（独立密钥 + issuer），刷新令牌走响应体而非
 * Cookie（移动端）。
 *
 * <p>公开路径由 {@code AppSecurityConfiguration} 逐条放行（login / refresh / logout），
 * 改密（{@code /password}）要求 App 访问令牌。
 */
@RestController
@RequestMapping("/api/v1/app/auth")
@Tag(name = "App 认证", description = "终端用户登录、刷新、注销与改密")
public class AppAuthController {

    private final AppAuthenticationService authenticationService;
    private final AppSessionService sessionService;
    private final AppPasswordService passwordService;

    public AppAuthController(AppAuthenticationService authenticationService,
                             AppSessionService sessionService,
                             AppPasswordService passwordService) {
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.passwordService = passwordService;
    }

    /**
     * 登录并获取令牌对。
     *
     * @param request     登录请求
     * @param httpRequest 用于取来源 IP 做限流
     * @return 令牌对（访问令牌与刷新令牌都在响应体）
     */
    @PostMapping("/login")
    @Operation(summary = "登录",
            description = "以 projectKey + 用户名 + 口令登录，签发 App 令牌对。"
                    + "访问令牌与刷新令牌都在响应体（移动端无 Cookie）。"
                    + "无论 projectKey 无效、用户名不存在还是口令错误都返回同一个错误码 60006，"
                    + "区分开会形成账号/项目枚举漏洞。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "401", description = "用户名或密码错误（60006）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "尝试过于频繁，触发限流（10029）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AppSessionResponse> login(@Valid @RequestBody AppLoginRequest request,
                                                    HttpServletRequest httpRequest) {
        return ResponseEntity.ok(AppSessionResponse.from(authenticationService.login(
                request.projectKey(), request.username(), request.password(),
                httpRequest.getRemoteAddr())));
    }

    /**
     * 用刷新令牌换一对新令牌。
     *
     * @param request 刷新请求
     * @return 新的令牌对
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌",
            description = "用请求体中的刷新令牌换取新的令牌对，并轮换刷新令牌。"
                    + "刷新时回库复验终端用户与项目角色状态——这是停用真正生效的地方。"
                    + "令牌无效、过期、已撤销或被复用都返回 60007。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "刷新成功"),
            @ApiResponse(responseCode = "401", description = "刷新令牌无效或已过期（60007）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AppSessionResponse> refresh(
            @RequestBody(required = false) AppRefreshRequest request) {
        // 缺失/空令牌统一交给 application 层按 60007 处理，这里不额外抛参数错误
        return ResponseEntity.ok(AppSessionResponse.from(sessionService.rotate(
                request == null ? null : request.refreshToken())));
    }

    /**
     * 退出登录。
     *
     * @param request 注销请求（刷新令牌可空）
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录",
            description = "作废整族刷新令牌。幂等：无论令牌是否有效都返回 204。"
                    + "已签发的访问令牌最长仍可用到其过期时刻（15 分钟）。")
    @ApiResponse(responseCode = "204", description = "已退出")
    public ResponseEntity<Void> logout(@RequestBody(required = false) AppRefreshRequest request) {
        sessionService.revoke(request == null ? null : request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * 修改自己的口令并撤销全部会话。
     *
     * @param jwt     已通过校验的 App 令牌，显式传递 appUserId、tenantId 与 projectId
     * @param request 原口令与新口令
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/password")
    @Operation(summary = "修改密码",
            description = "修改当前终端用户自己的口令，并撤销其全部会话（含调用者自己）。"
                    + "需提交原口令验证本人操作。成功后旧刷新令牌全部失效，需重新登录。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "改密成功"),
            @ApiResponse(responseCode = "400", description = "原密码不正确（60008）或新口令过短（10001）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或已过期（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody AppChangePasswordRequest request) {
        passwordService.changePassword(
                AppJwtIdentity.appUserId(jwt), AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                request.oldPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

}
