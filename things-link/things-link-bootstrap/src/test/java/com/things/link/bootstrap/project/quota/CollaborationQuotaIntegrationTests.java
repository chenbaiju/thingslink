package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.ProjectMemberService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** S14-R4d：项目排他许可、账号与租户容量锁下的真实成员新增。 */
class CollaborationQuotaIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TenantProvisioning tenants;
    @Autowired ProjectMemberService service;
    @Autowired TenantResourcePackageService packages;
    private UUID tenant, foreignTenant, actor, project, second;
    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<UUID> projects = new ArrayList<>();
    private final List<UUID> accounts = new ArrayList<>();

    @BeforeEach void seed() {
        tenant = tx.execute(s -> tenants.createTenant("R4d owner"));
        foreignTenant = tx.execute(s -> tenants.createTenant("R4d foreign"));
        tenantIds.add(tenant); tenantIds.add(foreignTenant);
        actor = account(tenant); project = project(); second = project();
    }
    @AfterEach void cleanup() {
        TenantContext.clear();
        for (UUID id : projects) jdbc.update("DELETE FROM sys_project_member WHERE project_id=?", id);
        for (UUID id : projects) jdbc.update("DELETE FROM sys_project WHERE id=?", id);
        for (UUID id : tenantIds) {
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?", id);
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?", id);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?", id);
            jdbc.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", id);
        }
        for (UUID id : accounts) jdbc.update("DELETE FROM sys_account WHERE id=?", id);
        for (UUID id : tenantIds) jdbc.update("DELETE FROM sys_tenant WHERE id=?", id);
        tenantIds.clear(); projects.clear(); accounts.clear();
    }
    private UUID account(UUID home) {
        UUID id = Uuid7.generate(); accounts.add(id);
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','R4d')", id, email(id));
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", Uuid7.generate(), home, id);
        return id;
    }
    private UUID project() { return project(tenant, actor); }
    private UUID project(UUID ownerTenant, UUID ownerAccount) {
        UUID id = Uuid7.generate(); projects.add(id);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'R4d','sh-1',?)", id, ownerTenant, "r4d" + id.toString().replace("-", ""));
        member(id, ownerAccount, "OWNER"); return id;
    }
    private void member(UUID pid, UUID id, String role) {
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)", Uuid7.generate(), pid, id, role);
    }
    private void plan(String code) {
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code=?) WHERE id=?", code, tenant);
    }
    private static String email(UUID id) { return id + "@example.com"; }
    private void invite(UUID pid, UUID id) {
        TenantContext.set(new TenantScope(tenant, pid, actor));
        try { service.invite(pid, email(id), ProjectRole.VIEWER); } finally { TenantContext.clear(); }
    }
    private void remove(UUID pid, UUID id) {
        TenantContext.set(new TenantScope(tenant, pid, actor));
        try { service.remove(pid, id); } finally { TenantContext.clear(); }
    }
    private static void refused(Runnable action, ProjectErrorCode code) {
        var e = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(e).isNotNull(); assertThat(e.errorCode()).isEqualTo(code);
    }

    @Test void freeHasZeroExternalSeatsButInternalAccountsRemainAllowed() {
        UUID internal = account(tenant), external = account(foreignTenant);
        invite(project, internal);
        refused(() -> invite(project, external), ProjectErrorCode.EXTERNAL_SEAT_QUOTA_EXCEEDED);
        refused(() -> invite(project, internal), ProjectErrorCode.ALREADY_MEMBER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_member WHERE account_id=?", Long.class, external)).isZero();
    }

    @Test void distinctSeatsAreSharedAndOnlyLastRemovalReleasesOne() {
        plan("PLAN_R1_STANDARD");
        List<UUID> ids = new ArrayList<>();
        for (int i=0;i<5;i++) { UUID id=account(foreignTenant); ids.add(id); invite(project,id); }
        invite(second, ids.getFirst());
        UUID sixth = account(foreignTenant);
        refused(() -> invite(second, sixth), ProjectErrorCode.EXTERNAL_SEAT_QUOTA_EXCEEDED);
        remove(project, ids.getFirst());
        refused(() -> invite(second, sixth), ProjectErrorCode.EXTERNAL_SEAT_QUOTA_EXCEEDED);
        remove(second, ids.getFirst()); invite(second, sixth);
    }

    @Test void concurrentDifferentAccountsCannotOversubscribeLastSeat() throws Exception {
        plan("PLAN_R1_STANDARD");
        for (int i=0;i<4;i++) invite(project, account(foreignTenant));
        UUID a=account(foreignTenant), b=account(foreignTenant);
        assertRace(project,a,second,b,50049);
    }

    @Test void sameAccountCannotCrossFiftyExternalProjectsEvenWithReusedSeat() throws Exception {
        plan("PLAN_R1_STANDARD");
        UUID target = account(foreignTenant);
        UUID anotherOwnerTenant = tx.execute(s -> tenants.createTenant("R4d third owner"));
        tenantIds.add(anotherOwnerTenant);
        UUID anotherOwner = account(anotherOwnerTenant);
        // 49条存量关系属于另一个owner租户；不能错误地把安全上限按当前租户计数。
        for (int i=0;i<49;i++) member(project(anotherOwnerTenant, anotherOwner), target, "VIEWER");
        assertRace(project,target,second,target,50050);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_member WHERE account_id=?", Long.class, target)).isEqualTo(50);
    }

    @Test void rollbackDoesNotOccupySeatAndPackagesAndAdjustmentsAreEffective() {
        var order=packages.createSimulatedPackageOrder(tenant,"EXTERNAL_COLLABORATOR_SEATS",1,null);
        packages.applySimulatedPackagePaymentSucceeded(order.id(),"r4d-"+tenant);
        UUID a=account(foreignTenant), b=account(foreignTenant);
        TenantContext.set(new TenantScope(tenant,project,actor));
        try { tx.executeWithoutResult(s -> { service.invite(project,email(a),ProjectRole.VIEWER); s.setRollbackOnly(); }); }
        finally { TenantContext.clear(); }
        invite(project,b);
        jdbc.update("UPDATE sys_tenant_resource_package SET status='EXPIRED' WHERE tenant_id=?",tenant);
        refused(() -> invite(project,a),ProjectErrorCode.EXTERNAL_SEAT_QUOTA_EXCEEDED);
        invite(second,b);
        jdbc.update("""
                INSERT INTO sys_tenant_resource_package(id,tenant_id,dimension_code,amount,unit,window_kind,
                  starts_at,ends_at,source,status,adjustment_reason,adjustment_operator_id,adjustment_key)
                VALUES (?,?,'EXTERNAL_COLLABORATOR_SEATS',2,'COUNT','NONE',now()-interval '1 hour',now()+interval '1 hour',
                  'OPERATION_ADJUSTMENT','ACTIVE','R4d test',?,'r4d-adjustment')
                """,Uuid7.generate(),tenant,actor);
        invite(project,a);
    }

    @Test void retainedDisabledAndDeletedProjectRelationshipsStillOccupySeats() {
        var order=packages.createSimulatedPackageOrder(tenant,"EXTERNAL_COLLABORATOR_SEATS",1,null);
        packages.applySimulatedPackagePaymentSucceeded(order.id(),"r4d-retained-"+tenant);
        UUID a=account(foreignTenant), b=account(foreignTenant); invite(project,a);
        jdbc.update("UPDATE sys_project_member SET status='DISABLED' WHERE account_id=?",a);
        jdbc.update("UPDATE sys_project SET status='DELETING',deleted_at=now(),lifecycle_generation=lifecycle_generation+1 WHERE id=?",project);
        refused(() -> invite(second,b),ProjectErrorCode.EXTERNAL_SEAT_QUOTA_EXCEEDED);
    }

    @Test void graceUsesProjectOwnerWithoutChangingOriginalAuditScope() {
        plan("PLAN_R1_STANDARD");
        UUID admin=account(foreignTenant), target=account(foreignTenant); member(project,admin,"ADMIN");
        jdbc.update("UPDATE sys_tenant_subscription SET status='GRACE',ends_at=now(),grace_ends_at=now()+interval '336 hours' WHERE tenant_id=?",tenant);
        TenantContext.set(new TenantScope(foreignTenant,project,admin));
        try { refused(() -> service.invite(project,email(target),ProjectRole.VIEWER),ProjectErrorCode.SUBSCRIPTION_GRACE_NO_EXPANSION); }
        finally { TenantContext.clear(); }
        jdbc.update("UPDATE sys_tenant_subscription SET status='ACTIVE',grace_ends_at=NULL WHERE tenant_id=?",tenant);
        jdbc.update("UPDATE sys_tenant_subscription SET status='GRACE',ends_at=now(),grace_ends_at=now()+interval '336 hours' WHERE tenant_id=?",foreignTenant);
        TenantContext.set(new TenantScope(foreignTenant,project,admin));
        try { service.invite(project,email(target),ProjectRole.VIEWER); } finally { TenantContext.clear(); }
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM sys_audit_log WHERE action='project.member.invited' AND target_id=?",UUID.class,target)).isEqualTo(foreignTenant);
    }

    @Test void missingProjectionFailsClosedAndValidBindingRestoresAdmission() {
        UUID target=account(foreignTenant); plan("FREE");
        refused(() -> invite(project,target),ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE);
        plan("PLAN_R1_STANDARD"); invite(project,target);
    }

    private void assertRace(UUID pa,UUID a,UUID pb,UUID b,int rejection) throws Exception {
        CyclicBarrier barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> race(barrier,pa,a)); var next=pool.submit(() -> race(barrier,pb,b));
            assertThat(List.of(first.get(25,TimeUnit.SECONDS),next.get(25,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0,rejection);
        }
    }
    private int race(CyclicBarrier barrier,UUID pid,UUID account) throws Exception {
        barrier.await(10,TimeUnit.SECONDS);
        try { invite(pid,account); return 0; } catch(BusinessException e) { return e.errorCode().code(); }
    }
}
