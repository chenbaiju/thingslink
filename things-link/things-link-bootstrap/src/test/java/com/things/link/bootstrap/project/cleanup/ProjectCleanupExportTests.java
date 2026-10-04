package com.things.link.bootstrap.project.cleanup;

import com.things.link.bootstrap.fixture.ProjectExportMinioFixture;

import com.things.link.export.application.ProjectExportCleanupWorker;
import com.things.link.export.application.ProjectExportPurgeContributor;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportJobRepository;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportPurgeRepository;
import com.things.link.project.application.ProjectCleanupAdmissionService;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupBatchService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.project.infrastructure.persistence.JdbcProjectCleanupRepository;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.storage.MinioPrivateObjectStorage;
import com.things.link.support.storage.PrivateObjectStorage;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0077：独占真实PG与真实MinIO证明清理贡献、对象前置及批次进度，不启动自动调度。 */
@Testcontainers
class ProjectCleanupExportTests {

    /** 与部署资格相同的PG/Timescale版本，隔离全局导出与项目领取。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_cleanup_export").withUsername("thingslink").withPassword("thingslink");
    /** owner只用于目录观察及精确时钟夹具。 */
    private static JdbcTemplate owner;
    /** 真实受限APP角色，领域必须在原事务配置RLS后才能看到行。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 与所有生产代理共享的数据源事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** 真实项目围栏与进度仓储。 */
    private JdbcProjectCleanupRepository projects;
    /** 首次准入和审计。 */
    private ProjectCleanupAdmissionService admission;
    /** 真实导出贡献器。 */
    private ProjectCleanupContributor exports;
    /** 五秒批次服务代理。 */
    private ProjectCleanupBatchService batches;
    /** 既有SECURITY DEFINER对象清理仓储。 */
    private JdbcProjectExportJobRepository jobs;
    /** 真实对象上传使用的临时文件，JUnit负责移除。 */
    @TempDir
    private Path temporaryDirectory;

