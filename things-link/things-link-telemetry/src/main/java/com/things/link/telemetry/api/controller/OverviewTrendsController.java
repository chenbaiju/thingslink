package com.things.link.telemetry.api.controller;

import com.things.link.telemetry.api.dto.response.OverviewTrendsResponse;
import com.things.link.telemetry.api.support.OverviewApiAuthorization;
import com.things.link.telemetry.application.OverviewTrendsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** 项目历史曲线只读入口。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/overview/trends")
@Tag(name = "项目概要", description = "当前项目设备、告警及用量概要")
public class OverviewTrendsController {
    /** 曲线查询服务。 */
    private final OverviewTrendsService service;
    /** HTTP 项目授权守卫。 */
    private final OverviewApiAuthorization authorization;
    /** @param service 曲线查询服务 @param authorization HTTP授权 */
    public OverviewTrendsController(OverviewTrendsService service, OverviewApiAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }
    /**
     * 读取当前项目十二类历史曲线；不会创建样本，不参与计费，返回明确的实测或模拟来源。
     * @param projectId 已进入且具有成员资格的项目标识
     * @param days 1、3、7、15或30天窗口；不合法窗口拒绝，缺失样本不推断为零
     * @return 按完整UTC小时聚合的曲线、来源、窗口和单位
     */
    @GetMapping
    @Operation(summary = "查询项目历史统计曲线", description = "只读查询完整小时统计，来源不混算，缺样为null；模拟统计不代表真实用量。")
    public OverviewTrendsResponse getOverviewTrends(@Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "窗口天数，仅1、3、7、15、30") @RequestParam(defaultValue = "1") int days) {
        authorization.requireRead(projectId);
        return service.get(projectId, days);
    }
}
