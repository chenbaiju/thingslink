package com.things.link.bootstrap.project.cleanup;

import com.things.link.project.application.ProjectCleanupAdmissionService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportPurgeRepository;
import com.things.link.task.infrastructure.persistence.JdbcTaskProjectCleanupRepository;
import com.things.link.project.infrastructure.persistence.JdbcProjectCleanupRepository;
import com.things.link.support.audit.AuditLogService;
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
import org.springframework.transaction.TransactionDefinition;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0078：独占真实PG验证数据库许可与Java围栏一致，普通不可变表权限保持不变。 */
@Testcontainers
class ProjectCleanupDatabaseAuthorizationTests {

    /** 全域迁移提供真实规则版本及引用，不通过放宽表权限替代受限函数验收。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_cleanup_authorization").withUsername("thingslink").withPassword("thingslink");
    /** 精确旧库与受限探针只存在本类隔离库。 */
    private static JdbcTemplate owner;
    /** 当前应用角色的真实连接。 */
    private JdbcTemplate app;
    /** 与SQL端口一致的Java原事务。 */
    private DataSourceTransactionManager transactions;
    /** 已交付的Java围栏用于资格一致性对照。 */
    private ProjectCleanupAdmissionService admission;

    /** 新迁移只新增授权函数；探针证明固定阶段的SECURITY DEFINER用法，不交付生产规则删除。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260905.0100").migrate();
        assertThat(flyway("20260905.0200").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0200").migrate().migrationsExecuted).isZero();
        owner.execute("""
                CREATE FUNCTION public.rule_cleanup_guard_probe(t uuid,p uuid,g bigint,k uuid,v uuid) RETURNS integer
                LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
                DECLARE removed integer;
                BEGIN
                  IF NOT public.project_cleanup_authorized(t,p,g,'RULE',k) THEN RETURN 0; END IF;
                  DELETE FROM public.rule_version WHERE tenant_id=t AND project_id=p AND id=v;
                  GET DIAGNOSTICS removed=ROW_COUNT;
                  RETURN removed;
                END; $$
                """);
        owner.execute("REVOKE ALL ON FUNCTION public.rule_cleanup_guard_probe(uuid,uuid,bigint,uuid,uuid) FROM PUBLIC");
        owner.execute("GRANT EXECUTE ON FUNCTION public.rule_cleanup_guard_probe(uuid,uuid,bigint,uuid,uuid) TO thingslink_app");
        owner.execute("CREATE ROLE cleanup_unprivileged NOLOGIN");
    }

    /** 项目候选独占且不删除只增审计；普通规则版本DELETE仍由数据库拒绝。 */
    @BeforeEach
    void setup() {
        for (String table : List.of("task_target", "task_execution", "task_schedule", "task_job",
                "sys_project_export_upload_cleanup", "sys_project_export_job")) {
            owner.update("DELETE FROM public." + table);
        }
        owner.update("DELETE FROM rule_version");
        owner.update("DELETE FROM rule_message");
        owner.update("DELETE FROM sys_project");
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        ProxyFactory proxy = new ProxyFactory(new ProjectCleanupAdmissionService(new JdbcProjectCleanupRepository(app),
                new AuditLogService(app, new ObjectMapper()), app));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        admission = (ProjectCleanupAdmissionService) proxy.getProxy();
    }

