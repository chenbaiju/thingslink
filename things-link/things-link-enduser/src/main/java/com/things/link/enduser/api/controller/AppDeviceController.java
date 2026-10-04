package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.AppSubmitCommandRequest;
import com.things.link.enduser.api.dto.request.IssueDeviceShareTokenRequest;
import com.things.link.enduser.api.dto.response.AppCommandResponse;
import com.things.link.enduser.api.dto.response.AppCurrentValueResponse;
import com.things.link.enduser.api.dto.response.AppDeviceResponse;
import com.things.link.enduser.api.dto.response.AppHistoryResponse;
import com.things.link.enduser.api.dto.response.DeviceShareTokenResponse;
import com.things.link.enduser.api.dto.response.DeviceTransferTokenResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppDeviceAccessService;
import com.things.link.enduser.application.DeviceShareService;
import com.things.link.enduser.application.DeviceTransferService;
import com.things.link.enduser.application.DeviceUnbindService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * App 终端用户设备数据面接口（S11-2b）。
 *
 * <p>与控制台设备接口平行但主体是终端用户（{@code app_user_id}），路径前缀
 * {@code /api/v1/app/devices}，走 App 安全链。所有端点都从 App 令牌的 {@code pid} 声明取项目，
 * <b>不接受客户端传 projectId</b>（单项目令牌，ADR 0036）。
 *
 * <p>两层授权（{@code app_user_role} × {@code app_user_device}）在
 * {@link AppDeviceAccessService} 内完成，本控制器只做参数绑定与响应映射。
 * 读放行任意 ACTIVE 角色 × 任意 ACTIVE 关系；控制额外要求 {@code relation_role ∈ {PRIMARY, MEMBER}}。
 */
@Validated
@RestController
@RequestMapping("/api/v1/app/devices")
@Tag(name = "App 设备", description = "终端用户访问已授权设备的列表、详情、当前值、历史与命令")
public class AppDeviceController {

    private final AppDeviceAccessService accessService;

    /** 设备自解绑用例。 */
    private final DeviceUnbindService unbindService;

    /** 主控转移令牌签发用例。 */
    private final DeviceTransferService transferService;

    /** MEMBER/READ_ONLY 共享令牌签发用例。 */
    private final DeviceShareService shareService;

    public AppDeviceController(AppDeviceAccessService accessService,
                               DeviceUnbindService unbindService,
                               DeviceTransferService transferService,
                               DeviceShareService shareService) {
        this.accessService = accessService;
        this.unbindService = unbindService;
        this.transferService = transferService;
        this.shareService = shareService;
    }

    /**
     * 当前 PRIMARY 为设备签发一次性共享令牌。
     *
     * @param jwt 已认证 App JWT
     * @param deviceId 目标设备 ID
     * @param request MEMBER/READ_ONLY 目标角色
     * @return 只出现一次的明文令牌
     */
    @PostMapping("/{deviceId}/share-tokens")
    @Operation(summary = "签发设备共享令牌",
            description = "仅设备当前 ACTIVE PRIMARY 可签发；目标角色只能是 MEMBER 或 READ_ONLY，令牌有效期 10 分钟。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "签发成功"),
            @ApiResponse(responseCode = "400", description = "请求角色不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "访问令牌或项目角色失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "设备不存在或当前用户不是主控（60010）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DeviceShareTokenResponse> issueShareToken(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId,
            @Valid @RequestBody IssueDeviceShareTokenRequest request) {
        return ResponseEntity.ok(DeviceShareTokenResponse.from(shareService.issue(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt), deviceId, request.relationRole())));
    }

