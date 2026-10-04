package com.things.link.bootstrap.entitlement;

import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 发行方运营入口：真实认证、数据库权限、有限正文、待审登记与操作审计。 */
@AutoConfigureMockMvc
class SelfHostedEnrollmentIntakeHttpTests extends AbstractIntegrationTest {
    private static final String PATH = "/api/v1/operations/self-hosted/enrollment-requests";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateLimiter limiter;
    private final List<Session> sessions = new ArrayList<>();

    @Test
    void operatorIntakeIsPendingAuditedAndIdempotentAcrossDeclaredChannels() throws Exception {
        Session operator = session();
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID requestId = Uuid7.generate(), deployment = Uuid7.generate(), tenant = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(requestId, deployment, tenant,
                key.getPublic(), key.getPrivate());

        assertThat(submit(null, "OFFLINE", envelope).getResponse().getStatus()).isEqualTo(401);
        assertThat(submit(operator.token(), "OFFLINE", envelope).getResponse().getStatus()).isEqualTo(403);
        assertThat(rows(deployment)).isZero();
        manage(operator.account(), true);

        MvcResult first = submitWithKey(operator.token(), "OFFLINE", envelope);
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(first.getResponse().getContentAsString()).contains(requestId.toString(), "PENDING", "OFFLINE")
                .doesNotContain("publicKeySha256", "requestSha256");
        MvcResult replay = submitWithKey(operator.token(), "ONLINE", envelope);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(replay.getResponse().getContentAsByteArray()))
                .isEqualTo(JSON.readTree(first.getResponse().getContentAsByteArray()));
        assertThat(rows(deployment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE action=? AND target_id=? "
                + "AND actor_account_id=?", Integer.class,
                "shc.enrollment.request.received", requestId, operator.account())).isEqualTo(2);

        byte[] conflict = EnrollmentRequestV1.create(Uuid7.generate(), deployment, tenant,
                key.getPublic(), key.getPrivate());
        assertThat(submit(operator.token(), "ONLINE", conflict).getResponse().getStatus()).isEqualTo(409);
        assertThat(rows(deployment)).isEqualTo(1);
        manage(operator.account(), false);
        assertThat(submit(operator.token(), "ONLINE", envelope).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void malformedAndOversizedRequestsNeverCreatePendingFacts() throws Exception {
        Session operator = session();
        manage(operator.account(), true);
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID deployment = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(Uuid7.generate(), deployment, Uuid7.generate(),
                key.getPublic(), key.getPrivate());
        byte[] badSignature = envelope.clone();
        badSignature[badSignature.length - 1] ^= 1;
        assertThat(submit(operator.token(), "OFFLINE", badSignature).getResponse().getStatus()).isEqualTo(400);
        assertThat(submit(operator.token(), "invalid", envelope).getResponse().getStatus()).isEqualTo(400);
        byte[] oversized = Arrays.copyOf(envelope, EnrollmentRequestV1.MAX_ENVELOPE_BYTES + 1);
        assertThat(submit(operator.token(), "OFFLINE", oversized).getResponse().getStatus()).isEqualTo(413);
        assertThat(rows(deployment)).isZero();
    }

    @Test
    void auditInsertFailureRollsBackThePendingRegistration() throws Exception {
        Session operator = session();
        manage(operator.account(), true);
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID deployment = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(Uuid7.generate(), deployment, Uuid7.generate(),
                key.getPublic(), key.getPrivate());
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.createStatement().execute("""
                    CREATE FUNCTION public.reject_shc_intake_test_audit() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit outage'; END $$
                    """);
            owner.createStatement().execute("""
                    CREATE TRIGGER reject_shc_intake_test_audit
                    BEFORE INSERT ON public.sys_audit_log FOR EACH ROW
                    WHEN (NEW.action = 'shc.enrollment.request.received')
                    EXECUTE FUNCTION public.reject_shc_intake_test_audit()
                    """);
            try {
                assertThat(submit(operator.token(), "OFFLINE", envelope).getResponse().getStatus())
                        .isEqualTo(500);
                assertThat(rows(deployment)).isZero();
            } finally {
                owner.createStatement().execute("DROP TRIGGER reject_shc_intake_test_audit "
                        + "ON public.sys_audit_log");
                owner.createStatement().execute("DROP FUNCTION public.reject_shc_intake_test_audit()");
            }
        }
    }

