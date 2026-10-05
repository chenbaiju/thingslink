package com.things.link.telemetry.api.controller;

import com.things.link.telemetry.api.dto.response.OverviewResponse;
import com.things.link.telemetry.api.support.OverviewApiAuthorization;
import com.things.link.telemetry.application.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 项目概要查询入口。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/overview")
@Tag(name = "项目概要", description = "当前项目设备、告警及用量概要")
public class OverviewController {
    /** 概要应用服务。 */
    private final OverviewService service;
    /** HTTP 第一层项目授权。 */
    private final OverviewApiAuthorization authorization;

    /** @param service 概要服务 @param authorization HTTP 授权守卫 */
    public OverviewController(OverviewService service, OverviewApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /**
     * 查询项目概要统计。
     *
     * @param projectId 项目 ID
     * @return 项目概要
     */
    @GetMapping
    @Operation(summary = "查询项目概要统计", description = "查询项目概要统计。")
    public ResponseEntity<OverviewResponse> get(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(OverviewResponse.from(service.get(projectId)));
    }
}