    /**
     * 当前 PRIMARY 为设备签发一次性主控转移令牌。
     *
     * @param jwt 已认证 App JWT
     * @param deviceId 目标设备 ID
     * @return 只出现一次的明文令牌
     */
    @PostMapping("/{deviceId}/transfer-tokens")
    @Operation(summary = "签发主控转移令牌",
            description = "仅设备当前 ACTIVE PRIMARY 可签发；令牌有效期 10 分钟，明文只返回一次。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "签发成功"),
            @ApiResponse(responseCode = "401", description = "访问令牌或项目角色失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "设备不存在或当前用户不是主控（60010）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DeviceTransferTokenResponse> issueTransferToken(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        return ResponseEntity.ok(DeviceTransferTokenResponse.from(transferService.issue(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt), deviceId)));
    }

    /**
     * 当前 App 用户关闭自己与设备的有效关系。
     *
     * @param jwt 已认证 App JWT
     * @param deviceId 目标设备 ID
     * @return 204；不存在或已关闭同样成功
     */
    @DeleteMapping("/{deviceId}/binding")
    @Operation(summary = "自解绑设备",
            description = "关闭当前用户与设备的有效关系并保留历史。重复解绑或关系不存在仍返回 204；"
                    + "角色失效时返回 60009。解绑后设备数据面立即不可见。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "解绑完成或无需变更"),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或角色已失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> unbindSelf(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        unbindService.unbindSelf(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt), deviceId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 列出当前终端用户在本项目可访问的设备（游标分页）。
     */
    @GetMapping
    @Operation(summary = "设备列表",
            description = "列出当前终端用户在令牌项目下已授权（有效绑定）的设备，按创建时间降序游标分页。"
                    + "解绑后立即不可见。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或角色已失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<CursorPage<AppDeviceResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "上一页游标") @RequestParam(required = false) String cursor,
            @Parameter(description = "单页数量") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {

        return ResponseEntity.ok(accessService.list(
                        AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), cursor, limit)
                .map(AppDeviceResponse::from));
    }

    /**
     * 单设备详情。
     */
    @GetMapping("/{deviceId}")
    @Operation(summary = "设备详情",
            description = "返回单个已授权设备的详情。未绑定、已删除或不属于当前项目统一返回 404。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或角色已失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "设备不存在或未授权（60010）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AppDeviceResponse> detail(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId) {

        return ResponseEntity.ok(AppDeviceResponse.from(accessService.detail(
                AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), deviceId)));
    }

    /**
     * 单设备多属性当前值。
     */
    @GetMapping("/{deviceId}/current-values")
    @Operation(summary = "设备当前值",
            description = "返回单个已授权设备若干属性的当前值；未请求的属性键不会出现在结果里。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或角色已失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "设备不存在或未授权（60010）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<AppCurrentValueResponse>> currentValues(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId,
            @Parameter(description = "属性键列表，逗号分隔") @RequestParam(required = false) List<String> keys) {

        return ResponseEntity.ok(accessService.currentValues(
                        AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), deviceId,
                        keys == null ? List.of() : keys).stream()
                .map(AppCurrentValueResponse::from)
                .toList());
    }

    /**
     * 单设备属性历史聚合曲线。
     */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "套餐历史窗口不可用（50048）", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.things.link.shared.error.ApiError.class)))
    @GetMapping("/{deviceId}/telemetry/history")
    @Operation(summary = "属性历史",
            description = "查询单个已授权设备的数值属性历史曲线，超出 2000 点自动升粒度。"
                    + "粒度取 RAW/ONE_MINUTE/ONE_HOUR/ONE_DAY，聚合取 AVG/MIN/MAX/SUM/COUNT。先按套餐窗口裁剪，聚合仅返回完整桶，缺投影503/50048。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "400", description = "参数不合法（10001）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或角色已失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "设备不存在或未授权（60010）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AppHistoryResponse> history(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId,
            @Parameter(description = "属性键") @RequestParam
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "属性标识符格式不合法") String propertyKey,
            @Parameter(description = "起始时刻（含，RFC3339）") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "结束时刻（不含，RFC3339）") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "请求粒度") @RequestParam(defaultValue = "RAW") String granularity,
            @Parameter(description = "聚合函数") @RequestParam(defaultValue = "AVG") String aggregation) {

        return ResponseEntity.ok(AppHistoryResponse.from(accessService.history(
                AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), deviceId,
                propertyKey, from, to, granularity, aggregation)));
    }

    /**
     * 下发设备命令。
     */
    @PostMapping("/{deviceId}/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "下发命令",
            description = "向已授权设备下发命令。要求有效绑定关系角色为 PRIMARY 或 MEMBER，"
                    + "READ_ONLY 返回 403（60011）。Idempotency-Key 必填；202 仅表示命令已受理。")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "命令已受理"),
            @ApiResponse(responseCode = "400", description = "参数不合法（10001）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或角色已失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "设备关系角色不允许控制（60011）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "设备不存在或未授权（60010）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AppCommandResponse submit(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId,
            @Parameter(description = "业务幂等键", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AppSubmitCommandRequest request) {

        return AppCommandResponse.from(accessService.submit(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), deviceId,
                idempotencyKey, request.commandKey(), request.input()));
    }

    /**
     * 回读命令状态。
     */
    @GetMapping("/{deviceId}/commands/{commandId}")
    @Operation(summary = "命令状态",
            description = "回读某条已下发命令的当前状态。命令不属于当前设备/项目时统一返回 404。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "访问令牌无效或角色已失效（60009）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "设备不存在或命令不存在（60010）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AppCommandResponse> status(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId,
            @Parameter(description = "命令 ID") @PathVariable UUID commandId) {

        return ResponseEntity.ok(AppCommandResponse.from(accessService.status(
                AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), deviceId, commandId)));
    }
}
