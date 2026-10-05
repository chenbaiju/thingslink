package com.things.link.bootstrap.project.cleanup;

import com.things.link.export.application.ProjectExportPurgeContributor;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportPurgeRepository;
import com.things.link.project.application.ProjectCleanupAdmissionService;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupBatchService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.project.infrastructure.persistence.JdbcProjectCleanupRepository;
import com.things.link.support.audit.AuditLogService;
import com.things.link.task.application.TaskProjectCleanupContributor;
import com.things.link.task.infrastructure.persistence.JdbcTaskProjectCleanupRepository;
import com.things.link.rule.application.RuleProjectCleanupContributor;
import com.things.link.rule.infrastructure.persistence.JdbcRuleProjectCleanupRepository;
import com.things.link.alarm.application.AlarmProjectCleanupContributor;
import com.things.link.alarm.infrastructure.persistence.JdbcAlarmProjectCleanupRepository;
import com.things.link.enduser.application.AppProjectCleanupContributor;
import com.things.link.enduser.infrastructure.persistence.JdbcAppProjectCleanupRepository;
import com.things.link.dashboard.application.DashboardProjectCleanupContributor;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import com.things.link.device.application.DeviceProjectCleanupContributor;
import com.things.link.iam.application.IamProjectCleanupContributor;
import com.things.link.iam.infrastructure.persistence.JdbcIamProjectCleanupRepository;
import com.things.link.device.infrastructure.persistence.JdbcDeviceProjectCleanupRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.things.link.telemetry.application.TelemetryProjectCleanupContributor;
import com.things.link.telemetry.infrastructure.persistence.JdbcTelemetryProjectCleanupRepository;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import com.things.link.project.application.SupportProjectCleanupContributor;
import com.things.link.project.application.ProjectMemberCleanupContributor;
import com.things.link.project.application.ProjectCleanupWorker;
import com.things.link.support.tenant.DatabaseWorkloadAspect;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import com.things.link.project.application.ProjectCleanupFinalizationContributor;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.cleanup.SupportProjectCleanupService;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0092：真实完整步骤与worker的单批推进、中断和租约恢复的真实资格。 */
@Testcontainers
class ProjectCleanupOrchestrationTests {
    /** 独占完整迁移库，使用与前序设备/遥测相同的实际数据库版本。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cleanup_orchestration").withUsername("thingslink").withPassword("thingslink");
    /** 迁移与全字段观察。 */
    private static JdbcTemplate owner;
    /** 实际APP权限执行被测入口。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 原事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** project持久事实。 */
    private JdbcProjectCleanupRepository projects;
    /** 真实准入、锁及租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 历史真实前置领域与空OTA阶段。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 被测project成员受限入口。 */
    private ProjectMemberCleanupContributor facts;
    /** 与墓碑条件更新同一事务的最终贡献。 */
    private ProjectCleanupFinalizationContributor finalization;
    /** 真实成员贡献及项目原事务。 */
    private ProjectCleanupBatchService batches;

