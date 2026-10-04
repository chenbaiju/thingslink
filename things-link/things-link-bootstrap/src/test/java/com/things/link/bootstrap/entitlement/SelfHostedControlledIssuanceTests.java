package com.things.link.bootstrap.entitlement;

import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.issuer.application.ControlledGrantIssuance;
import com.things.link.issuer.application.EnrollmentRegistry;
import com.things.link.issuer.infrastructure.keys.LocalControlledIssuerKey;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 PG 验证正式发行作业的归属、责任审核、专属角色和原文件恢复。 */
class SelfHostedControlledIssuanceTests extends AbstractIntegrationTest {
    @TempDir Path temporary;
    @Autowired EnrollmentRegistry registry;
    @Autowired JdbcTemplate jdbc;
    private final List<UUID> requests = new ArrayList<>();
    private final List<UUID> reviewers = new ArrayList<>();
    private final List<UUID> grants = new ArrayList<>();

    @Test
    void firstControlledGrantRequiresBindingAndOneReviewThenRecoversOriginalBytes() throws Exception {
        ensureAdministrators();
        var deploymentKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID request = Uuid7.generate(), deployment = Uuid7.generate(), tenant = Uuid7.generate();
        byte[] requestFile = EnrollmentRequestV1.create(request, deployment, tenant,
                deploymentKey.getPublic(), deploymentKey.getPrivate());
        var verifiedRequest = EnrollmentRequestV1.verify(requestFile);
        registry.register(requestFile, EnrollmentRegistry.Channel.OFFLINE);
        requests.add(request);
        byte[] evidence = MessageDigest.getInstance("SHA-256")
                .digest("test-only-customer-evidence".getBytes(StandardCharsets.UTF_8));
        UUID grantId = Uuid7.generate();
        grants.add(grantId);
        Path keyDirectory = temporary.resolve("issuer-key");
        char[] password = "test-only-long-password".toCharArray();
        LocalControlledIssuerKey.initialize(keyDirectory, password);
        var issuer = new ControlledGrantIssuance(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "shc_issue_test_admin", "test-only"));
        try {
            assertThat(issuer.recover(request, grantId)).isNull();
            assertThatThrownBy(() -> issuer.issue(request, grantId, keyDirectory, password))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("归属");
            bind(request, evidence);
            assertThatThrownBy(() -> issuer.issue(request, grantId, keyDirectory, password))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("一名授权审核者");
            attest(request, deployment, tenant, verifiedRequest, evidence, "STANDARD");

            assertThatThrownBy(() -> jdbc.queryForObject(
                    "SELECT public.shc_issuance_lock(?)", Boolean.class, request))
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM sys_shc_signed_grant", Integer.class))
                    .rootCause().hasMessageContaining("permission denied");

            try (Connection owner = owner()) {
                owner.createStatement().execute("""
                        CREATE FUNCTION public.reject_shc_issuance_test_audit() RETURNS trigger
                        LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test issuance audit outage'; END $$
                        """);
                owner.createStatement().execute("""
                        CREATE TRIGGER reject_shc_issuance_test_audit
                        BEFORE INSERT ON public.sys_audit_log FOR EACH ROW
                        WHEN (NEW.action = 'shc.grant.issued')
                        EXECUTE FUNCTION public.reject_shc_issuance_test_audit()
                        """);
                try {
                    assertThatThrownBy(() -> issuer.issue(request, grantId, keyDirectory, password))
                            .hasMessageContaining("test issuance audit outage");
                    assertThat(issuer.recover(request, grantId)).isNull();
                } finally {
                    owner.createStatement().execute(
                            "DROP TRIGGER reject_shc_issuance_test_audit ON public.sys_audit_log");
                    owner.createStatement().execute("DROP FUNCTION public.reject_shc_issuance_test_audit()");
                }
            }

            var issued = issuer.issue(request, grantId, keyDirectory, password);
            assertThat(issued.sequence()).isEqualTo(1);
            assertThat(issued.tier()).isEqualTo("STANDARD");
            assertThat(issued.deploymentId()).isEqualTo(deployment);
            assertThat(issued.tenantId()).isEqualTo(tenant);
            assertThat(issued.endsAt()).isEqualTo(issued.startsAt().atOffset(
                    java.time.ZoneOffset.UTC).plusYears(1).toInstant());
            assertThat(issuer.recover(request, grantId).envelope()).containsExactly(issued.envelope());
            assertThat(issuer.issue(request, grantId, temporary.resolve("missing-key"), null)
                    .envelope()).containsExactly(issued.envelope());
            assertThatThrownBy(() -> issuer.recover(UUID.randomUUID(), grantId))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("恢复申请");
            UUID secondGrant = Uuid7.generate();
            grants.add(secondGrant);
            assertThatThrownBy(() -> issuer.issue(request, secondGrant, keyDirectory, password))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("续期");
            try (Connection owner = owner()) {
                try (var statement = owner.prepareStatement(
                        "SELECT count(*) FROM sys_audit_log WHERE action='shc.grant.issued' AND target_id=?")) {
                    statement.setObject(1, grantId);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        assertThat(result.getInt(1)).isEqualTo(1);
                    }
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void ensureAdministrators() throws SQLException {
        try (Connection owner = owner()) {
            owner.createStatement().execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='shc_issue_test_admin') "
                    + "THEN CREATE ROLE shc_issue_test_admin LOGIN PASSWORD 'test-only'; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='shc_customer_test_admin') "
                    + "THEN CREATE ROLE shc_customer_test_admin LOGIN PASSWORD 'test-only'; END IF; END $$");
            owner.createStatement().execute(
                    "GRANT thingslink_shc_issue_admin TO shc_issue_test_admin");
            owner.createStatement().execute(
                    "GRANT thingslink_shc_customer_admin TO shc_customer_test_admin");
        }
    }

