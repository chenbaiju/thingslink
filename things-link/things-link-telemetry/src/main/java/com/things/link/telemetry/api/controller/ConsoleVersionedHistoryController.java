package com.things.link.telemetry.api.controller;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.telemetry.api.dto.response.PropertyHistoryResponse;
import com.things.link.telemetry.application.ConsoleVersionedHistoryService;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 独立Console严格历史入口，保留旧历史接口的既有响应与失败语义。 */
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/telemetry/property/history/versioned")
public class ConsoleVersionedHistoryController {
    /** 每个完整UTF-8响应最多4MiB，不能裁掉历史点后伪称完整。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** 封闭查询参数；重复参数不能形成不同层的解释差异。 */
    private static final Set<String> PARAMETERS = Set.of("propertyKey", "expectedModelVersionId", "from", "to", "granularity", "aggregation");
    /** HTTP首层成员授权。 */
    private final ProjectService projects;
    /** 同事务Console授权及严格历史读取。 */
    private final ConsoleVersionedHistoryService service;
    /** 实际响应编码及字节计数。 */
    private final ObjectMapper mapper;

    /** @param projects 项目授权 @param service 严格历史服务 @param mapper 响应编码器 */
    public ConsoleVersionedHistoryController(ProjectService projects, ConsoleVersionedHistoryService service, ObjectMapper mapper) {
        this.projects = projects;
        this.service = service;
        this.mapper = mapper;
    }

    /** @param projectId 项目 @param deviceId 设备 @param propertyKey 顶层属性 @param expectedModelVersionId 精确模型
     * @param from 包含起点 @param to 不含终点 @param granularity 请求粒度 @param aggregation 聚合方式
     * @param request 原始参数集合 @return 不缓存的完整有界版本化响应
     */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "套餐历史窗口不可用（50048）", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.things.link.shared.error.ApiError.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getConsoleVersionedPropertyHistory", summary = "读取Console严格版本化属性历史", description = "先与所属租户套餐历史窗口求交；聚合仅返回完整区间桶，缺投影503/50048")
    @SecurityRequirement(name = "consoleAccessBearer")
    @ApiResponse(responseCode = "200", description = "完整历史，最多2000点和4MiB；最终粒度仍超限则拒绝",
            content = @Content(schema = @Schema(implementation = PropertyHistoryResponse.class)))
    public ResponseEntity<byte[]> query(@PathVariable UUID projectId, @PathVariable UUID deviceId,
            @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String propertyKey,
            @RequestParam UUID expectedModelVersionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "RAW") HistoryGranularity granularity,
            @RequestParam(defaultValue = "AVG") HistoryAggregation aggregation, HttpServletRequest request) {
        projects.requireRoleInProject(projectId);
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !PARAMETERS.contains(entry.getKey())
                || entry.getValue().length != 1 || entry.getValue()[0].isBlank())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        byte[] bytes = mapper.writeValueAsBytes(PropertyHistoryResponse.from(service.query(projectId, deviceId,
                propertyKey, expectedModelVersionId, from, to, granularity, aggregation)));
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("版本化历史响应超过4MiB预算");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON).body(bytes);
    }
}
