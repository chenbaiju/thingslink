package com.things.link.telemetry.api.controller;

import com.things.link.telemetry.api.dto.request.SubmitDeviceCommandRequest;
import com.things.link.telemetry.api.dto.response.DeviceCommandResponse;
import com.things.link.telemetry.api.support.DeviceCommandApiAuthorization;
import com.things.link.telemetry.application.DeviceCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** 设备命令受理与结果查询 API。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/commands")
@Tag(name = "设备命令", description = "可靠受理、派发并查询设备命令终态")
public class DeviceCommandController {
    /** 命令应用服务。 */ private final DeviceCommandService service;
    /** HTTP 第一层授权。 */ private final DeviceCommandApiAuthorization authorization;
    /** JSON 响应映射器。 */ private final ObjectMapper objectMapper;
    /** 创建控制器。 */
    public DeviceCommandController(DeviceCommandService service,
                                   DeviceCommandApiAuthorization authorization,
                                   ObjectMapper objectMapper) {
        this.service = service; this.authorization = authorization; this.objectMapper = objectMapper;
    }

    /** 受理命令；202 表示已可靠写入事实与 Outbox，不代表设备已经执行。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "下发设备命令", description = "Idempotency-Key 必填；202 仅表示命令与 Outbox 已同事务受理")
    public DeviceCommandResponse submit(@PathVariable UUID projectId, @PathVariable UUID deviceId,
                                        @Parameter(required = true) @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody SubmitDeviceCommandRequest request) {
        authorization.requireControl(projectId);
        return DeviceCommandResponse.from(service.submit(
                projectId, deviceId, key, request.commandKey(), request.input()), objectMapper);
    }

    /** 查询命令事实与全部派发尝试。 */
    @GetMapping("/{commandId}")
    @Operation(summary = "查询设备命令结果")
    public DeviceCommandResponse get(@PathVariable UUID projectId, @PathVariable UUID deviceId,
                                     @PathVariable UUID commandId) {
        authorization.requireControl(projectId);
        return DeviceCommandResponse.from(service.get(projectId, deviceId, commandId), objectMapper);
    }
}
