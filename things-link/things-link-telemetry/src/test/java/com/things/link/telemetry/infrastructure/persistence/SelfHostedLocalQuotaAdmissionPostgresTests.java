package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 PostgreSQL 检查跨项目事实、原子日账和并发第 N+1 条拒绝。 */
@Testcontainers
class SelfHostedLocalQuotaAdmissionPostgresTests {
    private static final String[] MIGRATIONS = {
            "/db/migration/project/V20260928_0120__self_hosted_local_grant_import.sql",
            "/db/migration/project/V20260928_0130__self_hosted_local_quota_projection.sql",
            "/db/migration/device/V20260928_0140__self_hosted_local_device_quota.sql",
            "/db/migration/telemetry/V20260928_0150__self_hosted_local_message_quota.sql"};
    private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-09-28T23:59:59Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("shc_quota").withUsername("postgres").withPassword("test-owner-password");

    private JdbcTemplate owner;
    private UUID tenant;
    private UUID firstProject;
    private UUID secondProject;
    private ApprovedSelfHostedRevision approved;

    @BeforeEach
    void migrateIsolatedFacts() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        owner = new JdbcTemplate(source);
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
        owner.execute("CREATE TABLE sys_tenant(id uuid PRIMARY KEY, name text NOT NULL)");
        owner.execute("CREATE TABLE sys_project(id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES sys_tenant(id))");
        owner.execute("""
                CREATE TABLE dev_device(id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
                    project_id uuid NOT NULL REFERENCES sys_project(id), deleted_at timestamptz)
                """);
        owner.execute("""
                CREATE TABLE sys_inbox_message(message_id uuid PRIMARY KEY,
                    project_id uuid NOT NULL REFERENCES sys_project(id), received_at timestamptz NOT NULL)
                """);
        owner.execute("""
                CREATE TABLE ts_device_command(id uuid PRIMARY KEY,
                    project_id uuid NOT NULL REFERENCES sys_project(id), accepted_at timestamptz NOT NULL)
                """);
        for (String resource : MIGRATIONS) {
            try (var stream = getClass().getResourceAsStream(resource)) {
                assertThat(stream).as(resource).isNotNull();
                owner.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        tenant = UUID.randomUUID();
        firstProject = UUID.randomUUID();
        secondProject = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "本地额度租户");
        owner.update("INSERT INTO sys_project(id,tenant_id) VALUES (?,?),(?,?)",
                firstProject, tenant, secondProject, tenant);
        approved = ApprovedSelfHostedRevision.loadApproved();
    }