    /** 升级前索引不存在，0290独立迁移可执行且重复运行无副作用。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260904.0280").migrate();
        assertThat(flyway("20260904.0290").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260904.0290").migrate().migrationsExecuted).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname LIKE '%purge_scope_idx'", Long.class))
                .isEqualTo(2);
    }

    /** 每例只清本类独占库；保留只增审计并避免全局候选交叉领取。 */
    @BeforeEach
    void setup() {
        owner.update("DELETE FROM sys_project_export_upload_cleanup");
        owner.update("DELETE FROM sys_project_export_job");
        owner.update("DELETE FROM sys_project");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(dataSource);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions = new DataSourceTransactionManager(dataSource);
        projects = new JdbcProjectCleanupRepository(app);
        admission = proxy(new ProjectCleanupAdmissionService(projects, new AuditLogService(app, new ObjectMapper()), app));
        exports = proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app)));
        batches = batch(exports);
        jobs = proxy(new JdbcProjectExportJobRepository(app));
        assertThat(app.queryForObject("SELECT count(*) FROM sys_project_export_job", Long.class)).isZero();
    }

    /** 非终态任务不能因项目已PURGING而被删除，生成的失败收束仍由既有worker负责。 */
    @Test
    void waitsForQueuedAndRunningJobs() {
        Fixture f = fixture();
        UUID job = job(f, "QUEUED");
        ProjectCleanupBatchResult first = execute();
        assertThat(first.blockedReason()).isEqualTo("EXPORT_NOT_TERMINAL");
        owner.update("UPDATE sys_project_export_job SET status='RUNNING' WHERE id=?", job);
        due(f);
        assertThat(execute().blockedReason()).isEqualTo("EXPORT_NOT_TERMINAL");
        assertThat(rows("sys_project_export_job", f)).isEqualTo(1);
        assertThat(stage(f)).isEqualTo("WAIT_EXPORT");
    }

    /** 真对象到期仍受签名宽限保护，宽限结束后才由既有worker删除并允许清元数据。 */
    @Test
    void retainsSuccessAndSignatureGraceUntilRealObjectDeletion() throws Exception {
        Fixture f = fixture();
        UUID job = job(f, "SUCCEEDED");
        String prefix = "projects/cleanup-test/" + UUID.randomUUID() + "/";
        String key = prefix + "adopted.zip";
        String neighbour = prefix + "other-project.zip";
        PrivateObjectStorage storage = storage();
        try {
            upload(storage, key);
            upload(storage, neighbour);
            UUID upload = cleanup(f, job, key, "ADOPTED", 1);
            owner.update("UPDATE sys_project_export_job SET current_upload_id=?,object_key=? WHERE id=?", upload, key, job);
            owner.update("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()+interval '5 minutes' WHERE upload_id=?", upload);
            assertThat(execute().blockedReason()).isEqualTo("EXPORT_NOT_TERMINAL");
            assertThat(jobs.claimExpired()).isEmpty();
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix)).containsExactlyInAnyOrder(key, neighbour);
            owner.update("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE upload_id=?", upload);
            ProjectExportCleanupWorker worker = new ProjectExportCleanupWorker(jobs, storage);
            worker.cleanupExpired(jobs.claimExpired().orElseThrow());
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix)).containsExactly(neighbour);
            due(f);
            assertThat(execute().deletedRows()).isEqualTo(1);
            assertThat(execute().deletedRows()).isEqualTo(1);
            assertThat(execute().complete()).isTrue();
            assertThat(stage(f)).isEqualTo("TASK");
            assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=?", Long.class, f.project()))
                    .isEqualTo(1);
        } finally {
            ProjectExportMinioFixture.deletePrefix(prefix);
        }
    }

    /** 首次DELETED后真实迟到对象仍必须有身份可再次删除，不能只看数据库的旧成功标记。 */
    @Test
    void rechecksLateUploadAfterAttemptBudgetBeforeDeletingItsIdentity() throws Exception {
        Fixture f = fixture();
        UUID job = job(f, "FAILED");
        String prefix = "projects/cleanup-late/" + UUID.randomUUID() + "/";
        String key = prefix + "late.zip";
        PrivateObjectStorage storage = storage();
        try {
            UUID upload = cleanup(f, job, key, "DELETED", 1);
            owner.update("""
                    UPDATE sys_project_export_upload_cleanup SET created_at=clock_timestamp()-interval '10 minutes',
                        deleted_at=clock_timestamp()-interval '9 minutes' WHERE upload_id=?
                    """, upload);
            upload(storage, key);
            assertThat(execute().blockedReason()).isEqualTo("EXPORT_UPLOAD_QUIESCENCE");
            assertThat(jobs.claimCleanup()).isEmpty();
            assertThat(rows("sys_project_export_upload_cleanup", f)).isEqualTo(1);
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix)).containsExactly(key);
            // 精确推进持久时钟夹具越过预算，不以十五分钟真实sleep替代边界验收。
            owner.update("""
                    UPDATE sys_project_export_upload_cleanup SET created_at=clock_timestamp()-interval '16 minutes',
                        next_attempt_at=clock_timestamp()-interval '1 second' WHERE upload_id=?
                    """, upload);
            new ProjectExportCleanupWorker(jobs, storage).cleanup(jobs.claimCleanup().orElseThrow());
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix)).isEmpty();
            due(f);
            assertThat(execute().deletedRows()).isEqualTo(1);
            assertThat(execute().deletedRows()).isEqualTo(1);
            assertThat(execute().complete()).isTrue();
        } finally {
            ProjectExportMinioFixture.deletePrefix(prefix);
        }
    }

    /** 1001个子行需要三片，最后独立清父行；其他项目与只增审计保留。 */
    @Test
    void deletesAtMostFiveHundredRowsAndResumesWithoutTouchingAnotherProject() {
        Fixture f = fixture();
        UUID job = job(f, "FAILED");
        cleanup(f, job, "bounded/" + UUID.randomUUID(), "DELETED", 1001);
        Fixture other = fixture();
        owner.update("UPDATE sys_project SET deleted_at=clock_timestamp() WHERE id=?", other.project());
        cleanup(other, job(other, "FAILED"), "other/" + UUID.randomUUID(), "DELETED", 2);
        assertThat(execute().deletedRows()).isEqualTo(500);
        batches = batch(exports);
        assertThat(execute().deletedRows()).isEqualTo(500);
        assertThat(execute().deletedRows()).isEqualTo(1);
        assertThat(execute().deletedRows()).isEqualTo(1);
        assertThat(execute().complete()).isTrue();
        assertThat(rows("sys_project_export_upload_cleanup", other)).isEqualTo(2);
        assertThat(rows("sys_project_export_job", other)).isEqualTo(1);
        assertThat(owner.queryForMap("SELECT cleanup_rows,cleanup_batches FROM sys_project WHERE id=?", f.project()))
                .containsEntry("cleanup_rows", 1002L).containsEntry("cleanup_batches", 4L);
    }

    /** 删除后抛异常必须回滚数据库事实和进度，同一未过期领取仍可重试。 */
    @Test
    void rollsBackDomainFailureAndRetryUsesTheSameClaim() {
        Fixture f = fixture();
        cleanup(f, job(f, "FAILED"), "rollback/" + UUID.randomUUID(), "DELETED", 3);
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        ProjectCleanupBatchService failing = batch(contributor(c -> {
            exports.clean(c);
            throw new IllegalStateException("injected after delete");
        }));
        assertThatThrownBy(() -> failing.execute(claim)).hasMessage("injected after delete");
        assertThat(rows("sys_project_export_upload_cleanup", f)).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(3);
    }

    /** 提交前已过期的租约不能留下领域删除或计数，即使开始时围栏有效。 */
    @Test
    void rollsBackDeletionWhenLeaseExpiresBeforeProgressCommit() {
        Fixture f = fixture();
        cleanup(f, job(f, "FAILED"), "expiry/" + UUID.randomUUID(), "DELETED", 1);
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        ProjectCleanupBatchService expiring = batch(contributor(c -> {
            ProjectCleanupBatchResult result = exports.clean(c);
            app.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", c.projectId());
            return result;
        }));
        assertThatThrownBy(() -> expiring.execute(claim)).hasMessageContaining("提交前租约失效");
        assertThat(rows("sys_project_export_upload_cleanup", f)).isEqualTo(1);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
    }

    /** 五秒事务预算必须取消真实慢SQL并回滚此前删除，不能只检查注解字面值。 */
    @Test
    void cancelsSlowDatabaseWorkWithinTheBatchBudget() {
        Fixture f = fixture();
        cleanup(f, job(f, "FAILED"), "timeout/" + UUID.randomUUID(), "DELETED", 1);
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        ProjectCleanupBatchService slow = batch(contributor(c -> {
            ProjectCleanupBatchResult result = exports.clean(c);
            app.execute("SELECT pg_sleep(6)");
            return result;
        }));
        assertThatThrownBy(() -> slow.execute(claim))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(rows("sys_project_export_upload_cleanup", f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
    }

    /** 错误身份不能进入贡献器，缺失或重复步骤不能当成空域，超限结果导致原删除回滚。 */
    @Test
    void rejectsForgedClaimsMissingDuplicateAndOversizedContributions() {
        Fixture f = fixture();
        cleanup(f, job(f, "FAILED"), "contract/" + UUID.randomUUID(), "DELETED", 1);
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        AtomicInteger calls = new AtomicInteger();
        ProjectCleanupBatchService observed = batch(contributor(c -> {
            calls.incrementAndGet();
            return exports.clean(c);
        }));
        ProjectCleanupClaim forged = new ProjectCleanupClaim(UUID.randomUUID(), claim.projectId(), claim.generation(),
                claim.stage(), claim.leaseToken(), claim.leaseUntil(), false);
        assertThat(observed.execute(forged)).isEmpty();
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, List.of())).execute(claim))
                .hasMessageContaining("尚未接齐");
        assertThatThrownBy(() -> new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, List.of(exports, exports)))
                .hasMessageContaining("重复装配");
        ProjectCleanupBatchService oversized = batch(contributor(c -> {
            exports.clean(c);
            return ProjectCleanupBatchResult.deleted(501);
        }));
        assertThatThrownBy(() -> oversized.execute(claim)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows("sys_project_export_upload_cleanup", f)).isEqualTo(1);
    }

    /** 独立事务批次不能污染调用方已经建立的另一项目RLS连接。 */
    @Test
    void preservesOuterTransactionScopeAcrossIndependentBatch() {
        Fixture f = fixture();
        cleanup(f, job(f, "FAILED"), "scope/" + UUID.randomUUID(), "DELETED", 1);
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        String original = UUID.randomUUID().toString();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            app.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class, original);
            assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
            assertThat(app.queryForObject("SELECT current_setting('app.project_id')", String.class)).isEqualTo(original);
        });
        assertThat(app.queryForObject("SELECT count(*) FROM sys_project_export_job", Long.class)).isZero();
    }

    /** @return 用真实APP角色领取并提交的一轮 */
    private ProjectCleanupBatchResult execute() {
        return batches.execute(admission.claimNext().orElseThrow()).orElseThrow();
    }

    /** @param f 当前项目；仅推进本类夹具退避，不修改生产时钟源 */
    private void due(Fixture f) {
        owner.update("UPDATE sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", f.project());
    }

    /** @return 已超窗口的独立项目，不依赖请求成员身份 */
    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'清理租户')", tenant);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','清理账号')", account, account + "@test.example");
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at)
                VALUES (?,?,'清理项目',?,'DELETING',1,clock_timestamp()-interval '31 days')
                """, project, tenant, "cleanup_" + project.toString().replace("-", ""));
        return new Fixture(tenant, project, account);
    }

    /** @param f 项目 @param status 任务状态 @return 含完整成功字段的真实历史任务 */
    private UUID job(Fixture f, String status) {
        UUID id = UUID.randomUUID();
        owner.update("""
                INSERT INTO sys_project_export_job(id,tenant_id,project_id,project_generation,requester_account_id,status,
                    snapshot_at,current_upload_id,object_key,object_size,object_sha256,succeeded_at,expires_at)
                VALUES (?,?,?,1,?,?,clock_timestamp()-interval '25 hours',gen_random_uuid(),'fixture',1,?,
                    clock_timestamp()-interval '25 hours',clock_timestamp()-interval '1 second')
                """, id, f.tenant(), f.project(), f.account(), status, "a".repeat(64));
        return id;
    }

    /** @param f 项目 @param job 父任务 @param key 对象前缀 @param status 清理状态 @param count 行数 @return 第一行upload身份 */
    private UUID cleanup(Fixture f, UUID job, String key, String status, int count) {
        List<UUID> uploads = owner.query("""
                INSERT INTO sys_project_export_upload_cleanup(id,export_id,tenant_id,project_id,upload_id,object_key,status,
                    created_at,deleted_at,next_attempt_at)
                SELECT gen_random_uuid(),?,?,?,gen_random_uuid(),CASE WHEN ?=1 THEN ? ELSE ? || '/' || n END,?,
                    clock_timestamp()-interval '25 hours', CASE WHEN ?='DELETED' THEN clock_timestamp() ELSE NULL END,
                    clock_timestamp()-interval '1 second' FROM generate_series(1,?) n RETURNING upload_id
                """, (rs, row) -> rs.getObject(1, UUID.class), job, f.tenant(), f.project(), count, key, key, status, status, count);
        return uploads.getFirst();
    }

    /** @param table 本类常量表名 @param f 项目 @return owner观测的实际剩余行数 */
    private long rows(String table, Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND project_id=?", Long.class, f.tenant(), f.project());
    }

    /** @param f 项目 @return 持久阶段 */
    private String stage(Fixture f) {
        return owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?", String.class, f.project());
    }

    /** @param contributor 故障或真实贡献器 @return 五秒事务代理 */
    private ProjectCleanupBatchService batch(ProjectCleanupContributor contributor) {
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, List.of(contributor)));
    }

    /** @param operation 可控故障点 @return 保留真实原事务的测试贡献器 */
    private ProjectCleanupContributor contributor(Function<ProjectCleanupClaim, ProjectCleanupBatchResult> operation) {
        return new ProjectCleanupContributor() {
            /** 故障集中在导出清理阶段。 */
            @Override
            public ProjectCleanupStage stage() { return ProjectCleanupStage.WAIT_EXPORT; }
            /** 原事务调用注入的故障操作。 */
            @Override
            public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        };
    }

    /** @param target 注解服务 @return 使用生产Spring事务语义的代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** @param target 迁移终点 @return 不受未来版本数量影响的迁移器 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam", "classpath:db/migration/export")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** @return 与既有MinIO验收一致的真实对象适配器 */
    private PrivateObjectStorage storage() {
        return new MinioPrivateObjectStorage(ProjectExportMinioFixture.client(), ProjectExportMinioFixture.client());
    }

    /** @param storage 真实存储 @param key 独占测试前缀下的对象键 */
    private void upload(PrivateObjectStorage storage, String key) throws Exception {
        Path file = temporaryDirectory.resolve(UUID.randomUUID() + ".zip");
        Files.write(file, new byte[]{1, 2, 3});
        storage.upload("export", key, file, "application/zip", Map.of());
    }

    /** @param tenant 项目计费归属 @param project 项目 @param account 导出责任账号 */
    private record Fixture(UUID tenant, UUID project, UUID account) {
    }
}
