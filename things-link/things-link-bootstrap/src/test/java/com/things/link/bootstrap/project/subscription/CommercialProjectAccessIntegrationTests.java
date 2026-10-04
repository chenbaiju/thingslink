package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.CommercialProjectAccessService;
import com.things.link.project.application.EntitlementAdjustmentRequest;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.project.application.SubscriptionLifecycleService;
import com.things.link.project.application.TenantEntitlementAdjustmentService;
import com.things.link.project.application.TenantOrderService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.EffectiveQuotaPolicyRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R7f-1 真库验证；独立租户及审计随测试容器回收，不访问真实环境。 */
class CommercialProjectAccessIntegrationTests extends AbstractIntegrationTest {

    /** 数据库事实断言。 */
    @Autowired private JdbcTemplate jdbc;
    /** 被测商业限制服务。 */
    @Autowired private CommercialProjectAccessService access;
    /** 独立FREE租户创建。 */
    @Autowired private TenantProvisioning provisioning;
    /** 原事务边界。 */
    @Autowired private TransactionTemplate tx;
    /** 权威与普通请求投影。 */
    @Autowired private EffectiveQuotaPolicyRepository quotas;
    /** 租户锁与订阅状态。 */
    @Autowired private TenantSubscriptionLifecycleRepository lifecycle;
    /** 真实模拟购买。 */
    @Autowired private TenantOrderService orders;
    /** 原生命周期推进。 */
    @Autowired private SubscriptionLifecycleService lifecycleService;
    /** 原策略绑定CAS。 */
    @Autowired private QuotaPolicyAssignmentService assignment;
    /** 原独立调整窗口。 */
    @Autowired private TenantEntitlementAdjustmentService adjustments;

    /** 后台路径明确不伪造JWT范围。 */
    @BeforeEach
    void clearScope() { TenantContext.clear(); }

    /** 当前测试创建的租户，避免异常投影夹具影响后续全局生命周期扫描。 */
    private final List<UUID> tenants=new ArrayList<>();

