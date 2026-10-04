package com.things.link.bootstrap.project.cleanup;

import com.things.link.export.application.ProjectExportPurgeContributor;
import com.things.link.ota.application.OtaProjectCleanupContributor;
import com.things.link.ota.infrastructure.persistence.JdbcOtaProjectCleanupRepository;
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
import com.things.link.project.application.ProjectCleanupFinalizationContributor;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.cleanup.SupportProjectCleanupService;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0091：成员末序、不可变墓碑、原子审计与共享身份保留的真实资格。 */
@Testcontainers
class ProjectCleanupFinalizationTests {
    /** 独占完整迁移库，使用与前序设备/遥测相同的实际数据库版本。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("member_project_cleanup").withUsername("thingslink").withPassword("thingslink");
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
    /** 包含OTA的十一个真实前置领域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 被测project成员受限入口。 */
    private ProjectMemberCleanupContributor facts;
    /** 与墓碑条件更新同一事务的最终贡献。 */
    private ProjectCleanupFinalizationContributor finalization;
    /** 真实成员贡献及项目原事务。 */
    private ProjectCleanupBatchService batches;

    /** 1400旧库事实保真升级、重跑零迁移，APP能力不向PUBLIC开放。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260905.1400").migrate();
        Fixture legacy = fixture(true, null); seed(legacy, 2);
        String before = snapshot(legacy);
        assertThat(flyway("20260905.1500").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.1500").migrate().migrationsExecuted).isZero();
        assertThat(flyway("20260906.0110").migrate().migrationsExecuted).isEqualTo(5);
        // 保留原旧库升级断言，再升级至当前真实清理合同；Java枚举不能运行在缺OTA阶段的旧约束上。
        assertThat(flyway("20260912.0410").migrate().migrationsExecuted).isPositive();
        assertThat(flyway("20260912.0410").migrate().migrationsExecuted).isZero();
        assertThat(snapshot(legacy)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.oid='public.project_member_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0", Long.class)).isZero();
    }

    /** 隔离库每例重置技术事实，保留不可变审计；不操作开发库。 */
    @BeforeEach
    void prepare() {
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
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
                proxy(new OtaProjectCleanupContributor(new JdbcOtaProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
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

    /** 1001成员分500/500/1清理，保留共享身份及邻居；完成审计和不可变墓碑只提交一次。 */
    @Test
    void drainsMembersThenAtomicallyFinalizesTombstoneOnce() {
        Fixture first = fixture(false, null); seed(first, 1001);
        Fixture neighbor = fixture(true, first);
        owner.update("INSERT INTO public.sys_project_member(id,project_id,account_id,role) SELECT gen_random_uuid(),?,account_id,'VIEWER' FROM public.sys_project_member WHERE project_id=?", neighbor.project(), first.project());
        seedUsage(first);
        String identity = identities();
        String neighbors = snapshot(neighbor);
        String usage = usage(first);
        String preserved = provenance(first);
        ProjectCleanupClaim claim = memberClaim();
        for (int expected : new int[]{500, 500, 1}) {
            long before = count(first);
            ProjectCleanupBatchResult result = batches.execute(claim).orElseThrow();
            assertThat(result.deletedRows()).isEqualTo(expected);
            assertThat(before-count(first)).isEqualTo(expected);
            assertThat(identities()).isEqualTo(identity); assertThat(snapshot(neighbor)).isEqualTo(neighbors);
            assertThat(usage(first)).isEqualTo(usage); claim = admission.claimNext().orElseThrow();
        }
        assertThat(batches.execute(claim).orElseThrow().complete()).isTrue();
        ProjectCleanupClaim last = admission.claimNext().orElseThrow();
        assertThat(last.stage()).isEqualTo("FINALIZE");
        assertThat(batches.execute(last).orElseThrow().complete()).isTrue();
        assertTombstone(first);
        assertThat(provenance(first)).isEqualTo(preserved);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(1001);
        assertThat(completions(first)).isEqualTo(1);
        assertThat(batches.execute(last)).isEmpty();
        assertThat(admission.claimNext()).isEmpty();
        assertThat(completions(first)).isEqualTo(1);
        assertThat(usage(first)).isEqualTo(usage); assertThat(identities()).isEqualTo(identity);
        assertThat(snapshot(neighbor)).isEqualTo(neighbors);
    }

    /** 封存期间审计写失败、审计后租约耗尽及墓碑后异常全部回滚，保留原领取重试能力。 */
    @ParameterizedTest
    @ValueSource(strings = {"audit", "lease", "after"})
    void rollsBackFinalizationAndAuditTogether(String mode) {
        Fixture first = fixture(false, null);
        ProjectCleanupClaim claim = finalClaim();
        String before = projectSnapshot(first);
        AuditLogService audits = new AuditLogService(app,new ObjectMapper()) {
            /** 故障发生在真实审计写后，防止空mock不能证明原子性。 */
            @Override public void record(AuditLogEntry entry) {
                super.record(entry);
                if (mode.equals("audit")) throw new IllegalStateException("受控审计后故障");
                if (mode.equals("lease")) app.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", first.project());
            }
        };
        ProjectCleanupFinalizationContributor subject = proxy(new ProjectCleanupFinalizationContributor(admission,projects,audits));
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> {
            subject.clean(claim);
            throw new IllegalStateException("受控墓碑后故障");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(projectSnapshot(first)).isEqualTo(before);
        assertThat(completions(first)).isZero();
        assertThat(batches.execute(claim).orElseThrow().complete()).isTrue();
        assertThat(completions(first)).isEqualTo(1);
    }

    /** 最后阶段仍发现成员时保持当前阶段且不写完成审计；不得静默跳过未清空关系。 */
    @Test
    void refusesFinalizationWithRemainingMembers() {
        Fixture first = fixture(false, null);
        ProjectCleanupClaim claim = finalClaim(); seed(first, 1);
        assertThat(batches.execute(claim).orElseThrow().blockedReason()).isEqualTo("PROJECT_MEMBERS_REMAIN");
        assertThat(completions(first)).isZero(); assertThat(count(first)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM public.sys_project WHERE id=?",String.class,first.project())).isEqualTo("PURGING");
    }

    /** 最终服务即使被直接调用也核对阶段和实际能力，拒绝越级/伪造/过期请求。 */
    @ParameterizedTest
    @ValueSource(strings = {"stage", "tenant", "generation", "token", "lease"})
    void rejectsInvalidFinalizationClaim(String field) {
        Fixture first = fixture(false, null); ProjectCleanupClaim current = finalClaim();
        if (field.equals("lease")) owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",first.project());
        ProjectCleanupClaim wrong = new ProjectCleanupClaim(field.equals("tenant")?UUID.randomUUID():current.tenantId(),current.projectId(),
                field.equals("generation")?current.generation()+1:current.generation(),field.equals("stage")?"PROJECT":current.stage(),
                field.equals("token")?UUID.randomUUID():current.leaseToken(),current.leaseUntil(),false);
        String before = projectSnapshot(first);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> finalization.clean(wrong))).isInstanceOf(IllegalStateException.class);
        assertThat(projectSnapshot(first)).isEqualTo(before); assertThat(completions(first)).isZero();
    }

    /** 所有墓碑字段不可被旧代码改写；项目键不释放给新项目，不可从旧设备路由复活。 */
    @ParameterizedTest
    @ValueSource(strings = {"name", "timezone", "project_key", "region", "updated_at", "cleanup_rows", "status"})
    void keepsFinalizedTombstoneImmutable(String field) {
        Fixture first = fixture(false, null); batches.execute(finalClaim()).orElseThrow();
        String before = projectSnapshot(first);
        String value = switch (field) {
            case "timezone" -> "'Asia/Shanghai'";
            case "region" -> "'another-region'";
            case "updated_at" -> "clock_timestamp()+interval '1 second'";
            case "cleanup_rows" -> "cleanup_rows+1";
            case "status" -> "'ACTIVE'";
            default -> "'changed'";
        };
        // 地区使用不同值，不可变守卫在目录外键检查前拒绝这次墓碑改写。
        String sql = "UPDATE public.sys_project SET "+field+"="+value+" WHERE id=?";
        assertSqlState(() -> owner.update(sql,first.project()),"23514");
        assertThat(projectSnapshot(first)).isEqualTo(before);
    }

    /** 项目键唯一约束仍保留，不能把清理墓碑的键重新授予新项目。 */
    @Test
    void retainsRoutingKeyReservation() {
        Fixture first = fixture(false, null); batches.execute(finalClaim()).orElseThrow();
        String key = owner.queryForObject("SELECT project_key FROM public.sys_project WHERE id=?",String.class,first.project());
        assertSqlState(() -> owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key) VALUES (gen_random_uuid(),?,'错误复用',?)",first.tenant(),key),"23505");
    }

    /** 直接状态更新不允许绕过FINALIZE；原项目状态和成员必须保留。 */
    @Test
    void databaseRejectsPrematureTombstone() {
        Fixture first = fixture(false,null); memberClaim();
        String before=projectSnapshot(first);
        assertSqlState(() -> owner.update("UPDATE public.sys_project SET status='PURGED',cleanup_stage='DONE',name='已清理项目',timezone='UTC',cleanup_completed_at=clock_timestamp(),cleanup_next_attempt_at=NULL,cleanup_lease_token=NULL,cleanup_lease_until=NULL WHERE id=?",first.project()),"23514");
        assertThat(projectSnapshot(first)).isEqualTo(before);
    }

    /** 两实例同一最终领取竞争时先提交者唯一封存，后等待者失权且不重复审计。 */
    @Test
    void serializesConcurrentFinalizersAndRejectsWaitingOldClaim() throws Exception {
        Fixture first=fixture(false,null); ProjectCleanupClaim claim=finalClaim();
        var written=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        AuditLogService audits=new AuditLogService(app,new ObjectMapper()) {
            /** 真实审计后持住原项目锁，明确观察另一个实例等待再放行。 */
            @Override public void record(AuditLogEntry entry) {
                super.record(entry); written.countDown();
                try { if (!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("未放行最终事务"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        };
        var custom=new ArrayList<>(prerequisites); custom.add(facts);
        custom.add(proxy(new ProjectCleanupFinalizationContributor(admission,projects,audits)));
        ProjectCleanupBatchService firstWorker=proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, custom));
        try (var workers=Executors.newFixedThreadPool(2)) {
            var early=workers.submit(() -> firstWorker.execute(claim));
            assertThat(written.await(2,TimeUnit.SECONDS)).isTrue();
            var late=workers.submit(() -> batches.execute(claim));
            try { awaitLock("SELECT id FROM public.sys_project WHERE tenant_id%"); } finally { release.countDown(); }
            assertThat(early.get(3,TimeUnit.SECONDS).orElseThrow().complete()).isTrue();
            assertThat(late.get(3,TimeUnit.SECONDS)).isEmpty();
        } finally { release.countDown(); }
        assertTombstone(first); assertThat(completions(first)).isEqualTo(1);
    }

    /** 成员删除后故障或租约失效，都回滚数据和项目计数，可用原领取重试。 */
    @ParameterizedTest
    @ValueSource(strings = {"delete", "lease"})
    void rollsBackChangesAndProgress(String mode) {
        Fixture first = fixture(false, null); seed(first, 1);
        ProjectCleanupClaim claim = memberClaim();
        String before = snapshot(first);
        String progress = projectSnapshot(first);
        assertThatThrownBy(() -> batch(c -> {
            app.execute("CREATE TEMP TABLE sys_project_member(LIKE public.sys_project_member) ON COMMIT DROP");
            ProjectCleanupBatchResult result = facts.clean(c);
            assertThat(result.deletedRows()).isEqualTo(1);
            if (!mode.equals("lease")) throw new IllegalStateException("受控PROJECT变更后失败");
            app.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", first.project());
            return result;
        }).execute(claim)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(first)).isEqualTo(before);
        assertThat(projectSnapshot(first)).isEqualTo(progress);
        assertThat(batches.execute(claim).orElseThrow().blockedReason()).isNull();
    }

    /** 数据库入口自身检查完整能力；错阶段、过期、只读和强隔离均不得清理。 */
    @ParameterizedTest
    @ValueSource(strings = {"tenant", "project", "generation", "token", "stage", "lease", "readonly", "repeatable"})
    void rejectsInvalidDatabaseCapability(String field) {
        Fixture first = fixture(false, null); seed(first, 1);
        ProjectCleanupClaim claim = memberClaim();
        if (field.equals("stage")) owner.update("UPDATE public.sys_project SET cleanup_stage='FINALIZE' WHERE id=?", first.project());
        if (field.equals("lease")) owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", first.project());
        ProjectCleanupClaim wrong = new ProjectCleanupClaim(field.equals("tenant") ? UUID.randomUUID() : claim.tenantId(),
                field.equals("project") ? UUID.randomUUID() : claim.projectId(), field.equals("generation") ? claim.generation()+1 : claim.generation(),
                claim.stage(), field.equals("token") ? UUID.randomUUID() : claim.leaseToken(), claim.leaseUntil(), false);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(field.equals("readonly"));
        if (field.equals("repeatable")) tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        String before = snapshot(first);
        assertSqlState(() -> tx.execute(status -> facts.clean(wrong)), field.equals("readonly") || field.equals("repeatable") ? "25001" : "42501");
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** 空域推进FINALIZE，ACTIVE普通成员即使已过保留期也不能以伪造清理能力删除。 */
    @Test
    void completesEmptyDomainAndRejectsActiveProject() {
        Fixture first = fixture(false, null);
        assertThat(batches.execute(memberClaim()).orElseThrow().complete()).isTrue();
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("FINALIZE");
        Fixture active = fixture(true, null); seed(active, 1);
        ProjectCleanupClaim wrong = new ProjectCleanupClaim(active.tenant(), active.project(), 0, "PROJECT", UUID.randomUUID(), java.time.Instant.now().plusSeconds(120), false);
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> facts.clean(wrong)), "42501");
        assertThat(count(active)).isEqualTo(1);
    }

    /** 直接SQL入口在父锁等待期间过期也必须回滚；不能仅依赖Java批次最后的进度CAS。 */
    @Test
    void rejectsLeaseConsumedWhileWaitingForParentEvenAtDatabaseEntry() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1);
        ProjectCleanupClaim claim = memberClaim();
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement lock = writer.prepareStatement("SELECT id FROM public.sys_project_member WHERE project_id=? FOR UPDATE")) {
                lock.setObject(1, first.project()); lock.executeQuery().close();
            }
            owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()+interval '500 milliseconds' WHERE id=?", first.project());
            var cleanup = workers.submit(() -> {
                try { new TransactionTemplate(transactions).execute(status -> facts.clean(claim)); return (RuntimeException) null; }
                catch (RuntimeException failure) { return failure; }
            });
            try {
                awaitLock("SELECT * FROM public.project_member_cleanup_batch%");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!Boolean.TRUE.equals(owner.queryForObject("SELECT cleanup_lease_until<=clock_timestamp() FROM public.sys_project WHERE id=?", Boolean.class, first.project()))) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("租约未按真实时钟到期");
                    Thread.sleep(10);
                }
            } finally { writer.commit(); }
            RuntimeException failure = cleanup.get(3, TimeUnit.SECONDS);
            assertThat(failure != null).as("直接数据库入口不能在父锁等待耗尽租约后提交删除").isTrue();
            assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
            assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("42501");
        }
        assertThat(count(first)).isEqualTo(1);
    }


    /** @return 包含OTA的全部真实前置领域后的PROJECT领取 */
    private ProjectCleanupClaim memberClaim() {
        assertThat(prerequisites).extracting(ProjectCleanupContributor::stage)
                .containsExactly(ProjectCleanupStage.WAIT_EXPORT, ProjectCleanupStage.TASK,
                        ProjectCleanupStage.RULE, ProjectCleanupStage.ALARM, ProjectCleanupStage.ENDUSER,
                        ProjectCleanupStage.DASHBOARD, ProjectCleanupStage.OTA, ProjectCleanupStage.INTEGRATION, ProjectCleanupStage.TELEMETRY,
                        ProjectCleanupStage.DEVICE, ProjectCleanupStage.IAM, ProjectCleanupStage.SUPPORT);
        for (ProjectCleanupContributor prerequisite : prerequisites) {
            ProjectCleanupClaim current = admission.claimNext().orElseThrow();
            assertThat(current.stage()).isEqualTo(prerequisite.stage().name());
            assertThat(batches.execute(current).orElseThrow().complete()).isTrue();
        }
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("PROJECT"); return claim;
    }
    /** @return 下一真实批次 */
    private ProjectCleanupBatchResult next() { return batches.execute(admission.claimNext().orElseThrow()).orElseThrow(); }
    /** @param f 仅调整测试失败退避，不缩短投递或幂等保留 */
    private void allowRetry(Fixture f) { owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", f.project()); }
    /** @param operation 故障包装 @return 保留原五秒事务的完整编排 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim, ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定PROJECT阶段。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.PROJECT; }
            /** 原事务内执行真实SQL与受控故障。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }
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
    /** @return 全部前置域及空PROJECT后的实际FINALIZE领取 */
    private ProjectCleanupClaim finalClaim() {
        assertThat(batches.execute(memberClaim()).orElseThrow().complete()).isTrue();
        ProjectCleanupClaim claim=admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("FINALIZE"); return claim;
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
    /** @param f 项目，真实计量FK必须贯穿墓碑保存 */
    private static void seedUsage(Fixture f) {
        owner.update("INSERT INTO public.sys_usage_fact(id,tenant_id,project_id,metric,usage_date,event_key,occurred_at) VALUES (gen_random_uuid(),?,?,'REST_API_CALL',current_date,'cleanup-test',now())",f.tenant(),f.project());
        owner.update("INSERT INTO public.sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value) VALUES (gen_random_uuid(),?,?,current_date,'REST_API_CALL',1)",f.tenant(),f.project());
    }
    /** @param f 项目 @return 原始计量与汇总全字段 */
    private static String usage(Fixture f) { return owner.queryForObject("SELECT jsonb_build_object('facts',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_usage_fact t WHERE project_id=?),'daily',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_usage_counter_daily t WHERE project_id=?))::text",String.class,f.project(),f.project()); }
    /** @param f 项目，墓碑有限字段和无领取资格 */
    private static void assertTombstone(Fixture f) {
        assertThat(owner.queryForObject("SELECT status='PURGED' AND cleanup_stage='DONE' AND name='已清理项目' AND timezone='UTC' AND cleanup_completed_at IS NOT NULL AND cleanup_lease_token IS NULL AND cleanup_lease_until IS NULL AND cleanup_next_attempt_at IS NULL AND cleanup_failure_code IS NULL FROM public.sys_project WHERE id=?",Boolean.class,f.project())).isTrue();
    }
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
                        "classpath:db/migration/dashboard", "classpath:db/migration/ota")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }
    /** @param tenant 归属 @param project 清理身份 */
    private record Fixture(UUID tenant, UUID project) { }
}
