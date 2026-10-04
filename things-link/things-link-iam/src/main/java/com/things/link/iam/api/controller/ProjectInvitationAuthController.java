package com.things.link.iam.api.controller;
import com.things.link.iam.api.dto.request.ProjectInvitationProofRequest;
import com.things.link.iam.api.dto.request.ProjectInvitationRegistrationRequest;
import com.things.link.iam.application.*;
import com.things.link.project.application.ProjectInvitationService;
import com.things.link.project.application.ProjectInvitationView;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** 两条精确公开路径，既不消费邀请，也不签发登录会话。 */
@RestController
@RequestMapping("/api/v1/auth/project-invitation")
@Tag(name="项目邀请")
public class ProjectInvitationAuthController {
    private final ProjectInvitationService invitations;
    private final ProjectInvitationRegistrationService registration;
    private final AuthRateLimiter limits;
    public ProjectInvitationAuthController(ProjectInvitationService invitations, ProjectInvitationRegistrationService registration, AuthRateLimiter limits) {
        this.invitations = invitations; this.registration = registration; this.limits = limits;
    }
    @PostMapping("/preview")
    @Operation(operationId="previewProjectInvitation",summary="以有效邀请码预览邀请，不消费")
    public ProjectInvitationView preview(@Valid @RequestBody ProjectInvitationProofRequest request, HttpServletRequest http) {
        limit(http); return invitations.preview(request.invitationId(), request.code());
    }
    @PostMapping("/register")
    @Operation(operationId="registerWithProjectInvitation",summary="按邀请绑定邮箱注册，仍需邮箱验证")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="注册成功，尚待验证邮箱")
    public ResponseEntity<Void> register(@Valid @RequestBody ProjectInvitationRegistrationRequest request, HttpServletRequest http) {
        limit(http);
        registration.register(request.invitationId(), request.code(), new RegisterCommand(request.email(), request.password(), request.displayName()),
                new ClientContext(http.getHeader("User-Agent"), http.getRemoteAddr()));
        return ResponseEntity.noContent().build();
    }
    private void limit(HttpServletRequest http) {
        if (!limits.tryAcquireInvitationProof(http.getRemoteAddr())) throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
    }
}
