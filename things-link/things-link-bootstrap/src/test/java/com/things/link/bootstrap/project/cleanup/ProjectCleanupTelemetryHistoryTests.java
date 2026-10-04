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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0083：真实共享压缩chunk、分层历史保留和刷新互斥；完整遥测贡献尚不装配。 */
@Testcontainers
class ProjectCleanupTelemetryHistoryTests {

    /** 每类独占TimescaleDB，固定版本资格和真实chunk均可观察。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("telemetry_history_cleanup").withUsername("thingslink").withPassword("thingslink");
    /** 固定历史查询目标，不引用Timescale私有物化表。 */
    private static final List<String> AGGREGATES = List.of("ts_property_point_1m_internal",
            "ts_property_point_1h_internal", "ts_property_point_1d_internal");
    /** owner仅初始化历史、维护作业和观察持久快照。 */
    private static JdbcTemplate owner;
    private static HikariDataSource ownerSource;
    private static HikariDataSource appSource;
    /** 清理SQL和项目批次使用实际APP连接。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 同物理数据库事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** 项目进度权威写入。 */
    private JdbcProjectCleanupRepository projects;
    /** 真实准入与租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 五个历史先行空域走真实贡献，看板以当前顺序完成空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 历史测试适配器在历史为空时保持TELEMETRY，不冒充完整域完成。 */
    private ProjectCleanupBatchService batches;

