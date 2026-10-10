package com.things.link.bootstrap.project.cleanup;

import com.things.link.export.application.ProjectExportPurgeContributor;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportPurgeRepository;
import com.things.link.dashboard.application.DashboardProjectCleanupContributor;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardProjectCleanupRepository;
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
import com.things.link.enduser.application.AppSessionProperties;
import com.things.link.enduser.application.AppSessionService;
import com.things.link.enduser.infrastructure.persistence.JdbcAppProjectCleanupRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppRefreshTokenRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserRoleRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.token.OpaqueToken;
import com.things.link.support.tenant.TenantTransactionLocalRlsScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import com.things.link.ota.application.OtaProjectCleanupContributor;
import com.things.link.ota.infrastructure.persistence.JdbcOtaProjectCleanupRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0081/ADR0100：真实App旧四表及先行grant清理、共享身份保留、轮换链/环及FK竞争。 */
@Testcontainers
class ProjectCleanupEndUserTests {

    /** 每类独占PG，保证全局项目领取和刷新入边完全受控。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_cleanup_enduser").withUsername("thingslink").withPassword("thingslink");
    /** 本片四张项目表；共享用户/安装不在生产清理集合。 */
    private static final List<String> TABLES=List.of("app_device_bind_token","app_refresh_token","app_user_device","app_user_role");
    /** owner仅创建受控历史、观测锁及比较持久快照。 */
    private static JdbcTemplate owner;
    /** 实际业务清理及刷新均用APP。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 会话跨项目撤销复用同一数据源上的单个租户范围组件实例。 */
    private TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;
    /** 原物理事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** 项目清理进度权威仓储。 */
    private JdbcProjectCleanupRepository projects;
    /** 真实准入、租约及初始审计。 */
    private ProjectCleanupAdmissionService admission;
    /** 四个真实前置都必须证明空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 本片真实ENDUSER贡献。 */
    private ProjectCleanupContributor endusers;
    /** 完整项目围栏和原事务提交。 */
    private ProjectCleanupBatchService batches;
    /** 真实会话业务服务，签发器若被调用则立即使测试失败。 */
    private AppSessionService sessions;

