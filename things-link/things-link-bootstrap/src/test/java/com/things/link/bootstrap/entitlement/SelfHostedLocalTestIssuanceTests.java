package com.things.link.bootstrap.entitlement;

import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.entitlement.application.GrantV1Verification;
import com.things.link.issuer.application.EnrollmentRegistry;
import com.things.link.issuer.application.LocalTestGrantIssuer;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.security.spec.X509EncodedKeySpec;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 源码树本机测试发行：真实 PG 申请、双证明、只追加事实和结果未知原字节恢复。 */
@ActiveProfiles({"test", "shc-lab"})
class SelfHostedLocalTestIssuanceTests extends AbstractIntegrationTest {
    @Autowired EnrollmentRegistry registry;
    @Autowired LocalTestGrantIssuer issuer;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> requests = new ArrayList<>();
    private final List<UUID> reviewers = new ArrayList<>();
    private final List<UUID> grants = new ArrayList<>();

    @Test
    void twoMatchingLocalTestFactsIssueApprovedRevisionAndReplayOriginalBytes() throws Exception {
        Fixture fixture = request();
        KeyPair signing = keys();
        UUID firstGrant = Uuid7.generate(), secondGrant = Uuid7.generate();
        grants.add(firstGrant);
        grants.add(secondGrant);
        assertThat(issuer.recover(fixture.request(), firstGrant)).isEmpty();

        assertThatThrownBy(() -> issue(fixture, firstGrant, signing))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("两名独立审核者");
        attest(fixture, "local-test:tenant-1", "STANDARD", "a".repeat(64));
        assertThatThrownBy(() -> issue(fixture, firstGrant, signing))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("两名独立审核者");
        attest(fixture, "local-test:tenant-1", "STANDARD", "a".repeat(64));

        var first = issue(fixture, firstGrant, signing);
        var approved = ApprovedSelfHostedRevision.loadApproved();
        var verified = GrantV1Verification.verify(first.envelope(),
                Map.of(first.keyId(), signing.getPublic()), LocalTestGrantIssuer.TEST_ISSUER,
                fixture.deployment(), fixture.tenant());
        approved.requireMatches(verified);
        assertThat(verified.grantId()).isEqualTo(firstGrant);
        assertThat(verified.sequence()).isEqualTo(1);
        assertThat(verified.endsAt()).isEqualTo(first.issuedAt().atOffset(ZoneOffset.UTC)
                .plusYears(1).toInstant());
        assertThat(verified.quotas()).isEqualTo(approved.tier("STANDARD").quotas());
        assertThat(verified.capabilities()).isEqualTo(approved.tier("STANDARD").capabilities());
        assertThat(first.envelopeSha256()).hasSize(32);
        // 模拟签名进程退出且临时私钥丢失：仅凭持久发行事实恢复原字节。
        var recovered = issuer.recover(fixture.request(), firstGrant).orElseThrow();
        assertThat(recovered.envelope()).isEqualTo(first.envelope());
        var recoveredKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(recovered.testPublicKeySpki()));
        var recoveredGrant = GrantV1Verification.verify(recovered.envelope(),
                Map.of(recovered.keyId(), recoveredKey), LocalTestGrantIssuer.TEST_ISSUER,
                fixture.deployment(), fixture.tenant());
        approved.requireMatches(recoveredGrant);
        assertThat(recoveredGrant.grantId()).isEqualTo(firstGrant);
        assertThat(jdbc.queryForObject("""
                SELECT issuance_kind FROM sys_shc_lab_issuance WHERE grant_id = ?
                """, String.class, firstGrant)).isEqualTo("TEST");

        var replay = issuer.issue(fixture.request(), firstGrant, recoveredKey,
                ignored -> { throw new AssertionError("已签发授权重试不得再次签名"); });
        assertThat(replay.sequence()).isEqualTo(1);
        assertThat(replay.envelope()).isEqualTo(first.envelope());
        assertThat(replay.envelopeSha256()).isEqualTo(first.envelopeSha256());
        assertThatThrownBy(() -> issuer.issue(fixture.request(), firstGrant, keys().getPublic(),
                ignored -> { throw new AssertionError("不应再次签名"); }))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("幂等键");

