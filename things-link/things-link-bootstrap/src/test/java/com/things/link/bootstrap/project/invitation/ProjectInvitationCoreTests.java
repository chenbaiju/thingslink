package com.things.link.bootstrap.project.invitation;

import com.things.link.project.application.ProjectInvitationService;
import com.things.link.project.application.ProjectInvitationView;
import com.things.link.project.application.ProjectInvitationCodes;
import com.things.link.project.application.ProjectMemberService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.ProjectInvitationRepository;
import com.things.link.project.domain.ProjectInvitation;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** 专属普通APP数据库：邀请码消费、成员、席位和审计的真实事务边界。 */
@Import(ProjectInvitationCoreTests.Configuration.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@OwnedTestContainers({"DATABASE"})
class ProjectInvitationCoreTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("invitation_core").withUsername("thingslink").withPassword("thingslink");
    private static final String URL = start();
    private static String start() { DATABASE.start(); return DATABASE.getJdbcUrl(); }
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") ApplicationRunner ignoredSharedQuota;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.things.link.iam.application.ProjectInvitationMailer invitationMailer;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.things.link.iam.infrastructure.security.JwtTokenIssuer issuer;
    @Autowired com.things.link.iam.application.AuthRateLimiter authLimits;
    @Autowired com.things.link.project.application.ProjectInvitationDeliveryService deliveries;
    @MockitoBean com.things.link.iam.application.EmailVerificationMailer verificationMailer;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TenantProvisioning tenants;
    @Autowired ProjectInvitationService service;
    @Autowired ProjectInvitationRepository invitations;
    @Autowired ProjectInvitationCodes codes;
    @Autowired ProjectMemberService members;
    @Autowired MutableClock clock;
    @Autowired com.things.link.project.application.ProjectCleanupAdmissionService cleanupAdmission;
    @Autowired com.things.link.project.application.ProjectCleanupBatchService cleanupBatches;
    private UUID tenant, foreignTenant, manager, recipient, project;

    @BeforeEach void setup() {
        clock.value = Instant.now();
        authLimits.clear();
        org.mockito.Mockito.doNothing().when(mailerSpy()).onRequested(org.mockito.ArgumentMatchers.any());
        tenant = tx.execute(s -> tenants.createTenant("邀请测试项目租户"));
        foreignTenant = tx.execute(s -> tenants.createTenant("邀请测试协作租户"));
        manager = account(tenant, true); recipient = account(foreignTenant, true); project = project();
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD') WHERE id=?", tenant);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("invitation_core");
    }
    private UUID account(UUID home, boolean verified) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at) VALUES (?,?,'test','邀请测试','ACTIVE',?)",
                id, email(id), verified ? java.sql.Timestamp.from(clock.instant()) : null);
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), home, id);
        return id;
    }
    private UUID project() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'邀请项目','sh-1',?)", id, tenant, "invite" + id.toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')", UUID.randomUUID(), id, manager);
        return id;
    }
    private String email(UUID id) { return id + "@example.test"; }
    private <T> T as(UUID actor, Supplier<T> action) {
        TenantContext.set(new TenantScope(actor.equals(manager) ? tenant : foreignTenant, null, actor));
        try { return action.get(); } finally { TenantContext.clear(); }
    }
    private ProjectInvitationView create(UUID pid, UUID target) { return as(manager, () -> service.create(pid, email(target), ProjectRole.VIEWER)); }
    private String code(UUID id) { return codes.issue(invitations.find(id).orElseThrow()); }
    private void accept(UUID actor, UUID id, String code) { as(actor, () -> { service.accept(id, code); return null; }); }
    private long membership(UUID target) { return jdbc.queryForObject("SELECT count(*) FROM sys_project_member WHERE account_id=?", Long.class, target); }
    private void refused(Runnable action, int code) {
        var failure = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(failure).isNotNull(); assertThat(failure.errorCode().code()).isEqualTo(code);
    }

    @Test void creationPreviewAndInboxDoNotCreateMembershipAndAcceptanceIsSingleUse() {
        var invitation = create(project, recipient);
        assertThat(invitation.code()).isNull(); assertThat(invitation.deliveryStatus()).isEqualTo("INBOX");
        assertThat(membership(recipient)).isZero();
        var inbox = as(recipient, () -> service.inbox(null, 20));
        assertThat(inbox.items()).hasSize(1);
        String token = inbox.items().getFirst().code();
        assertThat(service.preview(invitation.id(), token).targetEmail()).isEqualTo(email(recipient));
        assertThat(membership(recipient)).isZero();
        accept(recipient, invitation.id(), token);
        assertThat(membership(recipient)).isEqualTo(1);
        assertThat(invitations.find(invitation.id()).orElseThrow().status()).isEqualTo(ProjectInvitation.Status.ACCEPTED);
        refused(() -> accept(recipient, invitation.id(), token), 50057);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='project.invitation.accepted'", Long.class, invitation.id())).isEqualTo(1);
    }

    @Test void wrongUnverifiedAndDisabledRecipientsCannotJoinOrReadCodes() {
        var invitation = create(project, recipient); String token = code(invitation.id());
        UUID wrong = account(foreignTenant, true);
        refused(() -> accept(wrong, invitation.id(), token), 50056);
        assertThat(as(wrong, () -> service.inbox(null, 20)).items()).isEmpty();
        jdbc.update("UPDATE sys_account SET email_verified_at=NULL WHERE id=?", recipient);
        refused(() -> as(recipient, () -> service.inbox(null, 20)), 50056);
        refused(() -> accept(recipient, invitation.id(), token), 50056);
        jdbc.update("UPDATE sys_account SET email_verified_at=now(),status='DISABLED' WHERE id=?", recipient);
        refused(() -> accept(recipient, invitation.id(), token), 50056);
        assertThat(membership(recipient)).isZero();
    }

    @Test void resendInvalidatesOldCodeAndOldDeliveryResultAndRevokeWins() {
        UUID unknown = UUID.randomUUID();
        var invitation = create(project, unknown); String old = code(invitation.id());
        assertThat(invitation.deliveryStatus()).isEqualTo("QUEUED");
        refused(() -> as(manager, () -> service.resend(project, invitation.id())), 10029);
        clock.value = clock.value.plusSeconds(61);
        var resent = as(manager, () -> service.resend(project, invitation.id()));
        assertThat(resent.revision()).isEqualTo(2);
        refused(() -> service.preview(invitation.id(), old), 50056);
        assertThat(invitations.recordDelivery(invitation.id(), 1, ProjectInvitation.Delivery.SENT)).isFalse();
        assertThat(invitations.recordDelivery(invitation.id(), 2, ProjectInvitation.Delivery.FAILED)).isTrue();
        String current = code(invitation.id());
        as(manager, () -> { service.revoke(project, invitation.id()); return null; });
        refused(() -> service.preview(invitation.id(), current), 50057);
    }

    @Test void expiryAndInviterRevocationAreRecheckedAtAcceptance() {
        var invitation = create(project, recipient); String token = code(invitation.id());
        clock.value = clock.value.plusSeconds(7 * 86400L);
        refused(() -> accept(recipient, invitation.id(), token), 50057);
        clock.value = clock.value.minusSeconds(7 * 86400L);
        jdbc.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", project, manager);
        refused(() -> accept(recipient, invitation.id(), token), 50002);
        assertThat(membership(recipient)).isZero();
    }

    @Test void duplicatePendingAndOldImmediateAddDoNotChangeInvitationSemantics() {
        var invitation = create(project, recipient); String token = code(invitation.id());
        refused(() -> create(project, recipient), 50058);
        as(manager, () -> members.invite(project, email(recipient), ProjectRole.OPERATOR));
        refused(() -> accept(recipient, invitation.id(), token), 50011);
        assertThat(invitations.find(invitation.id()).orElseThrow().status()).isEqualTo(ProjectInvitation.Status.PENDING);
        assertThat(jdbc.queryForObject("SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", String.class, project, recipient)).isEqualTo("OPERATOR");
    }

    @Test void rollbackRestoresBothPendingInvitationAndAvailableSeat() {
        var invitation = create(project, recipient); String token = code(invitation.id());
        as(recipient, () -> tx.execute(status -> { service.accept(invitation.id(), token); status.setRollbackOnly(); return null; }));
        assertThat(membership(recipient)).isZero();
        assertThat(invitations.find(invitation.id()).orElseThrow().status()).isEqualTo(ProjectInvitation.Status.PENDING);
        accept(recipient, invitation.id(), token);
        assertThat(membership(recipient)).isEqualTo(1);
    }

    @Test void concurrentAcceptanceCreatesExactlyOneMember() throws Exception {
        var invitation = create(project, recipient); String token = code(invitation.id());
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> raceAccept(barrier, recipient, invitation.id(), token));
            var second = pool.submit(() -> raceAccept(barrier, recipient, invitation.id(), token));
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 50057);
        }
        assertThat(membership(recipient)).isEqualTo(1);
    }
    @Test void concurrentDifferentProjectsCannotOversubscribeFinalSeat() throws Exception {
        for (int i = 0; i < 4; i++) {
            UUID existing = account(foreignTenant, true);
            as(manager, () -> members.invite(project, email(existing), ProjectRole.VIEWER));
        }
        UUID other = account(foreignTenant, true), second = project();
        var a = create(project, recipient); var b = create(second, other);
        String ca = code(a.id()), cb = code(b.id()); var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> raceAccept(barrier, recipient, a.id(), ca));
            var next = pool.submit(() -> raceAccept(barrier, other, b.id(), cb));
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), next.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 50049);
        }
        assertThat(membership(recipient) + membership(other)).isEqualTo(1);
        assertThat(List.of(invitations.find(a.id()).orElseThrow().status(), invitations.find(b.id()).orElseThrow().status()))
                .containsExactlyInAnyOrder(ProjectInvitation.Status.ACCEPTED, ProjectInvitation.Status.PENDING);
    }
    @Test void disabledInviterAndArchivedProjectCannotGrantMembership() {
        var invitation = create(project, recipient); String token = code(invitation.id());
        jdbc.update("UPDATE sys_account SET status='DISABLED' WHERE id=?", manager);
        refused(() -> accept(recipient, invitation.id(), token), 50057);
        jdbc.update("UPDATE sys_account SET status='ACTIVE' WHERE id=?", manager);
        jdbc.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        refused(() -> accept(recipient, invitation.id(), token), 50017);
        assertThat(membership(recipient)).isZero();
    }

    @Test void controlledCleanupDrainsInvitationsInBoundedBatchesAndPreservesNeighbor() {
        var base = create(project, recipient);
        UUID neighbor = project(); var retained = create(neighbor, UUID.randomUUID());
        var owner = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(URL, "thingslink", "thingslink"));
        owner.update("""
                INSERT INTO sys_project_invitation(id,tenant_id,project_id,inviter_account_id,target_email,role,status,
                    revision,code_nonce,expires_at,delivery_channel,delivery_status,created_at,updated_at)
                SELECT gen_random_uuid(),tenant_id,project_id,inviter_account_id,'cleanup'||n||'@example.test',role,status,
                    revision,gen_random_uuid(),expires_at,delivery_channel,delivery_status,created_at,updated_at
                  FROM sys_project_invitation CROSS JOIN generate_series(1,500) n WHERE id=?
                """, base.id());
        owner.update("UPDATE sys_project SET status='DELETING',deleted_at=clock_timestamp()-interval '31 days',"
                + "lifecycle_generation=lifecycle_generation+1 WHERE id=?", project);
        var claim = cleanupAdmission.claimNext().orElseThrow();
        int phases = 0;
        while (!claim.stage().equals("PROJECT")) {
            assertThat(++phases).isLessThan(20);
            assertThat(cleanupBatches.execute(claim).orElseThrow().complete()).isTrue();
            claim = cleanupAdmission.claimNext().orElseThrow();
        }
        assertThat(cleanupBatches.execute(claim).orElseThrow().deletedRows()).isEqualTo(500);
        claim = cleanupAdmission.claimNext().orElseThrow();
        assertThat(cleanupBatches.execute(claim).orElseThrow().deletedRows()).isEqualTo(2);
        claim = cleanupAdmission.claimNext().orElseThrow();
        assertThat(cleanupBatches.execute(claim).orElseThrow().complete()).isTrue();
        claim = cleanupAdmission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("FINALIZE");
        assertThat(cleanupBatches.execute(claim).orElseThrow().complete()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_invitation WHERE project_id=?", Long.class, project)).isZero();
        assertThat(invitations.find(retained.id())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_account WHERE id=?", Long.class, recipient)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_project WHERE id=?", String.class, project)).isEqualTo("PURGED");
    }

    @Test void httpProofInboxAcceptanceAndReplayKeepRealIdentityAndNoStore() throws Exception {
        var created = http("POST", "/api/v1/projects/" + project + "/invitations", manager,
                java.util.Map.of("email", email(recipient), "role", "VIEWER"), 200);
        UUID id = UUID.fromString(created.path("id").asString());
        assertThat(created.path("code").isNull() || created.path("code").isMissingNode()).isTrue();
        http("GET", "/api/v1/project-invitations", null, null, 401);
        var inbox = http("GET", "/api/v1/project-invitations", recipient, null, 200);
        String code = inbox.path("items").get(0).path("code").asString();
        http("POST", "/api/v1/auth/project-invitation/preview", null,
                java.util.Map.of("invitationId", id, "code", code), 200);
        assertThat(membership(recipient)).isZero();
        UUID stranger = account(foreignTenant, true);
        http("POST", "/api/v1/project-invitations/" + id + "/accept", stranger, java.util.Map.of("code", code), 404);
        http("POST", "/api/v1/project-invitations/" + id + "/accept", recipient, java.util.Map.of("code", code), 204);
        var replay = http("POST", "/api/v1/project-invitations/" + id + "/accept", recipient, java.util.Map.of("code", code), 409);
        assertThat(replay.path("code").asInt()).isEqualTo(50057);
        assertThat(membership(recipient)).isEqualTo(1);
        http("GET", "/api/v1/projects/" + project + "/invitations", recipient, null, 404);
        http("GET", "/api/v1/project-invitations?limit=1&limit=2", recipient, null, 400);
    }

    @Test void httpRegistrationCannotSubstituteEmailOrSkipMailboxVerification() throws Exception {
        String target = UUID.randomUUID() + "@example.test";
        var invitation = as(manager, () -> service.create(project, target, ProjectRole.OPERATOR));
        var body = new java.util.HashMap<String, Object>();
        body.put("invitationId", invitation.id()); body.put("code", code(invitation.id()));
        body.put("email", "substituted@example.test"); body.put("password", "invitation-secure-test-password");
        http("POST", "/api/v1/auth/project-invitation/register", null, body, 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_account WHERE email=?", Long.class, "substituted@example.test")).isZero();
        body.put("email", target);
        http("POST", "/api/v1/auth/project-invitation/register", null, body, 204);
        UUID registered = jdbc.queryForObject("SELECT id FROM sys_account WHERE email=?", UUID.class, target);
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM sys_account WHERE id=?", Boolean.class, registered)).isTrue();
        assertThat(membership(registered)).isZero();
        assertThat(invitations.find(invitation.id()).orElseThrow().status()).isEqualTo(ProjectInvitation.Status.PENDING);
        // 使用新注册者的真实租户签JWT也不能绕过邮箱验证。
        http("POST", "/api/v1/project-invitations/" + invitation.id() + "/accept", registered,
                java.util.Map.of("code", code(invitation.id())), 404);
        jdbc.update("UPDATE sys_account SET email_verified_at=now() WHERE id=?", registered);
        http("POST", "/api/v1/project-invitations/" + invitation.id() + "/accept", registered,
                java.util.Map.of("code", code(invitation.id())), 204);
        assertThat(membership(registered)).isEqualTo(1);
    }

    @Test void httpManagementResendAndRevokeRejectOldProofAndWrongManagers() throws Exception {
        var invitation = create(project, recipient);
        String token = code(invitation.id());
        http("POST", "/api/v1/projects/" + project + "/invitations/" + invitation.id() + "/resend", recipient, null, 404);
        clock.value = clock.value.plusSeconds(61);
        http("POST", "/api/v1/projects/" + project + "/invitations/" + invitation.id() + "/resend", manager, null, 200);
        http("POST", "/api/v1/auth/project-invitation/preview", null,
                java.util.Map.of("invitationId", invitation.id(), "code", token), 404);
        token = code(invitation.id());
        http("DELETE", "/api/v1/projects/" + project + "/invitations/" + invitation.id(), manager, null, 204);
        http("POST", "/api/v1/auth/project-invitation/preview", null,
                java.util.Map.of("invitationId", invitation.id(), "code", token), 409);
    }

    @Test void anonymousProofHasBoundedSourceRateAndDoesNotEnumerateWithoutCode() throws Exception {
        var invitation = create(project, recipient);
        var proof = java.util.Map.of("invitationId", invitation.id(), "code", "x".repeat(43));
        for (int count = 0; count < 30; count++) {
            var result = http("POST", "/api/v1/auth/project-invitation/preview", null, proof, 404);
            assertThat(result.path("code").asInt()).isEqualTo(50056);
            assertThat(result.toString()).doesNotContain(email(recipient));
        }
        var throttled = http("POST", "/api/v1/auth/project-invitation/preview", null, proof, 429);
        assertThat(throttled.path("code").asInt()).isEqualTo(10029);
        assertThat(membership(recipient)).isZero();
    }

    @Test void invitationCursorCannotMoveBetweenRecipientOrPageSize() {
        create(project, recipient); create(project(), recipient);
        var first = as(recipient, () -> service.inbox(null, 1));
        assertThat(first.hasMore()).isTrue();
        var next = as(recipient, () -> service.inbox(first.nextCursor(), 1));
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());
        UUID stranger = account(foreignTenant, true);
        refused(() -> as(stranger, () -> service.inbox(first.nextCursor(), 1)), 10001);
        refused(() -> as(recipient, () -> service.inbox(first.nextCursor(), 2)), 10001);
    }

    @Test void acceptAndRevokeHaveOnlyOneWinner() throws Exception {
        var invitation = create(project, recipient); String token = code(invitation.id());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var accepted = executor.submit(() -> raceAccept(barrier, recipient, invitation.id(), token));
            var revoked = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { as(manager, () -> { service.revoke(project, invitation.id()); return null; }); return 0; }
                catch (BusinessException failure) { return failure.errorCode().code(); }
            });
            assertThat(List.of(accepted.get(15, TimeUnit.SECONDS), revoked.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 50057);
        }
        var state = invitations.find(invitation.id()).orElseThrow().status();
        assertThat(state).isIn(ProjectInvitation.Status.ACCEPTED, ProjectInvitation.Status.REVOKED);
        assertThat(membership(recipient)).isEqualTo(state == ProjectInvitation.Status.ACCEPTED ? 1 : 0);
    }

    @Test void notificationIsAfterCommitOnDedicatedExecutorAndRollbackNeverDispatches() throws Exception {
        org.mockito.Mockito.reset(mailerSpy());
        var dispatched = new java.util.concurrent.CompletableFuture<String>();
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            dispatched.complete(Thread.currentThread().getName());
            return null;
        }).when(mailerSpy()).onRequested(org.mockito.ArgumentMatchers.any());
        as(manager, () -> tx.execute(status -> {
            service.create(project, email(UUID.randomUUID()), ProjectRole.VIEWER);
            org.mockito.Mockito.verifyNoInteractions(mailerSpy());
            status.setRollbackOnly(); return null;
        }));
        assertThat(dispatched).isNotDone();
        as(manager, () -> tx.execute(status -> {
            service.create(project, email(UUID.randomUUID()), ProjectRole.VIEWER);
            org.mockito.Mockito.verifyNoInteractions(mailerSpy()); return null;
        }));
        assertThat(dispatched.get(5, TimeUnit.SECONDS)).startsWith("mail-");
    }

    @Test void deliveryRevalidatesRevisionAndNeverSendsRevokedInvitation() {
        var value = create(project, UUID.randomUUID());
        assertThat(deliveries.prepare(value.id(), value.revision())).isPresent();
        assertThat(deliveries.prepare(value.id(), value.revision() + 1)).isEmpty();
        as(manager, () -> { service.revoke(project, value.id()); return null; });
        assertThat(deliveries.prepare(value.id(), value.revision())).isEmpty();
        deliveries.complete(value.id(), value.revision(), true);
        assertThat(invitations.find(value.id()).orElseThrow().deliveryStatus()).isEqualTo(ProjectInvitation.Delivery.QUEUED);
    }

    private tools.jackson.databind.JsonNode http(String method, String path, UUID actor, Object body, int expected) throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (actor != null) {
            UUID home = jdbc.queryForObject("SELECT tenant_id FROM sys_tenant_member WHERE account_id=? LIMIT 1", UUID.class, actor);
            String token = issuer.issue(new com.things.link.iam.application.AuthenticatedPrincipal(actor, home, null)).value();
            request.header("Authorization", "Bearer " + token);
        }
        // 同键重复接受必须进入领域重新判断，不能由公共幂等层重放旧成功。
        if (path.endsWith("/accept")) request.header("Idempotency-Key", "invitation-accept-test");
        if (body != null) request.content(json.writeValueAsString(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getStatus()).as("%s %s body=%s", method, path, response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isBlank() ? json.createObjectNode() : json.readTree(response.getContentAsString());
    }

    private com.things.link.iam.application.ProjectInvitationMailer mailerSpy() {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(invitationMailer);
    }

    private int raceAccept(CyclicBarrier barrier, UUID actor, UUID id, String token) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try { accept(actor, id, token); return 0; } catch (BusinessException failure) { return failure.errorCode().code(); }
    }

    static class MutableClock extends Clock {
        volatile Instant value = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean(name = "projectInvitationClock") MutableClock invitationClock() { return new MutableClock(); }
        @Bean DynamicPropertyRegistrar invitationDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> URL);
                registry.add("spring.flyway.url", () -> URL);
                registry.add("spring.flyway.user", DATABASE::getUsername);
                registry.add("spring.flyway.password", DATABASE::getPassword);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }
}
