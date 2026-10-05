package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.AlarmEvidence;
import com.things.link.assistant.application.AlarmEvidenceService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.util.UUID;

/** 当前Console身份的单设备告警事实页；禁止任意过滤、时间窗和身份参数。 */
@RestController
@Tag(name = "Agent 告警证据", description = "有界事故明细及返回前当前权限复核")
public class AlarmEvidenceController {
    private final AlarmEvidenceService service;

    /** @param service 受权读取和允许字段投影服务 */
    public AlarmEvidenceController(AlarmEvidenceService service) { this.service = service; }

    /**
     * 四种当前项目角色读取事故事实页；不调用模型，空页不代表正常。
     * @param projectId 当前登录身份所选项目
     * @param deviceId 项目内单个设备
     * @param expectedModelVersionId 当前模型精确预期，变化时拒绝返回
     * @param cursor 上页游标，仅允许原账号、设备、模型及页大小使用
     * @param limit 页大小，默认二十且最多五十
     * @param request 原始查询参数，用于拒绝未知、重复及空参数
     * @return 禁止缓存的事实页，显式提示历史事故来源模型未提供
     */
    @GetMapping("/api/v1/projects/{projectId}/assistant/devices/{deviceId}/alarms")
    @Operation(operationId = "getAssistantDeviceAlarms", summary = "读取受权单设备告警事实页",
            description = "按告警更新时间降序，最多50条；无时间窗，不含自由文本类型，当前模型不证明历史事故来源，空页不代表正常。")
    @SecurityRequirement(name = "consoleAccessBearer")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "受权事故事实页",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                schema = @Schema(implementation = AlarmEvidence.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数或游标不合法"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "当前Console身份无效"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "项目或设备不可见"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "当前模型已变化"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "读取限流"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "证据源读取失败")
    })
    public ResponseEntity<AlarmEvidence> read(
            @Parameter(description = "当前受权项目") @PathVariable UUID projectId,
            @Parameter(description = "单个项目内设备") @PathVariable UUID deviceId,
            @Parameter(description = "精确当前模型版本") @RequestParam UUID expectedModelVersionId,
            @Parameter(description = "账号和查询绑定的下一页游标", schema = @Schema(minLength = 1, maxLength = 4096))
            @RequestParam(required = false) String cursor,
            @Parameter(description = "默认20，允许1至50", schema = @Schema(minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        var parameters = request.getParameterMap();
        if (!parameters.containsKey("expectedModelVersionId")
                || !Set.of("expectedModelVersionId", "cursor", "limit").containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(values -> values.length != 1 || values[0].isBlank()))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.read(projectId, deviceId, expectedModelVersionId, cursor, limit));
    }
}
