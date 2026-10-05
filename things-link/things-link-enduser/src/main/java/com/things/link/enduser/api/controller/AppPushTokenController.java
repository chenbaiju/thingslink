package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.RegisterAppPushTokenRequest;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppPushTokenService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** App 安装实例 PUSH token 管理接口（G2-A2a）。 */
@RestController
@RequestMapping("/api/v1/app/push-tokens")
@Tag(name = "App 推送令牌", description = "注册、轮换或吊销当前终端用户的安装实例 PUSH token")
public class AppPushTokenController {

    /** 安装实例应用服务。 */
    private final AppPushTokenService service;

    /** @param service 安装实例应用服务 */
    public AppPushTokenController(AppPushTokenService service) {
        this.service = service;
    }

    /**
     * 注册或轮换安装实例；响应不回显明文、密文或内部 token ID。
     *
     * @param jwt 已认证 App JWT，显式提供租户、当前项目与用户身份
     * @param request 安装实例与厂商 token
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PutMapping
    @Operation(summary = "注册或轮换 PUSH 安装实例",
            description = "同一 installationId 原位轮换厂商 token；token 受 AES-256-GCM 保护且响应不回显。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注册或轮换完成"),
            @ApiResponse(responseCode = "400", description = "安装实例、厂商或 token 不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "App 访问令牌无效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> register(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody RegisterAppPushTokenRequest request) {
        service.register(AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt),
                request.installationId(), request.provider(), request.token());
        return ResponseEntity.noContent().build();
    }

    /**
     * 幂等吊销当前用户的一个安装实例。
     *
     * @param jwt 已认证 App JWT，显式提供租户、当前项目与用户身份
     * @param installationId 安装实例 UUID
     * @return 204；不存在或已吊销同样成功
     */
    @DeleteMapping("/{installationId}")
    @Operation(summary = "吊销 PUSH 安装实例",
            description = "吊销后保留历史事实；重复吊销或当前用户不存在该 installationId 仍返回 204。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "吊销完成或无需变更"),
            @ApiResponse(responseCode = "401", description = "App 访问令牌无效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,
                                       @Parameter(description = "安装实例 UUID")
                                       @PathVariable UUID installationId) {
        service.revoke(AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), installationId);
        return ResponseEntity.noContent().build();
    }
}