    /** 真实0600旧库升级0700且重复幂等，测试只关闭本容器的时钟作业。 */
    @BeforeAll
    static void migrate() {
        ownerSource = source("thingslink", "cleanup-telemetry-owner");
        owner = new JdbcTemplate(ownerSource);
        flyway("20260905.0600").migrate();
        assertThat(flyway("20260905.0700").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0700").migrate().migrationsExecuted).isZero();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        owner.queryForList("SELECT alter_job(job_id,scheduled=>false) FROM timescaledb_information.jobs");
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a "
                + "WHERE p.oid='public.telemetry_project_history_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0",
                Long.class)).isZero();
        appSource = source("thingslink_app", "cleanup-telemetry-app");
    }

    @AfterAll
    static void closeSources() {
        if (appSource != null) appSource.close();
        if (ownerSource != null) ownerSource.close();
    }

    private static HikariDataSource source(String user, String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(user);
        config.setPassword("thingslink");
        config.setPoolName(name);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        // 压缩 chunk 的观察查询不复用服务端预编译计划；被测 APP 仍保留驱动默认行为。
        if (user.equals("thingslink")) config.addDataSourceProperty("prepareThreshold", "0");
        return new HikariDataSource(config);
    }

    /** 隔离本类每例，生产清理不能借用此owner全表清夹具。 */
    @BeforeEach
    void prepare() {
        owner.update("DELETE FROM public.ts_property_point_internal");
        for (String aggregate : AGGREGATES) owner.update("DELETE FROM public." + aggregate);
        owner.update("DELETE FROM public.ts_property_aggregate_backfill");
        owner.update("DELETE FROM public.sys_project");
        app = new JdbcTemplate(appSource);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions = new DataSourceTransactionManager(appSource);
        projects = new JdbcProjectCleanupRepository(app);
        admission = proxy(new ProjectCleanupAdmissionService(projects, new AuditLogService(app, new ObjectMapper()), app));
        prerequisites = List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))),
                proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app))),
                proxy(new AlarmProjectCleanupContributor(new JdbcAlarmProjectCleanupRepository(app))),
                proxy(new AppProjectCleanupContributor(new JdbcAppProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyContributor(),
                ProjectCleanupDashboardCompatibilityFixture.emptyOtaContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner));
        batches = batch(this::history);
    }

    /** 1001个完整分组令raw和三级聚合各1001行；500行有界且全程保留同chunk邻居快照。 */
    @Test
    void cleansFourThousandHistoryRowsInBoundedCompressedBatches() {
        Fixture first = fixture(false, 20);
        Fixture neighbor = fixture(true, 20);
        points(first, 1001, true);
        points(neighbor, 3, true);
        refresh(first);
        backfill(first, false);
        compress();
        List<String> before = snapshot(neighbor);
        assertThat(sharedCompressedChunks(first, neighbor)).isPositive();
        ProjectCleanupClaim claim = historyClaim();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(500);
        assertThat(rawCount(first)).isEqualTo(501);
        // 重建服务模拟实例丢失内存，剩余进度只从数据库领取恢复。
        batches = batch(this::history);
        assertThat(drain(first, neighbor, before)).isEqualTo(3505);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(4005);
        assertThat(owner.queryForObject("SELECT cleanup_batches FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(13);
        assertThat(snapshot(first)).allSatisfy(value -> assertThat(value).isEqualTo("[]"));
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("TELEMETRY");
        assertThat(sharedCompressedChunks(first, neighbor)).isPositive();
    }

    /** 单个压缩segment超过500条时仍只DELETE500，不因解压批块误删整段。 */
    @Test
    void limitsDeletionInsideOneLargeCompressedSegment() {
        Fixture first = fixture(false, 20);
        Fixture neighbor = fixture(true, 20);
        points(first, 1001, false);
        points(neighbor, 1001, false);
        refresh(first);
        compress();
        List<String> before = snapshot(neighbor);
        assertThat(batches.execute(historyClaim()).orElseThrow().deletedRows()).isEqualTo(500);
        assertThat(rawCount(first)).isEqualTo(501);
        assertThat(snapshot(neighbor)).isEqualTo(before);
        assertThat(drain(first, neighbor, before)).isEqualTo(504);
    }

    /** raw已按90天真实删除时只删项目聚合，不能重刷共享窗口丢掉邻居长期汇总。 */
    @Test
    void preservesNeighborsAggregatesAfterRawRetentionHasRemovedTheirSource() {
        Fixture first = fixture(false, 110);
        Fixture neighbor = fixture(true, 110);
        points(first, 1, false);
        points(neighbor, 1, false);
        refresh(first);
        List<String> before = snapshot(neighbor);
        owner.queryForList("SELECT drop_chunks('public.ts_property_point_internal',older_than=>interval '90 days')");
        before = snapshot(neighbor);
        assertThat(rawCount(neighbor)).isZero();
        assertThat(before.subList(1,4)).allSatisfy(value -> assertThat(value).contains("42"));
        assertThat(batches.execute(historyClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        assertThat(drain(first, neighbor, before)).isEqualTo(2);
        assertThat(snapshot(neighbor)).isEqualTo(before);
    }

    /** 历史SQL已经删除后故障，原项目进度与所有聚合/压缩点必须一起回滚，再领取同token成功。 */
    @Test
    void rollsBackDeletedCompressedRowsAndProgressOnFailure() {
        Fixture first = fixture(false, 20);
        points(first, 1001, false);
        refresh(first);
        compress();
        List<String> before = snapshot(first);
        ProjectCleanupClaim claim = historyClaim();
        ProjectCleanupBatchService broken = batch(c -> {
            assertThat(history(c).deletedRows()).isEqualTo(500);
            throw new IllegalStateException("历史删除后受控失败");
        });
        assertThatThrownBy(() -> broken.execute(claim)).hasMessage("历史删除后受控失败");
        assertThat(snapshot(first)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?", Long.class, first.project())).isZero();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(500);
    }

    /** 未知实时视图布局必须保留raw，不能因DELETE语法或视图投影变化猜测兼容。 */
    @Test
    void blocksChangedAggregateLayoutBeforeDeletingAnyHistory() {
        Fixture first = fixture(false, 20);
        points(first, 1, false);
        ProjectCleanupClaim claim = historyClaim();
        owner.execute("ALTER MATERIALIZED VIEW public.ts_property_point_1m_internal SET(timescaledb.materialized_only=false)");
        try {
            assertThat(batches.execute(claim).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_AGGREGATE_LAYOUT_CHANGED");
            assertThat(rawCount(first)).isEqualTo(1);
        } finally {
            owner.execute("ALTER MATERIALIZED VIEW public.ts_property_point_1m_internal SET(timescaledb.materialized_only=true)");
        }
        makeDue(first);
        assertThat(next().deletedRows()).isEqualTo(1);
    }

    /** 有效回补租约保留，租约过期后逐批回收；历史子域为空也保持当前整体阶段。 */
    @Test
    void waitsForBackfillLeaseAndCompletesOnlyHistorySubdomain() {
        Fixture first = fixture(false, 20);
        backfill(first, true);
        assertThat(batches.execute(historyClaim()).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_BACKFILL_IN_FLIGHT");
        assertThat(owner.queryForObject("SELECT count(*) FROM public.ts_property_aggregate_backfill WHERE project_id=?", Long.class, first.project())).isEqualTo(1);
        owner.update("UPDATE public.ts_property_aggregate_backfill SET lease_until=clock_timestamp()-interval '1 second' WHERE project_id=?", first.project());
        makeDue(first);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(next().blockedReason()).isEqualTo("TELEMETRY_REMAINING");
    }

    /** TEMP同名空表不能冒充历史已清空；完整身份和阶段由project权威授权，错误时42501。 */
    @Test
    void rejectsWrongIdentityAndIgnoresTemporaryHistoryTables() {
        Fixture first = fixture(false, 20);
        points(first, 1, false);
        ProjectCleanupClaim claim = historyClaim();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.execute(status -> history(new ProjectCleanupClaim(claim.tenantId(), claim.projectId(),
                claim.generation(), claim.stage(), UUID.randomUUID(), claim.leaseUntil(), false))))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(batch(c -> {
            app.execute("CREATE TEMP TABLE ts_property_point_internal ON COMMIT DROP AS SELECT * FROM public.ts_property_point WITH NO DATA");
            return history(c);
        }).execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
        owner.update("UPDATE public.sys_project SET cleanup_stage='DEVICE',cleanup_lease_token=?,cleanup_lease_until=clock_timestamp()+interval '1 minute' WHERE id=?",
                UUID.randomUUID(), first.project());
        assertThatThrownBy(() -> transaction.execute(status -> history(claim))).hasRootCauseInstanceOf(SQLException.class);
    }

    /** 领域删除成功但最终项目租约已失效时，压缩行和租约修改均随原事务回滚。 */
    @Test
    void rollsBackWhenFinalLeaseFenceExpiresAfterDeletion() {
        Fixture first=fixture(false,20);
        points(first,1001,false);
        compress();
        ProjectCleanupClaim claim=historyClaim();
        List<String> before=snapshot(first);
        assertThatThrownBy(() -> batch(c -> {
            ProjectCleanupBatchResult result=history(c);
            app.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",first.project());
            return result;
        }).execute(claim)).hasMessage("项目清理批次提交前租约失效");
        assertThat(snapshot(first)).isEqualTo(before);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(500);
    }

    /** 请求在DELETE等待行锁时被原扫描器续租，锁后谓词必须保留它而不是误报历史完成。 */
    @Test
    void rechecksBackfillLeaseAfterRowLockWait() throws Exception {
        Fixture first=fixture(false,20);
        backfill(first,false);
        ProjectCleanupClaim claim=historyClaim();
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement update=writer.prepareStatement("UPDATE public.ts_property_aggregate_backfill SET lease_until=clock_timestamp()+interval '5 minutes' WHERE project_id=?")) {
                update.setObject(1,first.project());
                update.executeUpdate();
            }
            var cleanup=workers.submit(() -> batches.execute(claim));
            try {
                awaitSqlLock("SELECT * FROM public.telemetry_project_history_cleanup_batch%");
            } finally {
                writer.commit();
            }
            assertThat(cleanup.get(3,TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_BACKFILL_IN_FLIGHT");
        }
        assertThat(owner.queryForObject("SELECT lease_until>clock_timestamp() FROM public.ts_property_aggregate_backfill WHERE project_id=?",
                Boolean.class,first.project())).isTrue();
    }

    /** 清理先持视图锁，真实刷新等待；释放后刷新读取已提交空raw，不复活已删项目。 */
    @Test
    void serializesRefreshAfterCleanupAndDoesNotResurrectHistory() throws Exception {
        Fixture first=fixture(false,20);
        Fixture neighbor=fixture(true,20);
        points(first,1,false);
        points(neighbor,1,false);
        refresh(first);
        List<String> before=snapshot(neighbor);
        assertThat(batches.execute(historyClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        ProjectCleanupClaim claim=admission.claimNext().orElseThrow();
        CountDownLatch deleted=new CountDownLatch(1);
        CountDownLatch commit=new CountDownLatch(1);
        try (var workers=Executors.newFixedThreadPool(2)) {
            var cleanup=workers.submit(() -> batch(c -> {
                ProjectCleanupBatchResult result=history(c);
                assertThat(result.deletedRows()).isEqualTo(1);
                deleted.countDown();
                await(commit);
                return result;
            }).execute(claim));
            try {
                assertThat(deleted.await(3,TimeUnit.SECONDS)).isTrue();
                var refresh=workers.submit(() -> refreshMinute(first));
                awaitRefreshLock();
                commit.countDown();
                assertThat(cleanup.get(3,TimeUnit.SECONDS).orElseThrow().deletedRows()).isEqualTo(1);
                refresh.get(3,TimeUnit.SECONDS);
            } finally {
                commit.countDown();
            }
        }
        assertThat(drain(first,neighbor,before)).isEqualTo(2);
        refresh(first);
        assertThat(snapshot(first)).allSatisfy(value -> assertThat(value).isEqualTo("[]"));
        assertThat(snapshot(neighbor)).isEqualTo(before);
    }

    /** 已排队的真实刷新先行，清理NOWAIT阻塞且不动历史；刷新完成后下一轮收束。 */
    @Test
    void defersWhenRefreshIsAheadAndResumesAfterItCommits() throws Exception {
        Fixture first=fixture(false,20);
        Fixture neighbor=fixture(true,20);
        points(first,1,false);
        points(neighbor,1,false);
        refresh(first);
        List<String> before=snapshot(neighbor);
        assertThat(batches.execute(historyClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        ProjectCleanupClaim claim=admission.claimNext().orElseThrow();
        try (Connection blocker=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock=blocker.prepareStatement("SELECT bucket FROM public.ts_property_point_1m_internal WHERE project_id=? FOR UPDATE")) {
                lock.setObject(1,first.project());
                lock.executeQuery().close();
            }
            var refresh=workers.submit(() -> refreshMinute(first));
            try {
                awaitRefreshLock();
                assertThat(batches.execute(claim).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_HISTORY_BUSY");
                assertThat(snapshot(first).get(1)).contains("42");
            } finally {
                blocker.commit();
            }
            refresh.get(3,TimeUnit.SECONDS);
        }
        makeDue(first);
        assertThat(drain(first,neighbor,before)).isEqualTo(2);
        assertThat(snapshot(neighbor)).isEqualTo(before);
    }

    /** CI115：维护锁让首轮返回合法BUSY，释放后原有清理循环仍须验证所有行和邻居。 */
    @Test
    void drainResumesAfterObservedMaintenanceLockWithoutCountingBusyAsDeletion() throws Exception {
        Fixture first = fixture(false, 20);
        Fixture neighbor = fixture(true, 20);
        points(first, 1, false);
        points(neighbor, 1, false);
        refresh(first);
        List<String> before = snapshot(neighbor);
        assertThat(batches.execute(historyClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        AtomicInteger busy = new AtomicInteger();
        try (Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.createStatement()) {
                // 与维护互斥的真实锁，不猜测CI当时持锁者，也不改生产NOWAIT或关闭维护。
                lock.execute("LOCK TABLE public.ts_property_point_1m_internal IN SHARE UPDATE EXCLUSIVE MODE");
            }
            batches = batch(claim -> {
                ProjectCleanupBatchResult result = history(claim);
                if ("TELEMETRY_HISTORY_BUSY".equals(result.blockedReason())) {
                    busy.incrementAndGet();
                    try {
                        blocker.commit();
                    } catch (SQLException failure) {
                        throw new IllegalStateException("释放受控维护锁失败", failure);
                    }
                }
                return result;
            });
            assertThat(drain(first, neighbor, before)).isEqualTo(3);
        }
        assertThat(busy.get()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(4);
        assertThat(owner.queryForObject("SELECT cleanup_batches FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(4);
    }

    /** CI115：合法锁忙不能被当作完成，持续持锁仍在原30轮预算内失败且数据不变。 */
    @Test
    void drainFailsWithinOriginalBudgetWhenMaintenanceLockNeverReleases() throws Exception {
        Fixture first = fixture(false, 20);
        Fixture neighbor = fixture(true, 20);
        points(first, 1, false);
        refresh(first);
        assertThat(batches.execute(historyClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        List<String> remaining = snapshot(first);
        try (Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.createStatement()) {
                lock.execute("LOCK TABLE public.ts_property_point_1m_internal IN SHARE UPDATE EXCLUSIVE MODE");
            }
            assertThatThrownBy(() -> drain(first, neighbor, snapshot(neighbor)))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("历史清理未在预期批次收束");
            assertThat(snapshot(first)).isEqualTo(remaining);
            blocker.rollback();
        }
        assertThat(drain(first, neighbor, snapshot(neighbor))).isEqualTo(3);
    }

    /** @param fixture 独立连接执行真正的维护CALL，五秒截止防止用例异常留下无限阻塞线程 */
    private void refreshMinute(Fixture fixture) {
        try (Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             PreparedStatement refresh=connection.prepareStatement("CALL public.refresh_continuous_aggregate('public.ts_property_point_1m_internal',?::timestamptz,?::timestamptz,true)")) {
            refresh.setQueryTimeout(5);
            refresh.setTimestamp(1,Timestamp.from(fixture.start()));
            refresh.setTimestamp(2,Timestamp.from(fixture.start().plusSeconds(86400)));
            refresh.execute();
        } catch (SQLException failure) {
            throw new IllegalStateException("真实刷新失败",failure);
        }
    }

    /** 必须观察到真实CALL数据库锁等待，再释放受控提交屏障。 */
    private void awaitRefreshLock() {
        awaitSqlLock("CALL public.refresh_continuous_aggregate%");
    }

    /** @param queryPrefix 仅匹配本类实际阻塞SQL，排除无关维护进程 */
    private void awaitSqlLock(String queryPrefix) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,queryPrefix))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }
        throw new AssertionError("未观察到真实SQL锁等待："+queryPrefix);
    }

    /** @param latch 受控短屏障；不以长sleep替代数据库锁证据 */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3,TimeUnit.SECONDS)) throw new AssertionError("历史清理提交屏障超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    /** @param claim 真实项目授权 @return 历史函数结果，完成仅转换为测试适配器的剩余域等待 */
    private ProjectCleanupBatchResult history(ProjectCleanupClaim claim) {
        ProjectCleanupBatchResult result = app.queryForObject("SELECT * FROM public.telemetry_project_history_cleanup_batch(?,?,?,?)",
                (row, number) -> new ProjectCleanupBatchResult(row.getInt("deleted_rows"),row.getBoolean("complete"),row.getString("blocked_reason")),
                claim.tenantId(), claim.projectId(), claim.generation(), claim.leaseToken());
        return result.complete() ? ProjectCleanupBatchResult.blocked("TELEMETRY_REMAINING") : result;
    }

    /** @return 五个历史真实空域和一个看板空域之后的TELEMETRY领取 */
    private ProjectCleanupClaim historyClaim() {
        for (int i = 0; i < ProjectCleanupStage.TELEMETRY.ordinal(); i++) assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("TELEMETRY");
        return claim;
    }

    /** @return 数据库领取的下一批次 */
    private ProjectCleanupBatchResult next() {
        return batches.execute(admission.claimNext().orElseThrow()).orElseThrow();
    }

    /** @param first 被清项目 @param neighbor 邻居 @param before 原快照 @return 剩余直接DELETE数 */
    private int drain(Fixture first, Fixture neighbor, List<String> before) {
        int deleted=0;
        for (int i=0;i<30;i++) {
            List<String> remaining = snapshot(first);
            Map<String, Object> progress = owner.queryForMap(
                    "SELECT cleanup_rows,cleanup_batches FROM public.sys_project WHERE id=?", first.project());
            ProjectCleanupBatchResult result=next();
            assertThat(result.deletedRows()).isBetween(0,500);
            assertThat(snapshot(neighbor)).isEqualTo(before);
            if ("TELEMETRY_REMAINING".equals(result.blockedReason())) return deleted;
            if ("TELEMETRY_HISTORY_BUSY".equals(result.blockedReason())) {
                // ADR0083允许维护先持锁；只接受零副作用的合法等待，不把忙碌计为成功删除。
                assertThat(result.deletedRows()).isZero();
                assertThat(result.complete()).isFalse();
                assertThat(snapshot(first)).isEqualTo(remaining);
                assertThat(owner.queryForMap("SELECT cleanup_rows,cleanup_batches FROM public.sys_project WHERE id=?",
                        first.project())).isEqualTo(progress);
                assertThat(owner.queryForObject("SELECT cleanup_stage='TELEMETRY' AND cleanup_failure_code='TELEMETRY_HISTORY_BUSY' "
                        + "AND cleanup_lease_token IS NULL AND cleanup_next_attempt_at>clock_timestamp() "
                        + "FROM public.sys_project WHERE id=?", Boolean.class, first.project())).isTrue();
                // 仅推进本例夹具的下次领取时刻，生产30秒退避不变；仍共享原30轮上限，持续锁忙必须失败。
                makeDue(first);
                continue;
            }
            assertThat(result.blockedReason()).isNull();
            deleted+=result.deletedRows();
        }
        throw new AssertionError("历史清理未在预期批次收束："+first.project());
    }

    /** @param operation 真实SQL端口或故障包装 @return 保持原批次五秒事务的测试适配器 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim,ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定历史子域所属阶段。 */
            @Override
            public ProjectCleanupStage stage() { return ProjectCleanupStage.TELEMETRY; }
            /** 真实SQL与项目进度共用原事务，不能额外提交。 */
            @Override
            public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** @param active 邻居保持可用 @param days 历史年龄 @return 持久项目和数据库日界 */
    private Fixture fixture(boolean active,int days) {
        Fixture fixture = new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),owner.queryForObject(
                "SELECT date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'-?*interval '1 day'",Timestamp.class,days).toInstant());
        owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'历史清理租户')",fixture.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) "
                + "VALUES (?,?,'历史项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                fixture.project(),fixture.tenant(),"hist_"+fixture.project().toString().replace("-",""),active?"ACTIVE":"DELETING",active?0:1,active);
        return fixture;
    }

    /** @param fixture 项目 @param count 实际原始点数 @param distinctDevices true保证三级聚合都有相同基数 */
    private void points(Fixture fixture,int count,boolean distinctDevices) {
        owner.update("INSERT INTO public.ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double) "
                + "SELECT ?,CASE WHEN ? THEN gen_random_uuid() ELSE ?::uuid END,'temperature',?::timestamptz+n*interval '1 millisecond',gen_random_uuid(),42 "
                + "FROM generate_series(1,?) n",fixture.project(),distinctDevices,fixture.device(),Timestamp.from(fixture.start().plusSeconds(60)),count);
    }

    /** @param fixture 项目 @param leased 是否保持有效原维护租约 */
    private void backfill(Fixture fixture,boolean leased) {
        owner.update("INSERT INTO public.ts_property_aggregate_backfill(tenant_id,project_id,window_start,window_end,lease_until) "
                + "VALUES (?,?,?, ?,CASE WHEN ? THEN now()+interval '5 minutes' ELSE NULL END)",fixture.tenant(),fixture.project(),
                Timestamp.from(fixture.start()),Timestamp.from(fixture.start().plusSeconds(86400)),leased);
    }

    /** @param fixture 完整raw准备后实际三层刷新，也用于清后无复活验证 */
    private void refresh(Fixture fixture) {
        for (String aggregate:AGGREGATES) owner.update("CALL public.refresh_continuous_aggregate(?::regclass,?::timestamptz,?::timestamptz,true)",
                "public."+aggregate,Timestamp.from(fixture.start()),Timestamp.from(fixture.start().plusSeconds(86400)));
    }

    /** 真实压缩共享原始chunk，不以伪装压缩标志替代数据面实验。 */
    private void compress() {
        owner.queryForList("SELECT compress_chunk(c,if_not_compressed=>true) FROM show_chunks('public.ts_property_point_internal') c");
    }

    /** @param first 项目A @param neighbor 项目B @return 同时覆盖二者时间且压缩的chunk数 */
    private long sharedCompressedChunks(Fixture first,Fixture neighbor) {
        assertThat(first.start()).isEqualTo(neighbor.start());
        return owner.queryForObject("SELECT count(*) FROM timescaledb_information.chunks WHERE hypertable_name='ts_property_point_internal' "
                + "AND range_start<=? AND range_end>? AND is_compressed",Long.class,Timestamp.from(first.start().plusSeconds(60)),Timestamp.from(neighbor.start().plusSeconds(60)));
    }

    /** @param fixture 项目 @return 原始点数 */
    private long rawCount(Fixture fixture) {
        return owner.queryForObject("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id=?",Long.class,fixture.project());
    }

    /** @param fixture 项目 @return raw与三级聚合所有字段快照 */
    private List<String> snapshot(Fixture fixture) {
        List<String> result=new ArrayList<>();
        result.add(owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY ts,device_id,property_key,message_id),'[]'::jsonb)::text "
                + "FROM public.ts_property_point_internal p WHERE project_id=?",String.class,fixture.project()));
        for (String aggregate:AGGREGATES) result.add(owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY bucket,device_id,property_key),'[]'::jsonb)::text "
                + "FROM public."+aggregate+" p WHERE project_id=?",String.class,fixture.project()));
        return result;
    }

    /** @param fixture 控制测试退避到期，不能改变清理代次或租约身份 */
    private void makeDue(Fixture fixture) {
        owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",fixture.project());
    }

    /** @param target 原事务服务 @return 实际Spring注解代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    /** @param target 固定升级截止 @return 完整工作区迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink")
                .locations("classpath:db/migration/support","classpath:db/migration/project","classpath:db/migration/iam",
                        "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
                        "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/enduser","classpath:db/migration/export")
                .placeholders(Map.of("app_role_password","thingslink")).target(target).load();
    }

    /** 明确业务身份和原始时间，raw历史不需要伪造当前设备或物模型。 */
    private record Fixture(UUID tenant,UUID project,UUID device,Instant start) { }
}
