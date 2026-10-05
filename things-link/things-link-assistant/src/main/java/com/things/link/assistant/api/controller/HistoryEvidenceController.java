package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.HistoryEvidenceService;
import com.things.link.telemetry.application.ConsoleHistoryEvidence;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 当前Console身份的历史证据入口；封闭参数，不接收身份、查询表达式或外部地址。 */
@RestController
@Tag(name = "Agent 历史证据", description = "有界历史读取及返回前当前权限复核")
public class HistoryEvidenceController {
    private final HistoryEvidenceService service;
    public HistoryEvidenceController(HistoryEvidenceService service) { this.service = service; }

    /**
     * 读取单设备、单数值属性的历史证据；四种当前项目角色可读，不调用模型。
     * @param projectId 当前登录身份所选项目标识
     * @param deviceId 项目内设备标识
     * @param expectedModelVersionId 期望的精确不可变模型版本，变化时拒绝返回
     * @param propertyKey 单个顶层数值属性键，不展开逗号、通配符或路径
     * @param from 包含起点，采用带时区的时间文本
     * @param to 排他终点，不得晚于有效窗口且总窗不超过24小时
     * @param request 原始请求，用于拒绝未知及重复参数
     * @return 禁止缓存的历史证据，显式区分缺数据与保留窗口外
     */
    @GetMapping("/api/v1/projects/{projectId}/assistant/devices/{deviceId}/history")
    @Operation(operationId = "getAssistantDeviceHistory", summary = "读取受权单属性历史证据",
            description = "单设备、单数值属性、最多24小时；保留裁剪、实际粒度和来源版本明确返回，空点不代表正常。")
    @SecurityRequirement(name = "consoleAccessBearer")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "历史证据",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ConsoleHistoryEvidence.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数或时间窗口无效"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "当前Console身份无效"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "项目或设备不可见"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "当前模型已变化"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "读取限流"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "事实读取失败")
    })
    public ResponseEntity<ConsoleHistoryEvidence> read(
            @Parameter(description = "当前项目标识") @PathVariable UUID projectId,
            @Parameter(description = "项目内设备标识") @PathVariable UUID deviceId,
            @Parameter(description = "精确当前模型版本标识") @RequestParam UUID expectedModelVersionId,
            @Parameter(description = "单个顶层数值属性键", schema = @io.swagger.v3.oas.annotations.media.Schema(pattern = "[A-Za-z0-9_-]{1,64}"))
            @RequestParam String propertyKey,
            @Parameter(description = "包含起点，带时区时间") @RequestParam Instant from,
            @Parameter(description = "排他终点，最多24小时") @RequestParam Instant to,
            HttpServletRequest request) {
        var parameters = request.getParameterMap();
        if (!parameters.keySet().equals(Set.of("expectedModelVersionId", "propertyKey", "from", "to"))
                || parameters.values().stream().anyMatch(values -> values.length != 1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.read(projectId, deviceId, expectedModelVersionId, propertyKey, from, to));
    }
}