    /** 新旧库明确截止迁移，重复执行不重写数据，PUBLIC不授执行。 */
    @BeforeAll
    static void migrate() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink"));
        flyway("20260905.0400").migrate();
        assertThat(flyway("20260905.0500").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0500").migrate().migrationsExecuted).isZero();
        flyway("20260906.0250").migrate();
        assertThat(flyway("20260906.0260").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260906.0260").migrate().migrationsExecuted).isZero();
        // 历史App迁移边界保持，实际Java后继阶段必须匹配含OTA的当前数据库合同。
        assertThat(flyway("20260912.0410").migrate().migrationsExecuted).isPositive();
        assertThat(flyway("20260912.0410").migrate().migrationsExecuted).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.oid='public.app_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0",Long.class)).isZero();
    }

    /** 测试库统一解除自引用后清夹具；生产函数仍必须自己处理指针与上限。 */
    @BeforeEach
    void setup() {
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        // 本类只备没有草稿/版本的稳定目录；先解除grant的复合父引用再清目录，不能污染旧用例。
        owner.update("DELETE FROM app_user_dashboard");
        owner.update("DELETE FROM dash_dashboard");
        owner.update("UPDATE app_refresh_token SET replaced_by=NULL");
        for (String table:TABLES) owner.update("DELETE FROM "+table);
        for (String table:List.of("app_push_token","app_user","dev_device","dev_type","sys_project")) owner.update("DELETE FROM "+table);
        DriverManagerDataSource source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink_app","thingslink");
        app=new JdbcTemplate(source);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        tenantTransactionLocalRlsScope = new TenantTransactionLocalRlsScope(app);
        transactions=new DataSourceTransactionManager(source);
        projects=new JdbcProjectCleanupRepository(app);
        admission=proxy(new ProjectCleanupAdmissionService(projects,new AuditLogService(app,new ObjectMapper()),app));
        prerequisites=List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))),
                proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app))),
                proxy(new AlarmProjectCleanupContributor(new JdbcAlarmProjectCleanupRepository(app))));
        endusers=proxy(new AppProjectCleanupContributor(new JdbcAppProjectCleanupRepository(app)));
        batches=batch(endusers);
        sessions=proxy(new AppSessionService(new JdbcAppRefreshTokenRepository(app),new JdbcAppUserRepository(app),
                new JdbcAppUserRoleRepository(app),principal->{throw new AssertionError("旧项目凭据不得触达JWT签发");},
                new ProjectLifecycleAccessService(new JdbcProjectRepository(app)), transactionLocalRlsScope,
                tenantTransactionLocalRlsScope, new AppSessionProperties(null), transactions));
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
    }

    /** 501条授权先500/1删除，再清角色；稳定键重入及真实FK同时保护同用户邻项目与看板父事实。 */
    @Test
    void deletesDashboardGrantsBeforeRolesInBoundedBatchesAndPreservesNeighbor() {
        Fixture f = fixture(false, null);
        grants(f, 501);
        Fixture other = fixture(true, f);
        grants(other, 2);
        List<UUID> originalIds = grantIds(f);
        String neighborBefore = projectSnapshot("app_user_dashboard", other);
        String neighborRoleBefore = projectSnapshot("app_user_role", other);
        String neighborDashboardBefore = projectSnapshot("dash_dashboard", other);
        String userBefore = entity("app_user", f.user());
        String pushBefore = entity("app_push_token", f.push());
        UUID dashboardId = owner.queryForObject("""
                SELECT dashboard_id FROM app_user_dashboard
                 WHERE tenant_id=? AND project_id=? ORDER BY id LIMIT 1
                """, UUID.class, f.tenant(), f.project());
        assertThatThrownBy(() -> owner.update("DELETE FROM dash_dashboard WHERE id=?", dashboardId))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23503"))
                .hasStackTraceContaining("app_user_dashboard_tenant_project_dashboard_fk");

        assertThat(batches.execute(enduserClaim()).orElseThrow())
                .isEqualTo(ProjectCleanupBatchResult.deleted(500));
        assertThat(grantIds(f)).containsExactlyElementsOf(originalIds.subList(500, 501));
        assertThat(rows("app_user_role", f)).isEqualTo(1);
        assertEnduserStage(f);
        // 重建应用编排对象模拟重入；持久进度与剩余稳定键决定下一批，不能依赖进程游标。
        batches = batch(endusers);
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(1));
        assertThat(grantIds(f)).isEmpty();
        assertThat(rows("app_user_role", f)).isEqualTo(1);
        assertEnduserStage(f);
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(1));
        assertThat(rows("app_user_role", f)).isZero();
        assertEnduserStage(f);
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",
                String.class, f.project())).isEqualTo("DASHBOARD");
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",
                Long.class, f.project())).isEqualTo(502L);
        assertThat(rows("dash_dashboard", f)).as("ENDUSER不能代替DASHBOARD清理父目录").isEqualTo(501);
        // 继续真实DASHBOARD贡献，验证新grant依赖边已解除而不是仅由阶段名称假称父域可清。
        ProjectCleanupContributor dashboards = proxy(new DashboardProjectCleanupContributor(
                new JdbcDashboardProjectCleanupRepository(app)));
        batches = proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope,
                List.of(prerequisites.get(0), prerequisites.get(1), prerequisites.get(2), prerequisites.get(3),
                        endusers, dashboards,
                        proxy(new OtaProjectCleanupContributor(new JdbcOtaProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyAssistantContributor(owner))));
        assertThat(drain()).isEqualTo(501);
        assertThat(rows("dash_dashboard", f)).isZero();
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",
                String.class, f.project())).isEqualTo("OTA");
        // 空OTA和历史不存在的INTEGRATION也必须逐阶段完成，不能跳过新增领域。
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",
                String.class, f.project())).isEqualTo("INTEGRATION");
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",
                String.class, f.project())).isEqualTo("ASSISTANT");
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",
                String.class, f.project())).isEqualTo("TELEMETRY");
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",
                Long.class, f.project())).isEqualTo(1003L);
        assertThat(projectSnapshot("app_user_dashboard", other)).isEqualTo(neighborBefore);
        assertThat(projectSnapshot("app_user_role", other)).isEqualTo(neighborRoleBefore);
        assertThat(projectSnapshot("dash_dashboard", other)).isEqualTo(neighborDashboardBefore);
        assertThat(entity("app_user", f.user())).isEqualTo(userBefore);
        assertThat(entity("app_push_token", f.push())).isEqualTo(pushBefore);
    }

    /** 在本类隔离历史库执行新增迁移本体，验证501号码的500/1边界并恢复历史函数，旧候选不被追改。 */
    @Test
    void notificationContactsAreCleanedBeforeRolesWithoutTouchingNeighbor() throws IOException {
        String tableMigration=new ClassPathResource("db/migration/enduser/V20261009_0150__app_project_notification_contact.sql").getContentAsString(StandardCharsets.UTF_8);
        String cleanupMigration=new ClassPathResource("db/migration/enduser/V20261009_0160__app_notification_contact_cleanup.sql").getContentAsString(StandardCharsets.UTF_8);
        String historicalCurrent=new ClassPathResource("db/migration/enduser/V20260906_0260__app_user_dashboard_cleanup.sql").getContentAsString(StandardCharsets.UTF_8);
        owner.execute(tableMigration);owner.execute(cleanupMigration);
        try {
            Fixture f=fixture(false,null), other=fixture(true,f);
            owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) SELECT gen_random_uuid(),?,'contact-cleanup-'||n,'hash' FROM generate_series(1,500) n",f.tenant());
            owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) SELECT gen_random_uuid(),tenant_id,?,id,'OBSERVER' FROM app_user WHERE tenant_id=?",f.project(),f.tenant());
            owner.update("INSERT INTO app_project_notification_contact(tenant_id,project_id,app_user_id,voice_number,revision) SELECT tenant_id,?,id,'+8613800000001',1 FROM app_user WHERE tenant_id=?",f.project(),f.tenant());
            owner.update("INSERT INTO app_project_notification_contact(tenant_id,project_id,app_user_id,sms_number,revision) VALUES(?,?,?,'+8613800000002',1)",other.tenant(),other.project(),other.user());
            assertThat(batches.execute(enduserClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.deleted(500));
            assertThat(rows("app_project_notification_contact",f)).isEqualTo(1);
            assertThat(rows("app_user_role",f)).isEqualTo(501);
            assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(1));
            assertThat(rows("app_project_notification_contact",f)).isZero();
            assertThat(rows("app_project_notification_contact",other)).isEqualTo(1);
            assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(500));
            assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(1));
            assertThat(next()).isEqualTo(ProjectCleanupBatchResult.done());
            assertThat(owner.queryForObject("SELECT sms_number FROM app_project_notification_contact WHERE project_id=?",String.class,other.project())).isEqualTo("+8613800000002");
            assertThat(owner.queryForObject("SELECT count(*) FROM app_user WHERE tenant_id=?",Long.class,f.tenant())).isEqualTo(501);
        } finally {
            owner.execute(historicalCurrent);
            owner.execute("DROP TABLE app_project_notification_contact");
        }
    }

    /** 运行真实历史0500函数证明其先删角色并误报空域；finally恢复0260，不手写替代清理实现。 */
    @Test
    void historicalCleanupLeavesGrantsAndAdvancesBeforeDashboardForeignKeyIsReleased() throws IOException {
        Fixture f = fixture(false, null);
        grants(f, 1);
        String grantBefore = projectSnapshot("app_user_dashboard", f);
        String historicalMigration = new ClassPathResource(
                "db/migration/enduser/V20260905_0500__app_project_cleanup.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String historicalFunction = historicalMigration.substring(historicalMigration.indexOf(
                "CREATE FUNCTION app_project_cleanup_batch("))
                .replaceFirst("CREATE FUNCTION app_project_cleanup_batch", "CREATE OR REPLACE FUNCTION app_project_cleanup_batch");
        String currentMigration = new ClassPathResource(
                "db/migration/enduser/V20260906_0260__app_user_dashboard_cleanup.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try {
            owner.execute(historicalFunction);
            assertThat(batches.execute(enduserClaim()).orElseThrow())
                    .isEqualTo(ProjectCleanupBatchResult.deleted(1));
            assertThat(rows("app_user_role", f)).as("旧函数错误地先清角色").isZero();
            assertThat(projectSnapshot("app_user_dashboard", f)).isEqualTo(grantBefore);
            assertThat(next()).as("旧函数遗漏grant残留却提前完成ENDUSER")
                    .isEqualTo(ProjectCleanupBatchResult.done());
            assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",
                    String.class, f.project())).isEqualTo("DASHBOARD");
            assertThatThrownBy(() -> owner.update("DELETE FROM dash_dashboard WHERE project_id=?", f.project()))
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23503"))
                    .hasStackTraceContaining("app_user_dashboard_tenant_project_dashboard_fk");
        } finally {
            owner.execute(currentMigration);
        }
    }

    /** 错误租约与真实贡献后故障对grant零提交，临时同名表不能遮蔽public事实或残留复核。 */
    @Test
    void grantCleanupRejectsWrongTokenAndRollsBackAfterContributionFailure() {
        Fixture f = fixture(false, null);
        grants(f, 1);
        ProjectCleanupClaim claim = enduserClaim();
        String grantBefore = projectSnapshot("app_user_dashboard", f);
        String roleBefore = projectSnapshot("app_user_role", f);
        Map<String, Object> progressBefore = owner.queryForMap(
                "SELECT cleanup_stage,cleanup_rows,cleanup_batches FROM sys_project WHERE id=?", f.project());
        ProjectCleanupBatchService wrongToken = batch(contributor(c -> {
            // 外层仍持真实项目锁、正确GUC和有效租约，故失败只能归于传入的错误token。
            app.queryForMap("SELECT * FROM public.app_project_cleanup_batch(?,?,?,?)",
                    c.tenantId(), c.projectId(), c.generation(), UUID.randomUUID());
            return ProjectCleanupBatchResult.done();
        }));
        assertThatThrownBy(() -> wrongToken.execute(claim))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("42501"));
        assertThat(projectSnapshot("app_user_dashboard", f)).isEqualTo(grantBefore);
        assertThat(projectSnapshot("app_user_role", f)).isEqualTo(roleBefore);
        ProjectCleanupBatchService broken = batch(contributor(c -> {
            app.execute("CREATE TEMP TABLE app_user_dashboard (LIKE public.app_user_dashboard) ON COMMIT DROP");
            for (String table : TABLES) {
                app.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + ") ON COMMIT DROP");
            }
            assertThat(endusers.clean(c)).isEqualTo(ProjectCleanupBatchResult.deleted(1));
            throw new IllegalStateException("grant清理贡献后受控失败");
        }));
        assertThatThrownBy(() -> broken.execute(claim)).hasMessage("grant清理贡献后受控失败");
        assertThat(projectSnapshot("app_user_dashboard", f)).isEqualTo(grantBefore);
        assertThat(projectSnapshot("app_user_role", f)).isEqualTo(roleBefore);
        assertThat(owner.queryForMap("SELECT cleanup_stage,cleanup_rows,cleanup_batches FROM sys_project WHERE id=?",
                f.project())).isEqualTo(progressBefore);
        assertThat(batches.execute(claim).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.deleted(1));
        assertThat(rows("app_user_role", f)).isEqualTo(1);
        assertEnduserStage(f);
        assertThat(drain()).isEqualTo(1);
    }

    /** 本项目所有授权按数据库UUID稳定顺序读取，精确断言首批领取边界。 */
    private List<UUID> grantIds(Fixture f) {
        return owner.queryForList("""
                SELECT id FROM app_user_dashboard WHERE tenant_id=? AND project_id=? ORDER BY id
                """, UUID.class, f.tenant(), f.project());
    }

    /** 清理新旧事实尚未全部复核为空时，执行指针必须留在ENDUSER。 */
    private void assertEnduserStage(Fixture f) {
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",
                String.class, f.project())).isEqualTo("ENDUSER");
    }

    /** 真实目录、Console操作者与复合FK造数；交替ACTIVE/REVOKED证明清理不遗漏撤销历史。 */
    private void grants(Fixture f, int count) {
        UUID actor = UUID.randomUUID();
        owner.update("""
                INSERT INTO sys_account(id,email,password_hash,display_name)
                VALUES (?,?,'test-only-unusable-hash','授权清理操作者')
                """, actor, actor + "@example.com");
        owner.update("""
                INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role)
                VALUES (gen_random_uuid(),?,?,?,'OBSERVER')
                """, f.tenant(), f.project(), f.user());
        owner.update("""
                WITH directories AS (
                    INSERT INTO dash_dashboard(id,tenant_id,project_id,management_name,created_by,updated_by)
                    SELECT gen_random_uuid(),?,?,'清理看板',?,? FROM generate_series(1,?)
                    RETURNING id
                ), ordered AS (
                    SELECT id,row_number() OVER (ORDER BY id) position FROM directories
                )
                INSERT INTO app_user_dashboard(id,tenant_id,project_id,app_user_id,dashboard_id,status,revision,
                    created_at,updated_at,revoked_at,created_by,updated_by,revoked_by)
                SELECT gen_random_uuid(),?,?,?,id,CASE WHEN position%2=0 THEN 'REVOKED' ELSE 'ACTIVE' END,
                    CASE WHEN position%2=0 THEN 2 ELSE 1 END,now(),now(),
                    CASE WHEN position%2=0 THEN now() ELSE NULL END,?,?,
                    CASE WHEN position%2=0 THEN ?::uuid ELSE NULL END FROM ordered
                """, f.tenant(), f.project(), actor, actor, count, f.tenant(), f.project(), f.user(), actor, actor, actor);
    }

    /** 只接受PostgreSQL给出的确切失败码，不用任意Java异常冒充FK或权限保护。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    /** 三类1001行真实分批；同租户同用户的ACTIVE邻居、共享用户和安装逐字段保持，旧会话清前清后均拒绝。 */
    @Test
    void cleansProjectFactsInBatchesAndPreservesSharedIdentityAndNeighborSession() {
        Fixture f=fixture(false,null);
        histories(f,1001);
        Fixture other=fixture(true,f);
        histories(other,1);
        String userBefore=entity("app_user",f.user());
        String pushBefore=entity("app_push_token",f.push());
        Map<String,String> neighbor=TABLES.stream().collect(java.util.stream.Collectors.toMap(Function.identity(),t->projectSnapshot(t,other)));
        invalidRefresh(f);
        assertThat(batches.execute(enduserClaim()).orElseThrow().deletedRows()).isEqualTo(500);
        assertThat(owner.queryForObject("SELECT revoked_at IS NULL FROM app_refresh_token WHERE id=?",Boolean.class,other.token()))
                .as("不能撤销共享用户在其他项目的会话").isTrue();
        batches=batch(endusers);
        assertThat(drain()).isEqualTo(2504);
        for (String table:TABLES) {
            assertThat(rows(table,f)).isZero();
            assertThat(projectSnapshot(table,other)).isEqualTo(neighbor.get(table));
        }
        assertThat(entity("app_user",f.user())).isEqualTo(userBefore);
        assertThat(entity("app_push_token",f.push())).isEqualTo(pushBefore);
        assertThat(owner.queryForMap("SELECT cleanup_stage,cleanup_rows,cleanup_batches FROM sys_project WHERE id=?",f.project()))
                .containsEntry("cleanup_stage","DASHBOARD").containsEntry("cleanup_rows",3004L).containsEntry("cleanup_batches",10L);
        invalidRefresh(f);
        assertThat(new JdbcAppRefreshTokenRepository(app).findByHash(OpaqueToken.hash(f.raw()))).isEmpty();
        assertThat(rows("dev_device",f)).isEqualTo(1);
    }

    /** 1001条本项目循环轮换出边只能500/500/1整理，撤销不计作DELETE且随后能收束。 */
    @Test
    void breaksProjectLocalCyclesInBoundedPreparationWithoutInventingDeletedRows() {
        Fixture f=fixture(false,null);
        histories(f,1001);
        owner.update("DELETE FROM app_device_bind_token WHERE project_id=?",f.project());
        owner.update("""
                WITH ordered AS (SELECT id,lead(id) OVER (ORDER BY id) following,
                    first_value(id) OVER (ORDER BY id) first FROM app_refresh_token WHERE project_id=?)
                UPDATE app_refresh_token t SET replaced_by=coalesce(o.following,o.first) FROM ordered o WHERE t.id=o.id
                """,f.project());
        assertThat(batches.execute(enduserClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(prepared(f)).isEqualTo(500);
        invalidRefresh(f);
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(prepared(f)).isEqualTo(1000);
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(prepared(f)).isEqualTo(1001);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,f.project())).isZero();
        assertThat(drain()).isEqualTo(2003);
    }

    /** 本项目指向邻居的出边只修改本行，邻居指向本项目的入边必须保留并阻塞父删除。 */
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void isolatesOutgoingAndIncomingCrossProjectReferences(boolean incoming) {
        Fixture f=fixture(false,null);
        token(f,f.token(),f.raw());
        Fixture other=fixture(true,f);
        token(other,other.token(),other.raw());
        if (incoming) owner.update("UPDATE app_refresh_token SET replaced_by=? WHERE id=?",f.token(),other.token());
        else owner.update("UPDATE app_refresh_token SET replaced_by=? WHERE id=?",other.token(),f.token());
        String before=entity("app_refresh_token",other.token());
        ProjectCleanupBatchResult result=batches.execute(enduserClaim()).orElseThrow();
        if (incoming) {
            assertThat(result.blockedReason()).isEqualTo("ENDUSER_REFRESH_REFERENCED");
            assertThat(rows("app_refresh_token",f)).isEqualTo(1);
        } else {
            assertThat(result).isEqualTo(ProjectCleanupBatchResult.deleted(0));
            assertThat(drain()).isEqualTo(1);
        }
        assertThat(entity("app_refresh_token",other.token())).isEqualTo(before);
    }

    /** 邻居先取得FK父锁，清理等待后读取新提交入边，不会仅依赖旧快照删父。 */
    @Test
    void seesCrossProjectReferenceCommittedWhileWaitingForTokenLock() throws Exception {
        Fixture f=fixture(false,null);
        token(f,f.token(),f.raw());
        Fixture other=fixture(true,f);
        token(other,other.token(),other.raw());
        ProjectCleanupClaim claim=enduserClaim();
        try (Connection writer=owner.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            reference(writer,other.token(),f.token());
            var cleanup=executor.submit(()->batches.execute(claim).orElseThrow());
            awaitLock("%public.app_project_cleanup_batch%");
            writer.commit();
            assertThat(cleanup.get(5,TimeUnit.SECONDS).blockedReason()).isEqualTo("ENDUSER_REFRESH_REFERENCED");
        }
        assertThat(rows("app_refresh_token",f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT replaced_by FROM app_refresh_token WHERE id=?",UUID.class,other.token())).isEqualTo(f.token());
    }

    /** 清理先持父token锁，后来的邻居入边等父删除后23503，邻居原记录及撤销状态保持。 */
    @Test
    void rejectsLateCrossProjectReferenceAfterCleanupHasLockedParent() throws Exception {
        Fixture f=fixture(false,null);
        token(f,f.token(),f.raw());
        Fixture other=fixture(true,f);
        token(other,other.token(),other.raw());
        String before=entity("app_refresh_token",other.token());
        ProjectCleanupClaim claim=enduserClaim();
        try (var executor=Executors.newSingleThreadExecutor()) {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>> update=new java.util.concurrent.atomic.AtomicReference<>();
            ProjectCleanupBatchService controlled=batch(contributor(c->{
                app.queryForObject("SELECT id FROM public.app_refresh_token WHERE id=? FOR UPDATE",UUID.class,f.token());
                update.set(executor.submit(()->{
                    try (Connection writer=owner.getDataSource().getConnection()) {
                        reference(writer,other.token(),f.token());
                        return "UPDATED";
                    } catch (SQLException failure) { return failure.getSQLState(); }
                }));
                awaitLock("%UPDATE app_refresh_token SET replaced_by%");
                return endusers.clean(c);
            }));
            assertThat(controlled.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
            assertThat(update.get().get(5,TimeUnit.SECONDS)).isEqualTo("23503");
        }
        assertThat(entity("app_refresh_token",other.token())).isEqualTo(before);
        assertThat(rows("app_refresh_token",f)).isZero();
    }

    /** 贡献后故障必须回滚真实删除；临时空表不遮蔽实际行，错误阶段/token即使直接函数也拒绝。 */
    @Test
    void rollsBackDeletionAndRejectsInvalidSqlIdentityDespiteTemporaryTables() {
        Fixture f=fixture(false,null);
        token(f,f.token(),f.raw());
        ProjectCleanupClaim claim=enduserClaim();
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->app.queryForMap(
                "SELECT * FROM public.app_project_cleanup_batch(?,?,?,?)",f.tenant(),f.project(),claim.generation(),UUID.randomUUID())))
                .hasRootCauseInstanceOf(SQLException.class);
        ProjectCleanupBatchService broken=batch(contributor(c->{
            for (String table:TABLES) app.execute("CREATE TEMP TABLE "+table+" (LIKE public."+table+") ON COMMIT DROP");
            assertThat(endusers.clean(c).deletedRows()).isEqualTo(1);
            throw new IllegalStateException("App清理受控失败");
        }));
        assertThatThrownBy(()->broken.execute(claim)).hasMessage("App清理受控失败");
        assertThat(rows("app_refresh_token",f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,f.project())).isZero();
        owner.update("UPDATE sys_project SET cleanup_stage='TELEMETRY' WHERE id=?",f.project());
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->endusers.clean(claim)))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    /** 出边整理失败也必须回滚撤销时间，不能因返回零删除而漏掉状态事务一致性。 */
    @Test
    void rollsBackPointerAndRevocationPreparationOnFailure() {
        Fixture f=fixture(false,null);
        token(f,f.token(),f.raw());
        owner.update("UPDATE app_refresh_token SET replaced_by=id WHERE id=?",f.token());
        String before=entity("app_refresh_token",f.token());
        ProjectCleanupClaim claim=enduserClaim();
        ProjectCleanupBatchService broken=batch(contributor(c->{
            assertThat(endusers.clean(c)).isEqualTo(ProjectCleanupBatchResult.deleted(0));
            throw new IllegalStateException("App整理受控失败");
        }));
        assertThatThrownBy(()->broken.execute(claim)).hasMessage("App整理受控失败");
        assertThat(entity("app_refresh_token",f.token())).isEqualTo(before);
        assertThat(batches.execute(claim).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(drain()).isEqualTo(1);
    }

    /** 没有项目关系仍保留孤立共享用户和安装，空域推进不意味着可收集共享父实体。 */
    @Test
    void completesEmptyDomainAndRetainsEvenOtherwiseUnreferencedSharedIdentity() {
        Fixture f=fixture(false,null);
        assertThat(batches.execute(enduserClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(entity("app_user",f.user())).contains("ACTIVE");
        assertThat(entity("app_push_token",f.push())).contains("ACTIVE");
    }

    /** @param f 原项目凭据 @throws AssertionError 任何非60007或JWT签发均失败 */
    private void invalidRefresh(Fixture f) {
        assertThatThrownBy(()->sessions.rotate(f.raw())).isInstanceOfSatisfying(BusinessException.class,
                failure->assertThat(failure.errorCode().code()).isEqualTo(60007));
    }

    /** @return 四个真实空域前置后的ENDUSER领取 */
    private ProjectCleanupClaim enduserClaim() {
        for (int i=0;i<4;i++) assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim=admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("ENDUSER");
        return claim;
    }

    /** @return 下一真实批次 */
    private ProjectCleanupBatchResult next() { return batches.execute(admission.claimNext().orElseThrow()).orElseThrow(); }

    /** @return 最多60次调用的实际DELETE计数，明确等待不能冒充完成 */
    private int drain() {
        int count=0;
        for (int i=0;i<60;i++) {
            ProjectCleanupBatchResult result=next();
            assertThat(result.blockedReason()).isNull();
            assertThat(result.deletedRows()).isBetween(0,500);
            count+=result.deletedRows();
            if (result.complete()) return count;
        }
        throw new AssertionError("App清理超出预期批次");
    }

    /** @param f 本项目 @return 指针已解除且已撤销的实际整理数量 */
    private long prepared(Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE project_id=? AND replaced_by IS NULL AND revoked_at IS NOT NULL",Long.class,f.project());
    }

    /** @param active 邻居保持ACTIVE @param shared 非空则共用真实租户用户与安装 @return 本项目持久身份 */
    private Fixture fixture(boolean active,Fixture shared) {
        Fixture f=new Fixture(shared==null?UUID.randomUUID():shared.tenant(),UUID.randomUUID(),
                shared==null?UUID.randomUUID():shared.user(),shared==null?UUID.randomUUID():shared.push(),
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"refresh_"+UUID.randomUUID());
        if (shared==null) {
            owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'App清理租户')",f.tenant());
            owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash,display_name) VALUES (?,?,'shared-user','hash','共享用户')",f.user(),f.tenant());
            owner.update("""
                    INSERT INTO app_push_token(id,tenant_id,app_user_id,installation_id,provider,token_cipher,token_nonce,key_id)
                    VALUES (?,?,?,gen_random_uuid(),'MOCK',decode(repeat('00',17),'hex'),decode(repeat('00',12),'hex'),'test-key')
                    """,f.push(),f.tenant(),f.user());
        }
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at)
                VALUES (?,?,'App清理项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)
                """,f.project(),f.tenant(),"app_"+f.project().toString().replace("-",""),active?"ACTIVE":"DELETING",active?0:1,active);
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'type','类型','STANDARD','DIRECT','DRAFT')",f.type(),f.tenant(),f.project());
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,'device','设备')",f.device(),f.tenant(),f.project(),f.type());
        return f;
    }

    /** @param f 真实项目/共享用户 @param count 能力token、refresh及关闭关系三类同等数量 */
    private void histories(Fixture f,int count) {
        token(f,f.token(),f.raw());
        owner.update("""
                INSERT INTO app_refresh_token(id,tenant_id,project_id,app_user_id,token_hash,family_id,expires_at)
                SELECT gen_random_uuid(),?,?,?,digest(gen_random_uuid()::text,'sha256'),gen_random_uuid(),now()+interval '10 days'
                FROM generate_series(2,?)
                """,f.tenant(),f.project(),f.user(),count);
        owner.update("""
                INSERT INTO app_device_bind_token(id,tenant_id,project_id,device_id,token_hash,purpose,target_role,issued_by_app_user_id,expires_at,max_attempts)
                SELECT gen_random_uuid(),?,?,?,digest(gen_random_uuid()::text,'sha256'),
                    CASE n%3 WHEN 0 THEN 'CLAIM' WHEN 1 THEN 'SHARE' ELSE 'TRANSFER' END,
                    CASE n%3 WHEN 1 THEN 'READ_ONLY' ELSE 'PRIMARY' END,?,now()+interval '1 day',3 FROM generate_series(1,?) n
                """,f.tenant(),f.project(),f.device(),f.user(),count);
        owner.update("""
                INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role,status)
                SELECT gen_random_uuid(),?,?,?,?,'MEMBER','CLOSED' FROM generate_series(1,?)
                """,f.tenant(),f.project(),f.user(),f.device(),count);
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (gen_random_uuid(),?,?,?,'OBSERVER')",f.tenant(),f.project(),f.user());
    }

    /** @param f 项目及用户 @param id 稳定token身份 @param raw 仅本类测试使用的原明文 */
    private void token(Fixture f,UUID id,String raw) {
        owner.update("INSERT INTO app_refresh_token(id,tenant_id,project_id,app_user_id,token_hash,family_id,expires_at) VALUES (?,?,?,?,?,gen_random_uuid(),now()+interval '10 days')",id,f.tenant(),f.project(),f.user(),OpaqueToken.hash(raw));
    }

    /** @param writer 独立事务 @param from 子token @param to FK父token */
    private void reference(Connection writer,UUID from,UUID to) throws SQLException {
        try (PreparedStatement sql=writer.prepareStatement("UPDATE app_refresh_token SET replaced_by=? WHERE id=?")) {
            sql.setObject(1,to);
            sql.setObject(2,from);
            sql.setQueryTimeout(4);
            sql.executeUpdate();
        }
    }

    /** @param table 固定共享表 @param id 真实身份 @return 全列稳定JSON快照 */
    private String entity(String table,UUID id) { return owner.queryForObject("SELECT row_to_json(t)::text FROM "+table+" t WHERE id=?",String.class,id); }

    /** @param table 本类固定项目表 @param f 项目 @return 包含哈希、撤销、指针的完整邻居快照 */
    private String projectSnapshot(String table,Fixture f) { return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM "+table+" t WHERE tenant_id=? AND project_id=?",String.class,f.tenant(),f.project()); }

    /** @param table 固定表 @param f 项目 @return owner可见真实数量 */
    private long rows(String table,Fixture f) { return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND project_id=?",Long.class,f.tenant(),f.project()); }

    /** @param contributor ENDUSER真实或受控贡献 @return 原四前置与本片的真实事务编排 */
    private ProjectCleanupBatchService batch(ProjectCleanupContributor contributor) {
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, List.of(prerequisites.get(0),prerequisites.get(1),prerequisites.get(2),prerequisites.get(3),contributor)));
    }

    /** @param operation 原事务受控交错 @return 保持ENDUSER身份的贡献 */
    private ProjectCleanupContributor contributor(Function<ProjectCleanupClaim,ProjectCleanupBatchResult> operation) {
        return new ProjectCleanupContributor() {
            /** 固定本片阶段。 */
            @Override
            public ProjectCleanupStage stage() { return ProjectCleanupStage.ENDUSER; }
            /** 原事务故障和真实清理不分离。 */
            @Override
            public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        };
    }

    /** @param target 注解用例 @return 真实事务代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    /** @param pattern 当前测试SQL识别，仅PG真实锁等待才放行 */
    private void awaitLock(String pattern) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,pattern))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }
        throw new AssertionError("未观察到App清理锁等待");
    }

    /** @param target 固定新旧库截止 @return 全部工作区迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink")
                .locations("classpath:db/migration/support","classpath:db/migration/project","classpath:db/migration/iam",
                        "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
                        "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/enduser","classpath:db/migration/export",
                        "classpath:db/migration/dashboard", "classpath:db/migration/ota")
                .placeholders(Map.of("app_role_password","thingslink")).target(target).load();
    }

    /** 稳定测试身份；第二项目显式复用tenant/user/push以证明共享保留而非仅靠随机项目隔离。 */
    private record Fixture(UUID tenant,UUID project,UUID user,UUID push,UUID type,UUID device,UUID token,String raw) { }
}
