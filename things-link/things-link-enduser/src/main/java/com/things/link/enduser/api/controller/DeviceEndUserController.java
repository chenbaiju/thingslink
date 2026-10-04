package com.things.link.enduser.api.controller;

import com.things.link.enduser.application.DeviceEndUserService;
import com.things.link.enduser.api.dto.response.DeviceEndUserResponse;
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

/** 独立反查入口，保持用户维度查询与授权写入合同。 */
@RestController
@Tag(name = "终端用户")
@SecurityRequirement(name = "consoleAccessBearer")
public class DeviceEndUserController {
    private final DeviceEndUserService service;
    public DeviceEndUserController(DeviceEndUserService service) {
        this.service = service;
    }
    @GetMapping("/api/v1/projects/{projectId}/devices/{deviceId}/end-users")
    @Operation(operationId = "listConsoleDeviceEndUsers", summary = "分页读取设备当前终端用户",
            description = "项目成员可读；只返回仍有效的设备关系、用户账号与项目赋值。按关系建立时间及ID倒序，limit为1至50。")
    public ResponseEntity<CursorPage<DeviceEndUserResponse>> list(@PathVariable UUID projectId,
            @PathVariable UUID deviceId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        if (!Set.of("cursor", "limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(projectId, deviceId, cursor, limit).map(DeviceEndUserResponse::from));
    }
}
