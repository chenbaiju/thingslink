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
@Tag(name="项目邀请")
@SecurityRequirement(name="consoleAccessBearer")
public class ProjectInvitationController {
    private final ProjectInvitationService service;
    public ProjectInvitationController(ProjectInvitationService service) { this.service = service; }

    @PostMapping("/api/v1/projects/{projectId}/invitations")
    @Operation(operationId="createProjectInvitation",summary="创建待接受项目邀请")
    public ProjectInvitationView create(@PathVariable UUID projectId, @Valid @RequestBody InviteMemberRequest request) {
        return service.create(projectId, request.email(), request.role());
    }
    @GetMapping("/api/v1/projects/{projectId}/invitations")
    @Operation(operationId="listProjectInvitations",summary="管理者分页读取项目邀请")
    public CursorPage<ProjectInvitationView> list(@PathVariable UUID projectId,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        query(request); return service.list(projectId, cursor, limit);
    }
    @PostMapping("/api/v1/projects/{projectId}/invitations/{invitationId}/resend")
    @Operation(operationId="resendProjectInvitation",summary="重发邀请并使旧码失效")
    public ProjectInvitationView resend(@PathVariable UUID projectId, @PathVariable UUID invitationId) {
        return service.resend(projectId, invitationId);
    }
    @DeleteMapping("/api/v1/projects/{projectId}/invitations/{invitationId}")
    @Operation(operationId="revokeProjectInvitation",summary="撤回尚未接受的邀请")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="撤回成功")
    public ResponseEntity<Void> revoke(@PathVariable UUID projectId, @PathVariable UUID invitationId) {
        service.revoke(projectId, invitationId); return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/v1/project-invitations")
    @Operation(operationId="listMyProjectInvitations",summary="已验证账号的邀请收件箱")
    public CursorPage<ProjectInvitationView> inbox(@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        query(request); return service.inbox(cursor, limit);
    }
    @PostMapping("/api/v1/project-invitations/{invitationId}/accept")
    @Operation(operationId="acceptProjectInvitation",summary="目标账号明确接受邀请",
            description="原子验证邮箱、权限和席位；重复接受拒绝，不缓存或重放旧成功响应。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="已接受，重复调用拒绝")
    public ResponseEntity<Void> accept(@PathVariable UUID invitationId, @Valid @RequestBody AcceptProjectInvitationRequest request) {
        service.accept(invitationId, request.code()); return ResponseEntity.noContent().build();
    }
    private static void query(HttpServletRequest request) {
        if (!Set.of("cursor", "limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