    private static void bind(UUID request, byte[] evidence) throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "shc_customer_test_admin", "test-only");
             var statement = admin.prepareStatement(
                     "SELECT (shc_customer_bind(?,?,?,?)).request_id")) {
            statement.setObject(1, request);
            statement.setString(2, "jagonzn");
            statement.setString(3, "test:customer-evidence");
            statement.setBytes(4, evidence);
            statement.executeQuery().close();
        }
    }

    private void attest(UUID request, UUID deployment, UUID tenant,
                        EnrollmentRequestV1.Verified verified, byte[] evidence, String tier)
            throws Exception {
        UUID reviewer = Uuid7.generate();
        reviewers.add(reviewer);
        try (Connection owner = owner(); var account = owner.prepareStatement("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """)) {
            account.setObject(1, reviewer);
            account.setString(2, "shc-controlled-test-" + reviewer + "@example.invalid");
            account.setString(3, "{noop}test-only");
            account.setString(4, "受控签发测试审核人");
            account.executeUpdate();
        }
        var revision = ApprovedSelfHostedRevision.loadApproved();
        jdbc.update("""
                INSERT INTO sys_shc_review_attestation
                    (request_id, reviewer_account_id, deployment_id, tenant_id,
                     public_key_sha256, request_sha256, organization_ref, evidence_sha256,
                     tier, revision_id, revision_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request, reviewer, deployment, tenant, verified.publicKeySha256(),
                verified.requestSha256(), "jagonzn", evidence, tier,
                revision.revisionId(), revision.sha256());
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            owner.createStatement().execute("ALTER TABLE sys_shc_signed_grant "
                    + "DISABLE TRIGGER sys_shc_signed_grant_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_review_attestation "
                    + "DISABLE TRIGGER sys_shc_review_attestation_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_customer_binding "
                    + "DISABLE TRIGGER sys_shc_customer_binding_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request "
                    + "DISABLE TRIGGER sys_shc_enrollment_request_immutable");
            try {
                for (UUID grant : grants) remove(owner,
                        "DELETE FROM sys_shc_signed_grant WHERE grant_id=?", grant);
                for (UUID request : requests) {
                    remove(owner, "DELETE FROM sys_shc_review_attestation WHERE request_id=?", request);
                    remove(owner, "DELETE FROM sys_shc_customer_binding WHERE request_id=?", request);
                    remove(owner, "DELETE FROM sys_shc_enrollment_request WHERE request_id=?", request);
                }
                for (UUID reviewer : reviewers) remove(owner,
                        "DELETE FROM sys_account WHERE id=?", reviewer);
            } finally {
                owner.createStatement().execute("ALTER TABLE sys_shc_signed_grant "
                        + "ENABLE TRIGGER sys_shc_signed_grant_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_review_attestation "
                        + "ENABLE TRIGGER sys_shc_review_attestation_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_customer_binding "
                        + "ENABLE TRIGGER sys_shc_customer_binding_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request "
                        + "ENABLE TRIGGER sys_shc_enrollment_request_immutable");
            }
            owner.commit();
        }
    }

    private static void remove(Connection connection, String sql, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }
}
