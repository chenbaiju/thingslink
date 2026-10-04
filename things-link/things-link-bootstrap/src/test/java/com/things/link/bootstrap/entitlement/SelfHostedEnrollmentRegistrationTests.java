package com.things.link.bootstrap.entitlement;

import com.things.link.issuer.infrastructure.EnrollmentRegistrationService;
import com.things.link.issuer.application.EnrollmentRegistry.Channel;
import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 发行方待审核登记：同一联网/离线申请、身份冲突与数据库事实。 */
class SelfHostedEnrollmentRegistrationTests extends AbstractIntegrationTest {
    @Autowired EnrollmentRegistrationService registration;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlineAndOfflineShareOneVerifiedPendingRegistration() throws Exception {
        KeyPair key = key();
        UUID request = Uuid7.generate(), deployment = Uuid7.generate(), tenant = Uuid7.generate();
        byte[] envelope = EnrollmentRequestV1.create(request, deployment, tenant,
                key.getPublic(), key.getPrivate());

        var first = registration.register(envelope, Channel.ONLINE);
        var replay = registration.register(envelope, Channel.OFFLINE);

        assertThat(first.requestId()).isEqualTo(request);
        assertThat(first.deploymentId()).isEqualTo(deployment);
        assertThat(first.tenantId()).isEqualTo(tenant);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(replay.channel()).isEqualTo(Channel.ONLINE);
        assertThat(replay.receivedAt()).isEqualTo(first.receivedAt());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_shc_enrollment_request WHERE deployment_id=?",
                Integer.class, deployment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_shc_enrollment_request WHERE status<>'PENDING'",
                Integer.class)).isZero();
    }

    @Test
    void alteredSignatureOrConflictingIdentityNeverChangesTheFirstFact() throws Exception {
        KeyPair key = key();
        UUID request = Uuid7.generate(), deployment = Uuid7.generate(), tenant = Uuid7.generate();
        byte[] original = EnrollmentRequestV1.create(request, deployment, tenant,
                key.getPublic(), key.getPrivate());
        byte[] tampered = original.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> registration.register(tampered, Channel.OFFLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_shc_enrollment_request WHERE deployment_id=?",
                Integer.class, deployment)).isZero();

        registration.register(original, Channel.OFFLINE);
        byte[] wrongTenant = EnrollmentRequestV1.create(request, deployment, Uuid7.generate(),
                key.getPublic(), key.getPrivate());
        byte[] secondRequest = EnrollmentRequestV1.create(Uuid7.generate(), deployment, tenant,
                key.getPublic(), key.getPrivate());
        assertThatThrownBy(() -> registration.register(wrongTenant, Channel.ONLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registration.register(secondRequest, Channel.ONLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM sys_shc_enrollment_request WHERE deployment_id=?",
                UUID.class, deployment)).isEqualTo(tenant);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_shc_enrollment_request WHERE deployment_id=?",
                Integer.class, deployment)).isEqualTo(1);
    }

    @Test
    void envelopeRejectsTailAndWrongDeploymentKey() throws Exception {
        KeyPair original = key(), another = key();
        byte[] request = EnrollmentRequestV1.create(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                original.getPublic(), original.getPrivate());
        byte[] tail = java.util.Arrays.copyOf(request, request.length + 1);
        assertThatThrownBy(() -> EnrollmentRequestV1.verify(tail))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] wrongKey = EnrollmentRequestV1.create(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                original.getPublic(), another.getPrivate());
        assertThatThrownBy(() -> EnrollmentRequestV1.verify(wrongKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registrationTableAndEveryColumnHaveChineseComments() {
        String table = jdbc.queryForObject(
                "SELECT obj_description('public.sys_shc_enrollment_request'::regclass,'pg_class')",
                String.class);
        assertThat(hasHan(table)).isTrue();
        var columnComments = jdbc.queryForList("""
                SELECT col_description(a.attrelid,a.attnum) AS comment
                  FROM pg_attribute a
                 WHERE a.attrelid='public.sys_shc_enrollment_request'::regclass
                   AND a.attnum>0 AND NOT a.attisdropped
                """, String.class);
        assertThat(columnComments).hasSize(9).allMatch(SelfHostedEnrollmentRegistrationTests::hasHan);
    }

    private static KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static boolean hasHan(String value) {
        return value != null && value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
