package com.things.link.rule.api.controller;

import com.things.link.rule.api.dto.response.RuleExecutionAttemptResponse;
import com.things.link.rule.api.dto.response.RuleExecutionSummaryResponse;
import com.things.link.rule.api.dto.response.RuleOptionResponse;
import com.things.link.rule.api.support.RuleExecutionApiAuthorization;
import com.things.link.rule.application.RuleExecutionLogQueryService;
import com.things.link.rule.domain.RuleExecutionLogQuery;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 上行规则执行日志只读查询接口；执行级聚合列表 + attempt 时间线详情 + 筛选下拉框数据源。 */
@Tag(name = "上行规则执行", description = "消息规则执行日志的只读查询，执行级列表与 attempt 时间线详情")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/rule-executions")
public class RuleExecutionLogController {
    /** 执行日志查询服务。 */ private final RuleExecutionLogQueryService service;
    /** HTTP 第一层项目授权。 */ private final RuleExecutionApiAuthorization authorization;
    /** @param service 查询用例 @param authorization HTTP 授权守卫 */
    public RuleExecutionLogController(RuleExecutionLogQueryService service, RuleExecutionApiAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }

    /**
     * 按规则、状态与最后尝试完成时间查询执行级聚合摘要，游标分页。
     *
     * @param projectId 接口指定的项目标识
     * @param ruleId 规则标识
     * @param status 状态筛选条件
     * @param from 查询时间区间起点
     * @param to 查询时间区间终点
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping
    @Operation(summary = "查询上行规则执行记录", description = "执行级聚合列表，支持按规则、状态与时间筛选")
    public ResponseEntity<CursorPage<RuleExecutionSummaryResponse>> list(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "规则标识") @RequestParam(required = false) UUID ruleId,
            @io.swagger.v3.oas.annotations.Parameter(description = "状态筛选条件") @RequestParam(required = false) String status,
            @io.swagger.v3.oas.annotations.Parameter(description = "查询时间区间起点") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @io.swagger.v3.oas.annotations.Parameter(description = "查询时间区间终点") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireProjectRead(projectId);
        RuleExecutionLogQuery query = new RuleExecutionLogQuery(projectId, ruleId, status, from, to, cursor, limit);
        return ResponseEntity.ok(service.list(query).map(RuleExecutionSummaryResponse::from));
    }

    /**
     * 查询一次逻辑执行的尝试时间线（升序）。
     *
     * @param projectId 接口指定的项目标识
     * @param messageId 消息标识
     * @param ruleId 规则标识
     * @param ruleVersionId 不可变规则版本标识
     * @return 符合当前查询条件的结果列表
     */
    @GetMapping("/attempts")
    @Operation(summary = "查询上行规则执行 attempt 时间线", description = "指定消息、规则与版本的一次逻辑执行的全部尝试，升序")
    public List<RuleExecutionAttemptResponse> attempts(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "消息标识") @RequestParam UUID messageId,
            @io.swagger.v3.oas.annotations.Parameter(description = "规则标识") @RequestParam UUID ruleId,
            @io.swagger.v3.oas.annotations.Parameter(description = "不可变规则版本标识") @RequestParam UUID ruleVersionId) {
        authorization.requireProjectRead(projectId);
        return service.attempts(projectId, messageId, ruleId, ruleVersionId).stream()
                .map(RuleExecutionAttemptResponse::from).toList();
    }

    /**
     * 查询已产生过执行事实的规则选项，作为筛选下拉框数据源。
     *
     * @param projectId 接口指定的项目标识
     * @return 符合当前查询条件的结果列表
     */
    @GetMapping("/rule-options")
    @Operation(summary = "查询执行记录规则筛选选项", description = "已产生过执行事实的规则 id 与当前名称，只读")
    public List<RuleOptionResponse> ruleOptions(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId) {
        authorization.requireProjectRead(projectId);
        return service.ruleOptions(projectId).stream().map(RuleOptionResponse::from).toList();
    }
}
