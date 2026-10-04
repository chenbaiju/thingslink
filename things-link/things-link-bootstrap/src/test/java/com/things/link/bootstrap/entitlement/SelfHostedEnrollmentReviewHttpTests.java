package com.things.link.bootstrap.entitlement;

import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.issuer.application.EnrollmentRegistry;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 审核权限、单人责任审核、可选第二人一致性和只追加审计的真实 HTTP/PG 回执。 */
@AutoConfigureMockMvc
class SelfHostedEnrollmentReviewHttpTests extends AbstractIntegrationTest {
    private static final String PATH = "/api/v1/operations/self-hosted/enrollment-requests/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateLimiter limiter;
    @Autowired EnrollmentRegistry registry;
    private final List<Session> sessions = new ArrayList<>();
    private final List<UUID> requests = new ArrayList<>();

    @Test
    void oneReviewIsReadyAndOptionalSecondMustAgree() throws Exception {
        Session first = session(), second = session(), third = session();
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID request = Uuid7.generate(), deployment = Uuid7.generate(), claimedTenant = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(request, deployment, claimedTenant,
                key.getPublic(), key.getPrivate());
        registry.register(envelope, EnrollmentRegistry.Channel.OFFLINE);
        requests.add(request);
        var verified = EnrollmentRequestV1.verify(envelope);
        String body = input(deployment, claimedTenant, verified, "STANDARD", "contract:2026-001",
                "a".repeat(64));

        assertThat(mvc.perform(get(PATH + request + "/review")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(review(first, request, body)).isEqualTo(403);
        assertThat(view(first, request)).isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/system/menus")
                .header("Authorization", "Bearer " + first.token())).andReturn()
                .getResponse().getContentAsString()).doesNotContain("SelfHostedReview");
        manage(first.account(), true);
        manage(second.account(), true);
        manage(third.account(), true);

        assertThat(mvc.perform(get("/api/v1/system/menus")
                .header("Authorization", "Bearer " + first.token())).andReturn()
                .getResponse().getContentAsString()).contains("SelfHostedReview")
                .doesNotContain("SelfHostedEnrollment");
        assertThat(mvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + first.token())).andReturn()
                .getResponse().getContentAsString()).contains("self_hosted:review")
                .doesNotContain("commercial:adjust");

        var detail = mvc.perform(get(PATH + request + "/review")
                .header("Authorization", "Bearer " + first.token())).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(detail.getResponse().getContentAsString()).contains("publicKeySha256", "requestSha256")
                .doesNotContain("publicKeySpki", "evidenceSha256");
        assertThat(review(first, request, input(deployment, Uuid7.generate(), verified, "STANDARD",
                "contract:2026-001", "a".repeat(64)))).isEqualTo(409);
        assertThat(review(first, request, body.replace(
                "fd311bd72a22909614f798ebd050f05860bf41c5ddb807ea7af679164ca21fe3",
                "0".repeat(64)))).isEqualTo(409);
        assertThat(count(request)).isZero();

        assertThat(review(first, request, body)).isEqualTo(200);
        var ready = mvc.perform(post(PATH + request + "/review")
                .header("Authorization", "Bearer " + first.token())
                .contentType("application/json").content(body)).andReturn();
        assertThat(ready.getResponse().getContentAsString())
                .contains("\"attestationCount\":1", "\"readyForIssuanceReview\":true");
        assertThat(count(request)).isEqualTo(1);
        assertThat(count(request)).isEqualTo(1);
        assertThat(review(first, request, input(deployment, claimedTenant, verified, "ENTERPRISE",
                "contract:2026-001", "a".repeat(64)))).isEqualTo(409);
        assertThat(review(second, request, input(deployment, claimedTenant, verified, "STANDARD",
                "contract:2026-001", "b".repeat(64)))).isEqualTo(409);
        assertThat(count(request)).isEqualTo(1);

        var completed = mvc.perform(post(PATH + request + "/review")
                .header("Authorization", "Bearer " + second.token())
                .contentType("application/json").content(body)).andReturn();
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        assertThat(completed.getResponse().getContentAsString())
                .contains("\"attestationCount\":2", "\"readyForIssuanceReview\":true");
        assertThat(count(request)).isEqualTo(2);
        assertThat(review(third, request, body)).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE action=? AND target_id=?",
                Integer.class, "shc.enrollment.review.attested", request)).isEqualTo(3);
        manage(first.account(), false);
        assertThat(review(first, request, body)).isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/system/menus")
                .header("Authorization", "Bearer " + first.token())).andReturn()
                .getResponse().getContentAsString()).doesNotContain("SelfHostedReview");
        assertThat(count(request)).isEqualTo(2);
    }

    @Test
    void failedAuditRollsBackAttestation() throws Exception {
        Session reviewer = session();
        manage(reviewer.account(), true);
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID request = Uuid7.generate(), deployment = Uuid7.generate(), tenant = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(request, deployment, tenant,
                key.getPublic(), key.getPrivate());
        registry.register(envelope, EnrollmentRegistry.Channel.ONLINE);
        requests.add(request);
        String body = input(deployment, tenant, EnrollmentRequestV1.verify(envelope), "FREE",
                "contract:2026-002", "c".repeat(64));
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.createStatement().execute("""
                    CREATE FUNCTION public.reject_shc_review_test_audit() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit outage'; END $$
                    """);
            owner.createStatement().execute("""
                    CREATE TRIGGER reject_shc_review_test_audit
                    BEFORE INSERT ON public.sys_audit_log FOR EACH ROW
                    WHEN (NEW.action = 'shc.enrollment.review.attested')
                    EXECUTE FUNCTION public.reject_shc_review_test_audit()
                    """);
            try {
                assertThat(review(reviewer, request, body)).isEqualTo(500);
                assertThat(count(request)).isZero();
            } finally {
                owner.createStatement().execute("DROP TRIGGER reject_shc_review_test_audit ON public.sys_audit_log");
                owner.createStatement().execute("DROP FUNCTION public.reject_shc_review_test_audit()");
            }
        }
        assertThat(review(reviewer, request, body)).isEqualTo(200);
        assertThat(count(request)).isEqualTo(1);
    }

    @Test
    void applicationDatabaseRoleCannotManageReviewersDirectly() {
        assertThatThrownBy(() -> jdbc.queryForObject(
                "SELECT result_version FROM shc_reviewer_manage(?,?,?,?)", Long.class,
                Uuid7.generate(), Uuid7.generate(), true, "越权探针"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.queryForObject(
                "SELECT count(*) FROM sys_shc_reviewer", Integer.class))
                .rootCause().hasMessageContaining("permission denied");
    }

    private String input(UUID deployment, UUID tenant, EnrollmentRequestV1.Verified verified,
                         String tier, String organization, String evidenceDigest) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "deploymentId", deployment, "tenantId", tenant,
                "publicKeySha256", HexFormat.of().formatHex(verified.publicKeySha256()),
                "requestSha256", HexFormat.of().formatHex(verified.requestSha256()),
                "organizationReference", organization, "evidenceSha256", evidenceDigest,
                "tier", tier, "revisionId", "self-hosted-product-revision-1",
                "revisionSha256", "fd311bd72a22909614f798ebd050f05860bf41c5ddb807ea7af679164ca21fe3"));
    }

    private int count(UUID request) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_shc_review_attestation WHERE request_id=?",
                Integer.class, request);
    }

    private int review(Session reviewer, UUID request, String body) throws Exception {
        return mvc.perform(post(PATH + request + "/review")
                .header("Authorization", "Bearer " + reviewer.token())
                .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
    }

    private int view(Session reviewer, UUID request) throws Exception {
        return mvc.perform(get(PATH + request + "/review")
                .header("Authorization", "Bearer " + reviewer.token())).andReturn().getResponse().getStatus();
    }

    private Session session() throws Exception {
        limiter.clear();
        String email = "shc-review-" + UUID.randomUUID() + "@example.com";
        String body = JSON.writeValueAsString(Map.of("email", email,
                "password", "correct-horse-battery-staple"));
        var registration = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json").content(body)).andReturn();
        assertThat(registration.getResponse().getStatus()).isIn(200, 201, 204);
        jdbc.update("UPDATE sys_account SET email_verified_at=now() WHERE email=?", email);
        var login = mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json").content(body)).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        UUID account = jdbc.queryForObject("SELECT id FROM sys_account WHERE email=?", UUID.class, email);
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM sys_tenant_member WHERE account_id=?",
                UUID.class, account);
        String token = JSON.readTree(login.getResponse().getContentAsByteArray())
                .get("accessToken").asString();
        Session result = new Session(account, tenant, token);
        sessions.add(result);
        return result;
    }

    private static void manage(UUID account, boolean enabled) throws Exception {
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.createStatement().execute("DO $$ BEGIN IF NOT EXISTS "
                    + "(SELECT 1 FROM pg_roles WHERE rolname='shc_review_test_admin') "
                    + "THEN CREATE ROLE shc_review_test_admin LOGIN PASSWORD 'test-only'; END IF; END $$");
            owner.createStatement().execute("GRANT thingslink_shc_review_admin TO shc_review_test_admin");
        }
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "shc_review_test_admin", "test-only");
             var query = admin.prepareStatement("SELECT result_version FROM shc_reviewer_manage(?,?,?,?)")) {
            query.setObject(1, Uuid7.generate());
            query.setObject(2, account);
            query.setBoolean(3, enabled);
            query.setString(4, "自部署申请审核验收");
            try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        for (Session session : sessions) manage(session.account(), false);
        // 共享测试容器内只清理由本类创建的不可变夹具；正式环境没有该管理路径。
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.setAutoCommit(false);
            owner.createStatement().execute("ALTER TABLE sys_shc_review_attestation DISABLE TRIGGER sys_shc_review_attestation_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_reviewer_operation DISABLE TRIGGER sys_shc_reviewer_operation_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request DISABLE TRIGGER sys_shc_enrollment_request_immutable");
            try {
                for (UUID request : requests) {
                    delete(owner, "DELETE FROM sys_shc_review_attestation WHERE request_id=?", request);
                    delete(owner, "DELETE FROM sys_shc_enrollment_request WHERE request_id=?", request);
                }
                for (Session session : sessions) {
                    delete(owner, "DELETE FROM sys_shc_reviewer_operation WHERE account_id=?", session.account());
                    delete(owner, "DELETE FROM sys_shc_reviewer WHERE account_id=?", session.account());
                    delete(owner, "DELETE FROM sys_tenant_member WHERE tenant_id=?", session.tenant());
                    delete(owner, "DELETE FROM sys_account WHERE id=?", session.account());
                    delete(owner, "DELETE FROM sys_tenant WHERE id=?", session.tenant());
                }
            } finally {
                owner.createStatement().execute("ALTER TABLE sys_shc_review_attestation ENABLE TRIGGER sys_shc_review_attestation_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_reviewer_operation ENABLE TRIGGER sys_shc_reviewer_operation_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request ENABLE TRIGGER sys_shc_enrollment_request_immutable");
            }
            owner.commit();
        }
    }

    private static void delete(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private record Session(UUID account, UUID tenant, String token) { }
}
