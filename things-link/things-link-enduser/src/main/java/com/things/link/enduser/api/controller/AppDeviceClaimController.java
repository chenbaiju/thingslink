package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.ConsumeDeviceClaimRequest;
import com.things.link.enduser.api.dto.response.DeviceClaimResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppDeviceClaimRateLimiter;
import com.things.link.enduser.application.DeviceClaimService;
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
 * App 云端设备认领入口（G2-A1c）。
 *
 * <p>项目、租户与消费人全部从已校验 App JWT 获取，不接受请求体覆盖。Controller 只负责
 * 低基数限流和参数映射，令牌行锁、角色复验与原子关系事务在 {@link DeviceClaimService}。
 */
@Validated
@RestController
@RequestMapping("/api/v1/app/device-claims")
@Tag(name = "App 设备认领", description = "终端用户消费一次性 CLAIM 令牌并取得设备主控关系")
public class AppDeviceClaimController {

    /** CLAIM 状态机。 */
    private final DeviceClaimService claimService;

    /** 用户/IP 双维度限流。 */
    private final AppDeviceClaimRateLimiter rateLimiter;

    /**
     * 创建 App 认领控制器。
     *
     * @param claimService CLAIM 状态机
     * @param rateLimiter 认领限流器
     */
    public AppDeviceClaimController(DeviceClaimService claimService,
                                    AppDeviceClaimRateLimiter rateLimiter) {
        this.claimService = claimService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 消费一次性令牌并建立 PRIMARY 关系。
     *
     * @param jwt 已认证 App JWT
     * @param request 明文令牌
     * @param servletRequest Servlet 请求，用于来源 IP 限流
     * @return 新建或幂等复用的设备关系
     */
    @PostMapping
    @Operation(summary = "认领设备",
            description = "消费一次性 CLAIM 令牌；令牌消费与 PRIMARY 关系变更同事务。"
                    + "同一消费人重试可幂等返回，其他过期/重放/跨项目统一返回 60012。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "认领成功或同消费人幂等重放"),
            @ApiResponse(responseCode = "400", description = "令牌无效或已过期（60012）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "App 访问令牌或项目角色失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "设备已有主控（60013）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "认领请求过于频繁（60015）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DeviceClaimResponse> consume(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConsumeDeviceClaimRequest request,
            HttpServletRequest servletRequest) {
        if (!rateLimiter.tryAcquire(AppJwtIdentity.appUserId(jwt), servletRequest.getRemoteAddr())) {
            throw new BusinessException(EndUserErrorCode.DEVICE_CLAIM_RATE_LIMITED);
        }
        return ResponseEntity.ok(DeviceClaimResponse.from(claimService.consume(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt), request.token())));
    }
}
