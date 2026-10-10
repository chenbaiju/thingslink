package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.AppAlarmResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppAlarmService;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.domain.AppAlarmQuery;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.UUID;

/** 不依赖看板上下文的App公共告警只读入口。 */
@RestController
@RequestMapping(value = "/api/v1/app/alarms", produces = "application/json")
@Validated
@Tag(name = "App 公共告警", description = "当前授权设备的独立告警历史与只读详情")
@SecurityRequirement(name = "appAccessBearer")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "授权告警事实"),
        @ApiResponse(responseCode = "400", description = "筛选或游标不合法", content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = "App身份或项目角色失效", content = @Content(schema = @Schema(implementation = ApiError.class)))
})
public class AppAlarmController {
    private final AppAlarmService alarms;
    /** @param alarms 当前授权告警用例 */
    public AppAlarmController(AppAlarmService alarms) { this.alarms=alarms; }
    /**
     * 查询当前账号已授权设备的告警事故，过滤后再分页。
     * @param jwt 已验签App令牌
     * @param deviceId 可选来源设备
     * @param severity 可选等级
     * @param conditionState 可选未解除或已解除状态
     * @param from 首次异常时间下界，包含；与to成对，最长366天
     * @param to 首次异常时间上界，不包含
     * @param cursor 身份与条件绑定游标
     * @param limit 页容量，1至50
     * @return 授权事故页，不含PENDING或未曾触发的恢复条件
     */
    @GetMapping
    @Operation(operationId = "getAppAlarms", summary = "App告警历史", description = "独立于看板，按首次异常时间倒序；时间区间[from,to)，不影响通知偏好。")
    public CursorPage<AppAlarmResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) UUID deviceId,
            @RequestParam(required=false) String severity,
            @RequestParam(required=false) String conditionState,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required=false) @Size(max=2048) String cursor,
            @RequestParam(defaultValue="20") @Min(1) @Max(50) int limit) {
        return alarms.list(identity(jwt),new AppAlarmQuery(deviceId,severity,conditionState,from,to),cursor,limit)
                .map(AppAlarmResponse::from);
    }
    /**
     * 读取单项当前授权事故；跨范围、失权、软删和不存在统一404。
     * @param jwt 已验签App令牌
     * @param id 告警事故标识
     * @return 公共事故详情；不提供确认或解除操作
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getAppAlarm", summary = "App告警详情", description = "读取当前绑定且未删除设备的单项告警事实；不存在或失权统一不可见，不提供确认或解除操作。")
    @ApiResponse(responseCode = "200", description = "授权告警详情", content = @Content(schema = @Schema(implementation = AppAlarmResponse.class)))
    @ApiResponse(responseCode = "404", description = "告警不存在或未授权（60060）", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AppAlarmResponse detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return AppAlarmResponse.from(alarms.detail(identity(jwt),id));
    }
    /** 从可信令牌构造身份，客户端不提供范围。 */
    private static AppAuthenticatedPrincipal identity(Jwt jwt) {
        return new AppAuthenticatedPrincipal(AppJwtIdentity.tenantId(jwt),AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt),AppJwtIdentity.projectGeneration(jwt));
    }
}
