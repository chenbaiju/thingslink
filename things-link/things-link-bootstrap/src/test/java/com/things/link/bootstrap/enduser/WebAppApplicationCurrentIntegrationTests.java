package com.things.link.bootstrap.enduser;

import com.things.link.dashboard.application.ApplicationRuntimeCurrentService;
import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;
import com.things.link.dashboard.infrastructure.persistence.JdbcApplicationCurrentRepository;
import com.things.link.enduser.application.AppRuntimeIdentityService;
import com.things.link.enduser.application.CurrentWebAppApplication;
import com.things.link.enduser.application.WebAppApplicationCurrentService;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12-2a4a：非owner真实事务验证current封闭读取、精确引用、生命周期与用户grant交集。 */
@Testcontainers
class WebAppApplicationCurrentIntegrationTests {
    /** 独占真实PG；只在本库注入不可变历史损坏，不修改生产ACL或共享夹具。 */
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("application_current").withUsername("thingslink").withPassword("thingslink");
    /** 完整生产迁移的固定grant基础候选，后续HTTP不影响本持久验收。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export", "classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** 仅生成真实版本夹具，不替代生产摘要计算或运行投影。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Owner只建立父事实及观察结果，current不借owner授权。 */
    private static JdbcTemplate owner;
    /** 空session双轴的普通APP连接验证集中SET LOCAL真正生效。 */
    private JdbcTemplate jdbc;
    /** 真实Spring事务管理器，全部跨域端口共用一连接。 */
    private PlatformTransactionManager transactions;
    /** 单次运行描述端口，脱离外层事务必须拒绝。 */
    private ApplicationRuntimeCurrentService descriptor;
    /** 实际App身份授权编排。 */
    private WebAppApplicationCurrentService service;
    /** 每例唯一身份避免清理顺序干扰；容器最终统一回收。 */
    private Fixture fixture;

