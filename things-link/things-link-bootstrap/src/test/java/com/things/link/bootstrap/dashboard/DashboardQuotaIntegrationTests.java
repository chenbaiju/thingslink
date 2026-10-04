package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.application.DashboardManagementService;
import com.things.link.dashboard.application.publication.DashboardPublicationService;
import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** S14-R4c：普通应用角色下的真实跨项目计数、幂等和并发准入。 */
class DashboardQuotaIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TenantProvisioning tenants;
    @Autowired DashboardManagementService service;
    @Autowired DashboardPublicationService publication;
    @Autowired DashboardRepository repository;
    @Autowired TenantResourcePackageService packages;
    private JdbcTemplate owner;
    private UUID tenant;
    private UUID project;
    private UUID second;
    private UUID account;
    private static final byte[] DRAFT = """
            {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},
             "models":[],"variables":[],"pages":[{"id":"overview","title":"额度验收","components":[]}]}
            """.getBytes(StandardCharsets.UTF_8);

    @BeforeEach void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tenant = tx.execute(s -> tenants.createTenant("R4c"));
        account = Uuid7.generate();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','R4c')", account, account + "@example.com");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", Uuid7.generate(), tenant, account);
        project = project(); second = project();
    }
    private UUID project() {
        UUID id = Uuid7.generate();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'R4c','sh-1',?)", id, tenant, "r4c" + id.toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')", Uuid7.generate(), id, account);
        return id;
    }
    @AfterEach void cleanup() {
        TenantContext.clear();
        for (String table : java.util.List.of("dash_dashboard_creation_result", "dash_dashboard_draft_model_ref", "dash_dashboard_draft", "dash_dashboard")) {
            owner.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
        }
        owner.update("DELETE FROM sys_project_member WHERE project_id IN (?,?)", project, second);
        owner.update("DELETE FROM sys_project WHERE tenant_id=?", tenant);
        owner.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?", tenant);
        owner.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?", tenant);
        owner.update("DELETE FROM sys_tenant_order WHERE tenant_id=?", tenant);
        owner.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", tenant);
        owner.update("DELETE FROM sys_account WHERE id=?", account);
        owner.update("DELETE FROM sys_tenant WHERE id=?", tenant);
    }

    @Test void freePoolIncludesOtherProjectsAndArchivedResources() {
        create(project, "one");
        jdbc.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        refused(() -> create(second, "two"), DashboardErrorCode.DASHBOARD_QUOTA_EXCEEDED);
        assertThat(scoped(second, () -> repository.countTenantDashboards(tenant, second))).isEqualTo(1);
        assertThat(scoped(second, () -> jdbc.queryForObject("SELECT count(*) FROM dash_dashboard", Long.class))).isZero();
        owner.update("UPDATE sys_project SET status='DELETING',deleted_at=now(),lifecycle_generation=lifecycle_generation+1 WHERE id=?", project);
        refused(() -> create(second, "three"), DashboardErrorCode.DASHBOARD_QUOTA_EXCEEDED);
    }

    @Test void repeatedCreationRestoresFullPoolAndSoftDeletionReleasesCapacity() {
        var first = create(project, "one");
        assertThat(create(project, "one").id()).isEqualTo(first.id());
        refused(() -> scoped(project, () -> service.createIdempotent(project, "one", "不同内容", DRAFT)), CommonErrorCode.RESOURCE_STATE_CONFLICT);
        scoped(project, () -> { publication.softDelete(project, first.id(), "0"); return null; });
        refused(() -> create(project, "one"), CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);
        create(second, "two");
        assertThat(scoped(second, () -> repository.countTenantDashboards(tenant, second))).isEqualTo(1);
    }

    @Test void twoProjectsRaceForOneTenantSlot() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> race(barrier, project, "race-a"));
            var b = pool.submit(() -> race(barrier, second, "race-b"));
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 60059);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_dashboard WHERE tenant_id=?", Long.class, tenant)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_dashboard_creation_result WHERE tenant_id=?", Long.class, tenant)).isEqualTo(1);
    }

    @Test void rollbackLeavesNoCatalogOrRecoveryMapping() {
        scoped(project, () -> tx.execute(s -> {
            service.createIdempotent(project, "rollback", "看板", DRAFT);
            s.setRollbackOnly(); return null;
        }));
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_dashboard_creation_result WHERE tenant_id=?", Long.class, tenant)).isZero();
        create(second, "next");
    }

    @Test void packageExpiryBlocksNewButPreservesReplayAndExistingCatalogs() {
        var order = packages.createSimulatedPackageOrder(tenant, "DASHBOARDS_MAX", 1, null);
        packages.applySimulatedPackagePaymentSucceeded(order.id(), "r4c-" + tenant);
        var first = create(project, "one"); create(second, "two");
        owner.update("UPDATE sys_tenant_resource_package SET status='EXPIRED' WHERE tenant_id=?", tenant);
        refused(() -> create(second, "three"), DashboardErrorCode.DASHBOARD_QUOTA_EXCEEDED);
        assertThat(create(project, "one").id()).isEqualTo(first.id());
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_dashboard WHERE tenant_id=?", Long.class, tenant)).isEqualTo(2);
        owner.update("""
                INSERT INTO sys_tenant_resource_package(id,tenant_id,dimension_code,amount,unit,window_kind,
                    starts_at,ends_at,source,status,adjustment_reason,adjustment_operator_id,adjustment_key)
                VALUES (?,?,'DASHBOARDS_MAX',2,'COUNT','NONE',now()-interval '1 hour',now()+interval '1 hour',
                    'OPERATION_ADJUSTMENT','ACTIVE','R4c test',?,'r4c-adjustment')
                """, Uuid7.generate(), tenant, account);
        create(second, "three");
        refused(() -> create(second, "four"), DashboardErrorCode.DASHBOARD_QUOTA_EXCEEDED);
    }

    @Test void missingProjectionGraceAndWrongIdentityFailClosed() {
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?", tenant);
        refused(() -> create(project, "missing"), ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE);
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", tenant);
        jdbc.update("UPDATE sys_tenant_subscription SET status='GRACE',ends_at=now(),grace_ends_at=now()+interval '336 hours' WHERE tenant_id=?", tenant);
        refused(() -> create(project, "grace"), ProjectErrorCode.SUBSCRIPTION_GRACE_NO_EXPANSION);
        assertThat(scoped(project, () -> jdbc.queryForObject("SELECT dashboard_tenant_capacity_count(?,?)", Long.class, UUID.randomUUID(), project))).isNull();
        assertThat(scoped(project, () -> jdbc.queryForObject("SELECT dashboard_tenant_capacity_count(?,?)", Long.class, tenant, second))).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_dashboard WHERE tenant_id=?", Long.class, tenant)).isZero();
    }

    private int race(CyclicBarrier barrier, UUID pid, String key) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try { create(pid, key); return 0; } catch (BusinessException e) { return e.errorCode().code(); }
    }
    private DashboardCatalogEntry create(UUID pid, String key) {
        return scoped(pid, () -> service.createIdempotent(pid, key, "看板", DRAFT));
    }
    private <T> T scoped(UUID pid, Supplier<T> action) {
        TenantContext.set(new TenantScope(tenant, pid, account));
        try { return action.get(); } finally { TenantContext.clear(); }
    }
    private static void refused(Runnable action, ErrorCode expected) {
        var error = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(error).isNotNull(); assertThat(error.errorCode()).isEqualTo(expected);
    }
}
