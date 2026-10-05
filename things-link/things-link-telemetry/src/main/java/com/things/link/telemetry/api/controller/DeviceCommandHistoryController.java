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
    /**
     * 分页读取目标设备命令历史。
     * 沿用控制权限，按受理时间及ID倒序；只返回摘要，包含属性设置，不将网关转发视为其自身命令。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/api/v1/projects/{projectId}/devices/{deviceId}/commands")
    @Operation(operationId = "listConsoleDeviceCommandHistory", summary = "分页读取目标设备命令历史",
            description = "沿用控制权限，按受理时间及ID倒序；只返回摘要，包含属性设置，不将网关转发视为其自身命令。")
    public ResponseEntity<CursorPage<DeviceCommandHistoryResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId, @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        authorization.requireControl(projectId);
        if (!Set.of("cursor", "limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(projectId, deviceId, cursor, limit).map(DeviceCommandHistoryResponse::from));
    }
}
