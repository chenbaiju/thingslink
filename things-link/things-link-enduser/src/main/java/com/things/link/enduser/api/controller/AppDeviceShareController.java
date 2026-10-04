package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.ConsumeDeviceShareRequest;
import com.things.link.enduser.api.dto.response.DeviceShareResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppDeviceClaimRateLimiter;
import com.things.link.enduser.application.DeviceShareService;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 设备共享消费入口（G2-A1f）。
 *
 * <p>租户、项目与接收者只取已校验 App JWT；Controller 复用绑定能力的用户/IP 低基数
 * 限流，令牌、当前 PRIMARY 与关系仲裁统一落在 {@link DeviceShareService}。
 */
@Validated
@RestController
@RequestMapping("/api/v1/app/device-shares")
@Tag(name = "App 设备共享", description = "接收一次性 SHARE 令牌并建立 MEMBER/READ_ONLY 关系")
public class AppDeviceShareController {

    /** 设备共享状态机。 */
    private final DeviceShareService shareService;

    /** CLAIM/TRANSFER/SHARE 共用的用户/IP 凭据消费限流器。 */
    private final AppDeviceClaimRateLimiter rateLimiter;

    /**
     * 创建设备共享控制器。
     *
     * @param shareService 共享状态机
     * @param rateLimiter 绑定凭据消费限流器
     */
    public AppDeviceShareController(DeviceShareService shareService,
                                    AppDeviceClaimRateLimiter rateLimiter) {
        this.shareService = shareService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 消费一次性 SHARE 令牌并建立共享关系。
     *
     * @param jwt 已认证 App JWT
     * @param request 明文令牌
     * @param servletRequest Servlet 请求，用于来源 IP 限流
     * @return MEMBER/READ_ONLY 关系
     */
    @PostMapping
    @Operation(summary = "接收设备共享",
            description = "签发者仍为当前 PRIMARY 时建立 MEMBER/READ_ONLY；既有关系不改写，同一接收者重放可幂等返回。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "共享成功或同接收者幂等重放"),
            @ApiResponse(responseCode = "400", description = "共享令牌无效或已过期（60019）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "App 访问令牌或项目角色失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "主控已变化、自共享或接收者已有关系（60020）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "共享请求过于频繁（60021）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DeviceShareResponse> consume(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConsumeDeviceShareRequest request,
            HttpServletRequest servletRequest) {
        if (!rateLimiter.tryAcquire(AppJwtIdentity.appUserId(jwt), servletRequest.getRemoteAddr())) {
            throw new BusinessException(EndUserErrorCode.DEVICE_SHARE_RATE_LIMITED);
        }
        return ResponseEntity.ok(DeviceShareResponse.from(shareService.consume(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt), request.token())));
    }
}
