package com.things.link.bootstrap.entitlement;

import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 显式运行的跨进程联调：真实 jagonzn JAR 申请文件进入发行方待审链。 */
@AutoConfigureMockMvc
class SelfHostedEnrollmentCrossProcessIT extends AbstractIntegrationTest {
    private static final String PATH = "/api/v1/operations/self-hosted/enrollment-requests";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateLimiter limiter;
    @TempDir Path temporary;
    private Session operator;

    @Test
    void actualJagonznJarProducesOnePendingFactAcrossChannelsAndRejectsBadOperations() throws Exception {
        Path jar = Path.of(System.getProperty("jagonzn.enrollment.jar",
                "missing-jagonzn-jar-property")).toAbsolutePath();
        assertThat(jar).as("先构建本工作树的真实 jagonzn JAR，并提供 -Djagonzn.enrollment.jar")
                .isRegularFile();
        UUID tenant = Uuid7.generate();
        Path identity = temporary.resolve("identity");
        runJar(jar, identity, tenant);
        byte[] envelope = Files.readAllBytes(identity.resolve("enrollment-request.tcshreq"));
        var verified = EnrollmentRequestV1.verify(envelope);
        runJar(jar, identity, tenant);
        assertThat(Files.readAllBytes(identity.resolve("enrollment-request.tcshreq")))
                .isEqualTo(envelope);

        operator = session();
        assertThat(submit(null, "OFFLINE", envelope)).isEqualTo(401);
        assertThat(submit(operator.token(), "OFFLINE", envelope)).isEqualTo(403);
        assertThat(count(verified.requestId())).isZero();
        manage(operator.account(), true);
        assertThat(submit(operator.token(), "OFFLINE", envelope)).isEqualTo(200);
        assertThat(submit(operator.token(), "ONLINE", envelope)).isEqualTo(200);
        assertThat(count(verified.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT channel FROM sys_shc_enrollment_request WHERE request_id=?",
                String.class, verified.requestId())).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE action=? AND target_id=?",
                Integer.class, "shc.enrollment.request.received", verified.requestId())).isEqualTo(2);
        var page = mvc.perform(get(PATH).header("Authorization", "Bearer " + operator.token())
                .param("limit", "50")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getResponse().getContentAsString()).contains(verified.requestId().toString())
                .doesNotContain("publicKeySha256", "requestSha256");

        var another = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] collision = EnrollmentRequestV1.create(Uuid7.generate(), verified.deploymentId(), tenant,
                another.getPublic(), another.getPrivate());
        assertThat(submit(operator.token(), "ONLINE", collision)).isEqualTo(409);
        assertThat(count(verified.requestId())).isEqualTo(1);
        manage(operator.account(), false);
        assertThat(submit(operator.token(), "OFFLINE", envelope)).isEqualTo(403);

        Path secondIdentity = temporary.resolve("identity-audit-failure");
        runJar(jar, secondIdentity, Uuid7.generate());
        byte[] secondEnvelope = Files.readAllBytes(secondIdentity.resolve("enrollment-request.tcshreq"));
        UUID secondRequest = EnrollmentRequestV1.verify(secondEnvelope).requestId();
        manage(operator.account(), true);
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.createStatement().execute("""
                    CREATE FUNCTION public.reject_shc_e2e_audit() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit outage'; END $$
                    """);
            owner.createStatement().execute("""
                    CREATE TRIGGER reject_shc_e2e_audit BEFORE INSERT ON public.sys_audit_log
                    FOR EACH ROW WHEN (NEW.action = 'shc.enrollment.request.received')
                    EXECUTE FUNCTION public.reject_shc_e2e_audit()
                    """);
            try {
                assertThat(submit(operator.token(), "ONLINE", secondEnvelope)).isEqualTo(500);
                assertThat(count(secondRequest)).isZero();
            } finally {
                owner.createStatement().execute("DROP TRIGGER reject_shc_e2e_audit ON public.sys_audit_log");
                owner.createStatement().execute("DROP FUNCTION public.reject_shc_e2e_audit()");
            }
        }
        assertThat(submit(operator.token(), "ONLINE", secondEnvelope)).isEqualTo(200);
        assertThat(count(secondRequest)).isEqualTo(1);
    }

    private static void runJar(Path jar, Path directory, UUID tenant) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-jar", jar.toString(),
                "--prepare-enrollment", directory.toString(), tenant.toString())
                .redirectErrorStream(true).start();
        if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("jagonzn 申请进程超时");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("申请文件：").doesNotContain("PRIVATE KEY");
    }

    private int count(UUID request) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_shc_enrollment_request WHERE request_id=?",
                Integer.class, request);
    }

    private int submit(String token, String source, byte[] envelope) throws Exception {
        var request = post(PATH).contentType("application/octet-stream")
                .header("X-Enrollment-Source", source).content(envelope);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private Session session() throws Exception {
        limiter.clear();
        String email = "shc-e2e-" + UUID.randomUUID() + "@example.com";
        String body = JSON.writeValueAsString(java.util.Map.of("email", email,
                "password", "correct-horse-battery-staple"));
        var registration = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json").content(body)).andReturn();
        assertThat(registration.getResponse().getStatus()).isIn(200, 201, 204);
        jdbc.update("UPDATE sys_account SET email_verified_at=now() WHERE email=?", email);
        var login = mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json").content(body)).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        UUID account = jdbc.queryForObject("SELECT id FROM sys_account WHERE email=?", UUID.class, email);
        UUID ownTenant = jdbc.queryForObject("SELECT tenant_id FROM sys_tenant_member WHERE account_id=?",
                UUID.class, account);
        String token = JSON.readTree(login.getResponse().getContentAsByteArray())
                .get("accessToken").asString();
        return new Session(account, ownTenant, token);
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
            query.setString(4, "自部署跨进程申请验收");
            try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        if (operator == null) return;
        manage(operator.account(), false);
        jdbc.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", operator.tenant());
        jdbc.update("DELETE FROM sys_account WHERE id=?", operator.account());
        jdbc.update("DELETE FROM sys_tenant WHERE id=?", operator.tenant());
    }

    private record Session(UUID account, UUID tenant, String token) { }
}
