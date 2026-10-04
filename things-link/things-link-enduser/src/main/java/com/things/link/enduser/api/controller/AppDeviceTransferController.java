package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.ConsumeDeviceTransferRequest;
import com.things.link.enduser.api.dto.response.DeviceTransferResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppDeviceClaimRateLimiter;
import com.things.link.enduser.application.DeviceTransferService;
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
 * App 主控转移消费入口（G2-A1e）。
 *
 * <p>租户、项目与接收者只取已校验 App JWT；Controller 复用绑定能力的用户/IP 低基数
 * 限流，令牌锁、设备级 PRIMARY 行锁和关系事务统一落在 {@link DeviceTransferService}。
 */
@Validated
@RestController
@RequestMapping("/api/v1/app/device-transfers")
@Tag(name = "App 设备主控转移", description = "接收一次性 TRANSFER 令牌并原子取得设备主控")
public class AppDeviceTransferController {

    /** 主控转移状态机。 */
    private final DeviceTransferService transferService;

    /** 与 CLAIM 共用的用户/IP 绑定凭据消费限流器。 */
    private final AppDeviceClaimRateLimiter rateLimiter;

    /**
     * 创建转移消费控制器。
     *
     * @param transferService 转移状态机
     * @param rateLimiter 绑定凭据消费限流器
     */
    public AppDeviceTransferController(DeviceTransferService transferService,
                                       AppDeviceClaimRateLimiter rateLimiter) {
        this.transferService = transferService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 消费一次性 TRANSFER 令牌并取得 PRIMARY。
     *
     * @param jwt 已认证 App JWT
     * @param request 明文令牌
     * @param servletRequest Servlet 请求，用于来源 IP 限流
     * @return 新主控关系
     */
    @PostMapping
    @Operation(summary = "接收设备主控",
            description = "旧 PRIMARY 原位降为 MEMBER、接收者取得 PRIMARY、令牌消费和审计同事务。"
                    + "同一接收者重放可幂等返回；过期/跨项目统一 60016，主控已变化返回 60017。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "转移成功或同接收者幂等重放"),
            @ApiResponse(responseCode = "400", description = "转移令牌无效或已过期（60016）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "App 访问令牌或项目角色失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "主控状态已变化或接收者冲突（60017）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "转移请求过于频繁（60018）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DeviceTransferResponse> consume(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConsumeDeviceTransferRequest request,
            HttpServletRequest servletRequest) {
        if (!rateLimiter.tryAcquire(AppJwtIdentity.appUserId(jwt), servletRequest.getRemoteAddr())) {
            throw new BusinessException(EndUserErrorCode.DEVICE_TRANSFER_RATE_LIMITED);
        }
        return ResponseEntity.ok(DeviceTransferResponse.from(transferService.consume(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt), request.token())));
    }
}
