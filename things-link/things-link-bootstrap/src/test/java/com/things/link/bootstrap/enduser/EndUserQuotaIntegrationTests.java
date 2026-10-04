package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.EndUserProvisioningService;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** S14-R4b：真实原事务中的租户身份计数、并发、失败关闭及存量兼容。 */
class EndUserQuotaIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TenantProvisioning tenants;
    @Autowired EndUserProvisioningService service;
    @Autowired PlanCapacityService capacity;
    @Autowired AppUserRepository users;
    @Autowired com.things.link.project.application.TenantResourcePackageService packages;
    private UUID tenant;
    private UUID project;
    private UUID secondProject;
    private UUID account;

    @BeforeEach
    void seed() {
        tenant = tx.execute(s -> tenants.createTenant("R4b"));
        account = Uuid7.generate();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','R4b')",
                account, account + "@example.com");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), tenant, account);
        project = project();
        // 直接构造存量第二项目，证明FREE降档后的跨项目身份仍共享一个池；不绕过生产创建接口。
        secondProject = project();
    }

    private UUID project() {
        UUID id = Uuid7.generate();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'R4b','sh-1',?)",
                id, tenant, "r4b" + id.toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), id, account);
        return id;
    }

    @AfterEach
    void cleanup() {
        TenantContext.set(new TenantScope(tenant, project, account));
        try {
            jdbc.update("DELETE FROM app_user WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM sys_project_member WHERE project_id IN (?,?)", project, secondProject);
            jdbc.update("DELETE FROM sys_project WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM sys_account WHERE id=?", account);
            jdbc.update("DELETE FROM sys_tenant WHERE id=?", tenant);
        } finally { TenantContext.clear(); }
    }

    @Test
    void sharedTenantPoolCountsDisabledAndPreservesDuplicateConflict() {
        create(project, "one");
        create(secondProject, "two");
        create(project, "three");
        withScope(() -> jdbc.update("UPDATE app_user SET status='LOCKED' WHERE tenant_id=?", tenant));
        refused(() -> create(secondProject, "four"), EndUserErrorCode.END_USER_QUOTA_EXCEEDED);
        refused(() -> create(project, " ONE "), EndUserErrorCode.END_USER_USERNAME_TAKEN);
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void concurrentLastSlotIsGrantedExactlyOnceAcrossProjects() throws Exception {
        create(project, "one"); create(project, "two");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> race(barrier, project, "a"));
            var b = pool.submit(() -> race(barrier, secondProject, "b"));
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 60058);
        }
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void rolledBackCreationDoesNotConsumeCapacity() {
        create(project, "one"); create(project, "two");
        withScope(() -> tx.executeWithoutResult(s -> {
            service.provision(project, "rolled-back", "secret123", null);
            s.setRollbackOnly();
        }));
        create(project, "three");
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void activeAdjustmentExpandsAndExpiryImmediatelyProtectsExistingUsers() {
        create(project, "one"); create(project, "two"); create(project, "three");
        UUID adjustment = Uuid7.generate();
        jdbc.update("""
                INSERT INTO sys_tenant_resource_package(id,tenant_id,dimension_code,amount,unit,window_kind,
                  starts_at,ends_at,source,status,adjustment_reason,adjustment_operator_id,adjustment_key)
                VALUES (?,?,'END_USERS_MAX',1,'COUNT','NONE',now()-interval '1 hour',now()+interval '1 hour',
                  'OPERATION_ADJUSTMENT','ACTIVE','R4b test',?,'r4b-capacity')
                """, adjustment, tenant, account);
        create(secondProject, "four");
        jdbc.update("UPDATE sys_tenant_resource_package SET ends_at=now()-interval '1 second' WHERE id=?", adjustment);
        refused(() -> create(project, "five"), EndUserErrorCode.END_USER_QUOTA_EXCEEDED);
        assertThat(count()).isEqualTo(4);
    }

    @Test
    void missingProjectionFailsClosedAndCorrectBindingRestoresAdmission() {
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?", tenant);
        refused(() -> create(project, "one"), ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE);
        assertThat(count()).isZero();
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", tenant);
        create(project, "one");
        assertThat(count()).isEqualTo(1);
        refused(() -> capacity.endUsersLimit(UUID.randomUUID(), project), ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE);
    }

    @Test
    void graceArchiveAndViewerCannotExpand() {
        jdbc.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", project, account);
        refused(() -> create(project, "viewer"), EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        jdbc.update("UPDATE sys_project_member SET role='OWNER' WHERE project_id=? AND account_id=?", project, account);
        jdbc.update("UPDATE sys_tenant_subscription SET status='GRACE',ends_at=now(),grace_ends_at=now()+interval '336 hours' WHERE tenant_id=?", tenant);
        refused(() -> create(project, "grace"), ProjectErrorCode.SUBSCRIPTION_GRACE_NO_EXPANSION);
        jdbc.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        refused(() -> create(project, "archive"), ProjectErrorCode.PROJECT_READ_ONLY);
        assertThat(count()).isZero();
    }

    @Test
    void purchasedPackageAndCrossTenantCallerUseTheResourceOwnerPool() {
        var order = packages.createSimulatedPackageOrder(tenant, "END_USERS_MAX", 1, null);
        packages.applySimulatedPackagePaymentSucceeded(order.id(), "r4b-purchase-" + tenant);
        create(project, "one"); create(project, "two"); create(project, "three");
        TenantContext.set(new TenantScope(UUID.randomUUID(), secondProject, account));
        try { service.provision(secondProject, "four", "secret123", null); }
        finally { TenantContext.clear(); }
        assertThat(count()).isEqualTo(4);
        refused(() -> create(project, "five"), EndUserErrorCode.END_USER_QUOTA_EXCEEDED);
    }

    /** 旧事务起点不能让已过期包继续授权；通过数据库时钟与屏障，不靠睡眠碰时间。 */
    @Test
    void admissionUsesStatementTimeEvenWhenTransactionBeganBeforePackageExpired() throws Exception {
        var order = packages.createSimulatedPackageOrder(tenant, "END_USERS_MAX", 1, null);
        packages.applySimulatedPackagePaymentSucceeded(order.id(), "r4b-clock-" + tenant);
        // 提前起算，保证后续把ends_at移到事务起点附近仍满足起止约束。
        jdbc.update("UPDATE sys_tenant_resource_package SET starts_at=now()-interval '1 hour' WHERE tenant_id=?", tenant);
        create(project, "one"); create(project, "two"); create(project, "three");
        var started = new java.util.concurrent.CompletableFuture<java.sql.Timestamp>();
        var changed = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> {
                TenantContext.set(new TenantScope(tenant, project, account));
                try {
                    BusinessException error = catchThrowableOfType(() -> tx.executeWithoutResult(s -> {
                        started.complete(jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class));
                        try { assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        service.provision(project, "expired", "secret123", null);
                    }), BusinessException.class);
                    return error;
                } finally { TenantContext.clear(); }
            });
            try {
                var transactionStart = started.get(10, TimeUnit.SECONDS);
                var expiry = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class);
                assertThat(expiry).isAfter(transactionStart);
                jdbc.update("UPDATE sys_tenant_resource_package SET ends_at=? WHERE tenant_id=?", expiry, tenant);
            } finally { changed.countDown(); }
            assertThat(result.get(20, TimeUnit.SECONDS).errorCode()).isEqualTo(EndUserErrorCode.END_USER_QUOTA_EXCEEDED);
        }
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void strongSnapshotOrMissingTransactionCannotPretendToSerialize() {
        withScope(() -> assertThatThrownBy(() -> users.lockTenantCapacity(tenant)).hasRootCauseInstanceOf(IllegalStateException.class));
        TransactionTemplate repeatable = new TransactionTemplate(tx.getTransactionManager());
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        withScope(() -> assertThatThrownBy(() -> repeatable.executeWithoutResult(s ->
                service.provision(project, "snapshot", "secret123", null))).isInstanceOfAny(IllegalStateException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));
        assertThat(count()).isZero();
    }

    private int race(CyclicBarrier barrier, UUID pid, String name) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try { create(pid, name); return 0; }
        catch (BusinessException e) { return e.errorCode().code(); }
    }
    private void create(UUID pid, String name) {
        TenantContext.set(new TenantScope(tenant, pid, account));
        try { service.provision(pid, name, "secret123", null); }
        finally { TenantContext.clear(); }
    }
    private void withScope(Runnable action) {
        TenantContext.set(new TenantScope(tenant, project, account));
        try { action.run(); } finally { TenantContext.clear(); }
    }
    private long count() {
        TenantContext.set(new TenantScope(tenant, project, account));
        try { return users.countByTenant(tenant); } finally { TenantContext.clear(); }
    }
    private static void refused(Runnable action, ErrorCode expected) {
        BusinessException error = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(error).isNotNull();
        assertThat(error.errorCode()).isEqualTo(expected);
    }
}
