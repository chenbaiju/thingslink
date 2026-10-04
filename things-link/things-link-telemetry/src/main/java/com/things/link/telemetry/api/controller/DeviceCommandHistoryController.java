package com.things.link.telemetry.api.controller;

import com.things.link.telemetry.api.support.DeviceCommandApiAuthorization;
import com.things.link.telemetry.application.DeviceCommandHistoryService;
import com.things.link.telemetry.api.dto.response.DeviceCommandHistoryResponse;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.util.UUID;

/** 独立历史入口，保持既有提交与单条结果合同。 */
@RestController
@Tag(name = "设备命令")
@SecurityRequirement(name = "consoleAccessBearer")
public class DeviceCommandHistoryController {
    private final DeviceCommandApiAuthorization authorization;
    private final DeviceCommandHistoryService service;
    public DeviceCommandHistoryController(DeviceCommandApiAuthorization authorization, DeviceCommandHistoryService service) {
        this.authorization = authorization; this.service = service;
    }
    @GetMapping("/api/v1/projects/{projectId}/devices/{deviceId}/commands")
    @Operation(operationId = "listConsoleDeviceCommandHistory", summary = "分页读取目标设备命令历史",
            description = "沿用控制权限，按受理时间及ID倒序；只返回摘要，包含属性设置，不将网关转发视为其自身命令。")
    public ResponseEntity<CursorPage<DeviceCommandHistoryResponse>> list(@PathVariable UUID projectId,
            @PathVariable UUID deviceId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        authorization.requireControl(projectId);
        if (!Set.of("cursor", "limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(projectId, deviceId, cursor, limit).map(DeviceCommandHistoryResponse::from));
    }
}
