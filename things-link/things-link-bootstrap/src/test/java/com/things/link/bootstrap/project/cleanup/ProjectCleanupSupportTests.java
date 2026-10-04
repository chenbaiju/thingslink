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
import com.things.link.support.cleanup.SupportProjectCleanupService;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0090：support投递、重放、幂等期限、租户共享槽和有界清理的真实资格。 */
@Testcontainers
class ProjectCleanupSupportTests {
    /** 独占完整迁移库，使用与前序设备/遥测相同的实际数据库版本。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("support_project_cleanup").withUsername("thingslink").withPassword("thingslink");
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
    /** 八个历史前置领域与当前看板空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 被测support受限服务。 */
    private SupportProjectCleanupContributor facts;
    /** 真实support贡献及项目原事务。 */
    private ProjectCleanupBatchService batches;

    /** 1300旧库事实保真升级、重跑零迁移，APP能力不向PUBLIC开放。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260905.1300").migrate();
        Fixture legacy = fixture(true, null); seed(legacy, 2, 2);
        String before = snapshot(legacy);
        assertThat(flyway("20260905.1400").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.1400").migrate().migrationsExecuted).isZero();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        assertThat(snapshot(legacy)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.oid='public.support_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0", Long.class)).isZero();
    }

    /** 隔离库每例重置技术事实，保留不可变审计；不操作开发库。 */
    @BeforeEach
    void prepare() {
        owner.update("DELETE FROM public.sys_outbox_event");
        owner.update("DELETE FROM public.sys_idempotency_record");
        owner.update("DELETE FROM public.sys_tenant_work_slot");
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
                ProjectCleanupDashboardCompatibilityFixture.emptyContributor(),
                ProjectCleanupDashboardCompatibilityFixture.emptyOtaContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
                proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app))),
                proxy(new DeviceProjectCleanupContributor(new JdbcDeviceProjectCleanupRepository(app))),
                proxy(new IamProjectCleanupContributor(new JdbcIamProjectCleanupRepository(app))));
        facts = proxy(new SupportProjectCleanupContributor(proxy(new SupportProjectCleanupService(app))));
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(facts);
        batches = proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** 两个1001行集合分六轮删除，逐轮核对行差/计数；同租户、异租户和NULL项目邻居保持。 */
    @Test
    void drainsTwoTablesInSeparateBoundedBatchesAndPreservesSharedFacts() {
        Fixture first = fixture(false, null); seed(first, 1001, 1001);
        Fixture neighbor = fixture(true, first); seed(neighbor, 1, 1);
        Fixture other = fixture(true, null); seed(other, 1, 1);
        owner.update("UPDATE public.sys_idempotency_record SET project_id=NULL WHERE project_id=?", other.project());
        owner.update("INSERT INTO public.sys_tenant_work_slot(work_type,tenant_id,lease_token,leased_until) VALUES ('NOTIFICATION',?,gen_random_uuid(),now()+interval '1 minute'),('NOTIFICATION',?,gen_random_uuid(),now()-interval '1 minute')", first.tenant(), other.tenant());
        ProjectCleanupClaim claim = supportClaim();
        String outside = outsideSnapshot(first);
        String shared = sharedSnapshot();
        int deleted = 0;
        List<Integer> sizes = new ArrayList<>();
        boolean complete = false;
        for (int i = 0; i < 8; i++) {
            long before = count(first);
            ProjectCleanupBatchResult result = batches.execute(claim).orElseThrow();
            assertThat(result.blockedReason()).isNull();
            assertThat(before-count(first)).isEqualTo(result.deletedRows());
            assertThat(result.deletedRows()).isBetween(0, 500);
            assertThat(outsideSnapshot(first)).isEqualTo(outside);
            assertThat(sharedSnapshot()).isEqualTo(shared);
            deleted += result.deletedRows();
            if (result.complete()) { complete = true; break; }
            sizes.add(result.deletedRows()); claim = admission.claimNext().orElseThrow();
        }
        assertThat(complete).isTrue();
        assertThat(sizes).containsExactly(500, 500, 1, 500, 500, 1);
        assertThat(deleted).isEqualTo(2002);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(2002);
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("PROJECT");
    }

    /** 任一活跃事实阻止整个本轮，过期IN_PROGRESS与已完成幂等均遵守持久期限。 */
    @ParameterizedTest
    @ValueSource(strings = {"pending", "lease", "published", "created", "idempotency"})
    void preservesDeliveryAndRetentionWindows(String mode) {
        Fixture first = fixture(false, null); seed(first, 1, 1);
        String reason = switch (mode) {
            case "pending" -> { owner.update("UPDATE public.sys_outbox_event SET status='PENDING',published_at=NULL"); yield "SUPPORT_OUTBOX_PENDING"; }
            case "lease" -> { owner.update("UPDATE public.sys_outbox_event SET lease_token=gen_random_uuid(),leased_until=now()+interval '1 minute'"); yield "SUPPORT_OUTBOX_IN_FLIGHT"; }
            case "published" -> { owner.update("UPDATE public.sys_outbox_event SET published_at=now()-interval '7 days'"); yield "SUPPORT_REPLAY_WINDOW"; }
            case "created" -> { owner.update("UPDATE public.sys_outbox_event SET created_at=now()-interval '7 days'"); yield "SUPPORT_REPLAY_WINDOW"; }
            default -> { owner.update("UPDATE public.sys_idempotency_record SET expires_at=now()+interval '1 day'"); yield "SUPPORT_IDEMPOTENCY_WINDOW"; }
        };
        String before = snapshot(first);
        assertThat(batches.execute(supportClaim()).orElseThrow().blockedReason()).isEqualTo(reason);
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** 真实发布仓储的领取和确认不会被清理截断；确认时刻重新开启重放窗口，旧确认不能复活删除行。 */
    @Test
    void waitsForRealPublisherClaimConfirmationAndReplayWindow() {
        Fixture first = fixture(false, null); seed(first, 1, 0);
        owner.update("UPDATE public.sys_outbox_event SET status='PENDING',published_at=NULL");
        UUID event = owner.queryForObject("SELECT id FROM public.sys_outbox_event", UUID.class);
        assertThat(batches.execute(supportClaim()).orElseThrow().blockedReason()).isEqualTo("SUPPORT_OUTBOX_PENDING");
        JdbcTransactionalOutboxRepository publisher = proxy(new JdbcTransactionalOutboxRepository(app));
        var delivery = publisher.claimReady(1, Duration.ofSeconds(30));
        assertThat(delivery.events()).hasSize(1);
        allowRetry(first);
        assertThat(next().blockedReason()).isEqualTo("SUPPORT_OUTBOX_IN_FLIGHT");
        assertThat(publisher.markPublished(event, delivery.leaseToken())).isTrue();
        allowRetry(first);
        assertThat(next().blockedReason()).isEqualTo("SUPPORT_REPLAY_WINDOW");
        owner.update("UPDATE public.sys_outbox_event SET published_at=now()-interval '9 days'");
        allowRetry(first);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(publisher.markPublished(event, delivery.leaseToken())).isFalse();
        assertThat(publisher.markRetry(event, delivery.leaseToken(), Instant.now(), "迟到确认")).isFalse();
        assertThat(publisher.claimReady(1, Duration.ofSeconds(30)).events()).isEmpty();
        assertThat(next().complete()).isTrue();
    }

    /** 旧库异常归属不能隐藏在RLS后；NULL租户的项目幂等也必须阻塞。 */
    @ParameterizedTest
    @ValueSource(strings = {"outbox", "idempotency", "nullTenant"})
    void rejectsMismatchedScope(String mode) {
        Fixture first = fixture(false, null); seed(first, 1, 1);
        if (mode.equals("outbox")) owner.update("UPDATE public.sys_outbox_event SET tenant_id=gen_random_uuid()");
        else owner.update("UPDATE public.sys_idempotency_record SET tenant_id=?", mode.equals("nullTenant") ? null : UUID.randomUUID());
        String before = snapshot(first);
        assertThat(batches.execute(supportClaim()).orElseThrow().blockedReason()).isEqualTo("SUPPORT_SCOPE_MISMATCH");
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** MVCC预检后等待行锁时，DELETE复验发布、租约、归属与幂等到期事实，不能按旧快照删除。 */
    @ParameterizedTest
    @ValueSource(strings = {"pending", "lease", "published", "idempotency", "tenant"})
    void rechecksFactsAfterWaitingForWriter(String mode) throws Exception {
        Fixture first = fixture(false, null); seed(first, mode.equals("idempotency") ? 0 : 1, mode.equals("idempotency") ? 1 : 0);
        ProjectCleanupClaim claim = supportClaim();
        String statement = switch (mode) {
            case "pending" -> "UPDATE public.sys_outbox_event SET status='PENDING',published_at=NULL WHERE project_id=?";
            case "lease" -> "UPDATE public.sys_outbox_event SET lease_token=gen_random_uuid(),leased_until=now()+interval '1 minute' WHERE project_id=?";
            case "published" -> "UPDATE public.sys_outbox_event SET published_at=now() WHERE project_id=?";
            case "tenant" -> "UPDATE public.sys_outbox_event SET tenant_id=gen_random_uuid() WHERE project_id=?";
            default -> "UPDATE public.sys_idempotency_record SET expires_at=now()+interval '1 day' WHERE project_id=?";
        };
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement update = writer.prepareStatement(statement)) { update.setObject(1, first.project()); update.executeUpdate(); }
            var cleanup = workers.submit(() -> batches.execute(claim));
            try { awaitLock("SELECT * FROM public.support_project_cleanup_batch%"); } finally { writer.commit(); }
            assertThat(cleanup.get(3, TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("SUPPORT_FACT_CHANGED");
        }
        assertThat(count(first)).isEqualTo(1);
    }

    /** 任一技术表删除后故障或租约失效，都回滚数据和项目计数，可用原领取重试。 */
    @ParameterizedTest
    @ValueSource(strings = {"outbox", "idempotency", "lease"})
    void rollsBackChangesAndProgress(String mode) {
        Fixture first = fixture(false, null); seed(first, mode.equals("idempotency") ? 0 : 1, mode.equals("idempotency") ? 1 : 0);
        ProjectCleanupClaim claim = supportClaim();
        String before = snapshot(first);
        String progress = projectSnapshot(first);
        assertThatThrownBy(() -> batch(c -> {
            app.execute("CREATE TEMP TABLE sys_outbox_event(LIKE public.sys_outbox_event) ON COMMIT DROP");
            ProjectCleanupBatchResult result = facts.clean(c);
            assertThat(result.deletedRows()).isEqualTo(1);
            if (!mode.equals("lease")) throw new IllegalStateException("受控SUPPORT变更后失败");
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
        Fixture first = fixture(false, null); seed(first, 1, 0);
        ProjectCleanupClaim claim = supportClaim();
        if (field.equals("stage")) owner.update("UPDATE public.sys_project SET cleanup_stage='PROJECT' WHERE id=?", first.project());
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

    /** 空域推进PROJECT，ACTIVE普通会话即使已过保留期也不能以伪造清理能力删除。 */
    @Test
    void completesEmptyDomainAndRejectsActiveProject() {
        Fixture first = fixture(false, null);
        assertThat(batches.execute(supportClaim()).orElseThrow().complete()).isTrue();
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("PROJECT");
        Fixture active = fixture(true, null); seed(active, 1, 0);
        ProjectCleanupClaim wrong = new ProjectCleanupClaim(active.tenant(), active.project(), 0, "SUPPORT", UUID.randomUUID(), java.time.Instant.now().plusSeconds(120), false);
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> facts.clean(wrong)), "42501");
        assertThat(count(active)).isEqualTo(1);
    }

    /** 直接SQL入口在父锁等待期间过期也必须回滚；不能仅依赖Java批次最后的进度CAS。 */
    @Test
    void rejectsLeaseConsumedWhileWaitingForParentEvenAtDatabaseEntry() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1, 0);
        ProjectCleanupClaim claim = supportClaim();
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement lock = writer.prepareStatement("SELECT id FROM public.sys_outbox_event WHERE project_id=? FOR UPDATE")) {
                lock.setObject(1, first.project()); lock.executeQuery().close();
            }
            owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()+interval '500 milliseconds' WHERE id=?", first.project());
            var cleanup = workers.submit(() -> {
                try { new TransactionTemplate(transactions).execute(status -> facts.clean(claim)); return (RuntimeException) null; }
                catch (RuntimeException failure) { return failure; }
            });
            try {
                awaitLock("SELECT * FROM public.support_project_cleanup_batch%");
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


    /** @return 八个历史真实前置和一个看板空域后的SUPPORT领取 */
    private ProjectCleanupClaim supportClaim() {
        for (int i = 0; i < ProjectCleanupStage.SUPPORT.ordinal(); i++) assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("SUPPORT"); return claim;
    }
    /** @return 下一真实批次 */
    private ProjectCleanupBatchResult next() { return batches.execute(admission.claimNext().orElseThrow()).orElseThrow(); }
    /** @param f 仅调整测试失败退避，不缩短投递或幂等保留 */
    private void allowRetry(Fixture f) { owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", f.project()); }
    /** @param operation 故障包装 @return 保留原五秒事务的完整编排 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim, ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定SUPPORT阶段。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.SUPPORT; }
            /** 原事务内执行真实SQL与受控故障。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }
    /** @param active 活跃邻居或待清理 @param shared 同租户邻居 @return 独立项目 */
    private static Fixture fixture(boolean active, Fixture shared) {
        Fixture f = new Fixture(shared == null ? UUID.randomUUID() : shared.tenant(), UUID.randomUUID());
        if (shared == null) owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'support清理租户')", f.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'support清理项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                f.project(), f.tenant(), "support_"+f.project().toString().replace("-", ""), active ? "ACTIVE" : "DELETING", active ? 0 : 1, active);
        return f;
    }
    /** @param f 项目 @param outbox 已发布九天的真实事件 @param idempotency 已过期的真实幂等行 */
    private static void seed(Fixture f, int outbox, int idempotency) {
        owner.update("""
                INSERT INTO public.sys_outbox_event(id,tenant_id,project_id,aggregate_type,aggregate_id,event_type,destination_topic,partition_key,payload,trace_id,status,published_at,created_at)
                SELECT gen_random_uuid(),?,?,'DEVICE',gen_random_uuid(),'DEVICE_COMMAND_DISPATCH','tc.device.downlink',gen_random_uuid()::text,'{}','test','PUBLISHED',now()-interval '9 days',now()-interval '10 days' FROM generate_series(1,?)
                """, f.tenant(), f.project(), outbox);
        owner.update("""
                INSERT INTO public.sys_idempotency_record(id,tenant_id,project_id,idempotency_key,request_method,request_path,request_body_hash,status,completed_at,expires_at)
                SELECT gen_random_uuid(),?,?,gen_random_uuid()::text,'POST','/test/cleanup',repeat('a',64),CASE WHEN i%2=0 THEN 'COMPLETED' ELSE 'IN_PROGRESS' END,
                    CASE WHEN i%2=0 THEN now()-interval '2 days' ELSE NULL END,now()-interval '1 day' FROM generate_series(1,?) i
                """, f.tenant(), f.project(), idempotency);
    }
    /** @param f 项目 @return 两个技术表的实际行数 */
    private static long count(Fixture f) { return owner.queryForObject("SELECT (SELECT count(*) FROM public.sys_outbox_event WHERE project_id=?)+(SELECT count(*) FROM public.sys_idempotency_record WHERE project_id=?)", Long.class, f.project(), f.project()); }
    /** @param f 项目 @return 全字段快照 */
    private static String snapshot(Fixture f) { return factsSnapshot(f, false); }
    /** @param f 项目 @return 所有非本项目及NULL项目行 */
    private static String outsideSnapshot(Fixture f) { return factsSnapshot(f, true); }
    /** @param f 项目 @param outside 是否观察邻居 @return 有稳定顺序的完整快照 */
    private static String factsSnapshot(Fixture f, boolean outside) {
        String predicate = outside ? "IS DISTINCT FROM" : "=";
        return owner.queryForObject("SELECT jsonb_build_object('outbox',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_outbox_event t WHERE project_id "+predicate+" ?),'idempotency',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_idempotency_record t WHERE project_id "+predicate+" ?))::text", String.class, f.project(), f.project());
    }
    /** @return 审计与租户共享槽的全字段快照，过期槽也不由项目删除 */
    private static String sharedSnapshot() { return owner.queryForObject("SELECT jsonb_build_object('audit',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_audit_log t),'slots',(SELECT jsonb_agg(to_jsonb(t) ORDER BY tenant_id,work_type) FROM public.sys_tenant_work_slot t))::text", String.class); }
    /** @param f 项目 @return 项目围栏、租约和累计行数 */
    private static String projectSnapshot(Fixture f) { return owner.queryForObject("SELECT to_jsonb(p)::text FROM public.sys_project p WHERE id=?", String.class, f.project()); }
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
                        "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/enduser", "classpath:db/migration/export")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }
    /** @param tenant 归属 @param project 事实清理轴 */
    private record Fixture(UUID tenant, UUID project) { }
}
