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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0089：IAM项目令牌安全保留、轮换链、共享身份和有界清理的真实资格。 */
@Testcontainers
class ProjectCleanupIamTests {
    /** 独占完整迁移库，使用与前序设备/遥测相同的实际数据库版本。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("iam_project_cleanup").withUsername("thingslink").withPassword("thingslink");
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
    /** 七个历史前置领域与当前看板空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 被测IAM受限仓储。 */
    private JdbcIamProjectCleanupRepository tokens;
    /** 真实IAM贡献及项目原事务。 */
    private ProjectCleanupBatchService batches;

    /** 旧1200库中的会话链与共享身份升级后逐字段保持，1300重跑零迁移。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260905.1200").migrate();
        Fixture legacy = fixture(true, null); seed(legacy, 3, true);
        String before = snapshot(legacy);
        String identity = identitySnapshot(legacy);
        assertThat(flyway("20260905.1300").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.1300").migrate().migrationsExecuted).isZero();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        assertThat(snapshot(legacy)).isEqualTo(before);
        assertThat(identitySnapshot(legacy)).isEqualTo(identity);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.oid='public.iam_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0", Long.class)).isZero();
    }

    /** 本隔离库一次清掉整个自引用集合，不跨项目操作生产数据；项目保留生产墓碑合同。 */
    @BeforeEach
    void prepare() {
        owner.update("DELETE FROM public.sys_refresh_token");
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
                ProjectCleanupDashboardCompatibilityFixture.emptyAssistantContributor(owner),
                proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app))),
                proxy(new DeviceProjectCleanupContributor(new JdbcDeviceProjectCleanupRepository(app))));
        tokens = new JdbcIamProjectCleanupRepository(app);
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(proxy(new IamProjectCleanupContributor(tokens)));
        batches = proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** 1001令牌链/环先500条整理再500条删除；真实行差、清理计数及共享账号/同族/无项目邻居保持。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void drainsBoundedChainsAndCyclesWithoutChangingSharedIdentities(boolean cycle) {
        Fixture first = fixture(false, null); seed(first, 1001, cycle);
        Fixture neighbor = fixture(true, first); seed(neighbor, 1, false);
        Fixture otherTenant = fixture(true, null); seed(otherTenant, 1, false);
        owner.update("UPDATE public.sys_refresh_token SET project_id=NULL,project_generation=0 WHERE project_id=?", otherTenant.project());
        String neighborBefore = snapshot(neighbor);
        String others = outsideSnapshot(first);
        String identity = identitySnapshot(first);
        ProjectCleanupClaim claim = iamClaim();
        int deleted = 0;
        int mutations = 0;
        boolean complete = false;
        for (int i = 0; i < 10; i++) {
            long before = count(first);
            long linksBefore = links(first);
            ProjectCleanupBatchResult result = batches.execute(claim).orElseThrow();
            assertThat(result.blockedReason()).isNull();
            assertThat(before - count(first)).isEqualTo(result.deletedRows());
            assertThat(result.deletedRows()).isBetween(0, 500);
            if (result.deletedRows() == 0 && !result.complete()) {
                assertThat(linksBefore - links(first)).isBetween(1L, 500L);
                mutations++;
            }
            assertThat(snapshot(neighbor)).isEqualTo(neighborBefore);
            assertThat(outsideSnapshot(first)).isEqualTo(others);
            assertThat(identitySnapshot(first)).isEqualTo(identity);
            deleted += result.deletedRows();
            if (result.complete()) { complete = true; break; }
            claim = admission.claimNext().orElseThrow();
        }
        assertThat(complete).isTrue();
        assertThat(deleted).isEqualTo(1001);
        assertThat(mutations).isEqualTo(cycle ? 3 : 2);
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("SUPPORT");
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(1001);
    }

    /** 七天保留未到先等待且无出边整理；到期可重领，原撤销时刻不被覆盖。 */
    @Test
    void waitsForRetentionBeforeChangingAnyToken() {
        Fixture first = fixture(false, null); seed(first, 1, true);
        owner.update("UPDATE public.sys_refresh_token SET expires_at=now()-interval '6 days',revoked_at=now()-interval '2 days' WHERE project_id=?", first.project());
        String before = snapshot(first);
        ProjectCleanupClaim claim = iamClaim();
        assertThat(batches.execute(claim).orElseThrow().blockedReason()).isEqualTo("IAM_TOKEN_RETENTION_WINDOW");
        assertThat(snapshot(first)).isEqualTo(before);
        owner.update("UPDATE public.sys_refresh_token SET expires_at=now()-interval '8 days' WHERE project_id=?", first.project());
        java.sql.Timestamp revoked = owner.queryForObject("SELECT revoked_at FROM public.sys_refresh_token WHERE project_id=?", java.sql.Timestamp.class, first.project());
        allowRetry(first);
        assertThat(next().deletedRows()).isZero();
        assertThat(links(first)).isZero();
        assertThat(owner.queryForObject("SELECT revoked_at FROM public.sys_refresh_token WHERE project_id=?", java.sql.Timestamp.class, first.project())).isEqualTo(revoked);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(next().complete()).isTrue();
    }

    /** 正常跨项目/NULL项目轮换前驱和跨租户异常入边都保留，不通过改邻居解除FK。 */
    @ParameterizedTest
    @ValueSource(strings = {"project", "none", "tenant"})
    void preservesIncomingReferencesFromOutsideProject(String mode) {
        Fixture first = fixture(false, null); seed(first, 1, false);
        Fixture neighbor = fixture(true, mode.equals("tenant") ? null : first); seed(neighbor, 1, false);
        owner.update("UPDATE public.sys_refresh_token SET replaced_by=?,project_id=CASE WHEN ? THEN NULL ELSE project_id END WHERE project_id=?",
                id(first), mode.equals("none"), neighbor.project());
        String outside = outsideSnapshot(first);
        String before = snapshot(first);
        assertThat(batches.execute(iamClaim()).orElseThrow().blockedReason()).isEqualTo("IAM_REFRESH_REFERENCED");
        assertThat(snapshot(first)).isEqualTo(before);
        assertThat(outsideSnapshot(first)).isEqualTo(outside);
    }

    /** 存量错tenant在任何改写前阻塞，不因本表豁免RLS就忽略归属。 */
    @Test
    void rejectsMismatchedTenant() {
        Fixture first = fixture(false, null); seed(first, 1, true);
        Fixture neighbor = fixture(true, null);
        owner.update("UPDATE public.sys_refresh_token SET tenant_id=? WHERE project_id=?", neighbor.tenant(), first.project());
        String before = snapshot(first);
        assertThat(batches.execute(iamClaim()).orElseThrow().blockedReason()).isEqualTo("IAM_SCOPE_MISMATCH");
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** UPDATE/DELETE都复验expires_at，锁等待期间续长安全窗口时不清链也不清token。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rechecksExpiryAfterWaitingForWriter(boolean linked) throws Exception {
        Fixture first = fixture(false, null); seed(first, 1, linked);
        ProjectCleanupClaim claim = iamClaim();
        String before = owner.queryForObject("SELECT (to_jsonb(t)-'expires_at')::text FROM public.sys_refresh_token t WHERE project_id=?", String.class, first.project());
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement update = writer.prepareStatement("UPDATE public.sys_refresh_token SET expires_at=now()+interval '1 day' WHERE project_id=?")) {
                update.setObject(1, first.project()); update.executeUpdate();
            }
            var cleanup = workers.submit(() -> batches.execute(claim));
            try { awaitLock("SELECT * FROM public.iam_project_cleanup_batch%"); } finally { writer.commit(); }
            assertThat(cleanup.get(3, TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("IAM_TOKEN_RETENTION_WINDOW");
        }
        assertThat(owner.queryForObject("SELECT (to_jsonb(t)-'expires_at')::text FROM public.sys_refresh_token t WHERE project_id=?", String.class, first.project())).isEqualTo(before);
    }

    /** 指针整理/删除任一步后故障或租约失效，都回滚数据和项目计数，可用原领取重试。 */
    @ParameterizedTest
    @ValueSource(strings = {"unlink", "delete", "lease"})
    void rollsBackChangesAndProgress(String mode) {
        Fixture first = fixture(false, null); seed(first, 1, mode.equals("unlink"));
        ProjectCleanupClaim claim = iamClaim();
        String before = snapshot(first);
        String progress = projectSnapshot(first);
        assertThatThrownBy(() -> batch(c -> {
            app.execute("CREATE TEMP TABLE sys_refresh_token(LIKE public.sys_refresh_token) ON COMMIT DROP");
            ProjectCleanupBatchResult result = tokens.clean(c);
            assertThat(result.deletedRows()).isEqualTo(mode.equals("unlink") ? 0 : 1);
            if (!mode.equals("lease")) throw new IllegalStateException("受控IAM变更后失败");
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
        Fixture first = fixture(false, null); seed(first, 1, false);
        ProjectCleanupClaim claim = iamClaim();
        if (field.equals("stage")) owner.update("UPDATE public.sys_project SET cleanup_stage='SUPPORT' WHERE id=?", first.project());
        if (field.equals("lease")) owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", first.project());
        ProjectCleanupClaim wrong = new ProjectCleanupClaim(field.equals("tenant") ? UUID.randomUUID() : claim.tenantId(),
                field.equals("project") ? UUID.randomUUID() : claim.projectId(), field.equals("generation") ? claim.generation()+1 : claim.generation(),
                claim.stage(), field.equals("token") ? UUID.randomUUID() : claim.leaseToken(), claim.leaseUntil(), false);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(field.equals("readonly"));
        if (field.equals("repeatable")) tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        String before = snapshot(first);
        assertSqlState(() -> tx.execute(status -> tokens.clean(wrong)), field.equals("readonly") || field.equals("repeatable") ? "25001" : "42501");
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** 空域推进SUPPORT，ACTIVE普通会话即使已过保留期也不能以伪造清理能力删除。 */
    @Test
    void completesEmptyDomainAndRejectsActiveProject() {
        Fixture first = fixture(false, null);
        assertThat(batches.execute(iamClaim()).orElseThrow().complete()).isTrue();
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("SUPPORT");
        Fixture active = fixture(true, null); seed(active, 1, false);
        ProjectCleanupClaim wrong = new ProjectCleanupClaim(active.tenant(), active.project(), 0, "IAM", UUID.randomUUID(), java.time.Instant.now().plusSeconds(120), false);
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> tokens.clean(wrong)), "42501");
        assertThat(count(active)).isEqualTo(1);
    }

    /** 外项目入边先持FK锁，清理锁后看到已提交引用并保留父token。 */
    @Test
    void incomingReferenceWinsBeforeParentLock() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1, false);
        Fixture neighbor = fixture(true, first); seed(neighbor, 1, false);
        ProjectCleanupClaim claim = iamClaim();
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            setReference(writer, neighbor, id(first));
            var cleanup = workers.submit(() -> batches.execute(claim));
            try { awaitLock("SELECT * FROM public.iam_project_cleanup_batch%"); } finally { writer.commit(); }
            assertThat(cleanup.get(3, TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("IAM_REFRESH_REFERENCED");
        }
        assertThat(count(first)).isEqualTo(1);
    }

    /** 清理先删父但未提交，后来的入边等待后23503，不产生悬挂会话链。 */
    @Test
    void parentDeletionWinsBeforeIncomingReference() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1, false);
        Fixture neighbor = fixture(true, first); seed(neighbor, 1, false);
        ProjectCleanupClaim claim = iamClaim();
        UUID parent = id(first);
        try (Connection cleanup = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink"); var workers = Executors.newSingleThreadExecutor()) {
            cleanup.setAutoCommit(false);
            try (PreparedStatement query = cleanup.prepareStatement("SELECT * FROM public.iam_project_cleanup_batch(?,?,?,?)")) {
                query.setObject(1, claim.tenantId()); query.setObject(2, claim.projectId()); query.setLong(3, claim.generation()); query.setObject(4, claim.leaseToken());
                try (var result = query.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getInt("deleted_rows")).isEqualTo(1); }
            }
            var insertion = workers.submit(() -> {
                try (Connection writer = connection()) { setReference(writer, neighbor, parent); return (SQLException) null; }
                catch (SQLException failure) { return failure; }
            });
            try { awaitLock("UPDATE public.sys_refresh_token SET replaced_by%"); } finally { cleanup.commit(); }
            SQLException failure = insertion.get(3, TimeUnit.SECONDS);
            assertThat(failure != null).isTrue(); assertThat(failure.getSQLState()).isEqualTo("23503");
        }
        assertThat(count(first)).isZero();
        assertThat(links(neighbor)).isZero();
    }
    /** 直接SQL入口在父锁等待期间过期也必须回滚；不能仅依赖Java批次最后的进度CAS。 */
    @Test
    void rejectsLeaseConsumedWhileWaitingForParentEvenAtDatabaseEntry() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1, false);
        ProjectCleanupClaim claim = iamClaim();
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement lock = writer.prepareStatement("SELECT id FROM public.sys_refresh_token WHERE project_id=? FOR UPDATE")) {
                lock.setObject(1, first.project()); lock.executeQuery().close();
            }
            owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()+interval '500 milliseconds' WHERE id=?", first.project());
            var cleanup = workers.submit(() -> {
                try { new TransactionTemplate(transactions).execute(status -> tokens.clean(claim)); return (RuntimeException) null; }
                catch (RuntimeException failure) { return failure; }
            });
            try {
                awaitLock("SELECT * FROM public.iam_project_cleanup_batch%");
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


    /** @return 七个历史真实前置和一个看板空域后的IAM领取 */
    private ProjectCleanupClaim iamClaim() {
        for (int i = 0; i < ProjectCleanupStage.IAM.ordinal(); i++) assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("IAM"); return claim;
    }
    /** @return 下一真实批次 */
    private ProjectCleanupBatchResult next() { return batches.execute(admission.claimNext().orElseThrow()).orElseThrow(); }
    /** @param f 仅调整测试失败退避，不缩短业务令牌保留 */
    private void allowRetry(Fixture f) { owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", f.project()); }
    /** @param operation 故障包装 @return 保留原五秒事务的完整编排 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim, ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定IAM阶段。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.IAM; }
            /** 原事务内执行真实SQL与受控故障。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }
    /** @param active 项目可用状态 @param shared 共用账号/租户/族的正常邻居 @return 真实项目身份 */
    private static Fixture fixture(boolean active, Fixture shared) {
        Fixture f = new Fixture(shared == null ? UUID.randomUUID() : shared.tenant(), UUID.randomUUID(),
                shared == null ? UUID.randomUUID() : shared.account(), shared == null ? UUID.randomUUID() : shared.family());
        if (shared == null) {
            owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'IAM清理租户')", f.tenant());
            owner.update("INSERT INTO public.sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}test-only','共享账号')", f.account(), f.account()+"@example.test");
            owner.update("INSERT INTO public.sys_tenant_member(id,tenant_id,account_id) VALUES (gen_random_uuid(),?,?)", f.tenant(), f.account());
        }
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'IAM清理项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                f.project(), f.tenant(), "iam_"+f.project().toString().replace("-", ""), active ? "ACTIVE" : "DELETING", active ? 0 : 1, active);
        return f;
    }
    /** @param f 项目 @param size 真实令牌数量 @param cycle 是否闭合本项目轮换环 */
    private static void seed(Fixture f, int size, boolean cycle) {
        owner.update("""
                INSERT INTO public.sys_refresh_token(id,account_id,tenant_id,project_id,project_generation,token_hash,family_id,issued_at,expires_at)
                SELECT gen_random_uuid(),?,?,?,0,digest(gen_random_uuid()::text,'sha256'),?,now()-interval '9 days',now()-interval '8 days'
                  FROM generate_series(1,?)
                """, f.account(), f.tenant(), f.project(), f.family(), size);
        owner.update("""
                WITH ordered AS (SELECT id,lead(id) OVER (ORDER BY id) AS successor,first_value(id) OVER (ORDER BY id) AS first_id
                    FROM public.sys_refresh_token WHERE project_id=?)
                UPDATE public.sys_refresh_token t SET replaced_by=coalesce(o.successor,CASE WHEN ? THEN o.first_id ELSE NULL END)
                  FROM ordered o WHERE t.id=o.id
                """, f.project(), cycle);
    }
    /** @param f 单行项目 @return 真实token主键 */
    private static UUID id(Fixture f) { return owner.queryForObject("SELECT id FROM public.sys_refresh_token WHERE project_id=?", UUID.class, f.project()); }
    /** @param f 项目 @return 真实剩余行数 */
    private static long count(Fixture f) { return owner.queryForObject("SELECT count(*) FROM public.sys_refresh_token WHERE project_id=?", Long.class, f.project()); }
    /** @param f 项目 @return 非空出边数量 */
    private static long links(Fixture f) { return owner.queryForObject("SELECT count(*) FROM public.sys_refresh_token WHERE project_id=? AND replaced_by IS NOT NULL", Long.class, f.project()); }
    /** @param f 项目 @return 包含哈希与撤销时刻的完整事实快照 */
    private static String snapshot(Fixture f) { return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM public.sys_refresh_token t WHERE project_id=?", String.class, f.project()); }
    /** @param f 项目 @return 其他项目及NULL项目全部会话快照 */
    private static String outsideSnapshot(Fixture f) { return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM public.sys_refresh_token t WHERE project_id IS DISTINCT FROM ?", String.class, f.project()); }
    /** @param f 项目 @return 账号、租户、成员完整身份快照 */
    private static String identitySnapshot(Fixture f) {
        return owner.queryForObject("SELECT jsonb_build_object('account',(SELECT to_jsonb(a) FROM public.sys_account a WHERE id=?),'tenant',(SELECT to_jsonb(t) FROM public.sys_tenant t WHERE id=?),'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM public.sys_tenant_member m WHERE account_id=?))::text", String.class, f.account(), f.tenant(), f.account());
    }
    /** @param f 项目 @return 围栏与删除计数快照 */
    private static String projectSnapshot(Fixture f) { return owner.queryForObject("SELECT to_jsonb(p)::text FROM public.sys_project p WHERE id=?", String.class, f.project()); }
    /** @return 独立受控并发连接 */
    private static Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"); }
    /** @param c 外项目写事务 @param f 前驱会话 @param parent 后继token */
    private static void setReference(Connection c, Fixture f, UUID parent) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE public.sys_refresh_token SET replaced_by=? WHERE project_id=?")) {
            update.setObject(1, parent); update.setObject(2, f.project()); update.executeUpdate();
        }
    }
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
    /** @param tenant 归属 @param project 清理轴 @param account 共享身份 @param family 跨项目轮换族 */
    private record Fixture(UUID tenant, UUID project, UUID account, UUID family) { }
}