    /** 正确SQL与Java入口同为true，错误四元归属/阶段/token及过期token同为false。 */
    @Test
    void agreesWithJavaFenceForCompleteAndForgedIdentities() {
        Fixture f = fixture();
        ProjectCleanupClaim c = f.claim();
        List<ProjectCleanupClaim> claims = List.of(c,
                altered(c, UUID.randomUUID(), c.projectId(), c.generation(), c.stage(), c.leaseToken()),
                altered(c, c.tenantId(), UUID.randomUUID(), c.generation(), c.stage(), c.leaseToken()),
                altered(c, c.tenantId(), c.projectId(), 99, c.stage(), c.leaseToken()),
                altered(c, c.tenantId(), c.projectId(), c.generation(), "TASK", c.leaseToken()),
                altered(c, c.tenantId(), c.projectId(), c.generation(), c.stage(), UUID.randomUUID()));
        for (ProjectCleanupClaim claim : claims) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                boolean sql = authorized(claim);
                assertThat(sql).isEqualTo(claim == c);
                assertThat(admission.lockCurrent(claim)).isEqualTo(sql);
            });
        }
        owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", c.projectId());
        ProjectCleanupClaim replacement = admission.claimNext().orElseThrow();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(authorized(c)).isFalse();
            assertThat(admission.lockCurrent(c)).isFalse();
            assertThat(authorized(replacement)).isTrue();
        });
    }

    /** APP直接DELETE仍被拒绝，受限探针必须具备当前RULE许可且不能删另一项目版本。 */
    @Test
    void retainsDirectDeleteBanAndLimitsPrivilegedDeleteToAuthorizedProject() {
        Fixture f = fixture();
        UUID neighbourTenant = UUID.randomUUID();
        UUID neighbourProject = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'邻居')", neighbourTenant);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'邻居',?)",
                neighbourProject, neighbourTenant, "neighbour_" + neighbourProject.toString().replace("-", ""));
        UUID otherVersion = version(neighbourTenant, neighbourProject, f.account());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            app.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, f.claim().projectId().toString());
            assertThat(app.queryForObject("SELECT count(*) FROM rule_version", Long.class)).isEqualTo(1);
        });
        assertThatThrownBy(() -> app.update("DELETE FROM rule_version WHERE id=?", f.version()))
                .hasRootCauseInstanceOf(SQLException.class);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(probe(f.claim(), otherVersion)).isZero();
            ProjectCleanupClaim forged = altered(f.claim(), f.claim().tenantId(), f.claim().projectId(),
                    f.claim().generation(), "RULE", UUID.randomUUID());
            assertThat(probe(forged, f.version())).isZero();
            assertThat(probe(f.claim(), f.version())).isEqualTo(1);
        });
        assertThat(versionExists(f.version())).isFalse();
        assertThat(versionExists(otherVersion)).isTrue();
        assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app','rule_version','DELETE')", Boolean.class)).isFalse();
    }

    /** 固定RULE阶段不能在真实WAIT_EXPORT期间被调用者绕过。 */
    @Test
    void refusesWrongPersistentStageAndNonPurgingProjects() {
        Fixture f = fixture();
        owner.update("UPDATE sys_project SET cleanup_stage='WAIT_EXPORT' WHERE id=?", f.claim().projectId());
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertThat(probe(f.claim(), f.version())).isZero());
        owner.update("""
                UPDATE sys_project SET status='PURGED',cleanup_stage='DONE',cleanup_completed_at=clock_timestamp(),
                    cleanup_next_attempt_at=NULL,cleanup_lease_token=NULL,cleanup_lease_until=NULL WHERE id=?
                """, f.claim().projectId());
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertThat(authorized(f.claim())).isFalse());
        UUID active = UUID.randomUUID();
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'正常项目',?)",
                active, f.claim().tenantId(), "active_" + active.toString().replace("-", ""));
        ProjectCleanupClaim wrong = altered(f.claim(), f.claim().tenantId(), active, 0, "RULE", f.claim().leaseToken());
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertThat(authorized(wrong)).isFalse());
        assertThat(versionExists(f.version())).isTrue();
    }

    /** 受限删除仍服从调用者原事务，外层失败恢复不可变版本而非留下半次授权操作。 */
    @Test
    void rollsBackPrivilegedWorkWithTheOriginalTransaction() {
        Fixture f = fixture();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(probe(f.claim(), f.version())).isEqualTo(1);
            throw new IllegalStateException("injected privileged cleanup rollback");
        })).hasMessage("injected privileged cleanup rollback");
        assertThat(versionExists(f.version())).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertThat(authorized(f.claim())).isTrue());
    }

    /** 只读与旧快照隔离不能借SECURITY DEFINER绕过原事务要求。 */
    @Test
    void rejectsReadOnlyAndOldSnapshotTransactions() {
        Fixture f = fixture();
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> authorized(f.claim())))
                .hasRootCauseInstanceOf(SQLException.class);
        for (int level : List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            TransactionTemplate isolated = new TransactionTemplate(transactions);
            isolated.setIsolationLevel(level);
            assertThatThrownBy(() -> isolated.executeWithoutResult(status -> authorized(f.claim())))
                    .hasRootCauseInstanceOf(SQLException.class);
        }
        assertThat(versionExists(f.version())).isTrue();
    }

    /** 锁等待期间租约已过期，函数必须使用锁后的实际事实，不能复用事务起点。 */
    @Test
    void checksLeaseAfterWaitingForProjectLock() throws Exception {
        Fixture f = fixture();
        try (var executor = Executors.newSingleThreadExecutor(); Connection blocker = owner.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement update = blocker.prepareStatement("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?")) {
                update.setObject(1, f.claim().projectId());
                update.executeUpdate();
            }
            var result = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> authorized(f.claim())));
            awaitLock("%project_cleanup_authorized%");
            blocker.commit();
            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    /** 函数返回后项目排他锁仍保护原事务，不能只在布尔检查语句期间短暂持有。 */
    @Test
    void holdsProjectLockUntilTheOriginalTransactionCommits() throws Exception {
        Fixture f = fixture();
        CountDownLatch permitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(authorized(f.claim())).isTrue();
                permitted.countDown();
                await(release);
            }));
            assertThat(permitted.await(3, TimeUnit.SECONDS)).isTrue();
            var contender = executor.submit(() -> owner.update("UPDATE sys_project SET cleanup_failure_code='PROBE' WHERE id=?", f.claim().projectId()));
            try {
                awaitLock("%UPDATE sys_project SET cleanup_failure_code%");
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    /** 无APP授权的普通角色不能执行端口，缺失身份也不能得到许可。 */
    @Test
    void deniesPublicExecutionAndMissingIdentity() throws Exception {
        Fixture f = fixture();
        try (Connection connection = owner.getDataSource().getConnection()) {
            connection.createStatement().execute("SET ROLE cleanup_unprivileged");
            try (PreparedStatement sql = connection.prepareStatement("SELECT public.project_cleanup_authorized(NULL,NULL,NULL,NULL,NULL)")) {
                assertThatThrownBy(sql::execute).isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            } finally {
                connection.createStatement().execute("RESET ROLE");
            }
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(app.queryForObject("SELECT public.project_cleanup_authorized(NULL,NULL,NULL,NULL,NULL)", Boolean.class)).isFalse();
            assertThat(authorized(f.claim())).isTrue();
        });
    }

    /** 同名临时项目表不能覆盖public权威事实，沿用项目既有临时schema隔离约束。 */
    @Test
    void ignoresTemporaryProjectTableShadowing() {
        Fixture f = fixture();
        UUID activeProject = UUID.randomUUID();
        UUID fakeToken = UUID.randomUUID();
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key) VALUES (?,?,'真实正常项目',?)",
                activeProject, f.claim().tenantId(), "real_" + activeProject.toString().replace("-", ""));
        ProjectCleanupClaim fake = altered(f.claim(), f.claim().tenantId(), activeProject, 0, "RULE", fakeToken);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            app.execute("CREATE TEMP TABLE sys_project (LIKE public.sys_project INCLUDING ALL) ON COMMIT DROP");
            app.update("""
                    INSERT INTO pg_temp.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at,
                        cleanup_stage,cleanup_started_at,cleanup_next_attempt_at,cleanup_lease_token,cleanup_lease_until)
                    VALUES (?,?,'伪造清理项目',?,'PURGING',0,clock_timestamp()-interval '31 days','RULE',
                        clock_timestamp(),clock_timestamp(),?,clock_timestamp()+interval '2 minutes')
                    """, activeProject, f.claim().tenantId(), "fake_" + activeProject.toString().replace("-", ""), fakeToken);
            assertThat(authorized(f.claim())).isTrue();
            assertThat(authorized(fake)).isFalse();
            assertThat(admission.lockCurrent(fake)).as("Java围栏不得接受临时表伪造的PURGING身份").isFalse();
            assertThat(admission.lockCurrent(f.claim())).isTrue();
        });
    }

    /** 空的同名TEMP领域表不能使真实仍有数据的领域被误判完成。 */
    @ParameterizedTest
    @ValueSource(strings = {"TASK", "WAIT_EXPORT"})
    void domainCleanupCannotMistakeEmptyTemporaryTablesForEmptyPersistentData(String stage) {
        Fixture f = fixture();
        ProjectCleanupClaim claim = altered(f.claim(), f.claim().tenantId(), f.claim().projectId(),
                f.claim().generation(), stage, f.claim().leaseToken());
        owner.update("UPDATE public.sys_project SET cleanup_stage=? WHERE id=?", stage, claim.projectId());
        if (stage.equals("TASK")) {
            owner.update("""
                    INSERT INTO public.task_job(id,tenant_id,project_id,name,status,target_type,command_key,input,created_by,created_at,updated_at)
                    VALUES (gen_random_uuid(),?,?,'真实任务','ACTIVE','ALL_DEVICES','command','{}',?,now(),now())
                    """, claim.tenantId(), claim.projectId(), f.account());
        } else {
            owner.update("""
                    INSERT INTO public.sys_project_export_job(id,tenant_id,project_id,project_generation,requester_account_id,status)
                    VALUES (gen_random_uuid(),?,?,?,?, 'FAILED')
                    """, claim.tenantId(), claim.projectId(), claim.generation(), f.account());
        }
        List<String> tables = stage.equals("TASK")
                ? List.of("task_target", "task_execution", "task_schedule", "task_job")
                : List.of("sys_project_export_upload_cleanup", "sys_project_export_job");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(admission.lockCurrent(claim)).isTrue();
            app.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, claim.tenantId().toString());
            app.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, claim.projectId().toString());
            for (String table : tables) {
                app.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + " INCLUDING ALL) ON COMMIT DROP");
            }
            var result = stage.equals("TASK") ? new JdbcTaskProjectCleanupRepository(app).clean(claim)
                    : new JdbcProjectExportPurgeRepository(app).clean(claim);
            assertThat(result.complete()).as("不能被空TEMP表冒充完整清理").isFalse();
            assertThat(result.deletedRows()).isEqualTo(1);
        });
    }

    /** @return 模拟已通过前两阶段的持久RULE现场，仅用于本片授权端口验收 */
    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'授权测试租户')", tenant);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','责任账号')", account, account + "@test.example");
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at)
                VALUES (?,?,'授权测试项目',?,'DELETING',1,clock_timestamp()-interval '31 days')
                """, project, tenant, "auth_" + project.toString().replace("-", ""));
        UUID version = version(tenant, project, account);
        ProjectCleanupClaim initial = admission.claimNext().orElseThrow();
        owner.update("UPDATE sys_project SET cleanup_stage='RULE' WHERE id=?", project);
        return new Fixture(altered(initial, tenant, project, initial.generation(), "RULE", initial.leaseToken()), version, account);
    }

    /** @param tenant 计费归属 @param project 项目 @param account 责任账号 @return 真实只追加版本 */
    private UUID version(UUID tenant, UUID project, UUID account) {
        UUID rule = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        owner.update("INSERT INTO rule_message(id,tenant_id,project_id,name,created_by,created_at,updated_at) VALUES (?,?,?,'规则',?,now(),now())",
                rule, tenant, project, account);
        owner.update("""
                INSERT INTO rule_version(id,tenant_id,project_id,rule_id,version_number,source,source_sha256,created_by,created_at)
                VALUES (?,?,?,?,1,'function execute() {}',?,?,now())
                """, version, tenant, project, rule, "a".repeat(64), account);
        return version;
    }

    /** @param claim 当前原事务中的完整身份 @return SQL端口资格 */
    private boolean authorized(ProjectCleanupClaim claim) {
        return Boolean.TRUE.equals(app.queryForObject("SELECT public.project_cleanup_authorized(?,?,?,?,?)", Boolean.class,
                claim.tenantId(), claim.projectId(), claim.generation(), claim.stage(), claim.leaseToken()));
    }

    /** @param claim 清理身份 @param version 待操作版本 @return 固定RULE阶段的受限删除结果 */
    private int probe(ProjectCleanupClaim claim, UUID version) {
        return app.queryForObject("SELECT public.rule_cleanup_guard_probe(?,?,?,?,?)", Integer.class,
                claim.tenantId(), claim.projectId(), claim.generation(), claim.leaseToken(), version);
    }

    /** @param version 目标版本 @return owner观察的实际存在性 */
    private boolean versionExists(UUID version) {
        return Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM rule_version WHERE id=?)", Boolean.class, version));
    }

    /** @param claim 原领取 @param tenant 租户 @param project 项目 @param generation 代次 @param stage 阶段 @param token token @return 有限错误身份 */
    private ProjectCleanupClaim altered(ProjectCleanupClaim claim, UUID tenant, UUID project, long generation, String stage, UUID token) {
        return new ProjectCleanupClaim(tenant, project, generation, stage, token, claim.leaseUntil(), false);
    }

    /** @param pattern 当前测试可识别的SQL，确认真实服务器等待后才解除屏障 */
    private void awaitLock(String pattern) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)", Boolean.class, pattern))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }
        throw new AssertionError("没有观察到真实锁等待：" + pattern);
    }

    /** @param latch 原事务释放屏障，必须有界等待 */
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    /** @param target 明确迁移终点 @return 包含真实规则前置的完整领域旧库 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam",
                        "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
                        "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/enduser", "classpath:db/migration/export")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** @param claim 模拟RULE入口的持久领取 @param version 只追加版本 @param account 责任账号 */
    private record Fixture(ProjectCleanupClaim claim, UUID version, UUID account) {
    }
}