    /** 仅清理本例业务事实，不清理不可变审计和来源快照；后者随容器回收。 */
    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID tenant:tenants) {
            jdbc.update("DELETE FROM sys_project WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",tenant);
        }
    }

    /** FREE不会全部解除，删除原保留项目后只恢复下一名额。 */
    @Test
    void freeRestorationHonorsCapacityAndDeletedProjects() {
        UUID tenant=tenant();
        List<UUID> projects=projects(tenant,3);
        restrict(tenant);
        assertThat(restore(tenant)).isZero();
        softDelete(projects.getFirst());
        assertThat(restore(tenant)).isEqualTo(1);
        assertThat(state(projects.get(1))).isEqualTo("ACTIVE");
        assertThat(state(projects.get(2))).isEqualTo("ARCHIVED");
        assertThat(restore(tenant)).isZero();
    }

    /** 用户归档不伪造商业台账，也不会因为名额腾出而自动恢复。 */
    @Test
    void userArchiveIsNotReclassifiedOrRestored() {
        UUID tenant=tenant();
        var projects=projects(tenant,3);
        jdbc.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",projects.get(1));
        var result=tx.execute(status -> access.restrictToCurrentLimit(tenant,current(tenant),Instant.now(),"TEST_FREE_TRANSITION"));
        assertThat(result.restrictedProjectIds()).containsExactly(projects.get(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_project_commercial_restriction WHERE project_id=?",Integer.class,projects.get(1))).isZero();
        softDelete(projects.getFirst());
        assertThat(restore(tenant)).isZero();
        assertThat(state(projects.get(1))).isEqualTo("ARCHIVED");
        assertThat(state(projects.get(2))).isEqualTo("ARCHIVED");
    }

    /** 即使已付费也只能恢复当前额度内的前N个，不能把所有旧台账一律解除。 */
    @Test
    void paidRestorationIsAlsoBounded() {
        UUID tenant=tenant();
        int max=Math.toIntExact(quotas.findByPlanRevision(revision("STANDARD")).orElseThrow().planQuota().projectsMax());
        var projects=projects(tenant,max+2);
        restrict(tenant);
        buy(tenant);
        assertThat(restore(tenant)).isEqualTo(max-1);
        assertThat(projects.stream().filter(id -> state(id).equals("ACTIVE")).count()).isEqualTo(max);
        assertThat(state(projects.getLast())).isEqualTo("ARCHIVED");
    }

    /** 独立PROJECTS_MAX调整继续参与FREE当前额度，恢复不能只硬编码基础1个。 */
    @Test
    void independentAdjustmentContributesToFreeCapacity() {
        UUID tenant=tenant();
        var projects=projects(tenant,3);
        restrict(tenant);
        Instant now=Instant.now();
        adjustments.createAdjustment(tenant,new EntitlementAdjustmentRequest("PROJECTS_MAX",1,
                now.minusSeconds(1),now.plus(1,ChronoUnit.DAYS),"容量恢复验收",UUID.randomUUID(),UUID.randomUUID().toString()));
        assertThat(restore(tenant)).isEqualTo(1);
        assertThat(state(projects.get(1))).isEqualTo("ACTIVE");
        assertThat(state(projects.get(2))).isEqualTo("ARCHIVED");
    }

    /** 投影缺失不得猜FREE或无限，恢复和台账保持不变。 */
    @Test
    void missingProjectionRefusesWithoutRestoring() {
        UUID tenant=tenant();
        var projects=projects(tenant,2);
        restrict(tenant);
        softDelete(projects.getFirst());
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?",tenant);
        assertThatThrownBy(() -> restore(tenant)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_QUOTA_UNAVAILABLE));
        assertThat(state(projects.get(1))).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_project_commercial_restriction WHERE project_id=?",String.class,projects.get(1)))
                .isEqualTo("ACTIVE");
    }

    /** 商业内部读取不赋予普通无JWT请求读取能力，并要求事务。 */
    @Test
    void systemProjectionDoesNotRelaxRequestScope() {
        UUID tenant=tenant();
        assertThat(quotas.findByTenantId(tenant)).isEmpty();
        assertThatThrownBy(() -> quotas.findForCommercialLifecycle(tenant))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        long projectsMax=tx.execute(status -> {
            lifecycle.lockTenantAndReadAssignmentVersion(tenant);
            return quotas.findForCommercialLifecycle(tenant).orElseThrow().projectsMax();
        });
        assertThat(projectsMax).isEqualTo(1L);
        assertThat(quotas.findByTenantId(tenant)).isEmpty();
    }

    /** 同事务切换绑定立即使用新额度，外层故障同时回滚绑定、项目和台账。 */
    @Test
    void currentTransactionProjectionAndRestorationRollbackTogether() {
        UUID tenant=tenant();
        var projects=projects(tenant,3);
        restrict(tenant);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long version=lifecycle.lockTenantAndReadAssignmentVersion(tenant);
            UUID policy=quotas.findByPlanRevision(revision("STANDARD")).orElseThrow().policyId();
            assignment.assign(tenant,policy,version);
            assertThat(access.restoreEligible(tenant,Instant.now())).isEqualTo(2);
            throw new IllegalStateException("rollback changed quota and restored projects");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(projects.get(1))).isEqualTo("ARCHIVED");
        assertThat(state(projects.get(2))).isEqualTo("ARCHIVED");
        assertThat(restore(tenant)).isZero();
    }

    /** GRACE不扩大写集合，受限免费仅按FREE额度补齐删除后腾出的名额。 */
    @Test
    void graceDoesNotRestoreButRestrictedFreeCanFillItsAllowedSlot() {
        UUID tenant=tenant();
        var projects=projects(tenant,3);
        restrict(tenant);
        buy(tenant);
        Instant end=lifecycle.findCurrentState(tenant).orElseThrow().endsAt();
        lifecycleService.advance(end);
        softDelete(projects.getFirst());
        assertThat(restore(tenant)).isZero();
        lifecycleService.advance(end.plus(14,ChronoUnit.DAYS));
        assertThat(state(projects.get(1))).isEqualTo("ACTIVE");
        assertThat(state(projects.get(2))).isEqualTo("ARCHIVED");
    }

    /** 因果锁等待证明：候选恢复不能使用等待之前的付费额度。 */
    @Test
    void waitingRestorationRechecksCommittedQuota() throws Exception {
        UUID tenant=tenant();
        var projects=projects(tenant,3);
        restrict(tenant);
        buy(tenant);
        String marker="r7f-restore-"+UUID.randomUUID();
        try (var blocker=owner(); var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var update=blocker.prepareStatement("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE'),quota_policy_assignment_version=quota_policy_assignment_version+1 WHERE id=?")) {
                    update.setObject(1,tenant); update.executeUpdate();
                }
                var future=worker.submit(() -> tx.execute(status -> {
                    jdbc.queryForObject("SELECT set_config('application_name',?,true)",String.class,marker);
                    return access.restoreEligible(tenant,Instant.now());
                }));
                awaitTenantLock(observer,marker);
                blocker.commit();
                assertThat(future.get(10,java.util.concurrent.TimeUnit.SECONDS)).isZero();
            } finally { blocker.rollback(); }
        }
        assertThat(state(projects.get(1))).isEqualTo("ARCHIVED");
        assertThat(state(projects.get(2))).isEqualTo("ARCHIVED");
    }

    /** 续费提交前扫描到的旧宽限行，租户锁放行后必须失去限制新付费策略的资格。 */
    @Test
    void staleExpiryCandidateCannotOverrideConcurrentRenewal() throws Exception {
        UUID tenant=tenant();
        buy(tenant);
        var projects=projects(tenant,3);
        Instant now=Instant.now(), expired=now.minus(20,ChronoUnit.DAYS);
        jdbc.update("UPDATE sys_tenant_subscription SET starts_at=?,ends_at=? WHERE tenant_id=? AND status='ACTIVE'",
                Timestamp.from(expired.minus(365,ChronoUnit.DAYS)),Timestamp.from(expired),tenant);
        lifecycleService.advance(expired);
        var renewal=orders.createSimulatedOrder(tenant,revision("STANDARD"));
        String marker="r7f-renew-"+UUID.randomUUID();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<com.things.link.project.application.SubscriptionLifecycleReport>>();
        try (var observer=owner(); var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                orders.applySimulatedPaymentSucceeded(renewal.id(),UUID.randomUUID().toString());
                future.set(worker.submit(() -> tx.execute(inner -> {
                    jdbc.queryForObject("SELECT set_config('application_name',?,true)",String.class,marker);
                    return lifecycleService.advance(now);
                })));
                awaitTenantLock(observer,marker);
            });
            assertThat(future.get().get(10,java.util.concurrent.TimeUnit.SECONDS).restrictedFreeEntered()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT q.code FROM sys_tenant t JOIN sys_quota_policy q ON q.id=t.quota_policy_id WHERE t.id=?",String.class,tenant))
                .isEqualTo("PLAN_R1_STANDARD");
        assertThat(projects.stream().map(this::state)).containsOnly("ACTIVE");
    }

    /** 独立观察连接避免小业务连接池被等待者占满。 */
    private java.sql.Connection owner() throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }

    /** 有界等待数据库报告指定任务确实在等待租户锁，不使用固定延时猜测时序。 */
    private void awaitTenantLock(java.sql.Connection observer,String marker) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        try (var query=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND lower(query) LIKE '%sys_tenant%'")) {
            query.setString(1,marker);
            while (System.nanoTime()<deadline) {
                try (var rows=query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1)>0) return;
                }
            }
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("锁观察失败",failure); }
        throw new AssertionError("未观察到预期租户锁等待："+marker);
    }

    /** 原事务建立FREE。 */
    private UUID tenant() { UUID id=tx.execute(status -> provisioning.createTenant("commercial-access-"+UUID.randomUUID())); tenants.add(id); return id; }
    /** 当前真实订阅。 */
    private UUID current(UUID tenant) { return lifecycle.findCurrentState(tenant).orElseThrow().id(); }
    /** 后台限制调用置于原事务。 */
    private void restrict(UUID tenant) { tx.executeWithoutResult(status -> access.restrictToCurrentLimit(tenant,current(tenant),Instant.now(),"TEST_FREE_TRANSITION")); }
    /** 后台恢复调用置于原事务。 */
    private int restore(UUID tenant) { return tx.execute(status -> access.restoreEligible(tenant,Instant.now())); }
    /** 项目状态。 */
    private String state(UUID project) { return jdbc.queryForObject("SELECT status FROM sys_project WHERE id=?",String.class,project); }
    /** 模拟已授权软删除事实，保留原业务行。 */
    private void softDelete(UUID project) { jdbc.update("UPDATE sys_project SET status='DELETING',deleted_at=now() WHERE id=?",project); }
    /** 使用真实模拟支付创建付费订阅。 */
    private void buy(UUID tenant) { var order=orders.createSimulatedOrder(tenant,revision("STANDARD")); orders.applySimulatedPaymentSucceeded(order.id(),UUID.randomUUID().toString()); }
    /** 冻结目录身份。 */
    private UUID revision(String code) { return jdbc.queryForObject("SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id=r.plan_id WHERE p.code=? AND r.revision_code='product-revision-1'",UUID.class,code); }
    /** 明确创建顺序的超额历史夹具，不声称绕过真实创建准入。 */
    private List<UUID> projects(UUID tenant,int count) {
        List<UUID> ids=new ArrayList<>();
        for (int i=0;i<count;i++) {
            UUID id=UUID.randomUUID(); ids.add(id);
            jdbc.update("""
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key,status,created_at,updated_at,lifecycle_generation)
                    VALUES(?,?,'quota-history','sh-1',?,'ACTIVE',?,now(),0)
                    """,id,tenant,"r7f"+id.toString().replace("-",""),Timestamp.from(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i)));
        }
        return ids;
    }
}
