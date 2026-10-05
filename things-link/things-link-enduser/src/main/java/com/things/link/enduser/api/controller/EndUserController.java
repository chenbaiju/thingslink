package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.EndUserRoleRequest;
import com.things.link.enduser.api.dto.request.IssueDeviceClaimTokenRequest;
import com.things.link.enduser.api.dto.request.ProvisionEndUserRequest;
import com.things.link.enduser.api.dto.response.DeviceClaimTokenResponse;
import com.things.link.enduser.api.dto.response.EndUserDeviceBindingResponse;
import com.things.link.enduser.api.dto.response.EndUserResponse;
import com.things.link.enduser.application.EndUserProvisioningService;
import com.things.link.enduser.application.EndUserQueryService;
import com.things.link.enduser.application.EndUserRoleService;
import com.things.link.enduser.application.DeviceClaimService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 控制台终端用户管理接口（S11-1b）。
 *
 * <h2>授权在服务层，不在 Controller</h2>
 * 与 {@code ProjectMemberController} 同理：预置/分配/停用/恢复/改角色由应用服务调用
 * {@code projectService.requireRoleInProject(...)} 后复验 {@code canManageMembers()}
 * （OWNER / ADMIN），读接口只要 {@code requireRoleInProject(...)}（任意成员）。
 *
 * <h2>非成员一律 404，不是 403</h2>
 * 403 等于确认「这个项目存在」，会变成项目枚举通道（详见 {@code ProjectMemberController}）。
 *
 * <h2>不提供租户级账号禁用</h2>
 * 本控制器只暴露项目级操作（{@code app_user_role} 的角色/状态）。租户级
 * {@code app_user.status} 是系统安全状态，项目 OWNER/ADMIN 不得修改（ADR 0035），
 * 因此没有对应的端点。
 */
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/end-users")
@Tag(name = "终端用户", description = "项目内终端用户的预置、角色分配、停用/恢复与设备绑定概览")
public class EndUserController {

    private final EndUserProvisioningService provisioningService;
    private final EndUserRoleService roleService;
    private final EndUserQueryService queryService;
    /** 一次性 CLAIM 令牌签发服务。 */
    private final DeviceClaimService claimService;
    /** 自解绑与管理员解绑共享的关系关闭服务。 */
    private final DeviceUnbindService unbindService;

    public EndUserController(EndUserProvisioningService provisioningService,
                             EndUserRoleService roleService,
                             EndUserQueryService queryService,
                             DeviceClaimService claimService,
                             DeviceUnbindService unbindService) {
        this.provisioningService = provisioningService;
        this.roleService = roleService;
        this.queryService = queryService;
        this.claimService = claimService;
        this.unbindService = unbindService;
    }

