package com.things.link.iam.application;
import com.things.link.project.application.ProjectInvitationService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import java.util.UUID;
/** 通过project应用端口校验邀请后复用原注册；从不跳过邮箱验证或添加项目成员。 */
@Service
public class ProjectInvitationRegistrationService {
    private final ProjectInvitationService invitations;
    private final RegistrationService registration;
    public ProjectInvitationRegistrationService(ProjectInvitationService invitations, RegistrationService registration) {
        this.invitations = invitations; this.registration = registration;
    }
    @Transactional
    public void register(UUID invitationId, String code, RegisterCommand command, ClientContext client) {
        var invitation = invitations.preview(invitationId, code);
        if (command.email() == null || !invitation.targetEmail().equals(command.email().trim().toLowerCase(Locale.ROOT)))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        registration.register(new RegisterCommand(invitation.targetEmail(), command.password(), command.displayName()), client);
    }
}
