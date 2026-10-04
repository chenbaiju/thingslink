package com.things.link.bootstrap.enduser;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.dashboard.application.ApplicationRuntimeSchemaService;
import com.things.link.dashboard.application.RuntimeDashboardSchema;
import com.things.link.dashboard.infrastructure.persistence.JdbcApplicationRuntimeSchemaRepository;
import com.things.link.dashboard.application.schema.DefaultDashboardPublicationCandidateFactory;
import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardSchemaCanonicalizer;
import com.things.link.enduser.application.AppRuntimeIdentityService;
import com.things.link.enduser.application.WebAppDashboardSchemaService;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserDashboardGrantRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserRoleRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12-2a5a：独占普通APP事务验证精确Schema当前入口、授权、摘要和原文大小。 */
@Testcontainers
class WebAppDashboardSchemaIntegrationTests {
    /** 损坏夹具只存在于本例独占库，普通APP的生产ACL始终有效。 */
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_schema").withUsername("thingslink").withPassword("thingslink");
    /** 固定前置迁移，不让未来发布资格改变本读取测试的数据库事实。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export", "classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** 仅创建夹具JSON，所有摘要由真实PG计算。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** owner只造版本/观察事实，不执行运行服务。 */
    private static JdbcTemplate owner;
    /** 普通APP连接，不预设session范围。 */
    private JdbcTemplate jdbc;
    /** 跨模块真实注解代理共用事务管理器。 */
    private PlatformTransactionManager transactions;
    /** Dashboard域公开精确描述。 */
    private ApplicationRuntimeSchemaService descriptor;
    /** App每请求身份与grant编排。 */
    private WebAppDashboardSchemaService service;
    /** 每例独立租户、项目、两看板和唯一grant。 */
    private Fixture fixture;

