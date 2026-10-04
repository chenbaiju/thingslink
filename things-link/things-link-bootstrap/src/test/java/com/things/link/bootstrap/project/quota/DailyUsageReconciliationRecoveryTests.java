package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.DailyUsageReconciliationService;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.project.application.TrustedProjectUsageFactRecorder;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * R4-4c real PostgreSQL transaction failure/retry, without stopping shared infrastructure.
 * Dates and a claimed lease are controlled fixtures: this is neither real UTC rollover nor
 * scanner/process-crash lease takeover qualification. REST facts are recorded through the
 * trusted production port; this does not qualify the HTTP filter's daily recording toggle.
 */
class DailyUsageReconciliationRecoveryTests extends AbstractIntegrationTest {
    @Autowired private DailyUsageReconciliationService reconciliation;
    @Autowired private TrustedProjectUsageFactRecorder facts;
    @Autowired private ProjectDailyQuotaDecisionService decisions;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate applicationJdbc;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private JdbcTemplate owner;

    @Test
    @Timeout(30)
    void lockFailureRollsBackBothDaysAndRetryRestoresAbsoluteUsageAndLease() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        seedOwnedProject(today);
        var scope = new DailyUsageScope(tenantId, projectId);
        Timestamp originalLease = leaseUntil();
        Timestamp originalAfter = reconcileAfter();
        assertThat(originalLease).isNotNull();
        assertThat(decisions.decideTrustedProject(tenantId, projectId, QuotaMetric.REST_API_CALL))
                .isEqualTo(QuotaStatus.NORMAL);
        assertThat(factCount()).isEqualTo(5);

