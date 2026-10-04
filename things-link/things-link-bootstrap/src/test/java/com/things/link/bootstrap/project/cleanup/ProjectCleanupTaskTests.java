package com.things.link.bootstrap.project.cleanup;

import com.things.link.export.application.ProjectExportPurgeContributor;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportPurgeRepository;
import com.things.link.project.application.ProjectCleanupAdmissionService;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupBatchService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.infrastructure.persistence.JdbcProjectCleanupRepository;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.support.audit.AuditLogService;
import com.things.link.task.application.TaskProjectCleanupContributor;
import com.things.link.task.infrastructure.persistence.JdbcTaskProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12-P0-5h3：真实受限PG验证任务有界清理、RLS隐藏子引用和原任务锁序竞争。 */
@Testcontainers
class ProjectCleanupTaskTests {

    /** 独占数据库，避免全局项目领取和并发锁观测混入其他测试。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_cleanup_task").withUsername("thingslink").withPassword("thingslink");
    /** owner只负责异常历史夹具与锁观测。 */
    private static JdbcTemplate owner;
    /** 实际业务查询使用RLS受限APP。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 所有贡献与项目进度的原事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** 已验收的项目围栏仓储。 */
    private JdbcProjectCleanupRepository projects;
    /** 项目准入、完整身份和原子审计。 */
    private ProjectCleanupAdmissionService admission;
    /** 本片任务贡献器。 */
    private ProjectCleanupContributor tasks;
    /** 前置导出必须真实确认空域，不手改项目阶段跳过。 */
    private ProjectCleanupContributor exports;
    /** 两阶段真实批次代理，RULE尚未交付不会被自动跳过。 */
    private ProjectCleanupBatchService batches;