    /** 原生产迁移创建真实普通角色、RLS、不可变版本及grant约束。 */
    @BeforeAll static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", "thingslink"))
                .target("20260906.0260").load().migrate();
    }

    /** 每例新APP数据源和唯一租户事实，不保留ThreadLocal或缓存授权。 */
    @BeforeEach void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        wire(jdbc);
        fixture = seed();
    }

    /** 只在descriptor使用可观察JDBC，身份与grant仍使用同物理事务数据源。 */
    private void wire(JdbcTemplate descriptorJdbc) {
        descriptor = proxy(new ApplicationRuntimeCurrentService(new JdbcApplicationCurrentRepository(descriptorJdbc)),
                ApplicationRuntimeCurrentService.class);
        service = proxy(new WebAppApplicationCurrentService(
                proxy(new AppRuntimeIdentityService(new TransactionLocalRlsScope(jdbc),
                        new JdbcAppUserRepository(jdbc), new JdbcAppUserRoleRepository(jdbc),
                        proxy(new ProjectLifecycleAccessService(new JdbcProjectRepository(jdbc)), ProjectLifecycleAccessService.class)),
                        AppRuntimeIdentityService.class),
                descriptor, new JdbcAppUserDashboardGrantRepository(jdbc)), WebAppApplicationCurrentService.class);
    }

    /** 无scope普通读为空、current原事务内读取成功且事务结束不遗留RLS范围。 */
    @Test void establishesScopeOnlyInsideOriginalAppTransaction() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_application", Long.class)).isZero();
        CurrentWebAppApplication current = current();
        assertThat(current.appUserId()).isEqualTo(fixture.user());
        assertThat(current.applicationId()).isEqualTo(fixture.app());
        assertThat(current.publicationRevision()).isEqualTo(1);
        assertThat(current.dashboards()).extracting(ApplicationPublishedDashboardReference::dashboardId)
                .containsExactly(fixture.second().id());
        assertThat(current.entryDashboardId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_application", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_dashboard", Long.class)).isZero();
        assertThatThrownBy(() -> descriptor.findCurrent(fixture.tenant(), fixture.project(), fixture.key()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 普通RLS端口没有公开定位函数的豁免能力：空scope及错项目均为空，只有显式合法二轴可见。 */
    @Test void currentDescriptorRespectsOriginalConnectionRls() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        var unscoped = tx.execute(status -> descriptor.findCurrent(fixture.tenant(), fixture.project(), fixture.key()));
        assertThat(unscoped).isEmpty();
        var wrongProject = tx.execute(status -> {
            new TransactionLocalRlsScope(jdbc).establish(fixture.tenant(), UUID.randomUUID());
            return descriptor.findCurrent(fixture.tenant(), fixture.project(), fixture.key());
        });
        assertThat(wrongProject).isEmpty();
        var scoped = tx.execute(status -> {
            new TransactionLocalRlsScope(jdbc).establish(fixture.tenant(), fixture.project());
            return descriptor.findCurrent(fixture.tenant(), fixture.project(), fixture.key());
        });
        assertThat(scoped).isPresent();
    }

    /** ACTIVE grant按应用顺序过滤；入口不可见不改选，恢复入口后保留原顺序。 */
    @Test void intersectsOnlyRequestedUserAndKeepsApplicationOrder() {
        grant(fixture, fixture.first(), fixture.user());
        CurrentWebAppApplication current = current();
        assertThat(current.entryDashboardId()).isEqualTo(fixture.first().id());
        assertThat(current.dashboards()).extracting(ApplicationPublishedDashboardReference::dashboardId)
                .containsExactly(fixture.first().id(), fixture.second().id());
        owner.update("UPDATE app_user_dashboard SET status='REVOKED',revision=2,updated_at=now(),revoked_at=now(),revoked_by=? WHERE app_user_id=?",
                fixture.actor(), fixture.user());
        unavailable(this::current);
        UUID neighbor = UUID.randomUUID();
        user(fixture, neighbor);
        grant(fixture, fixture.second(), neighbor);
        unavailable(this::current);
        assertThat(service.current(fixture.tenant(), fixture.project(), neighbor, 0, fixture.key()).dashboards()).hasSize(1);
    }

    /** 看板独立V2不偷换应用V1；撤回暂不可见，重发仍恢复精确旧V1，软删永久隐藏。 */
    @Test void dashboardLifecycleNeverReplacesApplicationExactVersion() {
        Board board = fixture.second();
        UUID newer = dashboardVersion(fixture, board.id(), 2, "新版导航");
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=2 WHERE id=?", newer, board.id());
        assertThat(current().dashboards().getFirst().dashboardVersionId()).isEqualTo(board.version());
        assertThat(current().dashboards().getFirst().title()).isEqualTo("看板二");
        owner.update("UPDATE dash_dashboard SET current_version_id=NULL,publication_revision=3 WHERE id=?", board.id());
        unavailable(this::current);
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=4 WHERE id=?", newer, board.id());
        assertThat(current().dashboards().getFirst().dashboardVersionId()).isEqualTo(board.version());
        owner.update("UPDATE dash_dashboard SET deleted_at=now(),current_version_id=NULL,publication_revision=5 WHERE id=?", board.id());
        unavailable(this::current);
    }

    /** 应用A→B→A的当前代次不倒退；撤回和软删后精确历史仍不可运行。 */
    @Test void applicationPublicationRevisionTracksCurrentObservation() {
        UUID newer = applicationVersion(fixture, 2, "应用B", List.of(fixture.second()), false, true);
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", newer, fixture.app());
        assertThat(current().applicationVersionId()).isEqualTo(newer);
        assertThat(current().displayName()).isEqualTo("应用B");
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=3 WHERE id=?", fixture.version(), fixture.app());
        assertThat(current().applicationVersionId()).isEqualTo(fixture.version());
        assertThat(current().publicationRevision()).isEqualTo(3);
        owner.update("UPDATE app_application SET current_version_id=NULL,publication_revision=4 WHERE id=?", fixture.app());
        unavailable(this::current);
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=5,deleted_at=now() WHERE id=?",
                fixture.version(), fixture.app());
        unavailable(this::current);
    }

    /** ARCHIVED只读可用；项目删除代次、暂停用户和禁用角色均沿身份60009而不混资源60023。 */
    @ParameterizedTest
    @ValueSource(strings = {"archived", "generation", "deleting", "locked", "disabled", "missing_user", "missing_role"})
    void checksRealIdentityAndProjectLifecycle(String state) {
        switch (state) {
            case "archived" -> owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.project());
            case "generation" -> owner.update("UPDATE sys_project SET lifecycle_generation=1 WHERE id=?", fixture.project());
            case "deleting" -> owner.update("UPDATE sys_project SET status='DELETING',deleted_at=now(),lifecycle_generation=1 WHERE id=?", fixture.project());
            case "locked" -> owner.update("UPDATE app_user SET status='LOCKED' WHERE id=?", fixture.user());
            case "disabled" -> owner.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?", fixture.user());
            case "missing_user", "missing_role" -> { /* 下方用不同真实身份区分两条失效路径。 */ }
            default -> throw new AssertionError(state);
        }
        if (state.equals("archived")) { assertThat(current().dashboards()).hasSize(1); return; }
        UUID selected = fixture.user();
        if (state.equals("missing_user") || state.equals("missing_role")) selected = UUID.randomUUID();
        if (state.equals("missing_role")) owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'test-only')",
                selected, fixture.tenant(), selected.toString());
        UUID identity = selected;
        business(() -> service.current(fixture.tenant(), fixture.project(), identity, 0, fixture.key()), 60009);
    }

    /** 完整可信双轴必须匹配；别项目应用、别用户grant及规范但不存在appKey均不可穿透。 */
    @Test void neverUsesPublicLocatorToCrossAppIdentityBoundary() {
        Fixture other = seed();
        unavailable(() -> service.current(fixture.tenant(), fixture.project(), fixture.user(), 0, other.key()));
        business(() -> service.current(other.tenant(), fixture.project(), fixture.user(), 0, fixture.key()), 60009);
        business(() -> service.current(fixture.tenant(), other.project(), fixture.user(), 0, fixture.key()), 60009);
        unavailable(() -> service.current(fixture.tenant(), fixture.project(), fixture.user(), 0, "app_" + "f".repeat(32)));
    }

    /** 摘要不符及缺精确持久关系属于内部完整性故障，不能伪装成空交集。 */
    @ParameterizedTest
    @ValueSource(strings = {"digest", "relation"})
    void doesNotHideCorruptPersistedSnapshot(String cause) {
        UUID broken = applicationVersion(fixture, 2, "损坏版本", List.of(fixture.second()),
                cause.equals("digest"), !cause.equals("relation"));
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", broken, fixture.app());
        assertThatThrownBy(this::current).isInstanceOfAny(DataAccessException.class, IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class)
                .hasMessageContaining(cause.equals("digest") ? "摘要" : "关系");
    }

    /** 摘要正确不表示快照结构正确；损坏历史必须在服务端字段/文本/范围复核中拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"extra", "title", "host"})
    void rejectsStructurallyCorruptSnapshotEvenWithMatchingDigest(String corruption) throws Exception {
        ObjectNode snapshot = (ObjectNode) JSON.readTree(owner.queryForObject(
                "SELECT snapshot::text FROM app_application_version WHERE id=?", String.class, fixture.version()));
        String message;
        switch (corruption) {
            case "extra" -> { snapshot.put("unregistered", true); message = "字段"; }
            case "title" -> { snapshot.put("displayName", "越".repeat(81)); message = "Title"; }
            case "host" -> { ((ObjectNode) snapshot.path("hostCompatibility")).put("maxExclusive", "1.0.0"); message = "半开区间"; }
            default -> throw new AssertionError(corruption);
        }
        UUID broken = UUID.randomUUID();
        owner.update("""
                INSERT INTO app_application_version(id,tenant_id,project_id,application_id,version_number,source_draft_revision,
                    snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                VALUES (?,?,?,?,2,0,?::jsonb,'PG_JSONB_TEXT_V1_SHA256',
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),?,now())
                """, broken, fixture.tenant(), fixture.project(), fixture.app(), snapshot.toString(), snapshot.toString(), fixture.actor());
        owner.update("""
                INSERT INTO app_application_version_dashboard_ref(tenant_id,project_id,application_id,application_version_id,
                    position,dashboard_id,dashboard_version_id)
                SELECT tenant_id,project_id,application_id,?,position,dashboard_id,dashboard_version_id
                  FROM app_application_version_dashboard_ref WHERE application_version_id=?
                """, broken, fixture.version());
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", broken, fixture.app());
        assertThatThrownBy(this::current).isInstanceOf(DataAccessException.class).hasMessageContaining(message);
    }

    /** descriptor实际SQL完成后发生发布，已观察A响应不能拼上B字段；下一请求读取完整B。 */
    @Test void concurrentPublicationDoesNotMixTwoApplicationVersions() throws Exception {
        UUID newer = applicationVersion(fixture, 2, "并发B", List.of(fixture.second()), false, true);
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        JdbcTemplate barrier = new JdbcTemplate(jdbc.getDataSource()) {
            /** 真SQL结果已完整取得才设屏障，不替换查询结果或制造虚假数据库事实。 */
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
                List<T> result = super.query(sql, mapper, arguments);
                if (once.compareAndSet(false, true)) {
                    observed.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("读取屏障超时");
                    } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }
                return result;
            }
        };
        wire(barrier);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reading = pool.submit(this::current);
            try {
                assertThat(observed.await(10, TimeUnit.SECONDS)).isTrue();
                owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", newer, fixture.app());
            } finally { release.countDown(); }
            CurrentWebAppApplication old = reading.get(10, TimeUnit.SECONDS);
            assertThat(old.applicationVersionId()).isEqualTo(fixture.version());
            assertThat(old.publicationRevision()).isEqualTo(1);
            assertThat(old.displayName()).isEqualTo("应用A");
            assertThat(current().applicationVersionId()).isEqualTo(newer);
            assertThat(current().displayName()).isEqualTo("并发B");
        }
    }

    /** 创建完整同域项目、App角色和两看板；仅第二项授权给当前用户。 */
    private Fixture seed() {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "app_" + compact(UUID.randomUUID()), null, null);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'current租户')", f.tenant());
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'current项目',?)", f.project(), f.tenant(), compact(f.project()));
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'test-only','发布夹具')", f.actor(), f.actor()+"@example.com");
        user(f, f.user());
        Board first = board(f, "看板一");
        Board second = board(f, "看板二");
        f = new Fixture(f.tenant(), f.project(), f.user(), f.actor(), f.app(), f.version(), f.key(), first, second);
        owner.update("INSERT INTO app_application(id,tenant_id,project_id,app_key,management_name,created_by,updated_by) VALUES (?,?,?,?,'仅管理',?,?)",
                f.app(), f.tenant(), f.project(), f.key(), f.actor(), f.actor());
        UUID version = applicationVersion(f, 1, "应用A", List.of(first, second), false, true);
        f = new Fixture(f.tenant(), f.project(), f.user(), f.actor(), f.app(), version, f.key(), first, second);
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=1 WHERE id=?", version, f.app());
        grant(f, second, f.user());
        return f;
    }

    /** 真实OBSERVER角色证明current依赖显式grant而非项目角色自动授予。 */
    private void user(Fixture f, UUID user) {
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'test-only')", user, f.tenant(), compact(user));
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'OBSERVER')",
                UUID.randomUUID(), f.tenant(), f.project(), user);
    }

    /** 固定READ持久事实；本片不复测上片管理API/CAS写入机制。 */
    private void grant(Fixture f, Board b, UUID user) {
        owner.update("INSERT INTO app_user_dashboard(id,tenant_id,project_id,app_user_id,dashboard_id,status,revision,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,'ACTIVE',1,now(),now(),?,?)",
                UUID.randomUUID(), f.tenant(), f.project(), user, b.id(), f.actor(), f.actor());
    }

    /** 创建稳定目录并指向真实不可变V1，不借D-145生产发布资格。 */
    private Board board(Fixture f, String title) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO dash_dashboard(id,tenant_id,project_id,management_name,created_by,updated_by) VALUES (?,?,?,'管理名',?,?)",
                id, f.tenant(), f.project(), f.actor(), f.actor());
        UUID version = dashboardVersion(f, id, 1, title);
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=1 WHERE id=?", version, id);
        return new Board(id, version, title);
    }

    /** PostgreSQL同时计算Schema权威摘要，Application快照随后引用相同持久元数据。 */
    private UUID dashboardVersion(Fixture f, UUID board, long number, String title) {
        UUID id = UUID.randomUUID();
        ObjectNode schema = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        schema.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        schema.putArray("models"); schema.putArray("variables");
        schema.putArray("pages").addObject().put("id", "main").put("title", title).putArray("components");
        owner.update("""
                INSERT INTO dash_dashboard_version(id,tenant_id,project_id,dashboard_id,version_number,source_draft_revision,
                    schema,schema_version,schema_digest_algorithm,schema_digest,required_components,required_resources,published_by_account_id,published_at)
                VALUES (?,?,?,?,?,0,?::jsonb,'tc.dashboard/v1','PG_JSONB_TEXT_V1_SHA256',
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'[]'::jsonb,'[]'::jsonb,?,now())
                """, id, f.tenant(), f.project(), board, number, schema.toString(), schema.toString(), f.actor());
        return id;
    }

    /** 完整快照和规范关系同时写入；独立参数只用于明确损坏反例。 */
    private UUID applicationVersion(Fixture f, long number, String title, List<Board> boards, boolean badDigest, boolean relations) {
        UUID id = UUID.randomUUID();
        ObjectNode snapshot = JSON.createObjectNode().put("formatVersion", "tc.application/v1").put("displayName", title);
        snapshot.putObject("hostCompatibility").put("minInclusive", "1.0.0").put("maxExclusive", "2.0.0");
        var refs = snapshot.putArray("dashboardRefs");
        for (Board board : boards) {
            ObjectNode ref = refs.addObject().put("dashboardId", board.id().toString()).put("dashboardVersionId", board.version().toString())
                    .put("dashboardVersionNumber", "1").put("title", board.title()).put("schemaVersion", "tc.dashboard/v1")
                    .put("schemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256")
                    .put("schemaDigest", owner.queryForObject("SELECT schema_digest FROM dash_dashboard_version WHERE id=?", String.class, board.version()));
            ref.putArray("pages").addObject().put("id", "main").put("title", board.title());
        }
        snapshot.put("entryDashboardId", boards.getFirst().id().toString());
        snapshot.putArray("requiredSchemas").add("tc.dashboard/v1");
        snapshot.putArray("requiredComponents"); snapshot.putArray("requiredResources");
        owner.update("""
                INSERT INTO app_application_version(id,tenant_id,project_id,application_id,version_number,source_draft_revision,
                    snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                VALUES (?,?,?,?,?,0,?::jsonb,'PG_JSONB_TEXT_V1_SHA256',
                    CASE WHEN ? THEN repeat('f',64) ELSE encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex') END,?,now())
                """, id, f.tenant(), f.project(), f.app(), number, snapshot.toString(), badDigest, snapshot.toString(), f.actor());
        if (relations) for (int i = 0; i < boards.size(); i++) {
            owner.update("INSERT INTO app_application_version_dashboard_ref(tenant_id,project_id,application_id,application_version_id,position,dashboard_id,dashboard_version_id) VALUES (?,?,?,?,?,?,?)",
                    f.tenant(), f.project(), f.app(), id, i, boards.get(i).id(), boards.get(i).version());
        }
        return id;
    }

    /** 当前身份参数与生产App JWT一致，但本片不引入HTTP映射。 */
    private CurrentWebAppApplication current() { return service.current(fixture.tenant(), fixture.project(), fixture.user(), 0, fixture.key()); }
    /** 资源隐藏保持统一60023，不能细分缺grant或撤回。 */
    private static void unavailable(Runnable call) { business(call, 60023); }
    /** 身份失效与资源隐藏必须有独立稳定分类。 */
    private static void business(Runnable call, int code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
    /** 规范公开key不使用阶段编号或可枚举数据库顺序。 */
    private static String compact(UUID id) { return id.toString().replace("-", ""); }
    /** 实际注解事务代理，不能通过直接调用掩盖MANDATORY配置。 */
    private <T> T proxy(T target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }
    /** @param id 稳定目录 @param version 应用精确V1 @param title 封存导航标题 */
    private record Board(UUID id, UUID version, String title) { }
    /** @param tenant 可信租户 @param project 可信项目 @param user App身份 @param actor 版本夹具Console身份
     * @param app 稳定应用 @param version 当前A @param key 公开键 @param first 原入口 @param second 显式授权看板 */
    private record Fixture(UUID tenant, UUID project, UUID user, UUID actor, UUID app, UUID version, String key, Board first, Board second) { }
}
