package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.application.ApplicationRuntimeResolutionService;
import com.things.link.dashboard.infrastructure.persistence.JdbcApplicationRuntimeRepository;
import com.things.link.enduser.application.ResolvedWebAppApplication;
import com.things.link.enduser.application.WebAppApplicationResolutionService;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectRuntimeResolutionService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * App公开resolve受限定位、事务局部RLS及跨领域application端口的真实PostgreSQL验收。
 *
 * <p>S12-2a2a只验证无HTTP内部链：普通应用账号先用固定函数取得最小可信身份，在同一物理事务连接
 * 建立项目范围，再由dashboard重验当前不可变版本并由project复核可读生命周期。成功不代表用户grant。</p>
 */
@Testcontainers
@DisplayName("App公开运行入口解析")
class ApplicationRuntimeResolutionIntegrationTests {

    /** 与生产迁移验收相同的真实PostgreSQL/TimescaleDB镜像。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("application_runtime_resolution")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** Bootstrap登记的全部迁移位置，ACL与RLS不得用测试简化表代替。 */
    private static final String[] MIGRATION_LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export",
            "classpath:db/migration/dashboard", "classpath:db/migration/ota"
    };

    /** S12-2a2a受限定位函数迁移版本。 */
    private static final String MIGRATION_TARGET = "20260906.0240";

    /** 固定规范应用键，错误路径另用相邻候选证明不可枚举。 */
    private static final String APP_KEY = "app_0123456789abcdef0123456789abcdef";

    /** 迁移owner只准备权威事实和观察ACL，不参与被测resolve调用。 */
    private static JdbcTemplate owner;

    /** 非owner生产应用角色，函数、RLS和普通查询都必须走本连接。 */
    private JdbcTemplate application;

    /** 经过真实Spring事务代理的Dashboard运行解析端口。 */
    private ApplicationRuntimeResolutionService applicationResolution;

    /** 经过真实Spring事务代理的Project运行描述端口。 */
    private ProjectRuntimeResolutionService projectResolution;

    /** 被测无HTTP跨领域resolve编排。 */
    private WebAppApplicationResolutionService resolver;

    /** 当前用例的权威租户身份。 */
    private UUID tenantId;

    /** 当前用例的权威项目身份。 */
    private UUID projectId;

    /** 当前用例的应用目录身份。 */
    private UUID applicationId;

    /** 当前用例的不可变发布版本身份。 */
    private UUID applicationVersionId;

    /** 当前用例的Console发布账号身份。 */
    private UUID accountId;

    /** 项目公开稳定键。 */
    private String projectKey;

    /** 执行完整生产迁移，确保受限函数以真实owner与应用角色ACL创建。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(MIGRATION_LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink"))
                .target(MIGRATION_TARGET)
                .load()
                .migrate();
    }

    /** 每例重建唯一发布应用和真实非owner服务链，避免生命周期变更污染其他断言。 */
    @BeforeEach
    void setUp() {
        owner.update("UPDATE app_application SET current_version_id = NULL");
        owner.update("DELETE FROM app_application_version");
        owner.update("DELETE FROM app_application_draft");
        owner.update("DELETE FROM app_application");
        owner.update("DELETE FROM sys_project_member");
        owner.update("DELETE FROM sys_project");
        owner.update("DELETE FROM sys_tenant_member");
        owner.update("DELETE FROM sys_tenant");
        owner.update("DELETE FROM sys_account");

        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        application = new JdbcTemplate(source);
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(source);

        applicationResolution = transactionalProxy(
                new ApplicationRuntimeResolutionService(new JdbcApplicationRuntimeRepository(application)),
                ApplicationRuntimeResolutionService.class, transactionManager);
        projectResolution = transactionalProxy(
                new ProjectRuntimeResolutionService(new JdbcProjectRepository(application)),
                ProjectRuntimeResolutionService.class, transactionManager);
        resolver = transactionalProxy(new WebAppApplicationResolutionService(
                        applicationResolution, new TransactionLocalRlsScope(application), projectResolution),
                WebAppApplicationResolutionService.class, transactionManager);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        applicationId = UUID.randomUUID();
        applicationVersionId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        projectKey = "runtime_" + compact(projectId);
        seedPublishedApplication("公开生产总览");
    }

    /** 函数只向应用角色开放三字段定位，且不会顺带赋予绕过RLS的表读写能力。 */
    @Test
    void restrictedFunctionHasExactAclAndMinimumProjection() {
        Map<String, Object> function = owner.queryForMap("""
                SELECT procedure.prosecdef AS security_definer,
                       procedure.provolatile = 's' AS stable,
                       array_to_string(procedure.proconfig, ',') AS configuration
                  FROM pg_proc procedure
                 WHERE procedure.oid =
                       'public.resolve_application_runtime_identity(varchar)'::regprocedure
                """);
        assertThat(function.get("security_definer")).isEqualTo(true);
        assertThat(function.get("stable")).isEqualTo(true);
        assertThat(function.get("configuration")).isEqualTo("search_path=pg_catalog, public");
        assertThat(owner.queryForObject("""
                SELECT has_function_privilege(
                           'thingslink_app',
                           'public.resolve_application_runtime_identity(varchar)',
                           'EXECUTE')
                """, Boolean.class)).isTrue();
        assertThat(owner.queryForObject("""
                SELECT count(*)
                  FROM pg_proc procedure,
                       LATERAL aclexplode(coalesce(
                           procedure.proacl, acldefault('f', procedure.proowner))) privilege
                 WHERE procedure.oid =
                       'public.resolve_application_runtime_identity(varchar)'::regprocedure
                   AND privilege.grantee = 0
                   AND privilege.privilege_type = 'EXECUTE'
                """, Long.class)).isZero();

        var locations = application.queryForList(
                "SELECT * FROM public.resolve_application_runtime_identity(?)", APP_KEY);
        assertThat(locations).hasSize(1);
        assertThat(locations.getFirst())
                .containsOnlyKeys("tenant_id", "project_id", "application_id")
                .containsEntry("tenant_id", tenantId)
                .containsEntry("project_id", projectId)
                .containsEntry("application_id", applicationId);
        assertThat(application.queryForObject(
                "SELECT count(*) FROM app_application", Long.class)).isZero();

        String neighborKey = "app_" + compact(UUID.randomUUID());
        assertThatThrownBy(() -> application.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, '越权写入', ?, ?)
                """, UUID.randomUUID(), tenantId, projectId, neighborKey, accountId, accountId))
                .isInstanceOf(DataAccessException.class);
    }

    /** 两个领域端口脱离enduser外层事务时须由真实Spring代理拒绝，不能在新事务中各自查询。 */
    @Test
    void domainResolutionPortsRequireOneOuterTransaction() {
        assertThatThrownBy(() -> applicationResolution.locateByAppKey(APP_KEY))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> projectResolution.findReadable(tenantId, projectId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 公开字段来自当前不可变版本与项目端口；管理名、草稿和内部身份都不能穿透成功结果。 */
    @Test
    void resolvesCurrentVersionDisplayNameAndReadableProject() {
        ResolvedWebAppApplication resolved = resolver.resolve(APP_KEY);

        assertThat(resolved).isEqualTo(new ResolvedWebAppApplication(
                APP_KEY, "公开生产总览", projectKey));
        assertThat(application.queryForObject(
                "SELECT count(*) FROM app_application", Long.class)).isZero();

        owner.update("UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", projectId);
        assertThat(resolver.resolve(APP_KEY).projectKey())
                .isEqualTo(projectKey);
    }

    /** 不存在、未发布、撤回、软删及项目不可读必须使用同一60023，不能暴露内部状态差异。 */
    @Test
    void lifecycleFailuresCollapseToRuntimeUnavailable() {
        assertUnavailable("app_ffffffffffffffffffffffffffffffff");

        owner.update("UPDATE app_application SET current_version_id = NULL WHERE id = ?", applicationId);
        assertUnavailable(APP_KEY);

        owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 2
                 WHERE id = ?
                """, applicationVersionId, applicationId);
        owner.update("UPDATE app_application SET deleted_at = now() WHERE id = ?", applicationId);
        assertUnavailable(APP_KEY);

        owner.update("UPDATE app_application SET deleted_at = NULL WHERE id = ?", applicationId);
        owner.update("""
                UPDATE sys_project
                   SET status = 'DELETING', deleted_at = now(), lifecycle_generation = lifecycle_generation + 1
                 WHERE id = ?
                """, projectId);
        assertUnavailable(APP_KEY);
    }

    /** 当前版本正文损坏属于服务端完整性故障，不能被统一不可见错误掩盖。 */
    @Test
    void malformedPublishedDisplayNamePreservesInfrastructureFailure() {
        // 不可变版本禁止UPDATE；先解除测试夹具指针再重建损坏历史，避免为反例绕开生产保护。
        owner.update("UPDATE app_application SET current_version_id = NULL WHERE id = ?", applicationId);
        owner.update("DELETE FROM app_application_version WHERE id = ?", applicationVersionId);
        insertPublishedVersion(42, true);
        owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 2
                 WHERE id = ?
                """, applicationVersionId, applicationId);

        assertThatThrownBy(() -> resolver.resolve(APP_KEY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("公开展示名称");
    }

    /** 合法展示字段也必须绑定完整JSONB权威摘要，摘要错配不能静默返回200。 */
    @Test
    void mismatchedPublishedSnapshotDigestPreservesInfrastructureFailure() {
        owner.update("UPDATE app_application SET current_version_id = NULL WHERE id = ?", applicationId);
        owner.update("DELETE FROM app_application_version WHERE id = ?", applicationVersionId);
        insertPublishedVersion("被篡改的公开名称", false);
        owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 2
                 WHERE id = ?
                """, applicationVersionId, applicationId);

        assertThatThrownBy(() -> resolver.resolve(APP_KEY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("快照摘要不一致");
    }

    /** 创建最小项目骨架、目录与当前不可变版本；管理名和草稿名刻意不同于发布展示名。 */
    private void seedPublishedApplication(String displayName) {
        owner.update("INSERT INTO sys_tenant(id, name) VALUES (?, '运行解析租户')", tenantId);
        owner.update("""
                INSERT INTO sys_account(id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', '运行解析发布人')
                """, accountId, accountId + "@example.com");
        owner.update("""
                INSERT INTO sys_tenant_member(id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), tenantId, accountId);
        owner.update("""
                INSERT INTO sys_project(id, tenant_id, name, region, project_key, status)
                VALUES (?, ?, '运行解析项目', 'sh-1', ?, 'ACTIVE')
                """, projectId, tenantId, projectKey);
        owner.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, '仅供Console管理', ?, ?)
                """, applicationId, tenantId, projectId, APP_KEY, accountId, accountId);
        owner.update("""
                INSERT INTO app_application_draft(
                    application_id, tenant_id, project_id, content, revision, updated_by)
                VALUES (?, ?, ?, '{"formatVersion":"tc.application/v1",\
                    "displayName":"未发布草稿名称"}'::jsonb, 0, ?)
                """, applicationId, tenantId, projectId, accountId);
        insertPublishedVersion(displayName, true);
        owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 1
                 WHERE id = ?
                """, applicationVersionId, applicationId);
    }

    /**
     * 插入字段闭合的不可变发布快照，并让摘要选择真实PG计算值或确定错值。
     *
     * @param displayName JSON展示名，可传非字符串构造持久损坏反例
     * @param validDigest 是否保存同一JSONB权威文本的真实摘要
     */
    private void insertPublishedVersion(Object displayName, boolean validDigest) {
        owner.update("""
                WITH candidate AS (
                    SELECT jsonb_build_object(
                        'formatVersion', 'tc.application/v1',
                        'displayName', ?,
                        'hostCompatibility', jsonb_build_object(
                            'minInclusive', '1.0.0', 'maxExclusive', '2.0.0'),
                        'dashboardRefs', jsonb_build_array(jsonb_build_object(
                            'dashboardId', ?, 'dashboardVersionId', ?, 'dashboardVersionNumber', '1',
                            'title', '生产总览', 'schemaVersion', 'tc.dashboard/v1',
                            'schemaDigestAlgorithm', 'PG_JSONB_TEXT_V1_SHA256',
                            'schemaDigest', repeat('c', 64),
                            'pages', jsonb_build_array(jsonb_build_object('id', 'main', 'title', '总览')))),
                        'entryDashboardId', ?,
                        'requiredSchemas', jsonb_build_array('tc.dashboard/v1'),
                        'requiredComponents', '[]'::jsonb,
                        'requiredResources', '[]'::jsonb) AS snapshot
                )
                INSERT INTO app_application_version(
                    id, tenant_id, project_id, application_id, version_number,
                    source_draft_revision, snapshot, snapshot_digest_algorithm,
                    snapshot_digest, published_by_account_id, published_at)
                SELECT ?, ?, ?, ?, 1, 0, snapshot, 'PG_JSONB_TEXT_V1_SHA256',
                       CASE WHEN ? THEN encode(digest(convert_to(snapshot::text, 'UTF8'), 'sha256'), 'hex')
                            ELSE repeat('f', 64) END,
                       ?, now()
                  FROM candidate
                """, displayName, projectId.toString(), applicationVersionId.toString(), projectId.toString(),
                applicationVersionId, tenantId, projectId, applicationId, validDigest, accountId);
    }

    /** 断言公开不可用统一折叠到60023且不携带内部状态明细。 */
    private void assertUnavailable(String appKey) {
        assertThatThrownBy(() -> resolver.resolve(appKey))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(EndUserErrorCode.APPLICATION_RUNTIME_UNAVAILABLE);
                    assertThat(failure.errorCode().code()).isEqualTo(60023);
                    assertThat(failure.details()).isEmpty();
                });
    }

    /** UUID去连接符后的32位小写十六进制，用于冻结格式的稳定项目或应用夹具键。 */
    private static String compact(UUID value) {
        return value.toString().replace("-", "");
    }

    /**
     * 为无Spring Boot上下文的专库测试应用真实注解事务拦截器。
     *
     * @param target 被代理服务
     * @param type 具体服务类型
     * @param transactionManager 应用账号数据源事务管理器
     * @return 读取生产{@code @Transactional}注解的CGLIB代理
     * @param <T> 服务类型
     */
    private static <T> T transactionalProxy(
            T target, Class<T> type, PlatformTransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }
}