    /** 旧库升级只添加四个范围索引及只读复核函数，不改旧级联权限。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260904.0290").migrate();
        assertThat(flyway("20260905.0100").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0100").migrate().migrationsExecuted).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname LIKE 'task_%purge_scope_idx'", Long.class))
                .isEqualTo(4);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_proc p, LATERAL aclexplode(p.proacl) a
                 WHERE p.oid='task_cleanup_targets_remain(uuid[])'::regprocedure AND a.grantee=0
                """, Long.class)).isZero();
    }

    /** 只移除本类隔离库夹具；审计保留，不依赖父级联清测试现场。 */
    @BeforeEach
    void setup() {
        for (String table : List.of("task_target", "task_execution", "task_schedule", "task_job", "sys_project")) {
            owner.update("DELETE FROM " + table);
        }
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions = new DataSourceTransactionManager(source);
        projects = new JdbcProjectCleanupRepository(app);
        admission = proxy(new ProjectCleanupAdmissionService(projects, new AuditLogService(app, new ObjectMapper()), app));
        tasks = proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app)));
        exports = proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app)));
        batches = batch(tasks);
    }

    /** 1001目标必须跨三批，执行/调度/定义各自提交，其他项目的每张表均保留。 */
    @Test
    void cleansTargetsBeforeParentsInBoundedResumableBatches() {
        Fixture f = fixture(false);
        targets(f, f.execution(), 1001);
        Fixture other = fixture(true);
        targets(other, other.execution(), 3);
        ProjectCleanupClaim claim = taskClaim();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(500);
        batches = batch(tasks);
        assertThat(next().deletedRows()).isEqualTo(500);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(next().complete()).isTrue();
        assertThat(owner.queryForMap("SELECT cleanup_stage,cleanup_rows,cleanup_batches FROM sys_project WHERE id=?", f.project()))
                .containsEntry("cleanup_stage", "RULE").containsEntry("cleanup_rows", 1004L).containsEntry("cleanup_batches", 6L);
        for (String table : List.of("task_target", "task_execution", "task_schedule", "task_job")) {
            assertThat(rows(table, f)).isZero();
            assertThat(rows(table, other)).isEqualTo(table.equals("task_target") ? 3L : 1L);
        }
    }

    /** 单列外键允许的异常跨项目子行必须阻塞父删除，不能被RLS隐藏后由CASCADE清除。 */
    @Test
    void rejectsHiddenCrossProjectTargetsWithoutGuessingTheirOwnership() {
        Fixture f = fixture(false);
        Fixture other = fixture(true);
        targets(other, f.execution(), 1);
        ProjectCleanupBatchResult result = batches.execute(taskClaim()).orElseThrow();
        assertThat(result.blockedReason()).isEqualTo("TASK_TARGET_REMAINS");
        assertThat(rows("task_execution", f)).isEqualTo(1);
        assertThat(rows("task_target", other)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
        assertThatThrownBy(() -> app.queryForObject("SELECT task_cleanup_targets_remain('{}'::uuid[])", Boolean.class))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    /** 子插入先获得FK锁时，清理等待后必须看到其刚提交的隐藏子行。 */
    @Test
    void rechecksHiddenTargetsCommittedWhileWaitingForParentLock() throws Exception {
        Fixture f = fixture(false);
        Fixture other = fixture(true);
        ProjectCleanupClaim claim = taskClaim();
        try (var executor = Executors.newSingleThreadExecutor(); Connection writer = owner.getDataSource().getConnection()) {
            writer.setAutoCommit(false);
            insertTarget(writer, other, f.execution());
            var cleanup = executor.submit(() -> batches.execute(claim).orElseThrow());
            awaitLock("%SELECT id FROM public.task_execution%");
            writer.commit();
            assertThat(cleanup.get(5, TimeUnit.SECONDS).blockedReason()).isEqualTo("TASK_TARGET_REMAINS");
        }
        assertThat(rows("task_execution", f)).isEqualTo(1);
        assertThat(rows("task_target", other)).isEqualTo(1);
    }

    /** 清理先锁执行时，随后子插入必须等父删除提交后按FK拒绝，不得成为隐藏级联牺牲者。 */
    @Test
    void parentLockPreventsNewReferenceBetweenCheckAndDelete() throws Exception {
        Fixture f = fixture(false);
        Fixture other = fixture(true);
        ProjectCleanupClaim claim = taskClaim();
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>> insertion = new java.util.concurrent.atomic.AtomicReference<>();
            ProjectCleanupBatchService controlled = batch(contributor(c -> {
                app.queryForObject("SELECT id FROM task_execution WHERE id=? FOR UPDATE", UUID.class, f.execution());
                insertion.set(executor.submit(() -> {
                    try (Connection connection = owner.getDataSource().getConnection()) {
                        insertTarget(connection, other, f.execution());
                        return "INSERTED";
                    } catch (SQLException failure) {
                        return failure.getSQLState();
                    }
                }));
                awaitLock("%INSERT INTO task_target%");
                return tasks.clean(c);
            }));
            assertThat(controlled.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
            assertThat(insertion.get().get(5, TimeUnit.SECONDS)).isEqualTo("23503");
        }
        assertThat(rows("task_execution", f)).isZero();
        assertThat(rows("task_target", other)).isZero();
    }

    /** 原execution→ACTIVE SHARE锁序在已PURGING项目两种交错下都明确拒绝新工作且不与清理死锁。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coexistsWithFrozenTaskWriteGuardInBothLockOrders(boolean cleanupFirst) throws Exception {
        Fixture f = fixture(false);
        ProjectCleanupClaim claim = taskClaim();
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        JdbcTemplate workerJdbc = new JdbcTemplate(source);
        DataSourceTransactionManager workerTransactions = new DataSourceTransactionManager(source);
        ProjectLifecycleAccessService access = new ProjectLifecycleAccessService(new JdbcProjectRepository(workerJdbc));
        try (var executor = Executors.newFixedThreadPool(2)) {
            if (cleanupFirst) {
                ProjectCleanupBatchService controlled = batch(contributor(c -> {
                    var worker = executor.submit(() -> new TransactionTemplate(workerTransactions).execute(status -> {
                        workerScope(workerJdbc, f);
                        workerJdbc.queryForObject("SELECT id FROM task_execution WHERE id=? FOR UPDATE", UUID.class, f.execution());
                        return access.lockActiveForWrite(f.tenant(), f.project());
                    }));
                    try {
                        assertThat(worker.get(2, TimeUnit.SECONDS)).isFalse();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    return tasks.clean(c);
                }));
                assertThat(controlled.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
            } else {
                CountDownLatch parentLocked = new CountDownLatch(1);
                CountDownLatch checkGuard = new CountDownLatch(1);
                var worker = executor.submit(() -> new TransactionTemplate(workerTransactions).execute(status -> {
                    workerScope(workerJdbc, f);
                    workerJdbc.queryForObject("SELECT id FROM task_execution WHERE id=? FOR UPDATE", UUID.class, f.execution());
                    parentLocked.countDown();
                    await(checkGuard);
                    return access.lockActiveForWrite(f.tenant(), f.project());
                }));
                assertThat(parentLocked.await(3, TimeUnit.SECONDS)).isTrue();
                var cleanup = executor.submit(() -> batches.execute(claim).orElseThrow());
                try {
                    awaitLock("%SELECT id FROM public.task_execution%");
                } finally {
                    checkGuard.countDown();
                }
                assertThat(worker.get(3, TimeUnit.SECONDS)).isFalse();
                assertThat(cleanup.get(3, TimeUnit.SECONDS).deletedRows()).isEqualTo(1);
            }
        }
    }

    /** 任务贡献器删除后故障不能泄漏目标删除，重新执行同一claim即可恢复。 */
    @Test
    void rollsBackTaskDeletionAndProgressOnFailure() {
        Fixture f = fixture(false);
        targets(f, f.execution(), 2);
        ProjectCleanupClaim claim = taskClaim();
        ProjectCleanupBatchService failed = batch(contributor(c -> {
            tasks.clean(c);
            throw new IllegalStateException("injected task delete failure");
        }));
        assertThatThrownBy(() -> failed.execute(claim)).hasMessage("injected task delete failure");
        assertThat(rows("task_target", f)).isEqualTo(2);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(2);
    }

    /** 空域必须显式证明后前进，不能因清理贡献缺失而被当作空。 */
    @Test
    void advancesAnEmptyTaskDomainWithoutFabricatingDeletedRows() {
        Fixture f = fixture(false);
        owner.update("DELETE FROM task_execution WHERE id=?", f.execution());
        owner.update("DELETE FROM task_schedule WHERE job_id=?", f.job());
        owner.update("DELETE FROM task_job WHERE id=?", f.job());
        assertThat(batches.execute(taskClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
    }

    /** @return 经真实空导出贡献器推进后的TASK领取 */
    private ProjectCleanupClaim taskClaim() {
        assertThat(batches.execute(admission.claimNext().orElseThrow()).orElseThrow().complete()).isTrue();
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("TASK");
        return claim;
    }

    /** @return 下一真实领取的提交结果 */
    private ProjectCleanupBatchResult next() {
        return batches.execute(admission.claimNext().orElseThrow()).orElseThrow();
    }

    /** @param recent true使邻居不满足清理准入 @return 完整任务引用夹具 */
    private Fixture fixture(boolean recent) {
        UUID tenant = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'任务清理租户')", tenant);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','责任账号')", account, account + "@test.example");
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at)
                VALUES (?,?,'任务清理项目',?,'DELETING',1,clock_timestamp()-?::interval)
                """, project, tenant, "task_" + project.toString().replace("-", ""), recent ? "1 day" : "31 days");
        owner.update("""
                INSERT INTO task_job(id,tenant_id,project_id,name,status,target_type,command_key,input,created_by,created_at,updated_at)
                VALUES (?,?,?,'任务','ACTIVE','ALL_DEVICES','command','{}',?,now(),now())
                """, job, tenant, project, account);
        owner.update("""
                INSERT INTO task_schedule(id,tenant_id,project_id,job_id,schedule_type,run_at,timezone,created_at,updated_at)
                VALUES (gen_random_uuid(),?,?,?,'ONCE',now(),'UTC',now(),now())
                """, tenant, project, job);
        owner.update("""
                INSERT INTO task_execution(id,tenant_id,project_id,job_id,target_type,command_key,input,requested_by,
                    trigger_type,status,started_at,created_at,updated_at)
                VALUES (?,?,?,?,'ALL_DEVICES','command','{}',?,'MANUAL','EXPANDING',now(),now(),now())
                """, execution, tenant, project, job, account);
        return new Fixture(tenant, project, job, execution);
    }

    /** @param f 目标行归属 @param execution 实际外键父行 @param count 真实子行数量 */
    private void targets(Fixture f, UUID execution, int count) {
        owner.update("""
                INSERT INTO task_target(execution_id,tenant_id,project_id,device_id,status)
                SELECT ?,?,?,gen_random_uuid(),'PENDING' FROM generate_series(1,?)
                """, execution, f.tenant(), f.project(), count);
    }

    /** @param connection 受控独立事务 @param f 目标归属 @param execution 外键父行 */
    private void insertTarget(Connection connection, Fixture f, UUID execution) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("INSERT INTO task_target(execution_id,tenant_id,project_id,device_id,status) VALUES (?,?,?,?,'PENDING')")) {
            sql.setObject(1, execution);
            sql.setObject(2, f.tenant());
            sql.setObject(3, f.project());
            sql.setObject(4, UUID.randomUUID());
            sql.setQueryTimeout(4);
            sql.executeUpdate();
        }
    }

    /** @param jdbc 原任务线程连接 @param f 持久项目归属 */
    private void workerScope(JdbcTemplate jdbc, Fixture f) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, f.tenant().toString());
        jdbc.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, f.project().toString());
    }

    /** @param queryPattern 当前测试可识别的SQL，确认真实服务器锁等待后才推进竞争 */
    private void awaitLock(String queryPattern) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE ?)
                    """, Boolean.class, queryPattern))) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }
        throw new AssertionError("没有观察到真实锁等待：" + queryPattern);
    }

    /** @param latch 受控交错屏障，不能无限等待掩盖锁序死锁 */
    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    /** @param table 本类常量表名 @param f 项目 @return 真实剩余行 */
    private long rows(String table, Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND project_id=?", Long.class, f.tenant(), f.project());
    }

    /** @param taskContributor 真实或受控任务贡献 @return 固定前置及任务阶段批次 */
    private ProjectCleanupBatchService batch(ProjectCleanupContributor taskContributor) {
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, List.of(exports, taskContributor)));
    }

    /** @param operation 在原任务贡献前后设置受控交错或故障 @return 仍处原事务的TASK贡献 */
    private ProjectCleanupContributor contributor(Function<ProjectCleanupClaim, ProjectCleanupBatchResult> operation) {
        return new ProjectCleanupContributor() {
            /** 只接管当前任务阶段。 */
            @Override
            public ProjectCleanupStage stage() { return ProjectCleanupStage.TASK; }
            /** 不改变真实贡献器的事务边界。 */
            @Override
            public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        };
    }

    /** @param target 注解服务 @return 生产事务语义代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** @param target 明确的旧库或新迁移终点 @return 与未来迁移数量解耦的Flyway */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam",
                        "classpath:db/migration/export", "classpath:db/migration/task")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** @param tenant 持久计费归属 @param project 任务项目 @param job 任务定义 @param execution 执行父行 */
    private record Fixture(UUID tenant, UUID project, UUID job, UUID execution) {
    }
}
