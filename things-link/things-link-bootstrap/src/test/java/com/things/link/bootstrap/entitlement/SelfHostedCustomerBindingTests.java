package com.things.link.bootstrap.entitlement;

import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.issuer.application.EnrollmentRegistry;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实数据库核对客户归属只能由独立管理会话绑定到已验签申请。 */
class SelfHostedCustomerBindingTests extends AbstractIntegrationTest {
    @Autowired EnrollmentRegistry registry;
    @Autowired JdbcTemplate jdbc;
    private final List<UUID> requests = new ArrayList<>();

    @Test
    void onlyControlledAdministratorCanBindTheExactRegisteredDeploymentAndTenant() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID request = Uuid7.generate(), deployment = Uuid7.generate(), tenant = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(request, deployment, tenant,
                key.getPublic(), key.getPrivate());
        var verified = EnrollmentRequestV1.verify(envelope);
        registry.register(envelope, EnrollmentRegistry.Channel.OFFLINE);
        requests.add(request);

        byte[] material = MessageDigest.getInstance("SHA-256")
                .digest("test-only-owner-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> jdbc.queryForObject(
                "SELECT (shc_customer_bind(?,?,?,?)).request_id", UUID.class,
                request, "jagonzn", "test:owner-evidence", material))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sys_shc_customer_binding "
                + "(request_id,deployment_id,tenant_id,public_key_sha256,request_sha256,"
                + "organization_ref,evidence_ref,evidence_sha256,database_actor) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", request, deployment, tenant,
                verified.publicKeySha256(), verified.requestSha256(), "jagonzn",
                "test:owner-evidence", material, "spoofed"))
                .rootCause().hasMessageContaining("permission denied");

        ensureTestAdministrator();
        assertThat(bind(request, "jagonzn", "test:owner-evidence", material)).isEqualTo(request);
        assertThat(bind(request, "jagonzn", "test:owner-evidence", material)).isEqualTo(request);
        assertThat(jdbc.queryForObject("SELECT deployment_id FROM sys_shc_customer_binding "
                + "WHERE request_id=?", UUID.class, request)).isEqualTo(deployment);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM sys_shc_customer_binding "
                + "WHERE request_id=?", UUID.class, request)).isEqualTo(tenant);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_shc_customer_binding "
                + "WHERE request_id=?", Integer.class, request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log "
                + "WHERE target_id=? AND action='shc.customer.bound'", Integer.class, request)).isEqualTo(1);
        assertThatThrownBy(() -> bind(request, "other-customer", "test:owner-evidence", material))
                .isInstanceOf(SQLException.class).hasMessageContaining("binding conflict");
        assertThatThrownBy(() -> bind(Uuid7.generate(), "jagonzn", "test:owner-evidence", material))
                .isInstanceOf(SQLException.class).hasMessageContaining("pending self-hosted enrollment");
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_shc_customer_binding "
                + "SET organization_ref='other-customer' WHERE request_id=?", request))
                .rootCause().hasMessageContaining("permission denied");
    }

    private static UUID bind(UUID request, String organization, String materialRef, byte[] digest)
            throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "shc_customer_test_admin", "test-only");
             var statement = admin.prepareStatement(
                     "SELECT (shc_customer_bind(?,?,?,?)).request_id")) {
            statement.setObject(1, request);
            statement.setString(2, organization);
            statement.setString(3, materialRef);
            statement.setBytes(4, digest);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static void ensureTestAdministrator() throws SQLException {
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.createStatement().execute("DO $$ BEGIN IF NOT EXISTS "
                    + "(SELECT 1 FROM pg_roles WHERE rolname='shc_customer_test_admin') "
                    + "THEN CREATE ROLE shc_customer_test_admin LOGIN PASSWORD 'test-only'; "
                    + "END IF; END $$");
            owner.createStatement().execute(
                    "GRANT thingslink_shc_customer_admin TO shc_customer_test_admin");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.setAutoCommit(false);
            owner.createStatement().execute("ALTER TABLE sys_shc_customer_binding "
                    + "DISABLE TRIGGER sys_shc_customer_binding_immutable");
            owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request "
                    + "DISABLE TRIGGER sys_shc_enrollment_request_immutable");
            try {
                for (UUID request : requests) {
                    try (var binding = owner.prepareStatement(
                            "DELETE FROM sys_shc_customer_binding WHERE request_id=?");
                         var enrollment = owner.prepareStatement(
                                 "DELETE FROM sys_shc_enrollment_request WHERE request_id=?")) {
                        binding.setObject(1, request);
                        binding.executeUpdate();
                        enrollment.setObject(1, request);
                        enrollment.executeUpdate();
                    }
                }
            } finally {
                owner.createStatement().execute("ALTER TABLE sys_shc_customer_binding "
                        + "ENABLE TRIGGER sys_shc_customer_binding_immutable");
                owner.createStatement().execute("ALTER TABLE sys_shc_enrollment_request "
                        + "ENABLE TRIGGER sys_shc_enrollment_request_immutable");
            }
            owner.commit();
        }
    }
}
