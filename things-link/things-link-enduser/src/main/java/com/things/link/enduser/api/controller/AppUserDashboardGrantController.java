package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.UpdateAppUserDashboardGrantRequest;
import com.things.link.enduser.api.dto.response.AppUserDashboardGrantResponse;
import com.things.link.enduser.api.support.AppUserDashboardGrantRequestParser;
import com.things.link.enduser.api.support.DashboardGrantApiAuthorization;
import com.things.link.enduser.application.AppUserDashboardGrantManagementService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Console终端用户看板READ授权管理接口。
 *
 * <p>S12-2a3b只向项目OWNER与ADMIN开放列表、详情和更新。Controller先执行实时项目角色守卫，
 * 管理服务再在事务内复验项目生命周期、用户、看板与CAS；软删看板只影响运行资格，不抹除历史授权响应。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/end-users/{appUserId}/dashboard-grants")
@Tag(name = "终端用户看板授权", description = "项目管理员维护终端用户对稳定看板的READ授权")
public class AppUserDashboardGrantController {

    /** HTTP首层实时OWNER/ADMIN角色守卫。 */
    private final DashboardGrantApiAuthorization authorization;

    /** 授权查询、生命周期复验、锁序、CAS与审计编排。 */
    private final AppUserDashboardGrantManagementService service;

    /** 更新正文的原始字节严格解析器。 */
    private final AppUserDashboardGrantRequestParser requestParser;

    /**
     * 创建终端用户看板授权管理控制器。
     *
     * @param authorization HTTP首层项目角色守卫
     * @param service 授权管理应用服务
     * @param requestParser 严格更新信封解析器
     */
    public AppUserDashboardGrantController(DashboardGrantApiAuthorization authorization,
                                           AppUserDashboardGrantManagementService service,
                                           AppUserDashboardGrantRequestParser requestParser) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.service = Objects.requireNonNull(service, "service");
        this.requestParser = Objects.requireNonNull(requestParser, "requestParser");
    }

    /**
     * 分页读取目标用户的全部ACTIVE与REVOKED看板授权。
     *
     * @param projectId 当前项目ID
     * @param appUserId 目标终端用户ID
     * @param cursor 上一页返回的不透明游标，首页为空
     * @param limit 单页数量，默认50，允许1至200
     * @return 只含授权合同字段的稳定分页响应
     */
    @GetMapping
    @Operation(operationId = "listAppUserDashboardGrants", summary = "终端用户看板授权分页",
            description = "OWNER/ADMIN可在ACTIVE或ARCHIVED项目读取目标用户的ACTIVE与REVOKED历史授权")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "授权分页读取成功"),
            @ApiResponse(responseCode = "400", description = "cursor、limit或路径参数不合法（10001）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "项目成员角色不足（60024）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目、用户或授权目标不可见（50001/60025）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<CursorPage<AppUserDashboardGrantResponse>> list(
            @io.swagger.v3.oas.annotations.Parameter(description = "当前项目ID") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "目标终端用户ID") @PathVariable UUID appUserId,
            @io.swagger.v3.oas.annotations.Parameter(description = "上一页返回的不透明游标，首页为空") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "单页数量，默认50，允许1至200") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireManage(projectId);
        return ResponseEntity.ok(service.list(projectId, appUserId, cursor, limit)
                .map(AppUserDashboardGrantResponse::from));
    }

    /**
     * 读取目标用户对一个稳定看板的授权历史事实。
     *
     * @param projectId 当前项目ID
     * @param appUserId 目标终端用户ID
     * @param dashboardId 稳定看板ID
     * @return ACTIVE或REVOKED授权响应
     */
    @GetMapping("/{dashboardId}")
    @Operation(operationId = "getAppUserDashboardGrant", summary = "终端用户看板授权详情",
            description = "OWNER/ADMIN可在ACTIVE或ARCHIVED项目读取授权；看板软删后仍只返回历史授权字段")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "授权详情读取成功",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AppUserDashboardGrantResponse.class))),
            @ApiResponse(responseCode = "400", description = "路径参数不合法（10001）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "项目成员角色不足（60024）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目、用户或授权不存在（50001/60025）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AppUserDashboardGrantResponse> get(
            @io.swagger.v3.oas.annotations.Parameter(description = "当前项目ID") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "目标终端用户ID") @PathVariable UUID appUserId,
            @io.swagger.v3.oas.annotations.Parameter(description = "稳定看板ID") @PathVariable UUID dashboardId) {
        authorization.requireManage(projectId);
        return ResponseEntity.ok(AppUserDashboardGrantResponse.from(
                service.get(projectId, appUserId, dashboardId)));
    }

    /**
     * 以显式expectedRevision首次授予、撤销或重新授予稳定看板READ权限。
     *
     * @param projectId 当前ACTIVE项目ID
     * @param appUserId 目标终端用户ID
     * @param dashboardId 稳定看板ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数公开OpenAPI合同
     * @param body 未绑定的原始双字符串JSON信封
     * @return 更新后的授权事实；匹配同状态时返回原事实且不推进revision
     */
    @PutMapping(value = "/{dashboardId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateAppUserDashboardGrant", summary = "更新终端用户看板授权",
            description = "仅OWNER/ADMIN可在ACTIVE项目更新；ARCHIVED沿50017拒绝。严格信封失败10002，"
                    + "字段业务语法60026，CAS冲突60027；公共幂等重放沿10009/10010/10014。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "授权已创建、更新或确认无需变化",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AppUserDashboardGrantResponse.class))),
            @ApiResponse(responseCode = "400", description = "信封或字段不合法（10002/60026）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "角色不足或项目只读（60024/50017）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目、用户、看板或授权不可见（50001/60025）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "revision冲突、幂等键异请求、在途或完成墓碑"
                    + "（60027/10009/10010/10014）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = UpdateAppUserDashboardGrantRequest.class)))
    public ResponseEntity<AppUserDashboardGrantResponse> update(
            @io.swagger.v3.oas.annotations.Parameter(description = "当前ACTIVE项目ID") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "目标终端用户ID") @PathVariable UUID appUserId,
            @io.swagger.v3.oas.annotations.Parameter(description = "稳定看板ID") @PathVariable UUID dashboardId,
            @Parameter(description = "可选公共写幂等键；完成重放不返回旧正文", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        UpdateAppUserDashboardGrantRequest request = requestParser.parseUpdate(body);
        return ResponseEntity.ok(AppUserDashboardGrantResponse.from(service.update(
                projectId, appUserId, dashboardId, request.expectedRevision(), request.status())));
    }
}
