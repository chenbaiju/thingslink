package com.things.link.rule.api.controller;

import com.things.link.rule.api.dto.response.RuleOptionResponse;
import com.things.link.rule.api.dto.response.RuleSceneExecutionDetailResponse;
import com.things.link.rule.api.dto.response.RuleSceneExecutionResponse;
import com.things.link.rule.api.support.RuleExecutionApiAuthorization;
import com.things.link.rule.application.RuleSceneExecutionQueryService;
import com.things.link.rule.domain.RuleSceneExecutionQuery;
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

/** 手动场景执行事实只读查询接口；执行事实列表 + 详情（含投递状态摘要）+ 筛选下拉框数据源。 */
@Tag(name = "场景执行", description = "手动场景执行事实的只读查询，执行列表与投递状态摘要详情")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/scene-executions")
public class RuleSceneExecutionController {
    /** 场景执行查询服务。 */ private final RuleSceneExecutionQueryService service;
    /** HTTP 第一层项目授权。 */ private final RuleExecutionApiAuthorization authorization;
    /** @param service 查询用例 @param authorization HTTP 授权守卫 */
    public RuleSceneExecutionController(RuleSceneExecutionQueryService service, RuleExecutionApiAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }

    /** 按场景、状态与落库时间查询执行事实，游标分页。 */
    @GetMapping
    @Operation(summary = "查询场景执行记录", description = "手动场景执行事实列表，支持按场景、状态与时间筛选")
    public ResponseEntity<CursorPage<RuleSceneExecutionResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID sceneId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireProjectRead(projectId);
        RuleSceneExecutionQuery query = new RuleSceneExecutionQuery(projectId, sceneId, status, from, to, cursor, limit);
        return ResponseEntity.ok(service.list(query).map(RuleSceneExecutionResponse::from));
    }

    /** 查询一次场景执行的详情：执行事实本体 + 通知与设备动作投递状态摘要。 */
    @GetMapping("/{executionId}")
    @Operation(summary = "查询场景执行详情", description = "执行事实 + 通知与设备动作投递状态摘要")
    public RuleSceneExecutionDetailResponse detail(@PathVariable UUID projectId, @PathVariable UUID executionId) {
        authorization.requireProjectRead(projectId);
        return RuleSceneExecutionDetailResponse.from(service.detail(projectId, executionId));
    }

    /** 查询已产生过执行事实的场景选项，作为筛选下拉框数据源。 */
    @GetMapping("/scene-options")
    @Operation(summary = "查询场景执行记录筛选选项", description = "已产生过执行事实的场景 id 与当前名称，只读")
    public List<RuleOptionResponse> sceneOptions(@PathVariable UUID projectId) {
        authorization.requireProjectRead(projectId);
        return service.sceneOptions(projectId).stream().map(RuleOptionResponse::from).toList();
    }
}
