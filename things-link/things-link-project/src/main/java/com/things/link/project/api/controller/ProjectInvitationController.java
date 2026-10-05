package com.things.link.project.api.controller;

import com.things.link.project.api.dto.request.AcceptProjectInvitationRequest;
import com.things.link.project.api.dto.request.InviteMemberRequest;
import com.things.link.project.application.ProjectInvitationService;
import com.things.link.project.application.ProjectInvitationView;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.util.UUID;

/** 管理者与真实收件人分别授权；原立即添加成员接口保持兼容。 */
@RestController
@Tag(name="项目邀请", description = "创建、撤销、预览、注册与一次性接受邀请，兼容旧成员添加接口")
@SecurityRequirement(name="consoleAccessBearer")
public class ProjectInvitationController {
    private final ProjectInvitationService service;
    public ProjectInvitationController(ProjectInvitationService service) { this.service = service; }

    /**
     * 创建待接受项目邀请。
     *
     * @param projectId 接口指定的项目标识
     * @param request 本次操作的请求数据，结构见 {@code InviteMemberRequest}
     * @return 当前接口的操作结果，响应结构见 {@code ProjectInvitationView}
     */
    @PostMapping("/api/v1/projects/{projectId}/invitations")
    @Operation(operationId="createProjectInvitation",summary="创建待接受项目邀请", description = "创建待接受项目邀请。")
    public ProjectInvitationView create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @Valid @RequestBody InviteMemberRequest request) {
        return service.create(projectId, request.email(), request.role());
    }
    /**
     * 管理者分页读取项目邀请。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/api/v1/projects/{projectId}/invitations")
    @Operation(operationId="listProjectInvitations",summary="管理者分页读取项目邀请", description = "管理者分页读取项目邀请。")
    public CursorPage<ProjectInvitationView> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor, @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        query(request); return service.list(projectId, cursor, limit);
    }
    /**
     * 重发邀请并使旧码失效。
     *
     * @param projectId 接口指定的项目标识
     * @param invitationId 项目邀请标识
     * @return 当前接口的操作结果，响应结构见 {@code ProjectInvitationView}
     */
    @PostMapping("/api/v1/projects/{projectId}/invitations/{invitationId}/resend")
    @Operation(operationId="resendProjectInvitation",summary="重发邀请并使旧码失效", description = "重发邀请并使旧码失效。")
    public ProjectInvitationView resend(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "项目邀请标识") @PathVariable UUID invitationId) {
        return service.resend(projectId, invitationId);
    }
    /**
     * 撤回尚未接受的邀请。
     *
     * @param projectId 接口指定的项目标识
     * @param invitationId 项目邀请标识
     * @return 操作完成后的 HTTP 响应，正文为空
     */
    @DeleteMapping("/api/v1/projects/{projectId}/invitations/{invitationId}")
    @Operation(operationId="revokeProjectInvitation",summary="撤回尚未接受的邀请", description = "撤回尚未接受的邀请。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="撤回成功")
    public ResponseEntity<Void> revoke(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "项目邀请标识") @PathVariable UUID invitationId) {
        service.revoke(projectId, invitationId); return ResponseEntity.noContent().build();
    }
    /**
     * 已验证账号的邀请收件箱。
     *
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/api/v1/project-invitations")
    @Operation(operationId="listMyProjectInvitations",summary="已验证账号的邀请收件箱", description = "已验证账号的邀请收件箱。")
    public CursorPage<ProjectInvitationView> inbox(@io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        query(request); return service.inbox(cursor, limit);
    }
    /**
     * 目标账号明确接受邀请。
     * 原子验证邮箱、权限和席位；重复接受拒绝，不缓存或重放旧成功响应。
     *
     * @param invitationId 项目邀请标识
     * @param request 本次操作的请求数据，结构见 {@code AcceptProjectInvitationRequest}
     * @return 操作完成后的 HTTP 响应，正文为空
     */
    @PostMapping("/api/v1/project-invitations/{invitationId}/accept")
    @Operation(operationId="acceptProjectInvitation",summary="目标账号明确接受邀请",
            description="原子验证邮箱、权限和席位；重复接受拒绝，不缓存或重放旧成功响应。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="已接受，重复调用拒绝")
    public ResponseEntity<Void> accept(@io.swagger.v3.oas.annotations.Parameter(description = "项目邀请标识") @PathVariable UUID invitationId, @Valid @RequestBody AcceptProjectInvitationRequest request) {
        service.accept(invitationId, request.code()); return ResponseEntity.noContent().build();
    }
    private static void query(HttpServletRequest request) {
        if (!Set.of("cursor", "limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
