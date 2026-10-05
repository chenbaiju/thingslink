package com.things.link.alarm.api.controller;

import com.things.link.alarm.api.dto.request.AlarmDeviceQueryRequest;
import com.things.link.alarm.api.dto.response.AlarmDeviceQueryResponse;
import com.things.link.alarm.api.support.AlarmApiAuthorization;
import com.things.link.alarm.api.support.AlarmDeviceQueryRequestParser;
import com.things.link.alarm.application.AlarmDeviceExpectation;
import com.things.link.alarm.application.ConsoleAlarmDeviceQueryService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 数据运行合同§3.6的精确Console只读POST；不扩大ACK/CLEAR等写入口权限。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/alarms/query")
@Tag(name = "告警实例")
public class AlarmDeviceQueryController {
    /** 数据运行合同§4限制每个最终UTF-8响应4MiB，不能按记录数猜字节或截断。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** HTTP第一层真实项目成员授权。 */
    private final AlarmApiAuthorization authorization;
    /** 绑定DTO前执行封闭JSON解析。 */
    private final AlarmDeviceQueryRequestParser parser;
    /** 独立二次授权与同事务设备过滤编排。 */
    private final ConsoleAlarmDeviceQueryService service;
    /** 最终HTTP编码器，计数与实际发送同一字节数组。 */
    private final ObjectMapper mapper;

    /** 创建Console告警按设备读取控制器。 */
    public AlarmDeviceQueryController(AlarmApiAuthorization authorization, AlarmDeviceQueryRequestParser parser,
            ConsoleAlarmDeviceQueryService service, ObjectMapper mapper) {
        this.authorization = authorization;
        this.parser = parser;
        this.service = service;
        this.mapper = mapper;
    }

    /**
     * 按设备和状态读取告警实例。
     *
     * @param projectId Console路径项目
     * @param body 原始UTF-8 JSON信封
     * @param request 禁止额外query成为未登记过滤条件
     * @return 最终编码的过滤后告警页
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryConsoleAlarmsByDevices", summary = "按设备和状态读取告警实例",
            description = "在真实项目成员与设备可见性校验后按设备和三组状态过滤分页；最终UTF-8 JSON不得超过4MiB。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = AlarmDeviceQueryRequest.class))))
    @SecurityRequirement(name = "consoleAccessBearer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "指定设备过滤后的只读告警页，完整响应不超过4MiB",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AlarmDeviceQueryResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求结构、设备模型、过滤条件或签名游标非法（10001）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Console身份缺失或失效",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目或设备不可见（50001/30020）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "命中公共读取限流",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "数据库、内部数据或完整响应预算异常（90000）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<byte[]> query(@io.swagger.v3.oas.annotations.Parameter(description = "Console路径项目") @PathVariable UUID projectId, @RequestBody byte[] body, HttpServletRequest request) {
        authorization.requireRead(projectId);
        if (request.getQueryString() != null) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        AlarmDeviceQueryRequest parsed = parser.parse(body);
        AlarmDeviceQueryResponse response = AlarmDeviceQueryResponse.from(service.query(projectId,
                parsed.devices().stream().map(device -> new AlarmDeviceExpectation(device.deviceId(), device.expectedModelVersionId())).toList(),
                parsed.conditionStates(), parsed.ackStates(), parsed.severities(), parsed.cursor(), parsed.limit()));
        byte[] bytes = mapper.writeValueAsBytes(response);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("告警查询响应超过4MiB预算");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(bytes);
    }
}