        var second = issue(fixture, secondGrant, keys());
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(second.startsAt()).isEqualTo(first.startsAt());
        assertThat(second.endsAt()).isEqualTo(first.endsAt());
        assertThat(second.envelope()).isNotEqualTo(first.envelope());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE action = 'shc.lab.grant.issued' AND target_id IN (?, ?)
                """, Integer.class, firstGrant, secondGrant)).isEqualTo(2);
        Fixture differentRequest = request();
        assertThatThrownBy(() -> issuer.recover(differentRequest.request(), firstGrant))
                .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("申请与原事实不符");
    }

    @Test
    void conflictingOrNonTestReviewsCannotIssue() throws Exception {
        Fixture fixture = request();
        KeyPair signing = keys();
        UUID grant = Uuid7.generate();
        grants.add(grant);
        attest(fixture, "local-test:tenant-2", "FREE", "b".repeat(64));
        attest(fixture, "local-test:tenant-2", "STANDARD", "b".repeat(64));
        assertThatThrownBy(() -> issue(fixture, grant, signing))
                .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("不一致");
        assertThat(count(grant)).isZero();

        Fixture nonTest = request();
        UUID otherGrant = Uuid7.generate();
        grants.add(otherGrant);
        attest(nonTest, "contract:real-customer", "FREE", "c".repeat(64));
        attest(nonTest, "contract:real-customer", "FREE", "c".repeat(64));
        assertThatThrownBy(() -> issue(nonTest, otherGrant, signing))
                .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("不一致");
        assertThat(count(otherGrant)).isZero();
    }

    @Test
    void concurrentIssuanceForOneDeploymentAllocatesDifferentSequences() throws Exception {
        Fixture fixture = request();
        attest(fixture, "local-test:parallel", "ENTERPRISE", "e".repeat(64));
        attest(fixture, "local-test:parallel", "ENTERPRISE", "e".repeat(64));
        UUID leftGrant = Uuid7.generate(), rightGrant = Uuid7.generate();
        grants.add(leftGrant);
        grants.add(rightGrant);
        KeyPair signing = keys();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(() -> issue(fixture, leftGrant, signing));
            var right = workers.submit(() -> issue(fixture, rightGrant, signing));
            var leftIssued = left.get(30, TimeUnit.SECONDS);
            var rightIssued = right.get(30, TimeUnit.SECONDS);
            assertThat(Set.of(leftIssued.sequence(), rightIssued.sequence())).containsExactlyInAnyOrder(1L, 2L);
            assertThat(leftIssued.startsAt()).isEqualTo(rightIssued.startsAt());
            assertThat(leftIssued.endsAt()).isEqualTo(rightIssued.endsAt());
            assertThat(issuer.recover(fixture.request(), leftGrant).orElseThrow().envelope())
                    .isEqualTo(leftIssued.envelope());
            assertThat(issuer.recover(fixture.request(), rightGrant).orElseThrow().envelope())
                    .isEqualTo(rightIssued.envelope());
        }
    }

    @Test
    void failedAuditRollsBackSignedFactAndRetryAllocatesFirstSequence() throws Exception {
        Fixture fixture = request();
        attest(fixture, "local-test:tenant-3", "FREE", "d".repeat(64));
        attest(fixture, "local-test:tenant-3", "FREE", "d".repeat(64));
        UUID grant = Uuid7.generate();
        grants.add(grant);
        KeyPair signing = keys();
        try (Connection owner = owner()) {
            owner.createStatement().execute("""
                    CREATE FUNCTION public.reject_shc_lab_audit() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit outage'; END $$
                    """);
            owner.createStatement().execute("""
                    CREATE TRIGGER reject_shc_lab_audit
                    BEFORE INSERT ON public.sys_audit_log FOR EACH ROW
                    WHEN (NEW.action = 'shc.lab.grant.issued')
                    EXECUTE FUNCTION public.reject_shc_lab_audit()
                    """);
            try {
                assertThatThrownBy(() -> issue(fixture, grant, signing))
                        .rootCause().hasMessageContaining("test audit outage");
                assertThat(count(grant)).isZero();
                assertThat(issuer.recover(fixture.request(), grant)).isEmpty();
            } finally {
                owner.createStatement().execute("DROP TRIGGER reject_shc_lab_audit ON public.sys_audit_log");
                owner.createStatement().execute("DROP FUNCTION public.reject_shc_lab_audit()");
            }
        }
        // 未提交意味着原私钥没有形成已发行事实，换新临时密钥仍分配首序号。
        KeyPair replacement = keys();
        var issued = issue(fixture, grant, replacement);
        assertThat(issued.sequence()).isEqualTo(1);
        assertThat(issued.endsAt()).isNull();
        assertThat(count(grant)).isEqualTo(1);
        assertThat(issuer.recover(fixture.request(), grant).orElseThrow().envelope())
                .isEqualTo(issued.envelope());
    }

    private LocalTestGrantIssuer.Issued issue(Fixture fixture, UUID grant, KeyPair signing)
            throws GeneralSecurityException {
        return issuer.issue(fixture.request(), grant, signing.getPublic(),
                value -> value.sign(signing.getPrivate()));
    }

    private Fixture request() throws Exception {
        KeyPair deploymentKey = keys();
        UUID request = Uuid7.generate(), deployment = Uuid7.generate(), tenant = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(request, deployment, tenant,
                deploymentKey.getPublic(), deploymentKey.getPrivate());
        registry.register(envelope, EnrollmentRegistry.Channel.OFFLINE);
        requests.add(request);
        return new Fixture(request, deployment, tenant, EnrollmentRequestV1.verify(envelope));
    }

    private void attest(Fixture fixture, String organization, String tier, String digest) throws Exception {
        UUID reviewer = Uuid7.generate();
        reviewers.add(reviewer);
        try (Connection owner = owner(); var account = owner.prepareStatement("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """)) {
            account.setObject(1, reviewer);
            account.setString(2, "shc-lab-" + reviewer + "@example.invalid");
            account.setString(3, "{noop}test-only");
            account.setString(4, "SHC 本地测试审核人");
            account.executeUpdate();
        }
        var revision = ApprovedSelfHostedRevision.loadApproved();
        jdbc.update("""
                INSERT INTO sys_shc_review_attestation
                    (request_id, reviewer_account_id, deployment_id, tenant_id,
                     public_key_sha256, request_sha256, organization_ref, evidence_sha256,
                     tier, revision_id, revision_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, decode(?, 'hex'), ?, ?, ?)
                """, fixture.request(), reviewer, fixture.deployment(), fixture.tenant(),
                fixture.verified().publicKeySha256(), fixture.verified().requestSha256(),
                organization, digest, tier, revision.revisionId(), revision.sha256());
    }

    private int count(UUID grant) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_shc_lab_issuance WHERE grant_id = ?",
                Integer.class, grant);
    }

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            owner.createStatement().execute("ALTER TABLE sys_audit_log DISABLE TRIGGER sys_audit_log_no_delete");
            owner.createStatement().execute("ALTER TABLE sys_shc_lab_issuance DISABLE TRIGGER sys_shc_lab_issuance_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_review_attestation DISABLE TRIGGER sys_shc_review_attestation_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request DISABLE TRIGGER sys_shc_enrollment_request_immutable");
            try {
                for (UUID grant : grants) {
                    remove(owner, "DELETE FROM sys_audit_log WHERE target_id = ? AND action = 'shc.lab.grant.issued'", grant);
                    remove(owner, "DELETE FROM sys_shc_lab_issuance WHERE grant_id = ?", grant);
                }
                for (UUID request : requests) {
                    remove(owner, "DELETE FROM sys_shc_review_attestation WHERE request_id = ?", request);
                    remove(owner, "DELETE FROM sys_shc_enrollment_request WHERE request_id = ?", request);
                }
                for (UUID reviewer : reviewers) {
                    remove(owner, "DELETE FROM sys_account WHERE id = ?", reviewer);
                }
            } finally {
                owner.createStatement().execute("ALTER TABLE sys_audit_log ENABLE TRIGGER sys_audit_log_no_delete");
                owner.createStatement().execute("ALTER TABLE sys_shc_lab_issuance ENABLE TRIGGER sys_shc_lab_issuance_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_review_attestation ENABLE TRIGGER sys_shc_review_attestation_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request ENABLE TRIGGER sys_shc_enrollment_request_immutable");
            }
            owner.commit();
        }
    }

    private static void remove(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private record Fixture(UUID request, UUID deployment, UUID tenant,
                           EnrollmentRequestV1.Verified verified) { }
}
