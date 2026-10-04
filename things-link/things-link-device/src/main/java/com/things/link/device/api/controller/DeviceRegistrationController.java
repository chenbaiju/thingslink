package com.things.link.device.api.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.device.api.dto.request.DeviceRegistrationRequest;
import com.things.link.device.api.dto.response.DeviceRegistrationResponse;
import com.things.link.device.application.DeviceRegistrationService;
import com.things.link.device.application.DeviceRegistrationRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一型一密动态注册协议入口（ADR 0003）。
 *
 * <p>该公开端点只做有类型的协议绑定，确权、RLS 注入、事务与凭据签发全部由应用服务负责。
 * 控制器不得直接访问数据库，否则会绕过设备域的事务和错误契约。</p>
 */
@Tag(name = "设备动态注册", description = "一型一密动态注册端点，设备使用产品密钥换取自己的 deviceKey 和 Access Token")
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@RestController
public class DeviceRegistrationController {

    /** 动态注册应用服务。 */
    private final DeviceRegistrationService service;
    /** 公开入口来源限流器。 */
    private final DeviceRegistrationRateLimiter rateLimiter;

    /** @param service 动态注册应用服务 */
    public DeviceRegistrationController(DeviceRegistrationService service,
                                        DeviceRegistrationRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 用产品凭据换取设备一机一密凭据。
     *
     * @param request 注册请求
     * @return 新设备标识与一次可见 Access Token
     */
    @PostMapping("/api/v1/emqx/register")
    @Operation(summary = "一型一密动态注册", description = "设备使用产品密钥注册，验证通过后自动创建设备实例并返回一机一密凭据")
    public ResponseEntity<DeviceRegistrationResponse> register(
            @Valid @RequestBody DeviceRegistrationRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire(httpRequest.getRemoteAddr())) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DeviceRegistrationResponse.from(service.register(
                        request.projectKey(), request.productKey(), request.productSecret(), request.deviceKey())));
    }
}
