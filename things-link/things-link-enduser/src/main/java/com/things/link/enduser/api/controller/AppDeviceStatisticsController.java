package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.AppDeviceStatisticsResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppDeviceStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 原生App四统计入口，不接受调用者自报租户或项目。 */
@RestController
@Tag(name = "App 设备")
public class AppDeviceStatisticsController {
    private final AppDeviceStatisticsService statistics;
    /** @param statistics 授权设备统计用例 */
    public AppDeviceStatisticsController(AppDeviceStatisticsService statistics) { this.statistics = statistics; }

    /**
     * 读取当前App账号在令牌项目下的设备总数、在线数、上报活跃数和告警设备数。
     * @param jwt 已认证的App令牌，范围只能由其可信声明解析
     * @return 同一数据库快照统计；项目角色失效返回401，未知历史上报不猜补
     */
    @GetMapping("/api/v1/app/devices/statistics")
    @Operation(operationId = "getAppDeviceStatistics", summary = "授权设备统计", description = "按当前有效项目角色与设备绑定聚合；不使用Console全项目概要或设备端时钟。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "授权设备统计"),
            @ApiResponse(responseCode = "401", description = "App令牌或项目角色失效",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AppDeviceStatisticsResponse read(@AuthenticationPrincipal Jwt jwt) {
        return AppDeviceStatisticsResponse.from(statistics.read(AppJwtIdentity.tenantId(jwt),
                AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt)));
    }
}
