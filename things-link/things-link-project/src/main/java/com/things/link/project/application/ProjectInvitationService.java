package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectInvitation;
import com.things.link.project.domain.ProjectInvitationRepository;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.query.SignedQueryCursorCodec;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 待接受邀请；始终使用真实接收人身份，禁止借切换TenantContext冒充邀请人来写成员。 */
@Service
public class ProjectInvitationService {
    private final ProjectInvitationRepository invitations;
    private final ProjectRepository projects;
    private final ProjectManagementWriteGuard management;
    private final AccountDirectory accounts;
    private final CollaborationAdmissionService admission;
    private final ProjectInvitationCodes codes;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;
    private final SignedQueryCursorCodec cursors;
    private final Clock clock;
    public ProjectInvitationService(ProjectInvitationRepository invitations, ProjectRepository projects,
            ProjectManagementWriteGuard management, AccountDirectory accounts, CollaborationAdmissionService admission,
            ProjectInvitationCodes codes, AuditLogService audit, ApplicationEventPublisher events,
            SignedQueryCursorCodec cursors, @org.springframework.beans.factory.annotation.Qualifier("projectInvitationClock")
            org.springframework.beans.factory.ObjectProvider<Clock> clocks) {
        this.invitations = invitations; this.projects = projects; this.management = management;
        this.accounts = accounts; this.admission = admission; this.codes = codes; this.audit = audit;
        this.events = events; this.cursors = cursors; this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public ProjectInvitationView create(UUID projectId, String email, ProjectRole role) {
        UUID actor = actor();
        management.requireMemberManager(projectId, actor);
        if (role == null) throw invalid();
        if (role == ProjectRole.OWNER) throw new BusinessException(ProjectErrorCode.OWNER_NOT_ASSIGNABLE);
        String target = normalize(email);
        var account = accounts.findByEmail(target);
        if (account.map(AccountRef::id).filter(actor::equals).isPresent()) throw new BusinessException(ProjectErrorCode.CANNOT_TARGET_SELF);
        if (account.isPresent() && projects.findRole(projectId, account.get().id()).isPresent())
            throw new BusinessException(ProjectErrorCode.ALREADY_MEMBER);
        var project = projects.findById(projectId).orElseThrow(ProjectInvitationService::notFound);
        Instant now = now();
        invitations.expirePending(projectId, target, now);
        var channel = account.isPresent() ? ProjectInvitation.Channel.INBOX : ProjectInvitation.Channel.EMAIL;
        var value = new ProjectInvitation(Uuid7.generate(), project.tenantId(), projectId, actor, target, role,
                ProjectInvitation.Status.PENDING, 1, UUID.randomUUID(), now.plus(Duration.ofDays(7)), channel,
                channel == ProjectInvitation.Channel.INBOX ? ProjectInvitation.Delivery.INBOX : ProjectInvitation.Delivery.QUEUED,
                null, null, now, now);
        try { invitations.create(value); }
        catch (DuplicateKeyException conflict) { throw new BusinessException(ProjectErrorCode.INVITATION_ALREADY_PENDING); }
        record(value, actor, "project.invitation.created");
        dispatch(value);
        return view(value, false);
    }

    @Transactional
    public ProjectInvitationView resend(UUID projectId, UUID id) {
        UUID actor = actor();
        management.requireMemberManager(projectId, actor);
        var old = invitations.lock(id, projectId).orElseThrow(ProjectInvitationService::notFound);
        if (old.status() == ProjectInvitation.Status.ACCEPTED || old.status() == ProjectInvitation.Status.REVOKED) throw unavailable();
        Instant now = now();
        if (now.isBefore(old.updatedAt().plusSeconds(60))) throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        var channel = accounts.findByEmail(old.targetEmail()).isPresent() ? ProjectInvitation.Channel.INBOX : ProjectInvitation.Channel.EMAIL;
        var value = new ProjectInvitation(old.id(), old.tenantId(), old.projectId(), actor, old.targetEmail(), old.role(),
                ProjectInvitation.Status.PENDING, Math.incrementExact(old.revision()), UUID.randomUUID(), now.plus(Duration.ofDays(7)), channel,
                channel == ProjectInvitation.Channel.INBOX ? ProjectInvitation.Delivery.INBOX : ProjectInvitation.Delivery.QUEUED,
                null, null, old.createdAt(), now);
        try { if (!invitations.replace(value, old.revision())) throw unavailable(); }
        catch (DuplicateKeyException conflict) { throw new BusinessException(ProjectErrorCode.INVITATION_ALREADY_PENDING); }
        record(value, actor, "project.invitation.resent");
        dispatch(value);
        return view(value, false);
    }

    @Transactional
    public void revoke(UUID projectId, UUID id) {
        UUID actor = actor();
        management.requireMemberManager(projectId, actor);
        var old = invitations.lock(id, projectId).orElseThrow(ProjectInvitationService::notFound);
        if (old.status() == ProjectInvitation.Status.ACCEPTED || old.status() == ProjectInvitation.Status.REVOKED) throw unavailable();
        var value = changed(old, ProjectInvitation.Status.REVOKED, null, null);
        if (!invitations.replace(value, old.revision())) throw unavailable();
        record(value, actor, "project.invitation.revoked");
    }

    /** 有效码只提供预览能力，不消费、不建成员，也不证明邮箱。 */
    @Transactional(readOnly = true)
    public ProjectInvitationView preview(UUID id, String code) {
        var value = verified(id, code);
        requirePending(value);
        if (projects.findRole(value.projectId(), value.inviterAccountId()).filter(ProjectRole::canManageMembers).isEmpty()
                || projects.findById(value.projectId()).isEmpty()) throw unavailable();
        return view(value, false);
    }

    @Transactional
    public void accept(UUID id, String code) {
        UUID actor = actor();
        var initial = verified(id, code);
        var recipient = account(actor);
        if (!initial.targetEmail().equals(normalize(recipient.email()))) throw notFound();
        // 统一项目→邀请锁顺序；等待项目锁后重新取得邀请事实，重发/撤回不会沿锁前快照放行。
        management.requireMemberManager(initial.projectId(), initial.inviterAccountId());
        var value = invitations.lock(id, initial.projectId()).orElseThrow(ProjectInvitationService::notFound);
        if (!codes.matches(value, code)) throw notFound();
        requirePending(value);
        // 多邀请交叉接受时按UUID固定顺序锁两端账号，避免A邀请B与B邀请A反向锁账户。
        for (UUID accountId : java.util.stream.Stream.of(actor, value.inviterAccountId()).distinct().sorted().toList()) {
            if (accountId.equals(actor)) {
                if (!accounts.lockVerifiedActive(actor)) throw notFound();
            } else if (!accounts.lockActive(accountId)) throw unavailable();
        }
        if (!value.targetEmail().equals(normalize(account(actor).email()))) throw notFound();
        admission.requireAcceptedInvitationAdmission(value.projectId(), actor, value.inviterAccountId());
        try { projects.addMember(Uuid7.generate(), value.projectId(), actor, value.role()); }
        catch (DuplicateKeyException conflict) { throw new BusinessException(ProjectErrorCode.ALREADY_MEMBER); }
        var accepted = changed(value, ProjectInvitation.Status.ACCEPTED, actor, now());
        if (!invitations.replace(accepted, value.revision())) throw unavailable();
        record(accepted, actor, "project.invitation.accepted");
    }

    @Transactional(readOnly = true)
    public CursorPage<ProjectInvitationView> list(UUID projectId, String cursor, int limit) {
        UUID actor = actor();
        if (projects.findRole(projectId, actor).filter(ProjectRole::canManageMembers).isEmpty()) throw notFound();
        return page(projectId, null, actor, cursor, limit);
    }

    @Transactional(readOnly = true)
    public CursorPage<ProjectInvitationView> inbox(String cursor, int limit) {
        UUID actor = actor();
        if (!accounts.isVerifiedActive(actor)) throw notFound();
        return page(null, normalize(account(actor).email()), actor, cursor, limit);
    }

    private CursorPage<ProjectInvitationView> page(UUID projectId, String email, UUID actor, String cursor, int limit) {
        if (limit < 1 || limit > 50) throw invalid();
        String binding = actor + "|" + projectId + "|" + email + "|" + limit;
        var anchor = cursors.decode(cursor, "PROJECT_INVITATION_PAGE", binding);
        Instant time = anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null);
        UUID id = anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null);
        List<ProjectInvitation> rows = email == null ? invitations.projectPage(projectId, time, id, limit + 1)
                : invitations.inboxPage(email, time, id, limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(row -> view(row, email != null)).toList();
        if (!more) return CursorPage.last(items);
        var last = rows.get(limit - 1);
        return CursorPage.of(items, cursors.encode("PROJECT_INVITATION_PAGE", binding, last.createdAt(), last.id()));
    }
    private ProjectInvitation verified(UUID id, String code) {
        if (id == null) throw notFound();
        var value = invitations.find(id).orElseThrow(ProjectInvitationService::notFound);
        if (!codes.matches(value, code)) throw notFound();
        return value;
    }
    private void requirePending(ProjectInvitation value) { if (!value.pendingAt(now())) throw unavailable(); }
    private AccountRef account(UUID id) {
        return accounts.findByIds(List.of(id)).stream().filter(account -> id.equals(account.id())).findFirst().orElseThrow(ProjectInvitationService::notFound);
    }
    private ProjectInvitationView view(ProjectInvitation value, boolean recipient) {
        boolean pending = value.pendingAt(now());
        String state = value.status() == ProjectInvitation.Status.PENDING && !pending ? "EXPIRED" : value.status().name();
        String name = projects.findById(value.projectId()).map(com.things.link.project.domain.Project::name).orElse("项目不可用");
        return new ProjectInvitationView(value.id(), value.projectId(), name, value.targetEmail(), value.role(), state,
                value.revision(), value.expiresAt(), value.deliveryChannel().name(), value.deliveryStatus().name(), value.createdAt(),
                recipient && pending ? codes.issue(value) : null);
    }
    private ProjectInvitation changed(ProjectInvitation value, ProjectInvitation.Status status, UUID account, Instant acceptedAt) {
        return new ProjectInvitation(value.id(), value.tenantId(), value.projectId(), value.inviterAccountId(), value.targetEmail(),
                value.role(), status, value.revision(), value.codeNonce(), value.expiresAt(), value.deliveryChannel(), value.deliveryStatus(),
                account, acceptedAt, value.createdAt(), now());
    }
    private void dispatch(ProjectInvitation value) {
        if (value.deliveryChannel() == ProjectInvitation.Channel.EMAIL)
            events.publishEvent(new ProjectInvitationDispatchRequested(value.id(), value.revision()));
    }
    private void record(ProjectInvitation value, UUID actor, String action) {
        audit.record(new AuditLogEntry(value.tenantId(), value.projectId(), actor, "project_invitation", value.id(), action,
                Map.of("revision", value.revision(), "role", value.role().name(), "status", value.status().name())));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static UUID actor() {
        UUID id = TenantContext.require().accountId();
        if (id == null) throw notFound();
        return id;
    }
    private static String normalize(String email) {
        if (email == null) throw invalid();
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid();
        return normalized;
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    private static BusinessException notFound() { return new BusinessException(ProjectErrorCode.INVITATION_NOT_FOUND); }
    private static BusinessException unavailable() { return new BusinessException(ProjectErrorCode.INVITATION_UNAVAILABLE); }
}