    /** 以原生产迁移建立角色、RLS、不可变历史和grant函数。 */
    @BeforeAll static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", "thingslink"))
                .target("20260906.0260").load().migrate();
    }

    /** 每例使用新连接范围与事实，无共享表DELETE或跨测试缓存。 */
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        wire(jdbc);
        fixture = WebAppRuntimeFixture.seed(owner);
    }

    /** 单次状态查询可包真实SQL屏障，身份和摘要运算仍使用同一事务数据源。 */
    private void wire(JdbcTemplate descriptorJdbc) {
        descriptor = proxy(new ApplicationRuntimeSchemaService(new JdbcApplicationRuntimeSchemaRepository(descriptorJdbc),
                new DefaultDashboardPublicationCandidateFactory(new JacksonDashboardSchemaParser(),
                        new JdbcDashboardSchemaCanonicalizer(jdbc))),
                ApplicationRuntimeSchemaService.class);
        var identity = proxy(new AppRuntimeIdentityService(new TransactionLocalRlsScope(jdbc),
                new JdbcAppUserRepository(jdbc), new JdbcAppUserRoleRepository(jdbc),
                proxy(new ProjectLifecycleAccessService(new JdbcProjectRepository(jdbc)), ProjectLifecycleAccessService.class)),
                AppRuntimeIdentityService.class);
        service = proxy(new WebAppDashboardSchemaService(identity, descriptor,
                new JdbcAppUserDashboardGrantRepository(jdbc)), WebAppDashboardSchemaService.class);
    }

    /** 正常结果只含一份精确旧Schema，真实普通APP范围随原事务结束消失。 */
    @Test void returnsOneAuthorizedSchemaInOriginalRlsTransaction() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dash_dashboard_version", Long.class)).isZero();
        RuntimeDashboardSchema result = read();
        assertThat(result.dashboardVersionId()).isEqualTo(fixture.authorized().dashboardVersionId());
        assertThat(result.applicationVersionId()).isEqualTo(fixture.applicationVersionId());
        assertThat(result.publicationRevision()).isEqualTo(1);
        assertThat(result.schema().path("pages").get(0).path("title").asString()).isEqualTo("授权看板");
        assertThat(result.requiredComponents()).isEmpty();
        assertThat(result.requiredResources()).isEmpty();
        assertThat(result.schemaDigest()).isEqualTo(owner.queryForObject(
                "SELECT encode(digest(convert_to(schema::text,'UTF8'),'sha256'),'hex') FROM dash_dashboard_version WHERE id=?",
                String.class, fixture.authorized().dashboardVersionId()));
        ((ObjectNode) result.schema()).put("polluted", true);
        assertThat(result.schema().has("polluted")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dash_dashboard_version", Long.class)).isZero();
        assertThatThrownBy(() -> descriptor.findSchema(fixture.tenantId(), fixture.projectId(), fixture.appKey(),
                fixture.applicationVersionId(), 1, fixture.authorized().dashboardVersionId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 端口不能借公开resolve越过RLS；空范围、错项目均无描述。 */
    @Test void descriptorCannotEscapeCallerRls() {
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        var empty = tx.execute(status -> descriptor.findSchema(fixture.tenantId(), fixture.projectId(), fixture.appKey(),
                fixture.applicationVersionId(), 1, fixture.authorized().dashboardVersionId()));
        assertThat(empty).isEmpty();
        var wrong = tx.execute(status -> {
            new TransactionLocalRlsScope(jdbc).establish(fixture.tenantId(), UUID.randomUUID());
            return descriptor.findSchema(fixture.tenantId(), fixture.projectId(), fixture.appKey(),
                    fixture.applicationVersionId(), 1, fixture.authorized().dashboardVersionId());
        });
        assertThat(wrong).isEmpty();
    }

    /** 已知版本ID、非目标grant、过时代次与应用路径均不能组成读取凭据。 */
    @Test void rejectsStaleOrUnrelatedEntryContexts() {
        unavailable(() -> read(fixture.applicationVersionId(), 2, fixture.authorized().dashboardVersionId()));
        unavailable(() -> read(UUID.randomUUID(), 1, fixture.authorized().dashboardVersionId()));
        unavailable(() -> read(fixture.applicationVersionId(), 1, UUID.randomUUID()));
        unavailable(() -> read(fixture.applicationVersionId(), 1, fixture.entry().dashboardVersionId()));
        Fixture other = WebAppRuntimeFixture.seed(owner);
        unavailable(() -> service.schema(fixture.tenantId(), fixture.projectId(), fixture.appUserId(), 0,
                other.appKey(), other.applicationVersionId(), 1, other.authorized().dashboardVersionId()));
    }

    /** A到B再回A必须递增代次；同一精确versionId也不允许旧入口重放。 */
    @Test void republishingOriginalApplicationDoesNotRestoreOldRevision() {
        UUID newer = installApplication(snapshot(), false, true);
        unavailable(this::read);
        assertThat(read(newer, 2, fixture.authorized().dashboardVersionId()).publicationRevision()).isEqualTo(2);
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=3 WHERE id=?",
                fixture.applicationVersionId(), fixture.applicationId());
        unavailable(this::read);
        assertThat(read(fixture.applicationVersionId(), 3, fixture.authorized().dashboardVersionId()).publicationRevision())
                .isEqualTo(3);
    }

    /** 最终SQL完成后并发切换当前版本，已观察结果必须完整A，次请求必须使用B及新代次。 */
    @Test void concurrentPublicationCannotMixSchemaEntrySnapshots() throws Exception {
        UUID newer = installApplication(snapshot(), false, true);
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=1 WHERE id=?",
                fixture.applicationVersionId(), fixture.applicationId());
        var observed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        JdbcTemplate barrier = new JdbcTemplate(jdbc.getDataSource()) {
            /** 真SQL结果全部取得后阻塞返回，不替换事实或模拟业务描述。 */
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
                List<T> result = super.query(sql, mapper, arguments);
                if (once.compareAndSet(false, true)) {
                    observed.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Schema读取屏障超时");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return result;
            }
        };
        wire(barrier);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reading = pool.submit(() -> { return read(); });
            try {
                assertThat(observed.await(10, TimeUnit.SECONDS)).isTrue();
                owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?",
                        newer, fixture.applicationId());
            } finally { release.countDown(); }
            RuntimeDashboardSchema result = reading.get(10, TimeUnit.SECONDS);
            assertThat(result.applicationVersionId()).isEqualTo(fixture.applicationVersionId());
            assertThat(result.publicationRevision()).isEqualTo(1);
            assertThat(result.dashboardVersionId()).isEqualTo(fixture.authorized().dashboardVersionId());
        }
        unavailable(this::read);
        assertThat(read(newer, 2, fixture.authorized().dashboardVersionId()).applicationVersionId()).isEqualTo(newer);
    }

    /** 看板当前指针只提供运行资格；独立新版本不能偷换应用封存旧Schema。 */
    @Test void directoryLifecycleKeepsExactOldSchema() {
        UUID newer = installDashboard(schema(), false, "[]", "[]");
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=2 WHERE id=?",
                newer, fixture.authorized().dashboardId());
        assertThat(read().dashboardVersionId()).isEqualTo(fixture.authorized().dashboardVersionId());
        owner.update("UPDATE dash_dashboard SET current_version_id=NULL,publication_revision=3 WHERE id=?", fixture.authorized().dashboardId());
        unavailable(this::read);
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=4 WHERE id=?",
                newer, fixture.authorized().dashboardId());
        assertThat(read().dashboardVersionId()).isEqualTo(fixture.authorized().dashboardVersionId());
        owner.update("UPDATE dash_dashboard SET deleted_at=now(),current_version_id=NULL,publication_revision=5 WHERE id=?", fixture.authorized().dashboardId());
        unavailable(this::read);
    }

    /** grant撤销与实际用户/项目变化必须在下一请求生效，ARCHIVED仍只读可用。 */
    @ParameterizedTest
    @ValueSource(strings = {"revoked", "locked", "disabled", "generation", "archived", "withdrawn"})
    void rechecksGrantAndIdentityEveryTime(String change) {
        assertThat(read()).isNotNull();
        switch (change) {
            case "revoked" -> owner.update("UPDATE app_user_dashboard SET status='REVOKED',revision=2,updated_at=now(),revoked_at=now(),revoked_by=? WHERE app_user_id=?",
                    fixture.actorId(), fixture.appUserId());
            case "locked" -> owner.update("UPDATE app_user SET status='LOCKED' WHERE id=?", fixture.appUserId());
            case "disabled" -> owner.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?", fixture.appUserId());
            case "generation" -> owner.update("UPDATE sys_project SET lifecycle_generation=1 WHERE id=?", fixture.projectId());
            case "archived" -> owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            case "withdrawn" -> owner.update("UPDATE app_application SET current_version_id=NULL,publication_revision=2 WHERE id=?", fixture.applicationId());
            default -> throw new AssertionError(change);
        }
        if (change.equals("archived")) { assertThat(read()).isNotNull(); return; }
        business(this::read, change.equals("revoked") || change.equals("withdrawn") ? 60023 : 60009);
    }

    /** 当前快照摘要或缺失目标关系不能被伪装成正常资源隐藏。 */
    @ParameterizedTest
    @ValueSource(strings = {"digest", "relation", "shape"})
    void rejectsApplicationIntegrityFailures(String failure) {
        ObjectNode snapshot = snapshot();
        if (failure.equals("shape")) snapshot.put("unknown", true);
        UUID version = installApplication(snapshot, failure.equals("digest"), !failure.equals("relation"));
        assertThatThrownBy(() -> read(version, 2, fixture.authorized().dashboardVersionId()))
                .isInstanceOfAny(DataAccessException.class, IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    /** 持久摘要和派生需求不可信；目标Schema损坏须保留完整性首因。 */
    @ParameterizedTest
    @ValueSource(strings = {"digest", "components", "resources", "schema"})
    void rejectsDashboardIntegrityFailures(String failure) {
        ObjectNode schema = schema();
        if (failure.equals("schema")) schema.put("unknown", true);
        String components = failure.equals("components") ? "[{\"kind\":\"TEXT\",\"componentVersion\":\"1.0.0\"}]" : "[]";
        String resources = failure.equals("resources") ? "[{\"resourceId\":\"phantom\",\"digest\":\"" + "a".repeat(64) + "\"}]" : "[]";
        UUID dashboard = installDashboard(schema, failure.equals("digest"), components, resources);
        UUID app = pointApplicationAt(dashboard);
        assertThatThrownBy(() -> read(app, 2, dashboard)).isInstanceOfAny(DataAccessException.class, IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    /** 重复IMAGE只派生一项组件和各唯一资源，清单按机器键排序且不要求生产Host装配。 */
    @Test void derivesOnlySelectedSchemaResourcesInStableOrder() {
        ObjectNode schema = schema();
        var components = ((ObjectNode) schema.path("pages").get(0)).putArray("components");
        String[] resources = {"zeta", "alpha", "zeta"};
        for (int index = 0; index < resources.length; index++) {
            var component = components.addObject().put("id", "image_" + index).put("kind", "IMAGE").put("componentVersion", "1.0.0");
            component.putObject("layout").put("x", index).put("y", 0).put("w", 1).put("h", 1);
            component.putObject("props").put("resourceId", resources[index]).put("resourceDigest", "b".repeat(64))
                    .put("alt", "图示").put("fit", "CONTAIN");
            component.putObject("bindings");
        }
        String resourceJson = "[{\"resourceId\":\"alpha\",\"digest\":\"" + "b".repeat(64)
                + "\"},{\"resourceId\":\"zeta\",\"digest\":\"" + "b".repeat(64) + "\"}]";
        UUID dashboard = installDashboard(schema, false,
                "[{\"kind\":\"IMAGE\",\"componentVersion\":\"1.0.0\"}]", resourceJson);
        UUID app = pointApplicationAt(dashboard);
        RuntimeDashboardSchema result = read(app, 2, dashboard);
        assertThat(result.requiredComponents()).extracting(value -> value.kind()).containsExactly("IMAGE");
        assertThat(result.requiredResources()).extracting(value -> value.resourceId()).containsExactly("alpha", "zeta");
    }

    /** PostgreSQL规范文本500KiB边界精确接受，超一字节拒绝；正文不是字符数或客户端重排文本。 */
    @ParameterizedTest
    @ValueSource(ints = {512000, 512001})
    void enforcesActualPostgresqlUtf8Boundary(int bytes) {
        ObjectNode schema = largeSchema(bytes);
        assertThat(owner.queryForObject("SELECT octet_length(?::jsonb::text)", Integer.class, schema.toString()))
                .isEqualTo(bytes);
        if (bytes == 512001) {
            assertThatThrownBy(() -> installDashboard(schema, false,
                    "[{\"kind\":\"TEXT\",\"componentVersion\":\"1.0.0\"}]", "[]"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("dash_dashboard_version_schema_ck");
            return;
        }
        UUID dashboard = installDashboard(schema, false,
                "[{\"kind\":\"TEXT\",\"componentVersion\":\"1.0.0\"}]", "[]");
        UUID app = pointApplicationAt(dashboard);
        RuntimeDashboardSchema result = read(app, 2, dashboard);
        assertThat(result.schemaUtf8Bytes()).isEqualTo(512000);
        assertThat(result.schema().path("pages").get(0).path("components")).hasSize(50);
    }

    /** 默认入口始终使用最初授权看板及初始应用代次。 */
    private RuntimeDashboardSchema read() {
        return read(fixture.applicationVersionId(), 1, fixture.authorized().dashboardVersionId());
    }

    /** 向实际App服务传入可信JWT身份与请求选择的精确上下文。 */
    private RuntimeDashboardSchema read(UUID applicationVersion, long revision, UUID dashboardVersion) {
        return service.schema(fixture.tenantId(), fixture.projectId(), fixture.appUserId(), 0,
                fixture.appKey(), applicationVersion, revision, dashboardVersion);
    }

    /** 读取并复制真实基线版本Schema，后续仅创建新不可变历史。 */
    private ObjectNode schema() {
        return (ObjectNode) JSON.readTree(owner.queryForObject("SELECT schema::text FROM dash_dashboard_version WHERE id=?",
                String.class, fixture.authorized().dashboardVersionId()));
    }

    /** 读取并复制真实应用快照。 */
    private ObjectNode snapshot() {
        return (ObjectNode) JSON.readTree(owner.queryForObject("SELECT snapshot::text FROM app_application_version WHERE id=?",
                String.class, fixture.applicationVersionId()));
    }

    /** 创建看板V2，不修改旧版本/不可变触发器；损坏摘要为显式测试输入。 */
    private UUID installDashboard(ObjectNode schema, boolean badDigest, String components, String resources) {
        UUID id = UUID.randomUUID();
        String digest = badDigest ? "f".repeat(64) : owner.queryForObject(
                "SELECT encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex')", String.class, schema.toString());
        owner.update("""
                INSERT INTO dash_dashboard_version(id,tenant_id,project_id,dashboard_id,version_number,source_draft_revision,
                    schema,schema_version,schema_digest_algorithm,schema_digest,required_components,required_resources,published_by_account_id,published_at)
                VALUES (?,?,?,?,2,0,?::jsonb,'tc.dashboard/v1','PG_JSONB_TEXT_V1_SHA256',?,?::jsonb,?::jsonb,?,now())
                """, id, fixture.tenantId(), fixture.projectId(), fixture.authorized().dashboardId(), schema.toString(), digest,
                components, resources, fixture.actorId());
        return id;
    }

    /** 新应用V2指向目标看板V2，导航与真实持久摘要逐项吻合。 */
    private UUID pointApplicationAt(UUID dashboardVersion) {
        ObjectNode snapshot = snapshot();
        ObjectNode ref = (ObjectNode) snapshot.path("dashboardRefs").get(1);
        ref.put("dashboardVersionId", dashboardVersion.toString()).put("dashboardVersionNumber", "2")
                .put("schemaDigest", owner.queryForObject("SELECT schema_digest FROM dash_dashboard_version WHERE id=?",
                        String.class, dashboardVersion));
        snapshot.set("requiredComponents", JSON.readTree(owner.queryForObject(
                "SELECT required_components::text FROM dash_dashboard_version WHERE id=?", String.class, dashboardVersion)));
        snapshot.set("requiredResources", JSON.readTree(owner.queryForObject(
                "SELECT required_resources::text FROM dash_dashboard_version WHERE id=?", String.class, dashboardVersion)));
        return installApplication(snapshot, false, true);
    }

    /** 保存独立应用版本与真实精确关系并切换当前指针；关系缺失反例不删任何父事实。 */
    private UUID installApplication(ObjectNode snapshot, boolean badDigest, boolean relations) {
        UUID id = UUID.randomUUID();
        String digest = badDigest ? "e".repeat(64) : owner.queryForObject(
                "SELECT encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex')", String.class, snapshot.toString());
        owner.update("""
                INSERT INTO app_application_version(id,tenant_id,project_id,application_id,version_number,source_draft_revision,
                    snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                VALUES (?,?,?,?,2,0,?::jsonb,'PG_JSONB_TEXT_V1_SHA256',?,?,now())
                """, id, fixture.tenantId(), fixture.projectId(), fixture.applicationId(), snapshot.toString(), digest, fixture.actorId());
        if (relations) {
            int position = 0;
            for (JsonNode ref : snapshot.path("dashboardRefs")) {
                owner.update("""
                        INSERT INTO app_application_version_dashboard_ref(tenant_id,project_id,application_id,application_version_id,
                            position,dashboard_id,dashboard_version_id) VALUES (?,?,?,?,?,?,?)
                        """, fixture.tenantId(), fixture.projectId(), fixture.applicationId(), id, position++,
                        UUID.fromString(ref.path("dashboardId").asString()), UUID.fromString(ref.path("dashboardVersionId").asString()));
            }
        }
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", id, fixture.applicationId());
        return id;
    }

    /** 50个合法文本组件分行布局；通过真实PG计数精确分配UTF-8中文和ASCII，单组件不超4096码点。 */
    private ObjectNode largeSchema(int targetBytes) {
        ObjectNode schema = schema();
        ObjectNode presentation = (ObjectNode) schema.path("presentation");
        presentation.put("theme", "LIGHT").put("columns", 24).put("rowHeight", 8).put("gap", 8);
        var components = ((ObjectNode) schema.path("pages").get(0)).putArray("components");
        for (int index = 0; index < 50; index++) {
            var component = components.addObject().put("id", "text_" + index).put("kind", "TEXT").put("componentVersion", "1.0.0");
            component.putObject("layout").put("x", index % 24).put("y", index / 24).put("w", 1).put("h", 1);
            component.putObject("props").put("content", "").put("align", "LEFT").put("size", "MEDIUM").put("tone", "REGULAR");
            component.putObject("bindings");
        }
        int baseline = owner.queryForObject("SELECT octet_length(?::jsonb::text)", Integer.class, schema.toString());
        int remaining = targetBytes - baseline;
        for (JsonNode component : components) {
            int amount = Math.min(remaining, 12000);
            ((ObjectNode) component.path("props")).put("content", "文".repeat(amount / 3) + "x".repeat(amount % 3));
            remaining -= amount;
        }
        assertThat(remaining).isZero();
        return schema;
    }

    /** 真实Spring注解代理用于证明MANDATORY与原事务RLS，而不是手动直调伪装事务。 */
    private <T> T proxy(T target, Class<T> type) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    /** 普通不可运行统一隐藏。 */
    private static void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        business(action, 60023);
    }

    /** 断言稳定业务错误，数据库异常不能被转换成同类成功反例。 */
    private static void business(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
