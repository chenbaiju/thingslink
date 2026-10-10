package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.AppSessionResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppProjectNavigationService;
import com.things.link.enduser.application.AppProjectSwitchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** App本人项目发现与双凭据换签；不接受客户端租户或用户身份。 */
@RestController
@SecurityRequirement(name="appAccessBearer")
@RequestMapping(produces="application/json")
@ApiResponses({
    @ApiResponse(responseCode="200",description="成功返回本人项目页或目标会话"),
    @ApiResponse(responseCode="400",description="参数或游标无效"),
    @ApiResponse(responseCode="401",description="当前身份或刷新凭据无效"),
    @ApiResponse(responseCode="404",description="目标项目不存在或未授权"),
    @ApiResponse(responseCode="429",description="超过适用配额或速率限制"),
    @ApiResponse(responseCode="503",description="导航未配置或依赖不可用")
})
@Tag(name="App 项目导航",description="本人项目发现及受控跨项目换签")
public class AppProjectNavigationController {
    private final AppProjectNavigationService navigation;
    private final AppProjectSwitchService switches;
    /** @param navigation 有界发现 @param switches 原子换签与可信目标投影 */
    public AppProjectNavigationController(AppProjectNavigationService navigation,AppProjectSwitchService switches) {
        this.navigation=navigation;this.switches=switches;
    }
    /**
     * 发现当前账号可进入的项目，空页可能仍有下一游标，身份与候选来自服务端复验。
     * @param jwt 已验签App身份
     * @param cursor 上次扫描位置
     * @param limit 返回条数上限
     * @return 当前可信身份和有界项目页；失效身份拒绝，不缓存
     */
    @GetMapping("/api/v1/app/projects")
    @Operation(operationId="listAppAuthorizedProjects",summary="发现本人项目",description="仅当前租户本人有效角色，每次最多扫描100个项目；空页可有nextCursor，limit为1至100，默认20。平台导航未配置返回60063/503。")
    public ResponseEntity<AppProjectNavigationService.Page> projects(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(navigation.discover(
                AppJwtIdentity.tenantId(jwt),AppJwtIdentity.projectId(jwt),AppJwtIdentity.appUserId(jwt),cursor,limit));
    }
    /**
     * 原子撤销源刷新族并签发同租户本人目标项目会话，不支持通用幂等响应重放。
     * @param jwt 已验签源项目身份
     * @param request 当前刷新凭据及目标项目定位
     * @return 目标会话及可信入口；失败不返回其他用户或租户的项目信息
     */
    @PostMapping("/api/v1/app/auth/switch-project")
    @Operation(operationId="switchAppProject",summary="切换本人项目",description="同时要求当前App Bearer和对应refreshToken；事务内复核源目标项目与本人角色，原子撤销旧族并签发新族。结果未知不得自动重放，需重新登录。目标不可见60064/404，刷新失效60007/401。")
    public ResponseEntity<SwitchResponse> switchProject(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SwitchRequest request) {
        var result=switches.switchProject(request.refreshToken(),AppJwtIdentity.tenantId(jwt),
                AppJwtIdentity.projectId(jwt),AppJwtIdentity.appUserId(jwt),request.targetProjectId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SwitchResponse(
                result.backend(),result.tenant(),result.user(),result.project().id(),result.project().projectKey(),
                result.project().name(),AppSessionResponse.from(result.session())));
    }
    /** @param refreshToken 当前源刷新凭据，不允许日志输出 @param targetProjectId 目标定位，不授予权限 */
    @Schema(name="AppProjectSwitchRequest",description="App项目换签请求")
    public record SwitchRequest(
            @Schema(description="当前源项目刷新凭据",requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Size(max=4096) String refreshToken,
            @Schema(description="目标定位，不授予权限",requiredMode=Schema.RequiredMode.REQUIRED) @NotNull UUID targetProjectId) {
        /** @return 脱敏请求标识 */
        @Override public String toString(){return "SwitchRequest[redacted]";}
    }
    /** @param backendInstanceId 平台实例 @param tenantId 本人租户 @param appUserId 本人ID @param projectId 目标项目 @param projectKey 可信入口键 @param projectName 目标名称 @param session 目标令牌对 */
    @Schema(name="AppProjectSwitchResponse",description="App目标项目会话与可信入口")
    public record SwitchResponse(
            @Schema(description="稳定平台实例",requiredMode=Schema.RequiredMode.REQUIRED) UUID backendInstanceId,
            @Schema(description="已认证租户",requiredMode=Schema.RequiredMode.REQUIRED) UUID tenantId,
            @Schema(description="已认证本人",requiredMode=Schema.RequiredMode.REQUIRED) UUID appUserId,
            @Schema(description="目标项目",requiredMode=Schema.RequiredMode.REQUIRED) UUID projectId,
            @Schema(description="可信目标入口键",requiredMode=Schema.RequiredMode.REQUIRED) String projectKey,
            @Schema(description="目标名称",requiredMode=Schema.RequiredMode.REQUIRED) String projectName,
            @Schema(description="目标令牌对，不得写日志",requiredMode=Schema.RequiredMode.REQUIRED) AppSessionResponse session) {
        /** @return 脱敏响应标识 */
        @Override public String toString(){return "SwitchResponse[redacted]";}
    }
}
