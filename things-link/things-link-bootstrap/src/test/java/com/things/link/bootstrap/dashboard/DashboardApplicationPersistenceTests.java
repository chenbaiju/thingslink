package com.things.link.bootstrap.dashboard;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebApp应用与看板目录、独立草稿、不可变版本、精确跨事实关系及有界项目清理的真实PostgreSQL合同。
 *
 * <p>S12-1a1a、S12-1b1a与S12-1b1b只建立持久事实，不提前验证尚未实现的发布服务或HTTP入口。本测试用迁移owner
 * 准备和观察事实，用真实{@code thingslink_app}验证RLS、权限及受控清理边界。
 */
@Testcontainers
@DisplayName("Dashboard应用持久事实")
class DashboardApplicationPersistenceTests {

    /** 与生产验收一致的PostgreSQL/TimescaleDB镜像，避免H2掩盖RLS与复合外键差异。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_application_persistence")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** 生产Bootstrap登记的十二个Flyway领域，旧库升级不能只加载dashboard单目录。 */
    private static final String[] MIGRATION_LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export",
            "classpath:db/migration/dashboard", "classpath:db/migration/ota"
    };

    /** 本片之前的全局最大版本，用于证明既有数据库可以增量进入首个dashboard迁移。 */
    private static final String LEGACY_TARGET = "20260905.1800";

    /** S12-1a1a首个dashboard事实迁移版本。 */
    private static final String DASHBOARD_TARGET = "20260906.0100";

    /** project域随后扩展清理阶段闭集的版本，真实清理不得伪造不存在的DASHBOARD阶段。 */
    private static final String CLEANUP_STAGE_TARGET = "20260906.0110";

    /** S12-1b1a看板目录、草稿、版本与六表清理迁移版本。 */
    private static final String DASHBOARD_CORE_TARGET = "20260906.0120";

    /** S12-1b1b模型与应用版本精确关系及九表清理迁移版本。 */
    private static final String DASHBOARD_RELATION_TARGET = "20260906.0130";

    /** 单次领域清理的硬上限；ADR0096禁止用无界级联代替批次。 */
    private static final int CLEANUP_BATCH_LIMIT = 500;

    /** 迁移owner仅用于夹具、目录断言和跨RLS结果观察。 */
    private static JdbcTemplate owner;

    /** 普通运行身份，RLS和版本表权限断言都必须走本连接。 */
    private JdbcTemplate application;

    /** 应用连接的真实事务管理器，保证set_config(..., true)与业务SQL同一事务。 */
    private DataSourceTransactionManager transactions;

    /**
     * 从旧全局最大版本升级到本片版本，并证明重复启动不重复执行迁移。
     */
    @BeforeAll
    static void migrateFromPreviousGlobalVersion() {
        owner = new JdbcTemplate(ownerDataSource());
        flyway(LEGACY_TARGET).migrate();
        assertThat(flyway(DASHBOARD_TARGET).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(CLEANUP_STAGE_TARGET).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(DASHBOARD_CORE_TARGET).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(DASHBOARD_RELATION_TARGET).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(DASHBOARD_RELATION_TARGET).migrate().migrationsExecuted).isZero();
    }

    /**
     * 每例清除独占数据库中的应用事实与身份骨架，避免一个失败路径改变下例前提。
     */
    @BeforeEach
    void resetDatabase() {
        owner.update("DELETE FROM app_application_version_dashboard_ref");
        owner.update("DELETE FROM dash_dashboard_draft_model_ref");
        owner.update("DELETE FROM dash_dashboard_version_model_ref");
        owner.update("UPDATE dash_dashboard SET current_version_id = NULL");
        owner.update("DELETE FROM dash_dashboard_draft");
        owner.update("DELETE FROM dash_dashboard_version");
        owner.update("DELETE FROM dash_dashboard");
        owner.update("UPDATE app_application SET current_version_id = NULL");
        owner.update("DELETE FROM app_application_draft");
        owner.update("DELETE FROM app_application_version");
        owner.update("DELETE FROM app_application");
        owner.update("DELETE FROM dev_thing_model_version");
        owner.update("DELETE FROM dev_type");
        owner.update("DELETE FROM sys_project_member");
        owner.update("DELETE FROM sys_project");
        owner.update("DELETE FROM sys_tenant_member");
        owner.update("DELETE FROM sys_tenant");
        owner.update("DELETE FROM sys_account");

        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        application = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
    }

    /**
     * 迁移必须创建六张完整注释、项目RLS及固定权限的事实表与受控清理函数。
     */
    @Test
    @DisplayName("迁移创建注释、RLS与受控清理入口")
    void migrationCreatesDocumentedRlsFactsAndRestrictedCleanup() {
        assertThat(owner.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relkind = 'r'
                   AND relname IN (
                       'app_application', 'app_application_draft', 'app_application_version',
                       'dash_dashboard', 'dash_dashboard_draft', 'dash_dashboard_version')
                 ORDER BY relname
                """, String.class)).containsExactly(
                "app_application", "app_application_draft", "app_application_version",
                "dash_dashboard", "dash_dashboard_draft", "dash_dashboard_version");
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns column_info
                 WHERE column_info.table_schema = 'public'
                   AND column_info.table_name IN (
                       'app_application', 'app_application_draft', 'app_application_version',
                       'dash_dashboard', 'dash_dashboard_draft', 'dash_dashboard_version')
                   AND col_description(
                       (quote_ident(column_info.table_schema) || '.'
                           || quote_ident(column_info.table_name))::regclass,
                           column_info.ordinal_position) IS NULL
                """, Long.class)).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM pg_class table_info
                 WHERE table_info.oid IN (
                     'app_application'::regclass, 'app_application_draft'::regclass,
                     'app_application_version'::regclass, 'dash_dashboard'::regclass,
                     'dash_dashboard_draft'::regclass, 'dash_dashboard_version'::regclass)
                   AND obj_description(table_info.oid, 'pg_class') IS NULL
                """, Long.class)).isZero();
        assertThat(owner.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE oid IN ('app_application'::regclass, 'app_application_draft'::regclass,
                     'app_application_version'::regclass, 'dash_dashboard'::regclass,
                     'dash_dashboard_draft'::regclass, 'dash_dashboard_version'::regclass)
                   AND relrowsecurity
                 ORDER BY relname
                """, String.class)).containsExactly(
                "app_application", "app_application_draft", "app_application_version",
                "dash_dashboard", "dash_dashboard_draft", "dash_dashboard_version");
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_policies
                 WHERE tablename IN (
                     'app_application', 'app_application_draft', 'app_application_version',
                     'dash_dashboard', 'dash_dashboard_draft', 'dash_dashboard_version')
                   AND policyname = 'project_isolation'
                   AND qual LIKE '%app_current_project%'
                   AND with_check LIKE '%app_current_project%'
                """, Long.class)).isEqualTo(6);
        assertThat(owner.queryForObject("""
                SELECT has_table_privilege('thingslink_app', 'dash_dashboard', 'SELECT,INSERT,UPDATE')
                   AND NOT has_table_privilege('thingslink_app', 'dash_dashboard', 'DELETE')
                   AND NOT has_table_privilege('thingslink_app', 'dash_dashboard', 'TRUNCATE')
                   AND has_table_privilege(
                       'thingslink_app', 'dash_dashboard_draft', 'SELECT,INSERT,UPDATE')
                   AND NOT has_table_privilege('thingslink_app', 'dash_dashboard_draft', 'DELETE')
                   AND NOT has_table_privilege('thingslink_app', 'dash_dashboard_draft', 'TRUNCATE')
                   AND has_table_privilege('thingslink_app', 'dash_dashboard_version', 'SELECT,INSERT')
                   AND NOT has_table_privilege('thingslink_app', 'dash_dashboard_version', 'UPDATE')
                   AND NOT has_table_privilege('thingslink_app', 'dash_dashboard_version', 'DELETE')
                   AND NOT has_table_privilege('thingslink_app', 'dash_dashboard_version', 'TRUNCATE')
                """, Boolean.class)).isTrue();
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM pg_proc function_info,
                       LATERAL aclexplode(coalesce(
                           function_info.proacl,
                           acldefault('f', function_info.proowner))) privilege
                 WHERE function_info.oid =
                       'public.dashboard_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure
                   AND privilege.grantee = 0
                   AND privilege.privilege_type = 'EXECUTE'
                """, Long.class)).isZero();
        assertThat(owner.queryForObject("""
                SELECT has_function_privilege(
                    'thingslink_app',
                    'public.dashboard_project_cleanup_batch(uuid,uuid,bigint,uuid)',
                    'EXECUTE')
                """, Boolean.class)).isTrue();
    }

    /**
     * 关系迁移必须为三类精确引用建立完整注释、RLS、约束、父删除索引及差异化写权限。
     */
    @Test
    @DisplayName("关系迁移创建注释、RLS、约束与最小权限")
    void referenceMigrationCreatesDocumentedRlsConstraintsAndPrivileges() {
        List<String> tables = List.of(
                "app_application_version_dashboard_ref",
                "dash_dashboard_draft_model_ref",
                "dash_dashboard_version_model_ref");
        assertThat(owner.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relkind = 'r'
                   AND relname IN (
                       'app_application_version_dashboard_ref',
                       'dash_dashboard_draft_model_ref',
                       'dash_dashboard_version_model_ref')
                 ORDER BY relname
                """, String.class)).containsExactlyElementsOf(tables);
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns column_info
                 WHERE column_info.table_schema = 'public'
                   AND column_info.table_name IN (
                       'app_application_version_dashboard_ref',
                       'dash_dashboard_draft_model_ref',
                       'dash_dashboard_version_model_ref')
                   AND col_description(
                       (quote_ident(column_info.table_schema) || '.'
                           || quote_ident(column_info.table_name))::regclass,
                           column_info.ordinal_position) IS NULL
                """, Long.class)).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_class table_info
                 WHERE table_info.relname IN (
                       'app_application_version_dashboard_ref',
                       'dash_dashboard_draft_model_ref',
                       'dash_dashboard_version_model_ref')
                   AND table_info.relnamespace = 'public'::regnamespace
                   AND obj_description(table_info.oid, 'pg_class') IS NULL
                """, Long.class)).isZero();
        assertThat(owner.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname IN (
                       'app_application_version_dashboard_ref',
                       'dash_dashboard_draft_model_ref',
                       'dash_dashboard_version_model_ref')
                   AND relrowsecurity
                 ORDER BY relname
                """, String.class)).containsExactlyElementsOf(tables);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_policies
                 WHERE tablename IN (
                       'app_application_version_dashboard_ref',
                       'dash_dashboard_draft_model_ref',
                       'dash_dashboard_version_model_ref')
                   AND policyname = 'project_isolation'
                   AND qual LIKE '%app_current_project%'
                   AND with_check LIKE '%app_current_project%'
                """, Long.class)).isEqualTo(3);
        assertThat(owner.queryForList("""
                SELECT conname FROM pg_constraint
                 WHERE conrelid IN (
                     'dash_dashboard_draft_model_ref'::regclass,
                     'dash_dashboard_version_model_ref'::regclass,
                     'app_application_version_dashboard_ref'::regclass)
                 ORDER BY conname
                """, String.class)).contains(
                "dash_dashboard_draft_model_ref_pk",
                "dash_dashboard_draft_model_ref_position_ck",
                "dash_dashboard_draft_model_ref_model_key_ck",
                "dash_dashboard_draft_model_ref_source_model_key_uk",
                "dash_dashboard_draft_model_ref_source_model_version_uk",
                "dash_dashboard_draft_model_ref_source_fk",
                "dash_dashboard_draft_model_ref_model_version_fk",
                "dash_dashboard_version_model_ref_pk",
                "dash_dashboard_version_model_ref_position_ck",
                "dash_dashboard_version_model_ref_model_key_ck",
                "dash_dashboard_version_model_ref_source_model_key_uk",
                "dash_dashboard_version_model_ref_source_model_version_uk",
                "dash_dashboard_version_model_ref_source_fk",
                "dash_dashboard_version_model_ref_model_version_fk",
                "app_application_version_dashboard_ref_pk",
                "app_application_version_dashboard_ref_position_ck",
                "app_application_version_dashboard_ref_source_dashboard_uk",
                "app_application_version_dashboard_ref_source_fk",
                "app_application_version_dashboard_ref_target_fk");
        assertThat(owner.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND indexname IN (
                       'dash_dashboard_draft_model_ref_model_version_idx',
                       'dash_dashboard_version_model_ref_model_version_idx',
                       'app_application_version_dashboard_ref_target_idx')
                 ORDER BY indexname
                """, String.class)).containsExactly(
                "app_application_version_dashboard_ref_target_idx",
                "dash_dashboard_draft_model_ref_model_version_idx",
                "dash_dashboard_version_model_ref_model_version_idx");
        assertThat(owner.queryForObject("""
                SELECT has_table_privilege(
                           'thingslink_app', 'dash_dashboard_draft_model_ref', 'SELECT,INSERT,DELETE')
                   AND NOT has_table_privilege(
                           'thingslink_app', 'dash_dashboard_draft_model_ref', 'UPDATE')
                   AND NOT has_table_privilege(
                           'thingslink_app', 'dash_dashboard_draft_model_ref', 'TRUNCATE')
                   AND has_table_privilege(
                           'thingslink_app', 'dash_dashboard_version_model_ref', 'SELECT')
                   AND NOT has_table_privilege(
                           'thingslink_app', 'dash_dashboard_version_model_ref',
                           'INSERT,UPDATE,DELETE,TRUNCATE')
                   AND has_table_privilege(
                           'thingslink_app', 'app_application_version_dashboard_ref', 'SELECT')
                   AND NOT has_table_privilege(
                           'thingslink_app', 'app_application_version_dashboard_ref',
                           'INSERT,UPDATE,DELETE,TRUNCATE')
                """, Boolean.class)).isTrue();
    }

    /**
     * 三类关系必须接受合法同项目事实，并拒绝跨项目、错父、重复位置、重复模型key及重复模型版本。
     */
    @Test
    @DisplayName("精确关系拒绝跨项目、错父与重复来源")
    void referenceRelationsRejectCrossProjectWrongParentsAndDuplicates() {
        Fixture first = fixture(false, null);
        Fixture second = fixture(false, first.tenantId());
        ApplicationFact applicationFact = seedApplication(first, 1);
        ApplicationFact siblingApplication = seedApplication(first, 2);
        DashboardFact dashboardFact = seedDashboard(first, 1);
        DashboardFact siblingDashboard = seedDashboard(first, 2);
        DashboardFact wrongTargetDashboard = seedDashboard(first, 3);
        DashboardFact otherDashboard = seedDashboard(second, 1);
        ModelFact firstModel = seedModel(first, 1);
        ModelFact secondModel = seedModel(first, 2);
        ModelFact otherModel = seedModel(second, 1);

        insertDraftModelReference(first, dashboardFact.dashboardId(), 0, "primary_model",
                firstModel.versionId());
        insertVersionModelReference(first, dashboardFact, 0, "primary_model", firstModel.versionId());
        insertApplicationDashboardReference(first, applicationFact, 0, dashboardFact);

        assertSqlState(() -> insertDraftModelReference(first, dashboardFact.dashboardId(), 1,
                "primary_model", secondModel.versionId()), "23505");
        assertSqlState(() -> insertDraftModelReference(first, dashboardFact.dashboardId(), 1,
                "secondary_model", firstModel.versionId()), "23505");
        assertSqlState(() -> insertDraftModelReference(first, dashboardFact.dashboardId(), 0,
                "secondary_model", secondModel.versionId()), "23505");
        assertSqlState(() -> insertDraftModelReference(first, dashboardFact.dashboardId(), 20,
                "overflow_model", secondModel.versionId()), "23514");
        assertSqlState(() -> insertDraftModelReference(first, dashboardFact.dashboardId(), 1,
                "Invalid-Model", secondModel.versionId()), "23514");
        assertSqlState(() -> insertDraftModelReference(first, otherDashboard.dashboardId(), 1,
                "other_project", secondModel.versionId()), "23503");
        assertSqlState(() -> insertDraftModelReference(first, dashboardFact.dashboardId(), 1,
                "cross_project", otherModel.versionId()), "23503");

        assertSqlState(() -> insertVersionModelReference(first, dashboardFact, 1,
                "primary_model", secondModel.versionId()), "23505");
        assertSqlState(() -> insertVersionModelReference(first, dashboardFact, 1,
                "secondary_model", firstModel.versionId()), "23505");
        assertSqlState(() -> insertVersionModelReference(first, dashboardFact, 0,
                "secondary_model", secondModel.versionId()), "23505");
        assertSqlState(() -> insertVersionModelReference(first, dashboardFact, 20,
                "overflow_model", secondModel.versionId()), "23514");
        assertSqlState(() -> insertVersionModelReference(first,
                new DashboardFact(dashboardFact.dashboardId(), siblingDashboard.versionId()),
                1, "wrong_parent", secondModel.versionId()), "23503");
        assertSqlState(() -> insertVersionModelReference(first, dashboardFact, 1,
                "cross_project", otherModel.versionId()), "23503");

        assertSqlState(() -> insertApplicationDashboardReference(first, applicationFact, 0,
                siblingDashboard), "23505");
        assertSqlState(() -> insertApplicationDashboardReference(first, applicationFact, 1,
                dashboardFact), "23505");
        assertSqlState(() -> insertApplicationDashboardReference(first, applicationFact, 5,
                siblingDashboard), "23514");
        assertSqlState(() -> insertApplicationDashboardReference(first,
                new ApplicationFact(applicationFact.applicationId(), siblingApplication.versionId(),
                        applicationFact.appKey()), 1, siblingDashboard), "23503");
        assertSqlState(() -> insertApplicationDashboardReference(first, applicationFact, 1,
                new DashboardFact(wrongTargetDashboard.dashboardId(), siblingDashboard.versionId())), "23503");
        assertSqlState(() -> insertApplicationDashboardReference(first, applicationFact, 2,
                otherDashboard), "23503");

        assertThat(projectReferenceSnapshot(first)).contains("primary_model");
        assertThat(projectReferenceSnapshot(second)).doesNotContain("primary_model");
    }

    /**
     * 三张关系表必须在缺少项目范围时零可见，选择项目后只暴露本项目并拒绝伪造跨项目写入。
     */
    @Test
    @DisplayName("关系RLS读取与写入均fail-closed")
    void referenceRlsHidesNeighborsAndRejectsCrossProjectWrites() {
        Fixture first = fixture(false, null);
        Fixture second = fixture(false, first.tenantId());
        seedReferenceGraph(first, 1);
        ReferenceGraph secondGraph = seedReferenceGraph(second, 2);
        List<String> tables = List.of(
                "app_application_version_dashboard_ref",
                "dash_dashboard_draft_model_ref",
                "dash_dashboard_version_model_ref");

        for (String table : tables) {
            assertThat(application.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
            assertThat(withScope(first, () -> application.queryForObject(
                    "SELECT count(*) FROM " + table, Long.class))).isEqualTo(1);
        }
        assertRuntimeSqlState(first, () -> insertDraftModelReference(
                application, second, secondGraph.dashboard().dashboardId(), 1,
                "forged_draft", secondGraph.model().versionId()), "42501");
        assertRuntimeSqlState(first, () -> insertVersionModelReference(
                application, second, secondGraph.dashboard(), 1,
                "forged_version", secondGraph.model().versionId()), "42501");
        assertRuntimeSqlState(first, () -> insertApplicationDashboardReference(
                application, second, secondGraph.application(), 1, secondGraph.dashboard()), "42501");

        assertThat(projectReferenceSnapshot(first)).isNotEqualTo(projectReferenceSnapshot(second));
    }

    /**
     * 两类发布关系对普通应用身份只读且对迁移owner不可原位修改，失败后关系快照必须零漂移。
     */
    @Test
    @DisplayName("发布关系拒绝普通角色与owner改写")
    void publishedReferencesAreImmutableForRuntimeAndMigrationOwner() {
        Fixture fixture = fixture(false, null);
        ReferenceGraph graph = seedReferenceGraph(fixture, 1);
        ModelFact insertCandidateModel = seedModel(fixture, 2);
        DashboardFact insertCandidateDashboard = seedDashboard(fixture, 2);
        String before = projectReferenceSnapshot(fixture);

        assertRuntimeSqlState(fixture, () -> insertVersionModelReference(
                application, fixture, graph.dashboard(), 1,
                "runtime_insert", insertCandidateModel.versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.update("""
                UPDATE dash_dashboard_version_model_ref SET model_key = 'changed_model'
                 WHERE dashboard_version_id = ?
                """, graph.dashboard().versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.update("""
                DELETE FROM dash_dashboard_version_model_ref WHERE dashboard_version_id = ?
                """, graph.dashboard().versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE dash_dashboard_version_model_ref"), "42501");
        assertRuntimeSqlState(fixture, () -> insertApplicationDashboardReference(
                application, fixture, graph.application(), 1, insertCandidateDashboard), "42501");
        assertRuntimeSqlState(fixture, () -> application.update("""
                UPDATE app_application_version_dashboard_ref SET position = 1
                 WHERE application_version_id = ?
                """, graph.application().versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.update("""
                DELETE FROM app_application_version_dashboard_ref WHERE application_version_id = ?
                """, graph.application().versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE app_application_version_dashboard_ref"), "42501");
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard_version_model_ref SET model_key = 'owner_changed'
                 WHERE dashboard_version_id = ?
                """, graph.dashboard().versionId()), "23514");
        assertSqlState(() -> owner.update("""
                UPDATE app_application_version_dashboard_ref SET position = 1
                 WHERE application_version_id = ?
                """, graph.application().versionId()), "23514");

        assertThat(projectReferenceSnapshot(fixture)).isEqualTo(before);
    }

    /**
     * 草稿与发布模型关系必须分别保护物模型父版本，应用关系必须同时保护两侧不可变父版本。
     */
    @Test
    @DisplayName("精确关系保护模型、应用与看板父版本")
    void referencesProtectEveryImmutableParent() {
        Fixture fixture = fixture(false, null);
        DashboardFact draftDashboard = seedDashboard(fixture, 1);
        DashboardFact versionDashboard = seedDashboard(fixture, 2);
        ModelFact draftModel = seedModel(fixture, 1);
        ModelFact versionModel = seedModel(fixture, 2);
        insertDraftModelReference(fixture, draftDashboard.dashboardId(), 0,
                "draft_model", draftModel.versionId());
        insertVersionModelReference(fixture, versionDashboard, 0,
                "version_model", versionModel.versionId());

        assertSqlState(() -> owner.update(
                "DELETE FROM dev_thing_model_version WHERE id = ?", draftModel.versionId()), "23503");
        assertSqlState(() -> owner.update(
                "DELETE FROM dev_thing_model_version WHERE id = ?", versionModel.versionId()), "23503");
        owner.update("DELETE FROM dash_dashboard_draft_model_ref WHERE thing_model_version_id = ?",
                draftModel.versionId());
        owner.update("DELETE FROM dash_dashboard_version_model_ref WHERE thing_model_version_id = ?",
                versionModel.versionId());
        assertThat(owner.update(
                "DELETE FROM dev_thing_model_version WHERE id = ?", draftModel.versionId())).isOne();
        assertThat(owner.update(
                "DELETE FROM dev_thing_model_version WHERE id = ?", versionModel.versionId())).isOne();

        ApplicationFact applicationFact = seedApplication(fixture, 1);
        DashboardFact applicationDashboard = seedDashboard(fixture, 3);
        insertApplicationDashboardReference(fixture, applicationFact, 0, applicationDashboard);
        owner.update("UPDATE app_application SET current_version_id = NULL WHERE id = ?",
                applicationFact.applicationId());
        owner.update("UPDATE dash_dashboard SET current_version_id = NULL WHERE id = ?",
                applicationDashboard.dashboardId());
        assertSqlState(() -> owner.update(
                "DELETE FROM app_application_version WHERE id = ?", applicationFact.versionId()), "23503");
        assertSqlState(() -> owner.update(
                "DELETE FROM dash_dashboard_version WHERE id = ?", applicationDashboard.versionId()), "23503");
        owner.update("DELETE FROM app_application_version_dashboard_ref WHERE application_version_id = ?",
                applicationFact.versionId());
        assertThat(owner.update(
                "DELETE FROM app_application_version WHERE id = ?", applicationFact.versionId())).isOne();
        assertThat(owner.update(
                "DELETE FROM dash_dashboard_version WHERE id = ?", applicationDashboard.versionId())).isOne();
    }

    /**
     * DEVICE阶段若越过DASHBOARD关系清理尝试删除模型父版本，必须返回稳定父引用阻塞而不是破坏Schema历史。
     */
    @Test
    @DisplayName("DEVICE清理拒绝尚有看板关系的模型版本")
    void deviceCleanupRejectsRemainingDashboardModelReferences() {
        Fixture fixture = fixture(true, null);
        DashboardFact dashboardFact = seedDashboard(fixture, 1);
        ModelFact modelFact = seedModel(fixture, 1);
        insertDraftModelReference(fixture, dashboardFact.dashboardId(), 0,
                "protected_model", modelFact.versionId());
        owner.update("UPDATE sys_project SET cleanup_stage = 'DEVICE' WHERE id = ?", fixture.projectId());

        assertThat(deviceCleanup(fixture)).isEqualTo(
                new CleanupResult(0, false, "DEVICE_PARENT_REFERENCE_REMAINS"));
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM dev_thing_model_version WHERE id = ?",
                Long.class, modelFact.versionId())).isOne();
        assertThat(count("dash_dashboard_draft_model_ref", fixture)).isOne();
    }

    /**
     * appKey由数据库仲裁全局唯一，目录、草稿、版本和当前指针必须保持同应用复合归属。
     */
    @Test
    @DisplayName("appKey与复合归属拒绝伪造")
    void appKeyAndCompositeOwnershipRejectCrossApplicationPointers() {
        Fixture first = fixture(false, null);
        Fixture second = fixture(false, first.tenantId());
        ApplicationFact firstApplication = seedApplication(first, 1);
        ApplicationFact siblingApplication = seedApplication(first, 2);
        ApplicationFact otherProjectApplication = seedApplication(second, 1);

        assertSqlState(() -> owner.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                VALUES (?, ?, ?, 'invalid-key', '非法定位', ?, ?)
                """, UUID.randomUUID(), first.tenantId(), first.projectId(), first.accountId(),
                first.accountId()), "23514");
        assertSqlState(() -> owner.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, '重复定位', ?, ?)
                """, UUID.randomUUID(), second.tenantId(), second.projectId(), firstApplication.appKey(),
                second.accountId(), second.accountId()), "23505");
        assertSqlState(() -> owner.update("""
                UPDATE app_application SET app_key = ? WHERE id = ?
                """, appKey(UUID.randomUUID()), firstApplication.applicationId()), "23514");
        assertSqlState(() -> owner.update("""
                UPDATE app_application SET project_id = ? WHERE id = ?
                """, second.projectId(), firstApplication.applicationId()), "23514");
        owner.update("""
                UPDATE app_application_draft
                   SET content = '{"formatVersion":"tc.application/v1","saved":true}'::jsonb,
                       revision = revision + 1,
                       updated_at = clock_timestamp()
                 WHERE application_id = ?
                """, firstApplication.applicationId());
        assertSqlState(() -> owner.update("""
                UPDATE app_application_draft SET revision = revision + 2 WHERE application_id = ?
                """, firstApplication.applicationId()), "23514");
        assertThat(owner.queryForObject("""
                SELECT revision = 1
                       AND content = '{"formatVersion":"tc.application/v1","saved":true}'::jsonb
                  FROM app_application_draft
                 WHERE application_id = ?
                """, Boolean.class, firstApplication.applicationId())).isTrue();

        assertSqlState(() -> owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 1
                 WHERE id = ?
                """, siblingApplication.versionId(), firstApplication.applicationId()), "23503");
        assertSqlState(() -> owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 1
                 WHERE id = ?
                """, otherProjectApplication.versionId(), firstApplication.applicationId()), "23503");
        owner.update("DELETE FROM app_application_draft WHERE application_id = ?",
                firstApplication.applicationId());
        assertSqlState(() -> owner.update("""
                INSERT INTO app_application_draft(
                    application_id, tenant_id, project_id, content, revision, updated_by)
                VALUES (?, ?, ?, '{"formatVersion":"tc.application/v1"}'::jsonb, 0, ?)
                """, firstApplication.applicationId(), second.tenantId(), second.projectId(),
                second.accountId()), "23503");

        assertThat(owner.queryForObject("""
                SELECT current_version_id FROM app_application WHERE id = ?
                """, UUID.class, firstApplication.applicationId()))
                .isEqualTo(firstApplication.versionId());
    }

    /**
     * 看板目录、草稿、版本和当前指针必须保持同项目同看板归属，草稿revision只允许精确推进一次。
     */
    @Test
    @DisplayName("看板复合归属与草稿revision拒绝伪造")
    void dashboardOwnershipAndDraftRevisionRejectForgedFacts() {
        Fixture first = fixture(false, null);
        Fixture second = fixture(false, first.tenantId());
        DashboardFact firstDashboard = seedDashboard(first, 1);
        DashboardFact siblingDashboard = seedDashboard(first, 2);
        DashboardFact otherProjectDashboard = seedDashboard(second, 1);

        assertSqlState(() -> owner.update("""
                INSERT INTO dash_dashboard(
                    id, tenant_id, project_id, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), first.tenantId(), first.projectId(), " ",
                first.accountId(), first.accountId()), "23514");
        assertSqlState(() -> owner.update("""
                INSERT INTO dash_dashboard(
                    id, tenant_id, project_id, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), first.tenantId(), first.projectId(), "非法\u0001名称",
                first.accountId(), first.accountId()), "23514");
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard SET management_name = ? WHERE id = ?
                """, "看板".repeat(41), firstDashboard.dashboardId()), "22001");
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard SET project_id = ? WHERE id = ?
                """, second.projectId(), firstDashboard.dashboardId()), "23514");
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard
                   SET current_version_id = ?, publication_revision = 2
                 WHERE id = ?
                """, siblingDashboard.versionId(), firstDashboard.dashboardId()), "23503");
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard
                   SET current_version_id = ?, publication_revision = 2
                 WHERE id = ?
                """, otherProjectDashboard.versionId(), firstDashboard.dashboardId()), "23503");
        assertSqlState(() -> insertDashboardVersion(second, firstDashboard.dashboardId(), 2,
                100, 0, 0, "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64)),
                "23503");

        owner.update("""
                UPDATE dash_dashboard_draft
                   SET content = '{"schemaVersion":"tc.dashboard/v1","saved":true}'::jsonb,
                       revision = revision + 1, updated_at = clock_timestamp()
                 WHERE dashboard_id = ?
                """, firstDashboard.dashboardId());
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard_draft SET revision = revision + 2 WHERE dashboard_id = ?
                """, firstDashboard.dashboardId()), "23514");
        assertThat(owner.queryForObject("""
                SELECT revision = 1
                       AND content = '{"schemaVersion":"tc.dashboard/v1","saved":true}'::jsonb
                  FROM dash_dashboard_draft WHERE dashboard_id = ?
                """, Boolean.class, firstDashboard.dashboardId())).isTrue();

        owner.update("DELETE FROM dash_dashboard_draft WHERE dashboard_id = ?", firstDashboard.dashboardId());
        assertSqlState(() -> owner.update("""
                INSERT INTO dash_dashboard_draft(
                    dashboard_id, tenant_id, project_id, content, revision, updated_by)
                VALUES (?, ?, ?, '{"schemaVersion":"tc.dashboard/v1"}'::jsonb, 0, ?)
                """, firstDashboard.dashboardId(), second.tenantId(), second.projectId(),
                second.accountId()), "23503");
        owner.update("""
                INSERT INTO dash_dashboard_draft(
                    dashboard_id, tenant_id, project_id, content, revision, updated_by)
                VALUES (?, ?, ?, '{"schemaVersion":"tc.dashboard/v1"}'::jsonb, ?, ?)
                """, firstDashboard.dashboardId(), first.tenantId(), first.projectId(),
                Long.MAX_VALUE, first.accountId());
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard_draft SET revision = 0 WHERE dashboard_id = ?
                """, firstDashboard.dashboardId()), "23514");

        assertThat(owner.queryForObject(
                "SELECT current_version_id FROM dash_dashboard WHERE id = ?",
                UUID.class, firstDashboard.dashboardId())).isEqualTo(firstDashboard.versionId());
    }

    /**
     * 看板草稿和版本必须接受精确512000字节规范JSONB，并拒绝摘要、组件和资源上限之外的版本。
     */
    @Test
    @DisplayName("看板Schema、摘要和派生集合边界由数据库裁决")
    void dashboardSchemaDigestAndDerivedRequirementsEnforceExactLimits() {
        Fixture fixture = fixture(false, null);
        DashboardFact fact = seedDashboard(fixture, 1);

        owner.update("""
                UPDATE dash_dashboard_draft
                   SET content = jsonb_build_object(
                       'schemaVersion', 'tc.dashboard/v1',
                       'pad', repeat('a', 512000 - octet_length(jsonb_build_object(
                           'schemaVersion', 'tc.dashboard/v1', 'pad', '')::text))),
                       revision = revision + 1
                 WHERE dashboard_id = ?
                """, fact.dashboardId());
        assertThat(owner.queryForObject("""
                SELECT octet_length(content::text) FROM dash_dashboard_draft WHERE dashboard_id = ?
                """, Integer.class, fact.dashboardId())).isEqualTo(512000);
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard_draft
                   SET content = jsonb_build_object(
                       'schemaVersion', 'tc.dashboard/v1',
                       'pad', repeat('a', 512001 - octet_length(jsonb_build_object(
                           'schemaVersion', 'tc.dashboard/v1', 'pad', '')::text))),
                       revision = revision + 1
                 WHERE dashboard_id = ?
                """, fact.dashboardId()), "23514");

        insertDashboardVersion(fixture, fact.dashboardId(), 2, 512000, 10, 50,
                "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64));
        assertThat(owner.queryForMap("""
                SELECT octet_length(schema::text) AS schema_bytes,
                       jsonb_array_length(required_components) AS component_count,
                       jsonb_array_length(required_resources) AS resource_count
                  FROM dash_dashboard_version
                 WHERE dashboard_id = ? AND version_number = 2
                """, fact.dashboardId())).containsAllEntriesOf(Map.of(
                "schema_bytes", 512000, "component_count", 10, "resource_count", 50));
        assertSqlState(() -> insertDashboardVersion(fixture, fact.dashboardId(), 3,
                512001, 10, 50, "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64)),
                "23514");
        assertSqlState(() -> insertDashboardVersion(fixture, fact.dashboardId(), 3,
                100, 11, 50, "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64)),
                "23514");
        assertSqlState(() -> insertDashboardVersion(fixture, fact.dashboardId(), 3,
                100, 10, 51, "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64)),
                "23514");
        assertSqlState(() -> insertDashboardVersion(fixture, fact.dashboardId(), 3,
                100, 10, 50, "tc.dashboard/v2", "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64)),
                "23514");
        assertSqlState(() -> insertDashboardVersion(fixture, fact.dashboardId(), 3,
                100, 10, 50, "tc.dashboard/v1", "SHA256", "b".repeat(64)), "23514");
        assertSqlState(() -> insertDashboardVersion(fixture, fact.dashboardId(), 3,
                100, 10, 50, "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "B".repeat(64)),
                "23514");
    }

    /**
     * 无项目范围必须零可见；选择项目后仍不能借合法外键把其他项目事实写入当前RLS范围。
     */
    @Test
    @DisplayName("项目RLS读取与写入均fail-closed")
    void projectRlsHidesNeighborsAndRejectsCrossProjectWrites() {
        Fixture first = fixture(false, null);
        Fixture second = fixture(false, first.tenantId());
        seedApplication(first, 1);
        seedApplication(second, 1);
        seedDashboard(first, 1);
        seedDashboard(second, 1);

        assertThat(application.queryForObject("SELECT count(*) FROM app_application", Long.class)).isZero();
        assertThat(application.queryForObject("SELECT count(*) FROM dash_dashboard", Long.class)).isZero();
        assertThat(application.queryForObject(
                "SELECT count(*) FROM dash_dashboard_draft", Long.class)).isZero();
        assertThat(application.queryForObject(
                "SELECT count(*) FROM dash_dashboard_version", Long.class)).isZero();
        assertThat(withScope(first, () -> application.queryForObject(
                "SELECT count(*) FROM app_application", Long.class))).isEqualTo(1);
        assertThat(withScope(first, () -> application.queryForObject(
                "SELECT count(*) FROM dash_dashboard", Long.class))).isEqualTo(1);
        assertThat(withScope(first, () -> application.queryForObject(
                "SELECT count(*) FROM dash_dashboard_draft", Long.class))).isEqualTo(1);
        assertThat(withScope(first, () -> application.queryForObject(
                "SELECT count(*) FROM dash_dashboard_version", Long.class))).isEqualTo(1);
        assertThatThrownBy(() -> withScope(first, () -> application.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, '跨项目写入', ?, ?)
                """, UUID.randomUUID(), second.tenantId(), second.projectId(), appKey(UUID.randomUUID()),
                second.accountId(), second.accountId())))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo("42501");
        assertThatThrownBy(() -> withScope(first, () -> application.update("""
                INSERT INTO dash_dashboard(
                    id, tenant_id, project_id, management_name, created_by, updated_by)
                VALUES (?, ?, ?, '跨项目看板', ?, ?)
                """, UUID.randomUUID(), second.tenantId(), second.projectId(),
                second.accountId(), second.accountId())))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo("42501");
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application WHERE project_id = ?", Long.class,
                second.projectId())).isEqualTo(1);
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM dash_dashboard WHERE project_id = ?", Long.class,
                second.projectId())).isEqualTo(1);
    }

    /**
     * 应用目录只允许软删除，草稿保留至项目清理，版本快照不可改写；普通角色不能取得物理删除或截断能力。
     */
    @Test
    @DisplayName("应用事实拒绝普通物理删除且版本不可变")
    void publishedVersionIsImmutableForRuntimeAndMigrationOwner() {
        Fixture fixture = fixture(false, null);
        ApplicationFact fact = seedApplication(fixture, 1);
        String factsBefore = projectApplicationSnapshot(fixture);
        String original = owner.queryForObject(
                "SELECT snapshot::text FROM app_application_version WHERE id = ?",
                String.class, fact.versionId());

        assertRuntimeSqlState(fixture, () -> application.update(
                "DELETE FROM app_application WHERE id = ?", fact.applicationId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE app_application"), "42501");
        assertRuntimeSqlState(fixture, () -> application.update(
                "DELETE FROM app_application_draft WHERE application_id = ?",
                fact.applicationId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE app_application_draft"), "42501");
        assertRuntimeSqlState(fixture, () -> application.update("""
                UPDATE app_application_version
                   SET snapshot = '{"formatVersion":"tc.application/v1","changed":true}'::jsonb
                 WHERE id = ?
                """, fact.versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.update(
                "DELETE FROM app_application_version WHERE id = ?", fact.versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE app_application_version"), "42501");
        assertSqlState(() -> owner.update("""
                UPDATE app_application_version
                   SET snapshot = '{"formatVersion":"tc.application/v1","ownerChanged":true}'::jsonb
                 WHERE id = ?
                """, fact.versionId()), "23514");

        assertThat(owner.queryForObject(
                "SELECT snapshot::text FROM app_application_version WHERE id = ?",
                String.class, fact.versionId())).isEqualTo(original);
        assertThat(projectApplicationSnapshot(fixture)).isEqualTo(factsBefore);
    }

    /**
     * 普通运行角色只能软删看板目录并保存草稿，任何身份都不能原位改写不可变看板版本。
     */
    @Test
    @DisplayName("看板事实拒绝普通物理删除且版本不可变")
    void dashboardVersionIsImmutableForRuntimeAndMigrationOwner() {
        Fixture fixture = fixture(false, null);
        DashboardFact fact = seedDashboard(fixture, 1);
        String factsBefore = projectDashboardSnapshot(fixture);
        String original = owner.queryForObject(
                "SELECT schema::text FROM dash_dashboard_version WHERE id = ?",
                String.class, fact.versionId());

        assertRuntimeSqlState(fixture, () -> application.update(
                "DELETE FROM dash_dashboard WHERE id = ?", fact.dashboardId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE dash_dashboard"), "42501");
        assertRuntimeSqlState(fixture, () -> application.update(
                "DELETE FROM dash_dashboard_draft WHERE dashboard_id = ?",
                fact.dashboardId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE dash_dashboard_draft"), "42501");
        assertRuntimeSqlState(fixture, () -> application.update("""
                UPDATE dash_dashboard_version
                   SET schema = '{"schemaVersion":"tc.dashboard/v1","changed":true}'::jsonb
                 WHERE id = ?
                """, fact.versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.update(
                "DELETE FROM dash_dashboard_version WHERE id = ?", fact.versionId()), "42501");
        assertRuntimeSqlState(fixture, () -> application.execute(
                "TRUNCATE TABLE dash_dashboard_version"), "42501");
        assertSqlState(() -> owner.update("""
                UPDATE dash_dashboard_version
                   SET schema = '{"schemaVersion":"tc.dashboard/v1","ownerChanged":true}'::jsonb
                 WHERE id = ?
                """, fact.versionId()), "23514");

        assertThat(owner.queryForObject(
                "SELECT schema::text FROM dash_dashboard_version WHERE id = ?",
                String.class, fact.versionId())).isEqualTo(original);
        assertThat(projectDashboardSnapshot(fixture)).isEqualTo(factsBefore);
    }

    /**
     * 错误代次或租约不得取得SECURITY DEFINER删除能力，失败后目标九表保持原样。
     */
    @Test
    @DisplayName("清理能力拒绝错误租约")
    void cleanupRejectsInvalidCapabilityWithoutDeletingFacts() {
        Fixture target = fixture(true, null);
        seedReferenceGraph(target, 1);
        String before = projectPersistenceSnapshot(target);

        assertSqlState(() -> cleanup(target, UUID.randomUUID()), "42501");
        assertSqlState(() -> cleanup(target, 2L, target.cleanupToken()), "42501");

        assertThat(projectPersistenceSnapshot(target)).isEqualTo(before);
    }

    /**
     * 清理函数内部真实写入在调用事务回滚时不得留下软删、指针或物理删除半事实。
     */
    @Test
    @DisplayName("受控清理事务回滚后可按原身份重入")
    void cleanupRollbackRestoresFactsAndAllowsReentry() {
        Fixture target = fixture(true, null);
        seedReferenceGraph(target, 1);
        String before = projectPersistenceSnapshot(target);

        CleanupResult rolledBack = new TransactionTemplate(transactions).execute(status -> {
            CleanupResult result = cleanup(target, target.cleanupToken());
            status.setRollbackOnly();
            return result;
        });

        assertThat(rolledBack).isEqualTo(new CleanupResult(0, false, null));
        assertThat(projectPersistenceSnapshot(target)).isEqualTo(before);
        assertThat(cleanup(target, target.cleanupToken()))
                .isEqualTo(new CleanupResult(0, false, null));
        assertThat(projectPersistenceSnapshot(target)).isNotEqualTo(before);
    }

    /**
     * 501个应用、看板及三类关系必须跨23轮按依赖顺序清除，每轮删除不超过500且相邻项目完整保留。
     */
    @Test
    @DisplayName("受控清理分批删除并保护相邻项目")
    void cleanupRunsInBoundedBatchesAndPreservesNeighbor() {
        Fixture target = fixture(true, null);
        Fixture neighbor = fixture(false, target.tenantId());
        seedApplications(target, CLEANUP_BATCH_LIMIT + 1);
        seedDashboards(target, CLEANUP_BATCH_LIMIT + 1);
        seedBatchReferences(target);
        seedReferenceGraph(neighbor, 1);
        String neighborBefore = projectPersistenceSnapshot(neighbor);
        List<CleanupCounts> expectedOrder = List.of(
                new CleanupCounts(501, 501, 501, 501, 500, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(501, 501, 501, 501, 501, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(501, 1, 501, 501, 501, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(501, 0, 501, 501, 501, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(501, 0, 1, 501, 501, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(501, 0, 0, 501, 501, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(501, 0, 0, 1, 501, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(501, 0, 0, 0, 501, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(1, 0, 0, 0, 1, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 501, 501, 501, 501, 0),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 501, 501, 501, 501, 500),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 501, 501, 501, 501, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 1, 501, 501, 501, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 0, 501, 501, 501, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 0, 1, 501, 501, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 0, 0, 501, 501, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 0, 0, 1, 501, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 0, 0, 0, 501, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 0, 0, 0, 1, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 501, 0, 0, 0, 0, 501),
                new CleanupCounts(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1),
                new CleanupCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new CleanupCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        long deletedRows = 0;
        CleanupResult result = null;
        for (int invocation = 0; invocation < expectedOrder.size(); invocation++) {
            result = cleanup(target, target.cleanupToken());
            assertThat(result.deletedRows()).isBetween(0, CLEANUP_BATCH_LIMIT);
            assertThat(result.blockedReason()).isNull();
            assertThat(result.complete()).as("清理调用%d的完成标记", invocation + 1)
                    .isEqualTo(invocation == expectedOrder.size() - 1);
            assertThat(cleanupCounts(target)).as("清理调用%d后的九表顺序", invocation + 1)
                    .isEqualTo(expectedOrder.get(invocation));
            assertThat(projectPersistenceSnapshot(neighbor)).isEqualTo(neighborBefore);
            deletedRows += result.deletedRows();
        }

        assertThat(result).isEqualTo(new CleanupResult(0, true, null));
        assertThat(deletedRows).isEqualTo(9L * (CLEANUP_BATCH_LIMIT + 1));
        for (String table : List.of(
                "app_application", "app_application_draft", "app_application_version_dashboard_ref",
                "app_application_version", "dash_dashboard", "dash_dashboard_draft_model_ref",
                "dash_dashboard_draft", "dash_dashboard_version_model_ref", "dash_dashboard_version")) {
            assertThat(count(table, target)).isZero();
        }
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM dev_thing_model_version WHERE project_id = ?",
                Long.class, target.projectId())).isOne();
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM dev_thing_model_version WHERE project_id = ?",
                Long.class, neighbor.projectId())).isOne();
        assertThat(projectPersistenceSnapshot(neighbor)).isEqualTo(neighborBefore);
        assertThat(cleanup(target, target.cleanupToken()))
                .isEqualTo(new CleanupResult(0, true, null));
    }

    /**
     * 创建独立身份与项目；清理目标直接进入已领取DASHBOARD阶段的真实持久状态。
     *
     * @param purging 是否创建为正在物理清理的项目。
     * @param existingTenant 复用租户；为空时新建租户。
     * @return 隔离夹具。
     */
    private Fixture fixture(boolean purging, UUID existingTenant) {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = existingTenant == null ? UUID.randomUUID() : existingTenant;
        UUID projectId = UUID.randomUUID();
        UUID cleanupToken = UUID.randomUUID();
        owner.update("""
                INSERT INTO sys_account(id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}dashboard-test', 'Dashboard测试账号')
                """, accountId, accountId + "@dashboard.test");
        if (existingTenant == null) {
            owner.update("INSERT INTO sys_tenant(id, name) VALUES (?, 'Dashboard测试租户')", tenantId);
        }
        owner.update("""
                INSERT INTO sys_project(
                    id, tenant_id, name, project_key, status, lifecycle_generation, deleted_at,
                    cleanup_stage, cleanup_started_at, cleanup_next_attempt_at,
                    cleanup_lease_token, cleanup_lease_until)
                VALUES (?, ?, 'Dashboard测试项目', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, projectId, tenantId, "dash_" + compact(projectId),
                purging ? "PURGING" : "ACTIVE", purging ? 1L : 0L,
                purging ? java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(31L * 86400)) : null,
                purging ? "DASHBOARD" : null,
                purging ? java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)) : null,
                purging ? java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)) : null,
                purging ? cleanupToken : null,
                purging ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(600)) : null);
        return new Fixture(tenantId, projectId, accountId, cleanupToken);
    }

    /**
     * 创建一套应用、草稿、版本及合法当前指针。
     *
     * @param fixture 归属夹具。
     * @param discriminator 同项目内定位符区分值。
     * @return 应用与版本身份。
     */
    private ApplicationFact seedApplication(Fixture fixture, int discriminator) {
        UUID applicationId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String key = appKey(UUID.randomUUID());
        owner.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, applicationId, fixture.tenantId(), fixture.projectId(), key,
                "应用" + discriminator, fixture.accountId(), fixture.accountId());
        owner.update("""
                INSERT INTO app_application_draft(
                    application_id, tenant_id, project_id, content, revision, updated_by)
                VALUES (?, ?, ?, ?::jsonb, 0, ?)
                """, applicationId, fixture.tenantId(), fixture.projectId(),
                applicationJson(discriminator), fixture.accountId());
        owner.update("""
                INSERT INTO app_application_version(
                    id, tenant_id, project_id, application_id, version_number,
                    source_draft_revision, snapshot, snapshot_digest_algorithm,
                    snapshot_digest, published_by_account_id, published_at)
                VALUES (?, ?, ?, ?, 1, 0, ?::jsonb, 'PG_JSONB_TEXT_V1_SHA256', ?, ?, now())
                """, versionId, fixture.tenantId(), fixture.projectId(), applicationId,
                applicationJson(discriminator), "a".repeat(64), fixture.accountId());
        owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 1
                 WHERE id = ?
                """, versionId, applicationId);
        return new ApplicationFact(applicationId, versionId, key);
    }

    /**
     * 用集合SQL创建清理批次，避免逐行夹具耗时掩盖函数本身的分批行为。
     *
     * @param fixture 目标项目。
     * @param count 应用数量。
     */
    private void seedApplications(Fixture fixture, int count) {
        owner.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                SELECT identity.id, ?, ?, 'app_' || replace(identity.id::text, '-', ''),
                       '批量应用' || identity.ordinal, ?, ?
                  FROM (SELECT gen_random_uuid() AS id, ordinal
                          FROM generate_series(1, ?) ordinal) identity
                """, fixture.tenantId(), fixture.projectId(), fixture.accountId(),
                fixture.accountId(), count);
        owner.update("""
                INSERT INTO app_application_draft(
                    application_id, tenant_id, project_id, content, revision, updated_by)
                SELECT id, tenant_id, project_id,
                       jsonb_build_object('formatVersion', 'tc.application/v1', 'id', id::text),
                       0, ?
                  FROM app_application
                 WHERE tenant_id = ? AND project_id = ?
                """, fixture.accountId(), fixture.tenantId(), fixture.projectId());
        owner.update("""
                INSERT INTO app_application_version(
                    id, tenant_id, project_id, application_id, version_number,
                    source_draft_revision, snapshot, snapshot_digest_algorithm,
                    snapshot_digest, published_by_account_id, published_at)
                SELECT gen_random_uuid(), tenant_id, project_id, id, 1, 0,
                       jsonb_build_object('formatVersion', 'tc.application/v1', 'id', id::text),
                       'PG_JSONB_TEXT_V1_SHA256', repeat('b', 64), ?, now()
                  FROM app_application
                 WHERE tenant_id = ? AND project_id = ?
                """, fixture.accountId(), fixture.tenantId(), fixture.projectId());
        owner.update("""
                UPDATE app_application application
                   SET current_version_id = version.id, publication_revision = 1
                  FROM app_application_version version
                 WHERE application.tenant_id = ? AND application.project_id = ?
                   AND version.tenant_id = application.tenant_id
                   AND version.project_id = application.project_id
                   AND version.application_id = application.id
                """, fixture.tenantId(), fixture.projectId());
    }

    /**
     * 创建一套看板、草稿、版本及合法当前指针。
     *
     * @param fixture 归属夹具。
     * @param discriminator 同项目内名称区分值。
     * @return 看板与版本身份。
     */
    private DashboardFact seedDashboard(Fixture fixture, int discriminator) {
        UUID dashboardId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        owner.update("""
                INSERT INTO dash_dashboard(
                    id, tenant_id, project_id, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, dashboardId, fixture.tenantId(), fixture.projectId(),
                "看板" + discriminator, fixture.accountId(), fixture.accountId());
        owner.update("""
                INSERT INTO dash_dashboard_draft(
                    dashboard_id, tenant_id, project_id, content, revision, updated_by)
                VALUES (?, ?, ?, ?::jsonb, 0, ?)
                """, dashboardId, fixture.tenantId(), fixture.projectId(),
                dashboardJson(discriminator), fixture.accountId());
        owner.update("""
                INSERT INTO dash_dashboard_version(
                    id, tenant_id, project_id, dashboard_id, version_number,
                    source_draft_revision, schema, schema_version, schema_digest_algorithm,
                    schema_digest, required_components, required_resources,
                    published_by_account_id, published_at)
                VALUES (?, ?, ?, ?, 1, 0, ?::jsonb, 'tc.dashboard/v1',
                        'PG_JSONB_TEXT_V1_SHA256', ?, '[]'::jsonb, '[]'::jsonb, ?, now())
                """, versionId, fixture.tenantId(), fixture.projectId(), dashboardId,
                dashboardJson(discriminator), "c".repeat(64), fixture.accountId());
        owner.update("""
                UPDATE dash_dashboard
                   SET current_version_id = ?, publication_revision = 1
                 WHERE id = ?
                """, versionId, dashboardId);
        return new DashboardFact(dashboardId, versionId);
    }

    /**
     * 以集合SQL创建清理所需的看板、草稿、版本与指针，避免501行逐条往返。
     *
     * @param fixture 目标项目。
     * @param count 看板数量。
     */
    private void seedDashboards(Fixture fixture, int count) {
        owner.update("""
                INSERT INTO dash_dashboard(
                    id, tenant_id, project_id, management_name, created_by, updated_by)
                SELECT identity.id, ?, ?, '批量看板' || identity.ordinal, ?, ?
                  FROM (SELECT gen_random_uuid() AS id, ordinal
                          FROM generate_series(1, ?) ordinal) identity
                """, fixture.tenantId(), fixture.projectId(), fixture.accountId(),
                fixture.accountId(), count);
        owner.update("""
                INSERT INTO dash_dashboard_draft(
                    dashboard_id, tenant_id, project_id, content, revision, updated_by)
                SELECT id, tenant_id, project_id,
                       jsonb_build_object('schemaVersion', 'tc.dashboard/v1', 'id', id::text),
                       0, ?
                  FROM dash_dashboard
                 WHERE tenant_id = ? AND project_id = ?
                """, fixture.accountId(), fixture.tenantId(), fixture.projectId());
        owner.update("""
                INSERT INTO dash_dashboard_version(
                    id, tenant_id, project_id, dashboard_id, version_number,
                    source_draft_revision, schema, schema_version, schema_digest_algorithm,
                    schema_digest, required_components, required_resources,
                    published_by_account_id, published_at)
                SELECT gen_random_uuid(), tenant_id, project_id, id, 1, 0,
                       jsonb_build_object('schemaVersion', 'tc.dashboard/v1', 'id', id::text),
                       'tc.dashboard/v1', 'PG_JSONB_TEXT_V1_SHA256', repeat('d', 64),
                       '[]'::jsonb, '[]'::jsonb, ?, now()
                  FROM dash_dashboard
                 WHERE tenant_id = ? AND project_id = ?
                """, fixture.accountId(), fixture.tenantId(), fixture.projectId());
        owner.update("""
                UPDATE dash_dashboard dashboard
                   SET current_version_id = version.id, publication_revision = 1
                  FROM dash_dashboard_version version
                 WHERE dashboard.tenant_id = ? AND dashboard.project_id = ?
                   AND version.tenant_id = dashboard.tenant_id
                   AND version.project_id = dashboard.project_id
                   AND version.dashboard_id = dashboard.id
                """, fixture.tenantId(), fixture.projectId());
    }

    /**
     * 创建一个没有设备绑定的不可变物模型版本，使父删除反例只受本片关系影响。
     *
     * @param fixture 归属项目。
     * @param discriminator 同项目内类型键区分值。
     * @return 类型及模型版本身份。
     */
    private ModelFact seedModel(Fixture fixture, int discriminator) {
        UUID typeId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_type(
                    id, tenant_id, project_id, type_key, name, device_kind,
                    access_protocol, network_type, status)
                VALUES (?, ?, ?, ?, ?, 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                """, typeId, fixture.tenantId(), fixture.projectId(),
                "dashboard_model_" + discriminator, "看板模型" + discriminator);
        owner.update("""
                INSERT INTO dev_thing_model_version(
                    id, tenant_id, project_id, device_type_id, version_number,
                    version_major, version_minor, version_patch, change_level,
                    schema_profile, model_snapshot, schema_digest, digest_algorithm)
                VALUES (?, ?, ?, ?, '1.0.0', 1, 0, 0, 'MAJOR',
                        'TC_PROPERTY_COMPOSITE_V1',
                        '{"properties":{},"events":{},"commands":{}}'::jsonb,
                        ?, 'PG_JSONB_TEXT_V1_SHA256')
                """, versionId, fixture.tenantId(), fixture.projectId(), typeId,
                Integer.toHexString(discriminator).substring(0, 1).repeat(64));
        return new ModelFact(typeId, versionId);
    }

    /**
     * 创建一套应用、看板、模型及三条合法精确关系。
     *
     * @param fixture 归属项目。
     * @param discriminator 同项目内夹具区分值。
     * @return 完整关系图身份。
     */
    private ReferenceGraph seedReferenceGraph(Fixture fixture, int discriminator) {
        ApplicationFact applicationFact = seedApplication(fixture, discriminator);
        DashboardFact dashboardFact = seedDashboard(fixture, discriminator);
        ModelFact modelFact = seedModel(fixture, discriminator);
        insertDraftModelReference(fixture, dashboardFact.dashboardId(), 0,
                "model_" + discriminator, modelFact.versionId());
        insertVersionModelReference(fixture, dashboardFact, 0,
                "model_" + discriminator, modelFact.versionId());
        insertApplicationDashboardReference(fixture, applicationFact, 0, dashboardFact);
        return new ReferenceGraph(applicationFact, dashboardFact, modelFact);
    }

    /**
     * 为批量应用和看板各建立一条关系，并共享同项目模型父版本以形成三类精确501行清理事实。
     *
     * @param fixture 批量事实归属项目。
     */
    private void seedBatchReferences(Fixture fixture) {
        ModelFact modelFact = seedModel(fixture, 1);
        owner.update("""
                WITH applications AS (
                    SELECT id AS application_id, current_version_id AS application_version_id,
                           row_number() OVER (ORDER BY id) AS ordinal
                      FROM app_application
                     WHERE tenant_id = ? AND project_id = ?
                ), dashboards AS (
                    SELECT id AS dashboard_id, current_version_id AS dashboard_version_id,
                           row_number() OVER (ORDER BY id) AS ordinal
                      FROM dash_dashboard
                     WHERE tenant_id = ? AND project_id = ?
                )
                INSERT INTO app_application_version_dashboard_ref(
                    tenant_id, project_id, application_id, application_version_id,
                    position, dashboard_id, dashboard_version_id)
                SELECT ?, ?, applications.application_id, applications.application_version_id,
                       0, dashboards.dashboard_id, dashboards.dashboard_version_id
                  FROM applications JOIN dashboards USING (ordinal)
                """, fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId());
        owner.update("""
                INSERT INTO dash_dashboard_draft_model_ref(
                    tenant_id, project_id, dashboard_id, position, model_key, thing_model_version_id)
                SELECT tenant_id, project_id, dashboard_id, 0, 'batch_model', ?
                  FROM dash_dashboard_draft
                 WHERE tenant_id = ? AND project_id = ?
                """, modelFact.versionId(), fixture.tenantId(), fixture.projectId());
        owner.update("""
                INSERT INTO dash_dashboard_version_model_ref(
                    tenant_id, project_id, dashboard_id, dashboard_version_id,
                    position, model_key, thing_model_version_id)
                SELECT tenant_id, project_id, dashboard_id, id, 0, 'batch_model', ?
                  FROM dash_dashboard_version
                 WHERE tenant_id = ? AND project_id = ?
                """, modelFact.versionId(), fixture.tenantId(), fixture.projectId());
    }

    /**
     * 插入草稿到模型版本的精确关系。
     *
     * @param fixture 关系归属项目。
     * @param dashboardId 草稿所属看板。
     * @param position models数组位置。
     * @param modelKey Schema模型别名。
     * @param modelVersionId 物模型版本。
     */
    private void insertDraftModelReference(
            Fixture fixture, UUID dashboardId, int position, String modelKey, UUID modelVersionId) {
        insertDraftModelReference(owner, fixture, dashboardId, position, modelKey, modelVersionId);
    }

    /**
     * 通过指定连接插入草稿模型关系，用于同时验证owner约束与运行角色RLS。
     *
     * @param jdbc 被测数据库身份。
     * @param fixture 关系字段归属项目。
     * @param dashboardId 草稿所属看板。
     * @param position models数组位置。
     * @param modelKey Schema模型别名。
     * @param modelVersionId 物模型版本。
     */
    private void insertDraftModelReference(
            JdbcTemplate jdbc, Fixture fixture, UUID dashboardId, int position,
            String modelKey, UUID modelVersionId) {
        jdbc.update("""
                INSERT INTO dash_dashboard_draft_model_ref(
                    tenant_id, project_id, dashboard_id, position, model_key, thing_model_version_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, fixture.tenantId(), fixture.projectId(), dashboardId,
                position, modelKey, modelVersionId);
    }

    /**
     * 插入不可变看板版本到模型版本的精确关系。
     *
     * @param fixture 关系归属项目。
     * @param dashboard 看板及版本身份。
     * @param position models数组位置。
     * @param modelKey Schema模型别名。
     * @param modelVersionId 物模型版本。
     */
    private void insertVersionModelReference(
            Fixture fixture, DashboardFact dashboard, int position,
            String modelKey, UUID modelVersionId) {
        insertVersionModelReference(owner, fixture, dashboard, position, modelKey, modelVersionId);
    }

    /**
     * 通过指定连接插入发布模型关系，用于验证运行角色RLS。
     *
     * @param jdbc 被测数据库身份。
     * @param fixture 关系字段归属项目。
     * @param dashboard 看板及版本身份。
     * @param position models数组位置。
     * @param modelKey Schema模型别名。
     * @param modelVersionId 物模型版本。
     */
    private void insertVersionModelReference(
            JdbcTemplate jdbc, Fixture fixture, DashboardFact dashboard, int position,
            String modelKey, UUID modelVersionId) {
        jdbc.update("""
                INSERT INTO dash_dashboard_version_model_ref(
                    tenant_id, project_id, dashboard_id, dashboard_version_id,
                    position, model_key, thing_model_version_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, fixture.tenantId(), fixture.projectId(), dashboard.dashboardId(),
                dashboard.versionId(), position, modelKey, modelVersionId);
    }

    /**
     * 插入不可变应用版本到不可变看板版本的精确关系。
     *
     * @param fixture 关系归属项目。
     * @param applicationFact 应用及版本身份。
     * @param position dashboardRefs导航位置。
     * @param dashboardFact 看板及版本身份。
     */
    private void insertApplicationDashboardReference(
            Fixture fixture, ApplicationFact applicationFact, int position, DashboardFact dashboardFact) {
        insertApplicationDashboardReference(owner, fixture, applicationFact, position, dashboardFact);
    }

    /**
     * 通过指定连接插入应用版本关系，用于验证运行角色RLS。
     *
     * @param jdbc 被测数据库身份。
     * @param fixture 关系字段归属项目。
     * @param applicationFact 应用及版本身份。
     * @param position dashboardRefs导航位置。
     * @param dashboardFact 看板及版本身份。
     */
    private void insertApplicationDashboardReference(
            JdbcTemplate jdbc, Fixture fixture, ApplicationFact applicationFact,
            int position, DashboardFact dashboardFact) {
        jdbc.update("""
                INSERT INTO app_application_version_dashboard_ref(
                    tenant_id, project_id, application_id, application_version_id,
                    position, dashboard_id, dashboard_version_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, fixture.tenantId(), fixture.projectId(), applicationFact.applicationId(),
                applicationFact.versionId(), position, dashboardFact.dashboardId(), dashboardFact.versionId());
    }

    /**
     * 插入一条指定字节数、组件数与资源数的看板版本，用真实CHECK裁决所有边界。
     *
     * @param fixture 归属项目。
     * @param dashboardId 归属看板。
     * @param versionNumber 看板内版本号。
     * @param schemaBytes 持久JSONB文本目标字节数。
     * @param componentCount 组件派生数组长度。
     * @param resourceCount 资源派生数组长度。
     * @param schemaVersion 独立Schema版本字段。
     * @param digestAlgorithm 摘要算法。
     * @param digest 摘要文本。
     */
    private void insertDashboardVersion(
            Fixture fixture, UUID dashboardId, long versionNumber, int schemaBytes,
            int componentCount, int resourceCount, String schemaVersion,
            String digestAlgorithm, String digest) {
        owner.update("""
                INSERT INTO dash_dashboard_version(
                    id, tenant_id, project_id, dashboard_id, version_number,
                    source_draft_revision, schema, schema_version, schema_digest_algorithm,
                    schema_digest, required_components, required_resources,
                    published_by_account_id, published_at)
                VALUES (?, ?, ?, ?, ?, 0,
                        jsonb_build_object(
                            'schemaVersion', 'tc.dashboard/v1',
                            'pad', repeat('a', ? - octet_length(jsonb_build_object(
                                'schemaVersion', 'tc.dashboard/v1', 'pad', '')::text))),
                        ?, ?, ?,
                        to_jsonb(ARRAY(SELECT 'component-' || ordinal
                                         FROM generate_series(1, ?) ordinal)),
                        to_jsonb(ARRAY(SELECT 'resource-' || ordinal
                                         FROM generate_series(1, ?) ordinal)),
                        ?, now())
                """, UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), dashboardId,
                versionNumber, schemaBytes, schemaVersion, digestAlgorithm, digest,
                componentCount, resourceCount, fixture.accountId());
    }

    /**
     * 在真实应用事务中建立双轴上下文。
     *
     * @param fixture 可信项目范围。
     * @param action 同事务动作。
     * @param <T> 动作结果类型。
     * @return 动作结果。
     */
    private <T> T withScope(Fixture fixture, Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> {
            application.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, fixture.tenantId().toString());
            application.queryForObject("SELECT set_config('app.project_id', ?, true)",
                    String.class, fixture.projectId().toString());
            return action.get();
        });
    }

    /**
     * 执行一次固定DASHBOARD阶段的数据库清理函数。
     *
     * @param fixture 清理目标及能力身份。
     * @param token 调用令牌。
     * @return 函数返回的批次结果。
     */
    private CleanupResult cleanup(Fixture fixture, UUID token) {
        return cleanup(fixture, 1L, token);
    }

    /**
     * 用显式代次执行一次固定DASHBOARD阶段的数据库清理函数，用于验证代次不可被忽略。
     *
     * @param fixture 清理目标及能力身份。
     * @param generation 调用方持有的项目生命周期代次。
     * @param token 调用令牌。
     * @return 函数返回的批次结果。
     */
    private CleanupResult cleanup(Fixture fixture, long generation, UUID token) {
        return application.queryForObject("""
                SELECT deleted_rows, complete, blocked_reason
                  FROM dashboard_project_cleanup_batch(?, ?, ?, ?)
                """, (resultSet, rowNumber) -> new CleanupResult(
                resultSet.getInt("deleted_rows"), resultSet.getBoolean("complete"),
                resultSet.getString("blocked_reason")),
                fixture.tenantId(), fixture.projectId(), generation, token);
    }

    /**
     * 调用DEVICE阶段受控清理，验证看板模型关系仍是父版本删除的真实外域入边。
     *
     * @param fixture DEVICE阶段项目与能力身份。
     * @return 设备领域清理结果。
     */
    private CleanupResult deviceCleanup(Fixture fixture) {
        return application.queryForObject("""
                SELECT deleted_rows, complete, blocked_reason
                  FROM dev_project_cleanup_batch(?, ?, ?, ?)
                """, (resultSet, rowNumber) -> new CleanupResult(
                resultSet.getInt("deleted_rows"), resultSet.getBoolean("complete"),
                resultSet.getString("blocked_reason")),
                fixture.tenantId(), fixture.projectId(), 1L, fixture.cleanupToken());
    }

    /**
     * 统计一个项目在指定固定表中的行数；表名只来自测试源码常量。
     *
     * @param table 应用、看板或关系九张持久事实表之一。
     * @param fixture 项目归属。
     * @return 行数。
     */
    private long count(String table, Fixture fixture) {
        return owner.queryForObject("SELECT count(*) FROM " + table
                        + " WHERE tenant_id = ? AND project_id = ?", Long.class,
                fixture.tenantId(), fixture.projectId());
    }

    /**
     * 读取三表完整JSON快照，用于证明失败或邻居保护不是只保住了行数。
     *
     * @param fixture 项目归属。
     * @return 稳定排序后的三表快照。
     */
    private String projectApplicationSnapshot(Fixture fixture) {
        return owner.queryForObject("""
                SELECT jsonb_build_object(
                    'applications', (SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY id), '[]'::jsonb)
                                       FROM app_application a
                                      WHERE tenant_id = ? AND project_id = ?),
                    'drafts', (SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY application_id), '[]'::jsonb)
                                FROM app_application_draft d
                               WHERE tenant_id = ? AND project_id = ?),
                    'versions', (SELECT coalesce(jsonb_agg(to_jsonb(v) ORDER BY id), '[]'::jsonb)
                                  FROM app_application_version v
                                 WHERE tenant_id = ? AND project_id = ?))::text
                """, String.class,
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId());
    }

    /**
     * 读取三张看板表的完整JSON快照，用于证明权限拒绝没有修改任何字段。
     *
     * @param fixture 项目归属。
     * @return 稳定排序后的三表快照。
     */
    private String projectDashboardSnapshot(Fixture fixture) {
        return owner.queryForObject("""
                SELECT jsonb_build_object(
                    'dashboards', (SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY id), '[]'::jsonb)
                                     FROM dash_dashboard d
                                    WHERE tenant_id = ? AND project_id = ?),
                    'drafts', (SELECT coalesce(jsonb_agg(to_jsonb(dd) ORDER BY dashboard_id), '[]'::jsonb)
                                FROM dash_dashboard_draft dd
                               WHERE tenant_id = ? AND project_id = ?),
                    'versions', (SELECT coalesce(jsonb_agg(to_jsonb(dv) ORDER BY id), '[]'::jsonb)
                                  FROM dash_dashboard_version dv
                                 WHERE tenant_id = ? AND project_id = ?))::text
                """, String.class,
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId());
    }

    /**
     * 读取三张精确关系表的完整JSON快照，证明拒绝、回滚与邻居保护覆盖关系内容而非仅行数。
     *
     * @param fixture 项目归属。
     * @return 稳定排序后的三表关系快照。
     */
    private String projectReferenceSnapshot(Fixture fixture) {
        return owner.queryForObject("""
                SELECT jsonb_build_object(
                    'applicationDashboardRefs', (
                        SELECT coalesce(jsonb_agg(to_jsonb(reference)
                                   ORDER BY application_id, application_version_id, position), '[]'::jsonb)
                          FROM app_application_version_dashboard_ref reference
                         WHERE tenant_id = ? AND project_id = ?),
                    'draftModelRefs', (
                        SELECT coalesce(jsonb_agg(to_jsonb(reference)
                                   ORDER BY dashboard_id, position), '[]'::jsonb)
                          FROM dash_dashboard_draft_model_ref reference
                         WHERE tenant_id = ? AND project_id = ?),
                    'versionModelRefs', (
                        SELECT coalesce(jsonb_agg(to_jsonb(reference)
                                   ORDER BY dashboard_id, dashboard_version_id, position), '[]'::jsonb)
                          FROM dash_dashboard_version_model_ref reference
                         WHERE tenant_id = ? AND project_id = ?))::text
                """, String.class,
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId());
    }

    /**
     * 合并应用、看板与关系九表完整快照，拒绝、回滚和邻居保护不能只比较行数。
     *
     * @param fixture 项目归属。
     * @return 九表稳定快照。
     */
    private String projectPersistenceSnapshot(Fixture fixture) {
        return projectApplicationSnapshot(fixture)
                + projectDashboardSnapshot(fixture)
                + projectReferenceSnapshot(fixture);
    }

    /**
     * 读取九表行数及两类已停止目录数，用于证明清理严格按指针、关系及父事实推进。
     *
     * @param fixture 目标项目。
     * @return 当前清理层级快照。
     */
    private CleanupCounts cleanupCounts(Fixture fixture) {
        return owner.queryForObject("""
                SELECT
                    (SELECT count(*) FROM app_application
                      WHERE tenant_id = ? AND project_id = ?) AS applications,
                    (SELECT count(*) FROM app_application_draft
                      WHERE tenant_id = ? AND project_id = ?) AS application_drafts,
                    (SELECT count(*) FROM app_application_version_dashboard_ref
                      WHERE tenant_id = ? AND project_id = ?) AS application_dashboard_refs,
                    (SELECT count(*) FROM app_application_version
                      WHERE tenant_id = ? AND project_id = ?) AS application_versions,
                    (SELECT count(*) FROM app_application
                      WHERE tenant_id = ? AND project_id = ?
                        AND deleted_at IS NOT NULL AND current_version_id IS NULL) AS stopped_applications,
                    (SELECT count(*) FROM dash_dashboard
                      WHERE tenant_id = ? AND project_id = ?) AS dashboards,
                    (SELECT count(*) FROM dash_dashboard_draft_model_ref
                      WHERE tenant_id = ? AND project_id = ?) AS dashboard_draft_model_refs,
                    (SELECT count(*) FROM dash_dashboard_draft
                      WHERE tenant_id = ? AND project_id = ?) AS dashboard_drafts,
                    (SELECT count(*) FROM dash_dashboard_version_model_ref
                      WHERE tenant_id = ? AND project_id = ?) AS dashboard_version_model_refs,
                    (SELECT count(*) FROM dash_dashboard_version
                      WHERE tenant_id = ? AND project_id = ?) AS dashboard_versions,
                    (SELECT count(*) FROM dash_dashboard
                      WHERE tenant_id = ? AND project_id = ?
                        AND deleted_at IS NOT NULL AND current_version_id IS NULL) AS stopped_dashboards
                """, (resultSet, rowNumber) -> new CleanupCounts(
                resultSet.getLong("applications"), resultSet.getLong("application_drafts"),
                resultSet.getLong("application_dashboard_refs"),
                resultSet.getLong("application_versions"), resultSet.getLong("stopped_applications"),
                resultSet.getLong("dashboards"), resultSet.getLong("dashboard_draft_model_refs"),
                resultSet.getLong("dashboard_drafts"), resultSet.getLong("dashboard_version_model_refs"),
                resultSet.getLong("dashboard_versions"), resultSet.getLong("stopped_dashboards")),
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId(), fixture.tenantId(), fixture.projectId(),
                fixture.tenantId(), fixture.projectId());
    }

    /**
     * 在应用身份与正确RLS范围中断言确切SQLSTATE。
     *
     * @param fixture 运行范围。
     * @param action 被拒绝的SQL。
     * @param state 预期SQLSTATE。
     */
    private void assertRuntimeSqlState(Fixture fixture, Runnable action, String state) {
        assertSqlState(() -> withScope(fixture, () -> {
            action.run();
            return null;
        }), state);
    }

    /**
     * 断言数据库失败的根因SQLSTATE，避免只按Spring异常大类误认真实约束来源。
     *
     * @param action 被拒绝的SQL。
     * @param state 预期SQLSTATE。
     */
    private void assertSqlState(Runnable action, String state) {
        assertThatThrownBy(action::run)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(state);
    }

    /**
     * 生成符合冻结格式的公开应用定位符。
     *
     * @param id 随机应用身份。
     * @return {@code app_}加32位小写十六进制。
     */
    private static String appKey(UUID id) {
        return "app_" + compact(id);
    }

    /**
     * 移除UUID分隔符，作为测试定位符的32位小写十六进制部分。
     *
     * @param id UUID。
     * @return 无连字符文本。
     */
    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    /**
     * 返回满足首版格式约束的最小声明式内容。
     *
     * @param discriminator 测试内容区分值。
     * @return JSON原文。
     */
    private static String applicationJson(int discriminator) {
        return "{\"formatVersion\":\"tc.application/v1\",\"value\":" + discriminator + "}";
    }

    /**
     * 返回满足首版格式约束的最小看板声明内容。
     *
     * @param discriminator 测试内容区分值。
     * @return JSON原文。
     */
    private static String dashboardJson(int discriminator) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"value\":" + discriminator + "}";
    }

    /**
     * 创建迁移owner数据源。
     *
     * @return 专库owner连接配置。
     */
    private static DriverManagerDataSource ownerDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * 创建与Bootstrap相同位置及角色占位符的Flyway实例。
     *
     * @param target 精确迁移终点。
     * @return Flyway实例。
     */
    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(MIGRATION_LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink"))
                .target(target)
                .load();
    }

    /**
     * 项目、运行账号及清理能力身份。
     *
     * @param tenantId 项目所有者租户。
     * @param projectId 项目。
     * @param accountId Console审计账号。
     * @param cleanupToken 项目清理租约令牌。
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID cleanupToken) {
    }

    /**
     * 应用及首个不可变版本身份。
     *
     * @param applicationId 应用ID。
     * @param versionId 版本ID。
     * @param appKey 公开定位符。
     */
    private record ApplicationFact(UUID applicationId, UUID versionId, String appKey) {
    }

    /**
     * 看板及首个不可变版本身份。
     *
     * @param dashboardId 看板ID。
     * @param versionId 看板版本ID。
     */
    private record DashboardFact(UUID dashboardId, UUID versionId) {
    }

    /**
     * 物模型类型及不可变版本身份。
     *
     * @param typeId 设备类型ID。
     * @param versionId 物模型版本ID。
     */
    private record ModelFact(UUID typeId, UUID versionId) {
    }

    /**
     * 一套应用、看板、模型及三类精确关系身份。
     *
     * @param application 应用及版本。
     * @param dashboard 看板及版本。
     * @param model 物模型及版本。
     */
    private record ReferenceGraph(
            ApplicationFact application, DashboardFact dashboard, ModelFact model) {
    }

    /**
     * 九表、三类关系及两类停止解析目录的清理顺序快照。
     *
     * @param applications 应用目录数。
     * @param applicationDrafts 应用草稿数。
     * @param applicationDashboardRefs 应用版本到看板版本关系数。
     * @param applicationVersions 应用版本数。
     * @param stoppedApplications 已软删并清指针的应用数。
     * @param dashboards 看板目录数。
     * @param dashboardDraftModelRefs 看板草稿模型关系数。
     * @param dashboardDrafts 看板草稿数。
     * @param dashboardVersionModelRefs 看板版本模型关系数。
     * @param dashboardVersions 看板版本数。
     * @param stoppedDashboards 已软删并清指针的看板数。
     */
    private record CleanupCounts(
            long applications,
            long applicationDrafts,
            long applicationDashboardRefs,
            long applicationVersions,
            long stoppedApplications,
            long dashboards,
            long dashboardDraftModelRefs,
            long dashboardDrafts,
            long dashboardVersionModelRefs,
            long dashboardVersions,
            long stoppedDashboards) {
    }

    /**
     * 数据库清理函数结果。
     *
     * @param deletedRows 本轮实际删除行数。
     * @param complete 本领域是否已清空。
     * @param blockedReason 未完成且无删除时的稳定阻塞原因。
     */
    private record CleanupResult(int deletedRows, boolean complete, String blockedReason) {
    }
}
