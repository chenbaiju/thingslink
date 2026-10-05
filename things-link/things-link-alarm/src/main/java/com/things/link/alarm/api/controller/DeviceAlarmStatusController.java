package com.things.link.alarm.api.controller;

import com.things.link.alarm.api.dto.response.DeviceAlarmStatusResponse;
import com.things.link.alarm.api.support.AlarmApiAuthorization;
import com.things.link.alarm.application.ConsoleDeviceAlarmStatusService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 独立Console摘要读取，权限与设备校验不依赖前端菜单或元数据。 */
@RestController
@Tag(name = "告警实例")
public class DeviceAlarmStatusController {
    private final AlarmApiAuthorization authorization;
    private final ConsoleDeviceAlarmStatusService service;
    public DeviceAlarmStatusController(AlarmApiAuthorization authorization, ConsoleDeviceAlarmStatusService service) {
        this.authorization = authorization;
        this.service = service;
    }

    /**
     * 批量读取设备活动告警状态。
     * 最多20台不同设备，共享数据库快照；模型异常或设备失效整批拒绝，ACK不影响ACTIVE。
     *
     * @param projectId 接口指定的项目标识
     * @param ids 待查询的设备标识列表
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<DeviceAlarmStatusResponse>}
     */
    @GetMapping("/api/v1/projects/{projectId}/alarms/device-status")
    @Operation(operationId = "getConsoleDeviceAlarmStatus", summary = "批量读取设备活动告警状态",
            description = "最多20台不同设备，共享数据库快照；模型异常或设备失效整批拒绝，ACK不影响ACTIVE。")
    @SecurityRequirement(name = "consoleAccessBearer")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完整设备状态摘要",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = DeviceAlarmStatusResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求集合或模型不合法"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Console身份失效"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "项目或设备不可见"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "读取限流"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "内部事实读取失败")
    })
    public ResponseEntity<DeviceAlarmStatusResponse> read(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @Parameter(description = "1..20个不同的规范小写UUID，重复deviceId参数",
                    array = @io.swagger.v3.oas.annotations.media.ArraySchema(minItems = 1, maxItems = 20, uniqueItems = true,
                            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "uuid")))
            @RequestParam(name = "deviceId") List<String> ids, HttpServletRequest request) {
        authorization.requireRead(projectId);
        if (!request.getParameterMap().keySet().equals(Set.of("deviceId"))) throw invalid();
        // 直接使用原始参数值，拒绝Spring列表转换隐式接受逗号分隔或空白修剪。
        List<String> raw = List.of(request.getParameterValues("deviceId"));
        if (raw.isEmpty() || raw.size() > 20) throw invalid();
        List<UUID> parsed;
        try {
            parsed = raw.stream().map(value -> {
                UUID id = UUID.fromString(value);
                if (!id.toString().equals(value)) throw new IllegalArgumentException();
                return id;
            }).toList();
        } catch (IllegalArgumentException exception) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(DeviceAlarmStatusResponse.from(service.read(projectId, parsed)));
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
