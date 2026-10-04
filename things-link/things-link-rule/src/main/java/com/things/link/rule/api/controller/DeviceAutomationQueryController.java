package com.things.link.rule.api.controller;

import com.things.link.rule.application.automation.DeviceAutomationQueryService;
import com.things.link.rule.api.dto.response.DeviceAutomationResponse;
import com.things.link.rule.api.dto.response.DeviceAutomationExecutionResponse;
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

/** 设备当前目标与执行快照使用不同端点，不从定义猜测历史参与。 */
@RestController
@Tag(name="自动化")
@SecurityRequirement(name="consoleAccessBearer")
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}")
public class DeviceAutomationQueryController {
    private final DeviceAutomationQueryService service;
    public DeviceAutomationQueryController(DeviceAutomationQueryService service) { this.service=service; }
    @GetMapping("/automations")
    @Operation(operationId="listConsoleDeviceAutomations",summary="分页读取当前发布版本关联设备的自动化",
            description="仅OWNER/ADMIN可读当前发布配置摘要；含暂停，区分上报源与定时设备上下文。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceAutomationResponse>> definitions(@PathVariable UUID projectId,@PathVariable UUID deviceId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.definitions(projectId,deviceId,cursor,limit).map(DeviceAutomationResponse::from));
    }
    @GetMapping("/automation-executions")
    @Operation(operationId="listConsoleDeviceAutomationExecutions",summary="分页读取设备自动化原执行历史",
            description="项目成员可读原执行版本和设备动作记录数；DISPATCHED不表示设备成功。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceAutomationExecutionResponse>> history(@PathVariable UUID projectId,@PathVariable UUID deviceId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(projectId,deviceId,cursor,limit).map(DeviceAutomationExecutionResponse::from));
    }
    private void validate(HttpServletRequest request) {
        if (!Set.of("cursor","limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v->v.length!=1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