    /**
     * 项目管理员关闭指定终端用户与设备的有效关系。
     *
     * @param projectId 项目 ID
     * @param appUserId 目标终端用户 ID
     * @param deviceId 目标设备 ID
     * @return 204；不存在或已关闭同样成功
     */
    @DeleteMapping("/{appUserId}/devices/{deviceId}")
    @Operation(summary = "管理员解绑设备",
            description = "仅项目 OWNER/ADMIN 可关闭指定终端用户与设备的有效关系。"
                    + "关系保留为 CLOSED 历史；重复解绑或关系不存在仍返回 204。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "解绑完成或无需变更"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理终端用户（60002）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在，或你不是它的成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> unbindByManager(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "终端用户 ID") @PathVariable UUID appUserId,
            @Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        unbindService.unbindByManager(projectId, appUserId, deviceId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 列出当前项目内已分配角色的终端用户（游标分页）。
     *
     * @param projectId 项目 ID
     * @param cursor    上一页游标
     * @param limit     单页数量
     * @return 一页终端用户
     */
    @GetMapping
    @Operation(summary = "终端用户列表",
            description = "列出当前项目内已分配角色的终端用户及其项目角色。项目内任何角色都可查看。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在，或你不是它的成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<CursorPage<EndUserResponse>> list(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "上一页游标") @RequestParam(required = false) String cursor,
            @Parameter(description = "单页数量") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {

        return ResponseEntity.ok(queryService.list(projectId, cursor, limit)
                .map(EndUserResponse::from));
    }

    /**
     * 预置一个终端用户账号（租户级登录身份，不分配项目角色）。
     *
     * @param projectId 项目 ID
     * @param request   用户名、口令、显示名
     * @return 新账号（尚未分配角色）
     */
    @PostMapping
    @Operation(summary = "预置终端用户",
            description = "在项目归属租户下预置一个终端用户账号。仅创建租户级登录身份，不分配项目角色；"
                    + "分配角色用「分配角色」端点。仅项目 OWNER 与 ADMIN 可调用。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "预置成功，返回不含项目角色的账号"),
            @ApiResponse(responseCode = "400", description = "参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理终端用户",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在，或你不是它的成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "套餐额度暂不可用（50048）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "用户名已存在（60003）或终端用户额度已满（60058）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<EndUserResponse> provision(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Valid @RequestBody ProvisionEndUserRequest request) {

        return ResponseEntity.ok(EndUserResponse.from(
                provisioningService.provision(projectId, request.username(), request.password(),
                        request.displayName())));
    }

    /**
     * 给一个已存在的终端用户分配本项目角色。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param request   目标角色
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/{appUserId}/role")
    @Operation(summary = "分配角色",
            description = "给一个已存在于项目归属租户的终端用户分配本项目角色。"
                    + "仅项目 OWNER 与 ADMIN 可调用；该用户在本项目已有角色时返回 409。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "分配成功"),
            @ApiResponse(responseCode = "400", description = "参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理终端用户",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或该终端用户不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "该用户已在本项目拥有角色",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> assign(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "终端用户 ID") @PathVariable UUID appUserId,
            @Valid @RequestBody EndUserRoleRequest request) {

        roleService.assign(projectId, appUserId, request.role());
        return ResponseEntity.noContent().build();
    }

    /**
     * 修改某终端用户在本项目中的角色。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param request   目标角色
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PatchMapping("/{appUserId}/role")
    @Operation(summary = "修改角色",
            description = "修改某终端用户在本项目中的项目角色。仅项目 OWNER 与 ADMIN 可调用。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "修改成功"),
            @ApiResponse(responseCode = "400", description = "参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理终端用户",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或该用户在本项目没有角色",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> updateRole(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "终端用户 ID") @PathVariable UUID appUserId,
            @Valid @RequestBody EndUserRoleRequest request) {

        roleService.updateRole(projectId, appUserId, request.role());
        return ResponseEntity.noContent().build();
    }

    /**
     * 停用某终端用户在本项目的角色（项目级，不改租户级登录状态）。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/{appUserId}/suspend")
    @Operation(summary = "停用项目角色",
            description = "停用某终端用户在本项目的角色。仅项目 OWNER 与 ADMIN 可调用；"
                    + "只影响本项目，不影响该用户在租户级或其他项目的身份。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "停用成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理终端用户",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或该用户在本项目没有角色",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> suspend(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "终端用户 ID") @PathVariable UUID appUserId) {

        roleService.suspend(projectId, appUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 恢复某终端用户在本项目已停用的角色。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/{appUserId}/restore")
    @Operation(summary = "恢复项目角色",
            description = "恢复某终端用户在本项目已停用的角色。仅项目 OWNER 与 ADMIN 可调用。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "恢复成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理终端用户",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或该用户在本项目没有角色",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> restore(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "终端用户 ID") @PathVariable UUID appUserId) {

        roleService.restore(projectId, appUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 列出某终端用户在本项目中的设备绑定概览。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @return 设备绑定列表（S11-3 之前为空）
     */
    @GetMapping("/{appUserId}/devices")
    @Operation(summary = "设备绑定概览",
            description = "列出某终端用户在本项目中的设备授权关系（设备 ID、关系角色、状态）。"
                    + "项目内任何角色都可查看。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在，或你不是它的成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<EndUserDeviceBindingResponse>> devices(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "终端用户 ID") @PathVariable UUID appUserId) {

        return ResponseEntity.ok(queryService.listDeviceBindings(projectId, appUserId).stream()
                .map(EndUserDeviceBindingResponse::from)
                .toList());
    }

    /**
     * 为项目内尚无主控的设备签发一次性 CLAIM 令牌。
     *
     * @param projectId 项目 ID
     * @param request   目标设备
     * @return 仅本次响应可见的明文令牌及失效时刻
     */
    @PostMapping("/device-claim-tokens")
    @Operation(summary = "签发设备认领令牌",
            description = "仅项目 OWNER/ADMIN 可为当前项目内尚无 PRIMARY 的设备签发。"
                    + "令牌有效期 10 分钟、最多 5 次命中后复核，服务端只保存 SHA-256。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "签发成功；明文仅本次响应可见"),
            @ApiResponse(responseCode = "400", description = "参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理终端用户（60002）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不可见或认领设备不存在（60014）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "设备已有主控（60013）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DeviceClaimTokenResponse> issueClaimToken(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Valid @RequestBody IssueDeviceClaimTokenRequest request) {
        return ResponseEntity.ok(DeviceClaimTokenResponse.from(
                claimService.issue(projectId, request.deviceId())));
    }
}
