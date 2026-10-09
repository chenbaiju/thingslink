package com.things.link.bootstrap.telemetry.overview;

import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.domain.OverviewTrendRepository;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OverviewTrendsDatabaseTests extends AbstractIntegrationTest {
    final UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), other = UUID.randomUUID();
    final Instant from = Instant.parse("2026-10-08T00:00:00Z"), to = from.plusSeconds(6 * 3600);
    @Autowired OverviewTrendRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate owner;
    SingleConnectionDataSource dataSource;

    @BeforeEach void prepare() throws Exception {
        dataSource = new SingleConnectionDataSource(DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()), true);
        owner = new JdbcTemplate(dataSource);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'overview trends')", tenant);
        for (UUID id : new UUID[]{project, other}) owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'trend','sh-1',?)", id, tenant, id.toString());
        TenantContext.set(new TenantScope(tenant, project, UUID.randomUUID()));
    }
    @AfterEach void clean() {
        TenantContext.clear();
        if (owner != null) {
            owner.update("DELETE FROM sys_project WHERE id IN (?,?)", project, other);
            owner.update("DELETE FROM sys_tenant WHERE id=?", tenant);
            dataSource.destroy();
        }
    }
    void sample(UUID id, String source, String metric, int hour, long value) {
        owner.update("INSERT INTO ts_project_overview_sample(tenant_id,project_id,source,metric,sampled_at,value) VALUES (?,?,?,?,?,?)", tenant, id, source, metric, Timestamp.from(from.plusSeconds(hour * 3600L)), value);
    }
    @Test void sumsCountersUsesLastGaugeAndReportsMissingHoursWithoutMixingSources() {
        for (int hour=0; hour<3; hour++) {
            sample(project, "SIMULATED", "message.total", hour, 100);
            sample(project, "OBSERVED", "message.total", hour, hour);
            sample(project, "OBSERVED", "device.active", hour, 8-hour);
        }
        sample(project, "OBSERVED", "message.total", 3, 5);
        sample(other, "OBSERVED", "message.total", 0, 999);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(repository.source(tenant, project, from, to)).isEqualTo("OBSERVED");
            var rows = repository.aggregate(tenant, project, from, to, 3, "OBSERVED");
            assertThat(rows).contains(new OverviewTrendRepository.Bucket("message.total",0,3,3), new OverviewTrendRepository.Bucket("device.active",0,6,3), new OverviewTrendRepository.Bucket("message.total",1,5,1));
            assertThat(repository.source(tenant, other, from, to)).isEqualTo("NONE");
            assertThat(repository.aggregate(tenant, other, from, to,3,"OBSERVED")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ts_project_overview_sample WHERE project_id=?",Long.class,other)).isZero();
        });
    }
    @Test void constraintsRejectInvalidValuesSourcesKeysTimesAndProjectOwner() {
        for (String sql : new String[]{
                "UPDATE ts_project_overview_sample SET value=-1 WHERE project_id=?",
                "UPDATE ts_project_overview_sample SET source='UNKNOWN' WHERE project_id=?",
                "UPDATE ts_project_overview_sample SET metric='invented' WHERE project_id=?",
                "UPDATE ts_project_overview_sample SET sampled_at=sampled_at+interval '1 minute' WHERE project_id=?",
                "UPDATE ts_project_overview_sample SET tenant_id=gen_random_uuid() WHERE project_id=?"}) {
            if (owner.queryForObject("SELECT count(*) FROM ts_project_overview_sample WHERE project_id=?",Integer.class,project)==0) sample(project,"SIMULATED","message.total",0,0);
            assertThatThrownBy(() -> owner.update(sql,project)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO ts_project_overview_sample(tenant_id,project_id,source,metric,sampled_at,value) VALUES (?,?,'SIMULATED','message.total',?,1)",tenant,other,Timestamp.from(from))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        });
        owner.update("DELETE FROM sys_project WHERE id=?",project);
        assertThat(owner.queryForObject("SELECT count(*) FROM ts_project_overview_sample WHERE project_id=?",Integer.class,project)).isZero();
    }
}