    /** 所有正式领域迁移与贡献器齐备，不用伪造零行步骤做运行恢复验收。 */
    @BeforeAll
    static void migrate() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink"));
        flyway("20260906.0110").migrate();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
    }

    /** 隔离库每例重置技术事实，保留不可变审计；不操作开发库。 */
    @BeforeEach
    void prepare() {
        owner.update("DELETE FROM public.sys_project_member");
        owner.update("DELETE FROM public.sys_outbox_event");
        owner.update("DELETE FROM public.sys_idempotency_record");
        owner.update("DELETE FROM public.sys_tenant_work_slot");
        owner.update("DELETE FROM public.sys_usage_fact");
        owner.update("DELETE FROM public.sys_usage_counter_daily");
        owner.update("DELETE FROM public.sys_project");
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions = new DataSourceTransactionManager(source);
        projects = new JdbcProjectCleanupRepository(app);
        admission = proxy(new ProjectCleanupAdmissionService(projects, new AuditLogService(app, new ObjectMapper()), app));
        prerequisites = List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))),
                proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app))),
                proxy(new AlarmProjectCleanupContributor(new JdbcAlarmProjectCleanupRepository(app))),
                proxy(new AppProjectCleanupContributor(new JdbcAppProjectCleanupRepository(app))),
                proxy(new DashboardProjectCleanupContributor(new JdbcDashboardProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyOtaContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyAssistantContributor(owner),
                proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app))),
                proxy(new DeviceProjectCleanupContributor(new JdbcDeviceProjectCleanupRepository(app))),
                proxy(new IamProjectCleanupContributor(new JdbcIamProjectCleanupRepository(app))),
                proxy(new SupportProjectCleanupContributor(proxy(new SupportProjectCleanupService(app)))));
        facts = proxy(new ProjectMemberCleanupContributor(projects));
        finalization = proxy(new ProjectCleanupFinalizationContributor(admission,projects,new AuditLogService(app,new ObjectMapper())));
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(facts); contributors.add(finalization);
        batches = proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** 历史12阶段真实执行并兼容空OTA阶段，每次最多一批；1001成员需三次非空删除和额外空批证明。 */
    @Test
    void advancesOneStageOrBoundedBatchPerTick() {
        Fixture first=fixture(false,null); seed(first,1001);
        Fixture neighbor=fixture(true,first); seed(neighbor,2);
        String outside=snapshot(neighbor);
        ProjectCleanupWorker worker=worker(admission,batches);
        for (int i=0;i<ProjectCleanupStage.PROJECT.ordinal();i++) {
            worker.cleanNextBatch(); assertThat(count(first)).isEqualTo(1001);
            assertThat(stage(first)).isEqualTo(ProjectCleanupStage.values()[i+1].name());
        }
        for (long remaining:new long[]{501,1,0}) {
            worker.cleanNextBatch(); assertThat(count(first)).isEqualTo(remaining); assertThat(stage(first)).isEqualTo("PROJECT");
        }
        worker.cleanNextBatch(); assertThat(stage(first)).isEqualTo("FINALIZE");
        worker.cleanNextBatch(); assertThat(stage(first)).isEqualTo("DONE");
        assertThat(completions(first)).isEqualTo(1);
        String done=projectSnapshot(first); worker.cleanNextBatch(); assertThat(projectSnapshot(first)).isEqualTo(done);
        assertThat(snapshot(neighbor)).isEqualTo(outside);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?",Long.class,first.project())).isEqualTo(1001);
    }

    /** 领取后实例退出没有执行领域；新实例不能偷未到期租约，到期后同代次新身份接管。 */
    @Test
    void recoversAbandonedClaimOnlyAfterLeaseExpiry() {
        Fixture first=fixture(false,null); seed(first,1);
        ProjectCleanupClaim abandoned=admission.claimNext().orElseThrow();
        String held=projectSnapshot(first);
        ProjectCleanupWorker second=worker(admission,batches); second.cleanNextBatch();
        assertThat(projectSnapshot(first)).isEqualTo(held);
        expire(first); second.cleanNextBatch();
        assertThat(stage(first)).isEqualTo("TASK"); assertThat(batches.execute(abandoned)).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM public.sys_audit_log WHERE project_id=? AND action='project.cleanup.started'",Long.class,first.project())).isEqualTo(1);
        assertThat(count(first)).isEqualTo(1);
    }

    /** 在途项目租约不挡住另一个租户的到期项目，恢复来自数据库事实而非进程内游标。 */
    @Test
    void processesAnotherTenantWhileFirstLeaseIsHeld() {
        Fixture first=fixture(false,null); ProjectCleanupClaim held=admission.claimNext().orElseThrow();
        Fixture second=fixture(false,null);
        worker(admission,batches).cleanNextBatch();
        assertThat(stage(first)).isEqualTo("WAIT_EXPORT"); assertThat(stage(second)).isEqualTo("TASK");
        assertThat(owner.queryForObject("SELECT cleanup_lease_token FROM public.sys_project WHERE id=?",UUID.class,first.project())).isEqualTo(held.leaseToken());
    }

    /** 成员删后异常先回滚，再记三十秒退避；下一触发不得立即重试，到期继续真实500行。 */
    @Test
    void rollsBackBeforeDeferringAndResumesWithoutSkippingStage() {
        Fixture first=fixture(false,null); seed(first,1001); advanceToMembers();
        ProjectCleanupWorker failing=worker(admission,failingBatch()); failing.cleanNextBatch();
        assertThat(count(first)).isEqualTo(1001); assertThat(stage(first)).isEqualTo("PROJECT");
        assertThat(owner.queryForObject("SELECT cleanup_failure_code FROM public.sys_project WHERE id=?",String.class,first.project())).isEqualTo("PROJECT_CLEANUP_FAILED");
        assertThat(owner.queryForObject("SELECT cleanup_rows=0 AND cleanup_lease_token IS NULL AND cleanup_next_attempt_at>clock_timestamp()+interval '20 seconds' FROM public.sys_project WHERE id=?",Boolean.class,first.project())).isTrue();
        String deferred=projectSnapshot(first); worker(admission,batches).cleanNextBatch(); assertThat(projectSnapshot(first)).isEqualTo(deferred);
        owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",first.project());
        worker(admission,batches).cleanNextBatch(); assertThat(count(first)).isEqualTo(501);
    }

    /** 退避数据库故障不能伪释放租约；新实例等待自然到期，旧执行身份不能提交。 */
    @Test
    void reliesOnLeaseRecoveryWhenDeferralPersistenceFails() {
        Fixture first=fixture(false,null); seed(first,1001); advanceToMembers();
        ProjectCleanupAdmissionService broken=proxy(new ProjectCleanupAdmissionService(projects,new AuditLogService(app,new ObjectMapper()),app) {
            /** 在真实批次回滚后拒绝退避写，保留数据库原租约。 */
            @Override public boolean defer(ProjectCleanupClaim claim,String code) { throw new IllegalStateException("受控退避数据库失败"); }
        });
        worker(broken,failingBatch()).cleanNextBatch();
        assertThat(count(first)).isEqualTo(1001);
        UUID token=owner.queryForObject("SELECT cleanup_lease_token FROM public.sys_project WHERE id=?",UUID.class,first.project());
        assertThat(token).isNotNull(); String held=projectSnapshot(first);
        worker(admission,batches).cleanNextBatch(); assertThat(projectSnapshot(first)).isEqualTo(held);
        ProjectCleanupClaim old=new ProjectCleanupClaim(first.tenant(),first.project(),1,"PROJECT",token,Instant.now().plusSeconds(120),false);
        expire(first); worker(admission,batches).cleanNextBatch(); assertThat(count(first)).isEqualTo(501);
        assertThat(batches.execute(old)).isEmpty();
    }

    /** @param entry 真实领取 @param service 真实或故障包装的批次 @return 带实际DATA路由切面的worker */
    private ProjectCleanupWorker worker(ProjectCleanupAdmissionService entry,ProjectCleanupBatchService service) {
        AspectJProxyFactory factory=new AspectJProxyFactory(new ProjectCleanupWorker(entry,service));
        factory.addAspect(new DatabaseWorkloadAspect()); return factory.getProxy();
    }
    /** 十次触发均执行真实前置域，只把成员留给被测故障。 */
    private void advanceToMembers() { for (int i=0;i<ProjectCleanupStage.PROJECT.ordinal();i++) worker(admission,batches).cleanNextBatch(); }
    /** @return 真正删除500成员后抛错的完整装配，不能用空mock证明事务回滚 */
    private ProjectCleanupBatchService failingBatch() {
        List<ProjectCleanupContributor> steps=new ArrayList<>(prerequisites);
        steps.add(new ProjectCleanupContributor() {
            /** 固定末序范围。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.PROJECT; }
            /** 原事务执行真实SQL后故障。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
                assertThat(facts.clean(claim).deletedRows()).isEqualTo(500);
                throw new IllegalStateException("成员已删后的受控故障");
            }
        });
        steps.add(finalization); return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, steps));
    }
    /** @param f 隔离夹具，模拟进程离线跨过两分钟，不更改生产租约合同 */
    private void expire(Fixture f) { owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.project()); }
    /** @param f 项目 @return 持久阶段 */
    private static String stage(Fixture f) { return owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?",String.class,f.project()); }
    /** @param active 活跃邻居或待清理 @param shared 同租户邻居 @return 独立项目 */
    private static Fixture fixture(boolean active, Fixture shared) {
        Fixture f = new Fixture(shared == null ? UUID.randomUUID() : shared.tenant(), UUID.randomUUID());
        if (shared == null) owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'成员清理租户')", f.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'成员清理项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                f.project(), f.tenant(), "member_"+f.project().toString().replace("-", ""), active ? "ACTIVE" : "DELETING", active ? 0 : 1, active);
        return f;
    }
    /** @param f 项目 @param size 含ACTIVE/DISABLED的共享账号成员集合 */
    private static void seed(Fixture f,int size) {
        owner.update("""
                WITH accounts AS (
                    INSERT INTO public.sys_account(id,email,password_hash,display_name)
                    SELECT gen_random_uuid(),gen_random_uuid()::text||'@example.test','{noop}test-only','清理保留账号' FROM generate_series(1,?) RETURNING id)
                INSERT INTO public.sys_project_member(id,project_id,account_id,role,status)
                SELECT gen_random_uuid(),?,id,'VIEWER',CASE WHEN row_number() OVER (ORDER BY id)%2=0 THEN 'DISABLED' ELSE 'ACTIVE' END FROM accounts
                """,size,f.project());
    }
    /** @param f 项目 @return 实际成员数 */
    private static long count(Fixture f) { return owner.queryForObject("SELECT count(*) FROM public.sys_project_member WHERE project_id=?",Long.class,f.project()); }
    /** @param f 项目 @return 成员全字段快照 */
    private static String snapshot(Fixture f) { return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY id),'[]'::jsonb)::text FROM public.sys_project_member m WHERE project_id=?",String.class,f.project()); }
    /** @param f 项目 @return 围栏/计数/状态完整事实 */
    private static String projectSnapshot(Fixture f) { return owner.queryForObject("SELECT to_jsonb(p)::text FROM public.sys_project p WHERE id=?",String.class,f.project()); }
    /** @return 共享账号和租户成员全字段 */
    private static String identities() { return owner.queryForObject("SELECT jsonb_build_object('accounts',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM public.sys_account a),'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM public.sys_tenant_member m))::text",String.class); }
    /** @param f 项目 @return 固定身份、路由、地区和原始时间 */
    private static String provenance(Fixture f) { return owner.queryForObject("SELECT jsonb_build_array(id,tenant_id,lifecycle_generation,project_key,region,created_at,deleted_at)::text FROM public.sys_project WHERE id=?",String.class,f.project()); }
    /** @param f 项目 @return 完成审计数 */
    private static long completions(Fixture f) { return owner.queryForObject("SELECT count(*) FROM public.sys_audit_log WHERE project_id=? AND action='project.cleanup.completed'",Long.class,f.project()); }
    /** @return 独立受控并发连接 */
    private static Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"); }
    /** @param query 实际被测语句前缀；观察服务端锁等待才释放另一事务 */
    private static void awaitLock(String query) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)", Boolean.class, query))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
        throw new AssertionError("未观察到预期FK锁等待");
    }
    /** @param action 真实SQL @param state 核对确切失败来源 */
    private static void assertSqlState(Runnable action, String state) {
        assertThatThrownBy(action::run).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo(state));
    }
    /** @param target 被代理对象 @return 使用真实事务的注解代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    /** @param target 固定升级边界 @return 工作区全部模块的真实Flyway迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam",
                        "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
                        "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/enduser", "classpath:db/migration/export",
                        "classpath:db/migration/dashboard")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }
    /** @param tenant 归属 @param project 清理身份 */
    private record Fixture(UUID tenant, UUID project) { }
}
