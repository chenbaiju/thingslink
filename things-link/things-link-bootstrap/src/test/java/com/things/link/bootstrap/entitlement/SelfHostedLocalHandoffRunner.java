package com.things.link.bootstrap.entitlement;

import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.issuer.application.EnrollmentRegistry;
import com.things.link.issuer.application.LocalTestGrantIssuer;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 只由本地交接脚本显式调用；从 jagonzn 原申请签发可传递的 TEST 封套。 */
@ActiveProfiles({"test", "shc-lab"})
class SelfHostedLocalHandoffRunner extends AbstractIntegrationTest {
    @Autowired EnrollmentRegistry registry;
    @Autowired LocalTestGrantIssuer issuer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void handOffOriginalSignedBytes() throws Exception {
        Path directory = Path.of(System.getProperty("shc.lab.handoff.dir")).toAbsolutePath();
        byte[] requestBytes = Files.readAllBytes(directory.resolve("identity/enrollment-request.tcshreq"));
        var request = EnrollmentRequestV1.verify(requestBytes);
        String template = Files.readString(Path.of(System.getProperty("shc.lab.mock.contract.template")),
                StandardCharsets.UTF_8);
        String contract = template.replace("${requestId}", request.requestId().toString())
                .replace("${deploymentId}", request.deploymentId().toString())
                .replace("${tenantId}", request.tenantId().toString());
        assertThat(contract).doesNotContain("${");
        byte[] contractBytes = contract.getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("mock-customer-contract.txt"), contractBytes);
        String evidenceSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(contractBytes));
        registry.register(requestBytes, EnrollmentRegistry.Channel.OFFLINE);
        attest(request, "STANDARD", evidenceSha256);
        attest(request, "STANDARD", evidenceSha256);

        var ephemeral = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var issued = issuer.issue(request.requestId(), Uuid7.generate(), ephemeral.getPublic(),
                grant -> grant.sign(ephemeral.getPrivate()));
        assertThat(issued.sequence()).isEqualTo(1);
        assertThat(issued.deploymentId()).isEqualTo(request.deploymentId());
        assertThat(issued.tenantId()).isEqualTo(request.tenantId());
        assertThat(issuer.recover(request.requestId(), issued.grantId()).orElseThrow().envelope())
                .isEqualTo(issued.envelope());

        Files.write(directory.resolve("grant.tcshgrant"), issued.envelope());
        Files.write(directory.resolve("issuer-public.der"), issued.testPublicKeySpki());
        Files.writeString(directory.resolve("key-id.txt"), issued.keyId(), StandardCharsets.US_ASCII);
    }

    private void attest(EnrollmentRequestV1.Verified request, String tier,
                        String evidenceSha256) throws Exception {
        UUID reviewer = Uuid7.generate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var insert = connection.prepareStatement("""
                     INSERT INTO sys_account (id, email, password_hash, display_name)
                     VALUES (?, ?, ?, ?)
                     """)) {
            insert.setObject(1, reviewer);
            insert.setString(2, "shc-handoff-" + reviewer + "@example.invalid");
            insert.setString(3, "{noop}test-only");
            insert.setString(4, "SHC 本地交接测试审核人");
            insert.executeUpdate();
        }
        var approved = ApprovedSelfHostedRevision.loadApproved();
        jdbc.update("""
                INSERT INTO sys_shc_review_attestation
                    (request_id, reviewer_account_id, deployment_id, tenant_id,
                     public_key_sha256, request_sha256, organization_ref, evidence_sha256,
                     tier, revision_id, revision_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, decode(?, 'hex'), ?, ?, ?)
                """, request.requestId(), reviewer, request.deploymentId(), request.tenantId(),
                request.publicKeySha256(), request.requestSha256(), "local-test:handoff",
                evidenceSha256, tier, approved.revisionId(), approved.sha256());
    }
}