    @Test
    void pendingQueueIsOperatorOnlyBoundedAndStableAcrossPages() throws Exception {
        Session operator = session();
        assertThat(mvc.perform(get(PATH)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(PATH).header("Authorization", "Bearer " + operator.token()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        manage(operator.account(), true);

        UUID[] requests = new UUID[3];
        for (int i = 0; i < requests.length; i++) {
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            requests[i] = Uuid7.generate();
            byte[] envelope = EnrollmentRequestV1.create(requests[i], Uuid7.generate(),
                    Uuid7.generate(), key.getPublic(), key.getPrivate());
            assertThat(submit(operator.token(), "OFFLINE", envelope).getResponse().getStatus()).isEqualTo(200);
        }

        MvcResult first = mvc.perform(get(PATH).header("Authorization", "Bearer " + operator.token())
                .param("limit", "2")).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        var page = JSON.readTree(first.getResponse().getContentAsByteArray());
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.toString()).doesNotContain("publicKey", "requestSha256");
        assertThat(page.toString()).contains(requests[2].toString(), requests[1].toString());
        MvcResult second = mvc.perform(get(PATH).header("Authorization", "Bearer " + operator.token())
                .param("limit", "2")
                .param("beforeReceivedAt", page.get(1).get("receivedAt").asString())
                .param("beforeRequestId", page.get(1).get("requestId").asString())).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getContentAsString()).contains(requests[0].toString())
                .doesNotContain(requests[1].toString(), requests[2].toString());
        assertThat(mvc.perform(get(PATH).header("Authorization", "Bearer " + operator.token())
                .param("limit", "51")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get(PATH).header("Authorization", "Bearer " + operator.token())
                .param("beforeRequestId", requests[0].toString())).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE action=? "
                + "AND actor_account_id=?", Integer.class, "shc.enrollment.queue.viewed", operator.account()))
                .isEqualTo(2);
        manage(operator.account(), false);
        assertThat(mvc.perform(get(PATH).header("Authorization", "Bearer " + operator.token()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    private int rows(UUID deployment) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_shc_enrollment_request WHERE deployment_id=?",
                Integer.class, deployment);
    }

    private MvcResult submit(String token, String source, byte[] body) throws Exception {
        var request = post(PATH).contentType("application/octet-stream")
                .header("X-Enrollment-Source", source).content(body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    private MvcResult submitWithKey(String token, String source, byte[] body) throws Exception {
        return mvc.perform(post(PATH).contentType("application/octet-stream")
                .header("X-Enrollment-Source", source)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "same-operator-retry")
                .content(body)).andReturn();
    }

    private Session session() throws Exception {
        limiter.clear();
        String email = "shc-intake-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}";
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
                    + "(SELECT 1 FROM pg_roles WHERE rolname='commercial_test_admin') "
                    + "THEN CREATE ROLE commercial_test_admin LOGIN PASSWORD 'test-only'; END IF; END $$");
            owner.createStatement().execute("GRANT thingslink_commercial_admin TO commercial_test_admin");
        }
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "commercial_test_admin", "test-only");
             var query = admin.prepareStatement("SELECT result_version FROM commercial_operator_manage(?,?,?,?)")) {
            query.setObject(1, Uuid7.generate());
            query.setObject(2, account);
            query.setBoolean(3, enabled);
            query.setString(4, "自部署待审申请验收");
            try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        for (Session session : sessions) {
            manage(session.account(), false);
            jdbc.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", session.tenant());
            jdbc.update("DELETE FROM sys_account WHERE id=?", session.account());
            jdbc.update("DELETE FROM sys_tenant WHERE id=?", session.tenant());
        }
    }

    private record Session(UUID account, UUID tenant, String token) { }
}
