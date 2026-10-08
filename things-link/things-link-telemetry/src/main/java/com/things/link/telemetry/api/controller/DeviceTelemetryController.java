package com.things.link.telemetry.api.controller;

import com.things.link.shared.page.CursorPage;
import com.things.link.telemetry.api.dto.response.PropertyPointResponse;
import com.things.link.telemetry.api.dto.response.PropertyHistoryResponse;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.telemetry.application.PropertyHistoryService;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * 控制台设备遥测查询接口。
 *
 * <p>设备上报不在这里暴露 HTTP 写入口。上行数据必须经过设备认证、Topic ACL 和
 * ingestion 标准化后调用应用端口，避免控制台账号伪造设备事实。</p>
 */
@Tag(name = "设备遥测", description = "查询设备属性历史数据点，使用键集游标分页")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/telemetry")
public class DeviceTelemetryController {
    /** 属性摄入与查询服务。 */
    private final PropertyIngestionService ingestion;
    /** S4 历史聚合查询服务。 */
    private final PropertyHistoryService historyService;
    /** 将 JSONB 历史值恢复为原生对象或数组，禁止 API 二次字符串化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建控制器。
     *
     * @param ingestion 属性摄入与查询服务
     */
    public DeviceTelemetryController(PropertyIngestionService ingestion, PropertyHistoryService historyService,
                                     ObjectMapper objectMapper) {
        this.ingestion = ingestion;
        this.historyService = historyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用键集游标查询设备属性历史。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 可选属性标识符
     * @param from 可选开始时刻，包含
     * @param to 可选结束时刻，不包含
     * @param cursor 可选下一页游标
     * @param limit 每页数量
     * @return 历史点分页
     */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "套餐历史窗口不可用（50048）", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.things.link.shared.error.ApiError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "原始属性历史游标页，值保留写入类型，查询范围与套餐历史窗口求交", useReturnTypeSchema = true)
    @GetMapping("/property")
    @Operation(summary = "属性历史查询", description = "按设备和时间窗口查询属性时序数据点，支持游标分页；查询先与所属租户套餐历史窗口求交，缺投影503/50048")
    public ResponseEntity<CursorPage<PropertyPointResponse>> listProperties(
            @io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选属性标识符") @RequestParam(required = false)
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "属性标识符格式不合法") String propertyKey,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选开始时刻，包含") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选结束时刻，不包含") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选下一页游标") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "每页数量") @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return ResponseEntity.ok(ingestion.listProperties(
                projectId, deviceId, propertyKey, from, to, cursor, limit)
                .map(point -> PropertyPointResponse.from(point, objectMapper)));
    }

    /**
     * 查询单个数值属性的历史曲线，超出 2000 点时自动升粒度。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 属性标识符
     * @param from 开始时刻，包含
     * @param to 结束时刻，不包含
     * @param granularity 请求粒度
     * @param aggregation 聚合函数
     * @return 含实际粒度的历史曲线
     */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "套餐历史窗口不可用（50048）", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.things.link.shared.error.ApiError.class)))
    @GetMapping("/property/history")
    @Operation(summary = "属性聚合历史查询",
            description = "查询单个数值属性，最多返回 2000 点；超限按 RAW、ONE_MINUTE、ONE_HOUR、ONE_DAY 自动升粒度；先裁剪至套餐窗口，仅返回完整区间聚合桶，缺投影503/50048")
    public ResponseEntity<PropertyHistoryResponse> history(
            @io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "属性标识符") @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]+", message = "属性标识符格式不合法") String propertyKey,
            @io.swagger.v3.oas.annotations.Parameter(description = "开始时刻，包含") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @io.swagger.v3.oas.annotations.Parameter(description = "结束时刻，不包含") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @io.swagger.v3.oas.annotations.Parameter(description = "请求粒度") @RequestParam(defaultValue = "RAW") HistoryGranularity granularity,
            @io.swagger.v3.oas.annotations.Parameter(description = "聚合函数") @RequestParam(defaultValue = "AVG") HistoryAggregation aggregation) {
        return ResponseEntity.ok(PropertyHistoryResponse.from(historyService.query(
                projectId, deviceId, propertyKey, from, to, granularity, aggregation)));
    }
}
