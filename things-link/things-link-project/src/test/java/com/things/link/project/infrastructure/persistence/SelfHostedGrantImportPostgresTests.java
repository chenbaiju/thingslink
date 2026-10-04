package com.things.link.project.infrastructure.persistence;

import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.project.application.SelfHostedGrantImportService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 同一真实 PostgreSQL 事务验证授权导入、序号和第二租户防线。 */
@Testcontainers
class SelfHostedGrantImportPostgresTests {
    private static final String ISSUER = "tc-local-test";
    private static final String KEY_ID = "lab-key";
    private static final Instant START = Instant.parse("2026-09-28T00:00:00.123456789Z");
    private static final String[] MIGRATIONS = {
            "V20260928_0120__self_hosted_local_grant_import.sql",
            "V20260928_0130__self_hosted_local_quota_projection.sql"};

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("shc_import")
            .withUsername("postgres")
            .withPassword("test-owner-password");

    private JdbcTemplate owner;
    private JdbcTemplate app;
    private DataSource appDataSource;
    private KeyPair keys;
    private ApprovedSelfHostedRevision revision;

    @BeforeEach
    void createIsolatedMigrationDatabase() throws Exception {
        DataSource ownerDataSource = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        owner.execute("DROP SCHEMA IF EXISTS public CASCADE");
        owner.execute("CREATE SCHEMA public");
        owner.execute("GRANT USAGE ON SCHEMA public TO PUBLIC");
        owner.execute("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='thingslink_app') THEN
                    CREATE ROLE thingslink_app LOGIN PASSWORD 'test-app-password';
                  END IF;
                END $$
                """);
        owner.execute("""
                CREATE TABLE public.sys_tenant (
                    id uuid PRIMARY KEY, name text NOT NULL, deleted_at timestamptz)
                """);
        owner.execute("GRANT SELECT, INSERT, UPDATE ON public.sys_tenant TO thingslink_app");
        Path directory = Files.createTempDirectory("shc-import-migration-");
        try {
            for (String migration : MIGRATIONS) {
                Path sql = directory.resolve(migration);
                try (var source = getClass().getResourceAsStream("/db/migration/project/" + migration)) {
                    assertThat(source).isNotNull();
                    Files.copy(source, sql);
                }
            }
            Flyway.configure().dataSource(ownerDataSource)
                    .locations("filesystem:" + directory).baselineOnMigrate(true).load().migrate();
        } finally {
            for (String migration : MIGRATIONS) Files.deleteIfExists(directory.resolve(migration));
            Files.deleteIfExists(directory);
        }
        appDataSource = dataSource("thingslink_app", "test-app-password");
        app = new JdbcTemplate(appDataSource);
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        revision = ApprovedSelfHostedRevision.loadApproved();
    }

    @Test
    void firstImportBindsOnlyTenantAndSameEnvelopeIsIdempotent() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID deployment = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "实验租户");
        var identity = installation(deployment, tenant);
        var service = service();
        byte[] free = grant(identity, "FREE", 1, START, null, null);

        assertThat(service.importGrant(free, identity).outcome())
                .isEqualTo(SelfHostedGrantImportService.Outcome.IMPORTED);
        assertThat(service.importGrant(free, identity).outcome())
                .isEqualTo(SelfHostedGrantImportService.Outcome.ALREADY_IMPORTED);
        assertThat(service.readCurrent(identity).orElseThrow().quotas())
                .containsEntry("EXTERNAL_COLLABORATOR_SEATS", 0L);
        assertThat(app.queryForObject("SELECT devices_max FROM sys_shc_local_grant_state", Long.class))
                .isEqualTo(3L);
        assertThat(app.queryForObject("SELECT count(*) FROM sys_shc_local_grant_import_audit", Integer.class))
                .isEqualTo(1);
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var second = workers.submit(() -> rejectSecondTenantAfter(go));
            var third = workers.submit(() -> rejectSecondTenantAfter(go));
            go.countDown();
            assertThat(second.get()).isTrue();
            assertThat(third.get()).isTrue();
        }
        assertThat(app.queryForObject("SELECT count(*) FROM sys_tenant", Integer.class)).isEqualTo(1);
    }

    @Test
    void signedProjectionRejectsTamperingAndLegacyRowRequiresVerifiedIdempotentBackfill() throws Exception {
        UUID tenant = UUID.randomUUID();
        var identity = installation(UUID.randomUUID(), tenant);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "投影实验租户");
        var service = service();
        byte[] free = grant(identity, "FREE", 1, START, null, null);
        service.importGrant(free, identity);
        owner.update("UPDATE sys_shc_local_grant_state SET devices_max=999 WHERE singleton");
        assertThatThrownBy(() -> service.readCurrent(identity))
                .hasMessageContaining("已导入授权损坏");
        assertThatThrownBy(() -> service.importGrant(free, identity))
                .hasMessageContaining("已导入授权损坏");
        owner.update("""
                UPDATE sys_shc_local_grant_state SET devices_max=NULL,
                    uplink_message_daily=NULL, downlink_message_daily=NULL WHERE singleton
                """);
        assertThatThrownBy(() -> app.queryForObject(
                "SELECT limit_value FROM shc_local_quota_limit('DEVICES_MAX')", Long.class))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> owner.queryForObject(
                "SELECT limit_value FROM shc_local_quota_limit('DEVICES_MAX')", Long.class))
                .rootCause().hasMessageContaining("requires verified re-import");
        assertThat(service.importGrant(free, identity).outcome())
                .isEqualTo(SelfHostedGrantImportService.Outcome.ALREADY_IMPORTED);
        assertThat(service.readCurrent(identity).orElseThrow().tier()).isEqualTo("FREE");
        assertThat(app.queryForObject("SELECT devices_max FROM sys_shc_local_grant_state", Long.class))
                .isEqualTo(3L);
        assertThat(app.queryForObject("SELECT count(*) FROM sys_shc_local_grant_import_audit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void invalidInputAndPeriodResetLeaveOldGrantIntact() throws Exception {
        UUID tenant = UUID.randomUUID();
        var identity = installation(UUID.randomUUID(), tenant);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "实验租户");
        var service = service();
        Instant end = START.atOffset(ZoneOffset.UTC).plusYears(1).toInstant();
        byte[] first = grant(identity, "STANDARD", 41, START, end, null);
        service.importGrant(first, identity);

        byte[] tampered = first.clone();
        tampered[25] ^= 1;
        assertThatThrownBy(() -> service.importGrant(tampered, identity))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> service.importGrant(grant(
                installation(UUID.randomUUID(), tenant),
                "STANDARD", 42, START, end, null), identity))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 42,
                START, end, Map.of("DEVICES_MAX", 999L)), identity))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 42,
                START.plusSeconds(1), end.plusSeconds(1), null), identity))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("首期期限");
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 40,
                START, end, null), identity))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("序号");
        byte[] changedKeyDigest = identity.deploymentPublicKeySha256();
        changedKeyDigest[0] ^= 1;
        var sameIdsDifferentKey = new SelfHostedGrantImportService.Installation(
                identity.deploymentId(), identity.tenantId(), changedKeyDigest);
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 42,
                START, end, null), sameIdsDifferentKey))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已导入授权损坏");
        assertThat(service.readCurrent(identity).orElseThrow().sequence()).isEqualTo(41);
        assertThat(app.queryForObject("SELECT count(*) FROM sys_shc_local_grant_import_audit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void twoInstancesSerializeHigherSequenceAndAuditFailureRollsBack() throws Exception {
        UUID tenant = UUID.randomUUID();
        var identity = installation(UUID.randomUUID(), tenant);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "实验租户");
        var firstInstance = service();
        var secondInstance = service();
        Instant end = START.atOffset(ZoneOffset.UTC).plusYears(1).toInstant();
        firstInstance.importGrant(grant(identity, "ENTERPRISE", 41, START, end, null), identity);
        byte[] fortyTwo = grant(identity, "STANDARD", 42, START, end, null);
        byte[] fortyThree = grant(identity, "PROFESSIONAL", 43, START, end, null);
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var lower = workers.submit(() -> importAfter(go, firstInstance, fortyTwo, identity));
            var higher = workers.submit(() -> importAfter(go, secondInstance, fortyThree, identity));
            go.countDown();
            lower.get();
            higher.get();
        }
        assertThat(service().readCurrent(identity).orElseThrow().sequence()).isEqualTo(43);
        assertThat(service().readCurrent(identity).orElseThrow().tier()).isEqualTo("PROFESSIONAL");

        owner.execute("""
                CREATE FUNCTION public.shc_test_reject_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced audit failure'; END $$
                """);
        owner.execute("""
                CREATE TRIGGER sys_shc_local_grant_import_audit_test_reject BEFORE INSERT
                ON public.sys_shc_local_grant_import_audit FOR EACH ROW
                EXECUTE FUNCTION public.shc_test_reject_audit()
                """);
        assertThatThrownBy(() -> firstInstance.importGrant(
                grant(identity, "FREE", 44, START, end, null), identity))
                .isInstanceOf(GeneralSecurityException.class);
        byte[] next = grant(identity, "STANDARD", 44, START, end, null);
        assertThatThrownBy(() -> firstInstance.importGrant(next, identity))
                .hasMessageContaining("forced audit failure");
        assertThat(secondInstance.readCurrent(identity).orElseThrow().sequence()).isEqualTo(43);
        owner.execute("DROP TRIGGER sys_shc_local_grant_import_audit_test_reject ON public.sys_shc_local_grant_import_audit");
        assertThat(firstInstance.importGrant(next, identity).sequence()).isEqualTo(44);
    }

    @Test
    void multiTenantBeforeFirstImportIsRejectedAndSaasRegistrationUnaffected() throws Exception {
        UUID tenant = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "先注册租户");
        app.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", UUID.randomUUID(), "未绑定时第二租户");
        var identity = installation(UUID.randomUUID(), tenant);
        assertThatThrownBy(() -> service().importGrant(grant(identity, "FREE", 1, START, null, null), identity))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只能有已确权的一个");
        assertThat(app.queryForObject("SELECT count(*) FROM sys_shc_local_grant_state", Integer.class))
                .isZero();
    }

    @Test
    void firstPeriodRequiresIssuerStartAndCurrentValidityButLaterIssueKeepsOrigin() throws Exception {
        UUID tenant = UUID.randomUUID();
        var identity = installation(UUID.randomUUID(), tenant);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "实验租户");
        var service = service();
        Instant end = START.atOffset(ZoneOffset.UTC).plusYears(1).toInstant();
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 1,
                START.plusSeconds(2), START, end, null), identity))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("起点必须等于");
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 1,
                START, START, end.minusSeconds(1), null), identity))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("公历周年");
        Instant future = START.plusSeconds(172_800);
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 1,
                future, future, future.atOffset(ZoneOffset.UTC).plusYears(1).toInstant(), null), identity))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未生效");
        Instant past = Instant.parse("2024-09-28T00:00:00Z");
        assertThatThrownBy(() -> service.importGrant(grant(identity, "STANDARD", 1,
                past, past, past.atOffset(ZoneOffset.UTC).plusYears(1).toInstant(), null), identity))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已过期");
        assertThat(app.queryForObject("SELECT count(*) FROM sys_shc_local_grant_state", Integer.class))
                .isZero();
        service.importGrant(grant(identity, "STANDARD", 1, START, end, null), identity);
        service.importGrant(grant(identity, "ENTERPRISE", 2,
                START.plusSeconds(60), START, end, null), identity);
        assertThat(service.readCurrent(identity).orElseThrow().startsAt()).isEqualTo(START);
        assertThat(service.readCurrent(identity).orElseThrow().issuedAt()).isEqualTo(START.plusSeconds(60));
    }

    private SelfHostedGrantImportService service() throws GeneralSecurityException {
        return new SelfHostedGrantImportService(new JdbcSelfHostedGrantRepository(app),
                new DataSourceTransactionManager(appDataSource),
                new SelfHostedGrantImportService.TrustedIssuer(ISSUER, Map.of(KEY_ID, keys.getPublic())),
                Clock.fixed(START.plusSeconds(86_400), ZoneOffset.UTC));
    }

    private static SelfHostedGrantImportService.Installation installation(UUID deployment, UUID tenant)
            throws GeneralSecurityException {
        byte[] publicKeyDigest = MessageDigest.getInstance("SHA-256")
                .digest(deployment.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return new SelfHostedGrantImportService.Installation(deployment, tenant, publicKeyDigest);
    }

    private DataSource dataSource(String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private static boolean importAfter(CountDownLatch go, SelfHostedGrantImportService service,
                                       byte[] envelope, SelfHostedGrantImportService.Installation identity)
            throws InterruptedException {
        go.await();
        try {
            service.importGrant(envelope, identity);
            return true;
        } catch (IllegalStateException stale) {
            return false;
        } catch (GeneralSecurityException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private boolean rejectSecondTenantAfter(CountDownLatch go) throws InterruptedException {
        go.await();
        assertThatThrownBy(() -> app.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)",
                UUID.randomUUID(), "第二租户"))
                .hasMessageContaining("self-hosted installation permits one billing tenant");
        return true;
    }

    private byte[] grant(SelfHostedGrantImportService.Installation identity, String tier, long sequence,
                         Instant starts, Instant ends, Map<String, Long> overrides) throws Exception {
        return grant(identity, tier, sequence, starts, starts, ends, overrides);
    }

    private byte[] grant(SelfHostedGrantImportService.Installation identity, String tier, long sequence,
                         Instant issued, Instant starts, Instant ends, Map<String, Long> overrides) throws Exception {
        var value = revision.tier(tier);
        Map<String, Long> quotas = new TreeMap<>(value.quotas());
        if (overrides != null) quotas.putAll(overrides);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        code(out, ISSUER);
        uuid(out, UUID.randomUUID());
        uuid(out, identity.deploymentId());
        uuid(out, identity.tenantId());
        code(out, tier);
        code(out, revision.revisionId());
        out.write(revision.sha256());
        out.writeLong(sequence);
        time(out, issued);
        time(out, starts);
        out.writeByte(ends == null ? 0 : 1);
        if (ends != null) time(out, ends);
        code(out, KEY_ID);
        out.writeShort(quotas.size());
        for (var entry : quotas.entrySet()) {
            code(out, entry.getKey());
            out.writeLong(entry.getValue());
        }
        Set<String> capabilities = new TreeSet<>(value.capabilities());
        out.writeShort(capabilities.size());
        for (String capability : capabilities) code(out, capability);
        out.flush();
        byte[] canonical = body.toByteArray();
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update("TC-SHC-GRANT-V1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        signature.update(canonical);
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        DataOutputStream signed = new DataOutputStream(envelope);
        signed.write("TCSHC1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        signed.writeInt(canonical.length);
        signed.write(canonical);
        signed.write(signature.sign());
        return envelope.toByteArray();
    }

    private static void code(DataOutputStream out, String code) throws Exception {
        byte[] bytes = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static void uuid(DataOutputStream out, UUID id) throws Exception {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void time(DataOutputStream out, Instant instant) throws Exception {
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
    }
}