    @Test
    void freeFactsAcrossTwoProjectsReachExactBoundaryAndReplayDoesNotChargeAgain() throws Exception {
        // 先有同日事实再导入：首个后续写入必须把原有事实算进去。
        device(firstProject);
        device(firstProject);
        owner.update("""
                INSERT INTO sys_inbox_message(message_id,project_id,received_at)
                SELECT gen_random_uuid(), ?::uuid, ?::timestamptz FROM generate_series(1,698)
                """, firstProject, Timestamp.from(BEFORE_MIDNIGHT));
        owner.update("""
                INSERT INTO ts_device_command(id,project_id,accepted_at)
                SELECT gen_random_uuid(), ?::uuid, ?::timestamptz FROM generate_series(1,299)
                """, firstProject, Timestamp.from(BEFORE_MIDNIGHT));
        bind("FREE");

        device(secondProject);
        assertThatThrownBy(() -> device(secondProject)).rootCause()
                .hasMessageContaining("self-hosted device quota exceeded");
        UUID atBoundary = UUID.randomUUID();
        uplink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT);
        uplink(secondProject, atBoundary, BEFORE_MIDNIGHT);
        assertThatThrownBy(() -> uplink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT))
                .rootCause().hasMessageContaining("self-hosted message quota exceeded");
        assertThat(owner.update("""
                INSERT INTO sys_inbox_message(message_id,project_id,received_at)
                VALUES (?,?,?) ON CONFLICT (message_id) DO NOTHING
                """, atBoundary, secondProject, Timestamp.from(BEFORE_MIDNIGHT))).isZero();
        downlink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT);
        assertThatThrownBy(() -> downlink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT))
                .rootCause().hasMessageContaining("self-hosted message quota exceeded");

        assertThat(deviceUsage()).isEqualTo(3L);
        assertThat(messageUsage(DAY, "UP")).isEqualTo(700L);
        assertThat(messageUsage(DAY, "DOWN")).isEqualTo(300L);
        assertThat(owner.queryForObject("SELECT used_count FROM sys_shc_local_message_usage "
                + "WHERE usage_date=? AND direction='UP'", Long.class, DAY)).isEqualTo(700L);

        // UTC 零点属于新日，而技术零值要拒绝首条。
        uplink(secondProject, UUID.randomUUID(), Instant.parse("2026-09-29T00:00:00Z"));
        assertThat(messageUsage(DAY.plusDays(1), "UP")).isEqualTo(1L);
        owner.update("UPDATE sys_shc_local_grant_state SET downlink_message_daily=0 WHERE singleton");
        assertThatThrownBy(() -> downlink(secondProject, UUID.randomUUID(),
                Instant.parse("2026-09-29T00:00:00Z")))
                .rootCause().hasMessageContaining("self-hosted message quota exceeded");
    }

    @Test
    void allApprovedTiersUseExactDeviceAndMessageLimits() throws Exception {
        String[] tiers = {"FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL"};
        bind("FREE");
        for (int index = 0; index < tiers.length; index++) {
            var quotas = approved.tier(tiers[index]).quotas();
            long deviceLimit = quotas.get("DEVICES_MAX");
            long upLimit = quotas.get("UPLINK_MESSAGE_DAILY");
            long downLimit = quotas.get("DOWNLINK_MESSAGE_DAILY");
            owner.update("""
                    UPDATE sys_shc_local_grant_state
                       SET devices_max=?,uplink_message_daily=?,downlink_message_daily=? WHERE singleton
                    """, deviceLimit, upLimit, downLimit);
            owner.execute("TRUNCATE dev_device,sys_inbox_message,ts_device_command,sys_shc_local_message_usage");
            owner.execute("ALTER TABLE dev_device DISABLE TRIGGER dev_device_shc_local_insert_admit");
            try {
                owner.update("""
                        INSERT INTO dev_device(id,tenant_id,project_id)
                        SELECT gen_random_uuid(), ?::uuid, ?::uuid
                          FROM generate_series(1, ?)
                        """, tenant, firstProject, deviceLimit - 1);
            } finally {
                owner.execute("ALTER TABLE dev_device ENABLE TRIGGER dev_device_shc_local_insert_admit");
            }
            // 日账 fixture 模拟同日已确认的历史消息；真实事实回读由 FREE 测试覆盖。
            LocalDate day = DAY.plusDays(index);
            owner.update("""
                    INSERT INTO sys_shc_local_message_usage
                        (billing_tenant_id,usage_date,direction,used_count)
                    VALUES (?,?, 'UP',?),(?,?, 'DOWN',?)
                    """, tenant, day, upLimit - 1, tenant, day, downLimit - 1);
            device(secondProject);
            assertThatThrownBy(() -> device(secondProject)).rootCause()
                    .hasMessageContaining("self-hosted device quota exceeded");
            Instant when = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            uplink(secondProject, UUID.randomUUID(), when);
            downlink(secondProject, UUID.randomUUID(), when);
            assertThatThrownBy(() -> uplink(secondProject, UUID.randomUUID(), when)).rootCause()
                    .hasMessageContaining("self-hosted message quota exceeded");
            assertThatThrownBy(() -> downlink(secondProject, UUID.randomUUID(), when)).rootCause()
                    .hasMessageContaining("self-hosted message quota exceeded");
            assertThat(deviceUsage()).isEqualTo(deviceLimit);
            assertThat(messageUsage(day, "UP")).isEqualTo(upLimit);
            assertThat(messageUsage(day, "DOWN")).isEqualTo(downLimit);
        }
    }

    @Test
    void concurrentWritersAcrossProjectsAdmitOnlyOneLastUplink() throws Exception {
        bind("FREE");
        owner.update("""
                INSERT INTO sys_shc_local_message_usage
                    (billing_tenant_id,usage_date,direction,used_count) VALUES (?,?, 'UP',699)
                """, tenant, DAY);
        CountDownLatch go = new CountDownLatch(1);
        List<Boolean> outcomes = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> attemptAfter(go, firstProject));
            var two = workers.submit(() -> attemptAfter(go, secondProject));
            go.countDown();
            outcomes.add(one.get());
            outcomes.add(two.get());
        }
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(messageUsage(DAY, "UP")).isEqualTo(700L);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_inbox_message", Long.class)).isEqualTo(1L);
    }

    @Test
    void absentGrantLeavesSaasFactsAloneButIncompleteImportedProjectionFailsClosed() throws Exception {
        for (int i = 0; i < 4; i++) device(firstProject);
        uplink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT);
        downlink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT);
        assertThat(deviceUsageWithoutBinding()).isEqualTo(4L);

        bind("FREE");
        owner.update("""
                UPDATE sys_shc_local_grant_state SET devices_max=NULL,
                    uplink_message_daily=NULL, downlink_message_daily=NULL WHERE singleton
                """);
        assertThatThrownBy(() -> device(secondProject)).rootCause()
                .hasMessageContaining("requires verified re-import");
        assertThatThrownBy(() -> uplink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT))
                .rootCause().hasMessageContaining("requires verified re-import");
        assertThatThrownBy(() -> downlink(secondProject, UUID.randomUUID(), BEFORE_MIDNIGHT))
                .rootCause().hasMessageContaining("requires verified re-import");
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_inbox_message", Long.class)).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM ts_device_command", Long.class)).isEqualTo(1L);
    }

    @Test
    void oneBulkStatementCountsEachInsertedFactOnceAndRejectsWholeOverflowBatch() throws Exception {
        bind("FREE");
        owner.update("""
                INSERT INTO sys_inbox_message(message_id,project_id,received_at)
                SELECT gen_random_uuid(), ?::uuid, ?::timestamptz FROM generate_series(1,700)
                """, firstProject, Timestamp.from(BEFORE_MIDNIGHT));
        assertThat(messageUsage(DAY, "UP")).isEqualTo(700L);
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO sys_inbox_message(message_id,project_id,received_at)
                SELECT gen_random_uuid(), ?::uuid, ?::timestamptz FROM generate_series(1,2)
                """, secondProject, Timestamp.from(BEFORE_MIDNIGHT)))
                .rootCause().hasMessageContaining("self-hosted message quota exceeded");
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_inbox_message", Long.class)).isEqualTo(700L);
        assertThat(messageUsage(DAY, "UP")).isEqualTo(700L);
    }

    private boolean attemptAfter(CountDownLatch go, UUID project) throws InterruptedException {
        go.await();
        try {
            uplink(project, UUID.randomUUID(), BEFORE_MIDNIGHT);
            return true;
        } catch (org.springframework.dao.DataAccessException rejected) {
            return false;
        }
    }

    private void bind(String tier) throws Exception {
        var quotas = approved.tier(tier).quotas();
        owner.update("""
                INSERT INTO sys_shc_local_grant_state
                    (deployment_id,billing_tenant_id,deployment_public_key_sha256,grant_id,
                     highest_sequence,signed_envelope,envelope_sha256,
                     devices_max,uplink_message_daily,downlink_message_daily)
                VALUES (?,?,decode(repeat('00',32),'hex'),?,1,
                    decode(repeat('00',76),'hex'),decode(repeat('00',32),'hex'),?,?,?)
                """, UUID.randomUUID(), tenant, UUID.randomUUID(),
                quotas.get("DEVICES_MAX"), quotas.get("UPLINK_MESSAGE_DAILY"),
                quotas.get("DOWNLINK_MESSAGE_DAILY"));
    }

    private UUID device(UUID project) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id) VALUES (?,?,?)", id, tenant, project);
        return id;
    }

    private void uplink(UUID project, UUID id, Instant receivedAt) {
        owner.update("INSERT INTO sys_inbox_message(message_id,project_id,received_at) VALUES (?,?,?)",
                id, project, Timestamp.from(receivedAt));
    }

    private void downlink(UUID project, UUID id, Instant acceptedAt) {
        owner.update("INSERT INTO ts_device_command(id,project_id,accepted_at) VALUES (?,?,?)",
                id, project, Timestamp.from(acceptedAt));
    }

    private long deviceUsage() {
        return owner.queryForObject("SELECT shc_local_device_usage(?)", Long.class, tenant);
    }

    private long deviceUsageWithoutBinding() {
        return owner.queryForObject("SELECT count(*) FROM dev_device WHERE tenant_id=? AND deleted_at IS NULL",
                Long.class, tenant);
    }

    private long messageUsage(LocalDate day, String direction) {
        return owner.queryForObject("SELECT shc_local_message_usage(?,?,?)", Long.class,
                tenant, day, direction);
    }
}
