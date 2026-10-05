package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.DeviceEvidenceService;
import com.things.link.assistant.application.DeviceEvidenceSnapshot;
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

/** Console只读证据入口，不接受用户指定身份、外部地址或任意查询表达式。 */
@RestController
@Tag(name = "Agent 证据", description = "项目授权范围内的有界设备诊断快照")
public class DeviceEvidenceController {
    private final DeviceEvidenceService service;
    public DeviceEvidenceController(DeviceEvidenceService service) { this.service = service; }

    /**
     * 读取单设备受权证据快照。
     * 当前Console项目成员可读；1至10个不同属性键，来源异常整批拒绝。不调用外部模型。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param expectedModelVersionId 调用方期望的不可变物模型版本
     * @param propertyKey 要读取的设备属性键列表
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<DeviceEvidenceSnapshot>}
     */
    @GetMapping("/api/v1/projects/{projectId}/assistant/devices/{deviceId}/snapshot")
    @Operation(operationId = "getAssistantDeviceSnapshot", summary = "读取单设备受权证据快照",
            description = "当前Console项目成员可读；1至10个不同属性键，来源异常整批拒绝。不调用外部模型。")
    @SecurityRequirement(name = "consoleAccessBearer")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "受权证据",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = DeviceEvidenceSnapshot.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数或模型不合法"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Console身份失效"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "项目或设备不可见"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "设备模型已变化"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "读取限流"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "事实读取失败")
    })
    public ResponseEntity<DeviceEvidenceSnapshot> read(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的不可变物模型版本") @RequestParam UUID expectedModelVersionId,
            @Parameter(description = "重复propertyKey参数，不支持逗号展开或通配符",
                array = @io.swagger.v3.oas.annotations.media.ArraySchema(minItems = 1, maxItems = 10, uniqueItems = true,
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", pattern = "[A-Za-z0-9_-]{1,64}")))
            @RequestParam List<String> propertyKey, HttpServletRequest request) {
        if (!request.getParameterMap().keySet().equals(Set.of("expectedModelVersionId", "propertyKey"))
                || request.getParameterValues("expectedModelVersionId").length != 1)
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(projectId, deviceId,
                expectedModelVersionId, List.of(request.getParameterValues("propertyKey"))));
    }
}