        try (Connection lock = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("""
                    SELECT used_value FROM sys_usage_counter_daily
                     WHERE tenant_id = ? AND project_id = ? AND usage_date = ? AND metric = 'REST_API_CALL'
                     FOR UPDATE
                    """)) {
                statement.setObject(1, tenantId);
                statement.setObject(2, projectId);
                statement.setObject(3, today);
                try (var row = statement.executeQuery()) {
                    assertThat(row.next()).as("Only this test's current-day row is locked").isTrue();
                    assertThat(row.getLong(1)).isEqualTo(1L);
                    assertThat(row.next()).isFalse();
                }
            }
            // The production service merges yesterday first; today's UPSERT now encounters a real PG lock.
            // SET LOCAL applies only to this transaction/connection, never another tenant or pooled session.
            Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
                applicationJdbc.execute("SET LOCAL lock_timeout = '250ms'");
                applicationJdbc.execute("SET LOCAL statement_timeout = '5s'");
                reconciliation.reconcile(scope);
            }));
            assertThat(failure).as("Current-day UPDATE must fail at the PostgreSQL lock boundary").isNotNull();
            assertThat(sqlState(failure)).as("Real PG lock timeout, not an injected Java exception").isEqualTo("55P03");
            assertThat(used(today.minusDays(1))).as("Earlier yesterday merge rolled back with today's failure").isEqualTo(1L);
            assertThat(used(today)).isEqualTo(1L);
            assertThat(factCount()).as("Previously committed facts survive the failed projection transaction").isEqualTo(5L);
            assertThat(leaseUntil()).as("Failure must not release the claimed lease early").isEqualTo(originalLease);
            assertThat(reconcileAfter()).as("Failure must not mark reconciliation complete").isEqualTo(originalAfter);
            assertThat(decisions.decideTrustedProject(tenantId, projectId, QuotaMetric.REST_API_CALL))
                    .isEqualTo(QuotaStatus.NORMAL);
            lock.rollback();
        }

        // Explicit service retry after removal of the lock; no fake lease expiry or scheduler takeover claim.
        reconciliation.reconcile(scope);
        assertRecovered(today);
        // Replay the same durable event and absolute reconciliation; neither can double the daily bill.
        assertThat(facts.recordTrusted(tenantId, projectId, QuotaMetric.REST_API_CALL,
                "recovery-today-0", today.atTime(12, 0).toInstant(ZoneOffset.UTC))).isTrue();
        reconciliation.reconcile(scope);
        assertRecovered(today);
        assertThat(LocalDate.now(ZoneOffset.UTC)).as("This test is an ordinary-day fixture, not UTC rollover evidence")
                .isEqualTo(today);
    }

    /** Seed only an owned project and stale daily projections; bypassing claim avoids leasing unrelated projects. */
    private void seedOwnedProject(LocalDate today) {
        owner.update("""
                INSERT INTO sys_quota_policy(id, code, rest_api_call_daily_limit)
                VALUES (?, ?, 2)
                """, policyId, "R4REC" + policyId.toString().replace("-", "").substring(0, 20));
        owner.update("INSERT INTO sys_tenant(id, name, quota_policy_id) VALUES (?, 'R4 recovery tenant', ?)",
                tenantId, policyId);
        owner.update("""
                INSERT INTO sys_project(id, tenant_id, name, region, project_key,
                    usage_reconcile_after, usage_reconcile_lease_until)
                VALUES (?, ?, 'R4 recovery project', 'sh-1', ?, now() - interval '1 minute', now() + interval '2 minutes')
                """, projectId, tenantId, "r4rec" + projectId.toString().replace("-", ""));
        for (LocalDate date : new LocalDate[]{today.minusDays(1), today}) {
            owner.update("""
                    INSERT INTO sys_usage_counter_daily(id, tenant_id, project_id, usage_date, metric, used_value)
                    VALUES (?, ?, ?, ?, 'REST_API_CALL', 1)
                    """, UUID.randomUUID(), tenantId, projectId, date);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(facts.recordTrusted(tenantId, projectId, QuotaMetric.REST_API_CALL,
                    "recovery-yesterday-" + i, today.minusDays(1).atTime(12, i).toInstant(ZoneOffset.UTC))).isTrue();
        }
        for (int i = 0; i < 3; i++) {
            assertThat(facts.recordTrusted(tenantId, projectId, QuotaMetric.REST_API_CALL,
                    "recovery-today-" + i, today.atTime(12, i).toInstant(ZoneOffset.UTC))).isTrue();
        }
    }

    private void assertRecovered(LocalDate today) {
        assertThat(used(today.minusDays(1))).isEqualTo(2L);
        assertThat(used(today)).isEqualTo(3L);
        assertThat(factCount()).isEqualTo(5L);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM sys_usage_counter_daily
                 WHERE tenant_id = ? AND project_id = ? AND metric = 'REST_API_CALL'
                """, Long.class, tenantId, projectId)).isEqualTo(2L);
        assertThat(leaseUntil()).as("Committed reconciliation completes the lease").isNull();
        assertThat(owner.queryForObject("""
                SELECT usage_reconcile_after > now() FROM sys_project WHERE tenant_id = ? AND id = ?
                """, Boolean.class, tenantId, projectId)).isTrue();
        assertThat(owner.queryForObject("""
                SELECT tenant_used_value FROM trusted_project_daily_quota_decision(?, ?, ?, 'REST_API_CALL')
                """, Long.class, tenantId, projectId, today)).isEqualTo(3L);
        assertThat(decisions.decideTrustedProject(tenantId, projectId, QuotaMetric.REST_API_CALL))
                .as("Decision reads the recovered current-day projection, excluding yesterday")
                .isEqualTo(QuotaStatus.DEGRADED);
    }

    private long used(LocalDate date) {
        return owner.queryForObject("""
                SELECT used_value FROM sys_usage_counter_daily
                 WHERE tenant_id = ? AND project_id = ? AND usage_date = ? AND metric = 'REST_API_CALL'
                """, Long.class, tenantId, projectId, date);
    }

    private long factCount() {
        return owner.queryForObject("SELECT count(*) FROM sys_usage_fact WHERE tenant_id = ? AND project_id = ?",
                Long.class, tenantId, projectId);
    }

    private Timestamp leaseUntil() {
        return owner.queryForObject("SELECT usage_reconcile_lease_until FROM sys_project WHERE tenant_id = ? AND id = ?",
                Timestamp.class, tenantId, projectId);
    }

    private Timestamp reconcileAfter() {
        return owner.queryForObject("SELECT usage_reconcile_after FROM sys_project WHERE tenant_id = ? AND id = ?",
                Timestamp.class, tenantId, projectId);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (owner != null) {
                owner.update("DELETE FROM sys_usage_fact WHERE tenant_id = ? AND project_id = ?", tenantId, projectId);
                owner.update("DELETE FROM sys_usage_counter_daily WHERE tenant_id = ? AND project_id = ?", tenantId, projectId);
                owner.update("DELETE FROM sys_project WHERE tenant_id = ? AND id = ?", tenantId, projectId);
                owner.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
                owner.update("DELETE FROM sys_quota_policy WHERE id = ?", policyId);
            }
        } finally {
            TenantContext.clear();
        }
    }
}
