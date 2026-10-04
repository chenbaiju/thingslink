package com.things.link.project.application;

import com.things.link.project.domain.ProjectInvitation;
import com.things.link.project.domain.ProjectInvitationRepository;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 通知适配器的窄端口；读取与结果回写各用短事务，SMTP不占用数据库事务。 */
@Service
public class ProjectInvitationDeliveryService {
    private final ProjectInvitationRepository invitations;
    private final ProjectRepository projects;
    private final ProjectInvitationCodes codes;
    private final AccountDirectory accounts;
    public ProjectInvitationDeliveryService(ProjectInvitationRepository invitations, ProjectRepository projects,
            ProjectInvitationCodes codes, AccountDirectory accounts) {
        this.invitations = invitations; this.projects = projects; this.codes = codes; this.accounts = accounts;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> prepare(UUID id, long revision) {
        return invitations.find(id).filter(value -> value.revision() == revision
                && value.pendingAt(Instant.now()) && value.deliveryChannel() == ProjectInvitation.Channel.EMAIL
                && value.deliveryStatus() == ProjectInvitation.Delivery.QUEUED)
                .filter(value -> accounts.isActive(value.inviterAccountId())
                        && projects.findById(value.projectId()).isPresent()
                        && projects.findRole(value.projectId(), value.inviterAccountId()).filter(ProjectRole::canManageMembers).isPresent())
                .map(value -> new Delivery(value.targetEmail(), codes.issue(value), value.expiresAt()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID id, long revision, boolean sent) {
        invitations.recordDelivery(id, revision, sent ? ProjectInvitation.Delivery.SENT : ProjectInvitation.Delivery.FAILED);
    }

    /** 仅交给发信适配器，不得返回管理HTTP或写日志。 */
    public record Delivery(String email, String code, Instant expiresAt) {
        @Override public String toString() { return "ProjectInvitationDelivery[redacted]"; }
    }
}
