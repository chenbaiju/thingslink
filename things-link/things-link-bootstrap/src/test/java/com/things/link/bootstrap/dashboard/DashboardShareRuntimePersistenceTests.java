package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.domain.DashboardShareCreationResult;
import com.things.link.dashboard.domain.DashboardShareRuntimeIdentity;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardShareRepository;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardShareRuntimeRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0101首次capability受限定位及后续普通RLS的真实数据库权限与范围证据。 */
@Testcontainers
class DashboardShareRuntimePersistenceTests {
    /** 独占真实PostgreSQL，不能把owner查询或共享HTTP容器当作匿名数据隔离证据。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_share_runtime_persistence").withUsername("thingslink").withPassword("thingslink");
    /** 正式十二域顺序按全局Flyway版本汇合，避免旧库跳过某领域父表。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export", "classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** 迁移owner只构造和检查fixture，不替代普通APP权限。 */ private static JdbcTemplate owner;
    /** 普通thingslink_app连接；无范围的直接表读必须不可见。 */ private static JdbcTemplate app;
    /** 普通角色独立物理事务。 */ private static TransactionTemplate transaction;
    /** 既有签发持久入口，用于准备完整封存图。 */ private static JdbcDashboardShareRepository repository;
    /** 本片匿名最小定位和普通RLS入口。 */ private static JdbcDashboardShareRuntimeRepository runtime;

    /** 从0280旧库单步升级到0290，重复启动不重复执行迁移。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260906.0280").migrate();
        assertThat(flyway("20260906.0290").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260906.0290").migrate().migrationsExecuted).isZero();
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new JdbcDashboardShareRepository(app);
        runtime = new JdbcDashboardShareRuntimeRepository(app);
    }

    /** definer权限只有APP可用，参数与返回形状精确闭合，不带secret/hash/Schema。 */
    @Test
    void locatorHasRestrictedAclAndMinimalOutputShape() {
        Fixture fixture = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "1"));
        String signature = "resolve_dashboard_share_identity(uuid,character varying)";
        assertThat(owner.queryForObject("SELECT prosecdef FROM pg_proc WHERE oid=?::regprocedure", Boolean.class, signature)).isTrue();
        assertThat(owner.queryForObject("SELECT proconfig::text FROM pg_proc WHERE oid=?::regprocedure", String.class, signature))
                .contains("search_path=pg_catalog, public");
        assertThat(owner.queryForObject("SELECT has_function_privilege('thingslink_app',?,'EXECUTE')", Boolean.class, signature)).isTrue();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_proc function_row, LATERAL aclexplode(function_row.proacl) acl
                 WHERE function_row.oid=?::regprocedure AND acl.grantee=0 AND acl.privilege_type='EXECUTE'
                """, Long.class, signature)).isZero();
        assertThat(owner.queryForObject("SELECT pronargs FROM pg_proc WHERE oid=?::regprocedure", Integer.class, signature)).isEqualTo(2);
        List<String> names = app.query("SELECT * FROM resolve_dashboard_share_identity(?,?)",
                (ResultSetExtractor<List<String>>) rows -> {
                    List<String> result = new ArrayList<>();
                    for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++) {
                        result.add(rows.getMetaData().getColumnName(index));
                    }
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("tenant_id", UUID.class)).isEqualTo(fixture.tenant());
                    assertThat(rows.getObject("project_id", UUID.class)).isEqualTo(fixture.project());
                    assertThat(rows.next()).isFalse();
                    return result;
                }, token.id(), token.secretHash());
        assertThat(names).containsExactly("tenant_id", "project_id");
    }

    /** 空或错误GUC不扩大普通表读；仅精确secret能力可越过定位例外返回最小归属。 */
    @Test
    void exactLocatorWorksWithoutScopeButOrdinaryTablesRemainHidden() {
        Fixture fixture = fixture(false);
        Fixture neighbor = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "2"));
        DashboardShareToken other = scoped(neighbor, () -> create(neighbor, "3"));
        assertThat(app.queryForObject("SELECT count(*) FROM dash_share_token", Long.class)).isZero();
        assertThat(runtime.locate(token.id(), token.secretHash())).contains(new DashboardShareRuntimeIdentity(fixture.tenant(), fixture.project()));
        assertThat(scoped(neighbor, () -> runtime.locate(token.id(), token.secretHash())))
                .contains(new DashboardShareRuntimeIdentity(fixture.tenant(), fixture.project()));
        assertThat(scoped(neighbor, () -> app.queryForObject("SELECT count(*) FROM dash_share_token WHERE id=?", Long.class, token.id()))).isZero();
        assertThat(runtime.locate(token.id(), other.secretHash())).isEmpty();
        assertThat(runtime.locate(other.id(), token.secretHash())).isEmpty();
        assertThat(runtime.locate(UUID.randomUUID(), token.secretHash())).isEmpty();
        assertThat(runtime.locate(token.id(), "")).isEmpty();
        assertThat(runtime.locate(token.id(), null)).isEmpty();
    }

    /** 未封存、撤销和到期都不能取得首次身份；检查DB时间而非缓存TTL。 */
    @Test
    void unsealedRevokedAndExpiredTokensDoNotLocate() {
        Fixture fixture = fixture(false);
        DashboardShareToken unsealed = scoped(fixture, () -> create(fixture, "4"));
        owner.update("DELETE FROM dash_share_creation_result WHERE share_id=?", unsealed.id());
        assertThat(runtime.locate(unsealed.id(), unsealed.secretHash())).isEmpty();
        DashboardShareToken revoked = scoped(fixture, () -> create(fixture, "5"));
        scoped(fixture, () -> repository.revoke(fixture.project(), fixture.dashboard(), revoked.id(), fixture.actor()));
        assertThat(runtime.locate(revoked.id(), revoked.secretHash())).isEmpty();
        // 过期fixture在首次INSERT就冻结过去的DB时刻，不绕过token不可变触发器篡改expiresAt。
        DashboardShareToken expired = scoped(fixture, () -> {
            Instant start = repository.databaseNow().minusSeconds(7200);
            DashboardShareToken value = new DashboardShareToken(UUID.randomUUID(), fixture.tenant(), fixture.project(),
                    fixture.dashboard(), fixture.version(), 0, "f".repeat(64), JsonMapper.builder().build().readTree("{}"),
                    "NONE", start, start.plusSeconds(3600), fixture.actor(), null, null);
            repository.create(value, List.of(), new DashboardShareCreationResult(fixture.tenant(), fixture.project(),
                    fixture.dashboard(), fixture.actor(), "6".repeat(64), "a".repeat(64), value.id(), start));
            return value;
        });
        assertThat(runtime.locate(expired.id(), expired.secretHash())).isEmpty();
    }

    /** 取得可信范围后普通RLS状态和scope完整返回，其他项目或错误hash仍不可读取。 */
    @Test
    void ordinaryStateAndScopesRequireMatchingProjectAndSelector() {
        Fixture fixture = fixture(false);
        Fixture neighbor = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "7"));
        var outsideScope = transaction.execute(status -> runtime.findState(
                fixture.tenant(), fixture.project(), token.id(), token.secretHash()));
        assertThat(outsideScope).isEmpty();
        scoped(fixture, () -> {
            var state = runtime.findState(fixture.tenant(), fixture.project(), token.id(), token.secretHash()).orElseThrow();
            assertThat(state.token().id()).isEqualTo(token.id());
            assertThat(state.databaseNow()).isAfterOrEqualTo(token.createdAt());
            assertThat(state.dashboardVersionNumber()).isEqualTo(1);
            assertThat(runtime.findScopes(fixture.tenant(), fixture.project(), token.id())).containsExactly(
                    new DashboardShareVariableScope("devices", fixture.model(), List.of(fixture.device())));
            assertThat(runtime.findState(fixture.tenant(), fixture.project(), token.id(), "0".repeat(64))).isEmpty();
            return null;
        });
        assertThat(scoped(neighbor, () -> runtime.findState(fixture.tenant(), fixture.project(), token.id(), token.secretHash()))).isEmpty();
        assertThat(scoped(neighbor, () -> runtime.findScopes(fixture.tenant(), fixture.project(), token.id()))).isEmpty();
    }

    /** 首次定位真正遇到数据库锁超时，不能返回空Optional而把基础设施故障伪装成未知token。 */
    @Test
    void databaseCancellationKeepsSqlCauseInsteadOfReturningUnknown() throws Exception {
        Fixture fixture = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "8"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DriverManagerDataSource ownerSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink");
        JdbcTemplate lockingOwner = new JdbcTemplate(ownerSource);
        TransactionTemplate ownerTransaction = new TransactionTemplate(new DataSourceTransactionManager(ownerSource));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var blocker = worker.submit(() -> ownerTransaction.execute(status -> {
                lockingOwner.execute("LOCK TABLE dash_share_token IN ACCESS EXCLUSIVE MODE");
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("定位锁测试放行超时");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("定位锁测试中断", interrupted);
                }
                return null;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> transaction.execute(status -> {
                    app.execute("SET LOCAL statement_timeout='250ms'");
                    return runtime.locate(token.id(), token.secretHash());
                })).isInstanceOf(DataAccessException.class).satisfies(failure -> {
                    Throwable cause = failure;
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("57014");
                });
            } finally {
                release.countDown();
            }
            blocker.get(5, TimeUnit.SECONDS);
        }
    }

    /** 构造同事务封存token与有界关系，只使用测试随机摘要。 */
    private DashboardShareToken create(Fixture fixture, String key) {
        Instant now = repository.databaseNow();
        String hash = owner.queryForObject("SELECT encode(digest(?,'sha256'),'hex')", String.class, UUID.randomUUID().toString());
        DashboardShareToken token = new DashboardShareToken(UUID.randomUUID(), fixture.tenant(), fixture.project(),
                fixture.dashboard(), fixture.version(), fixture.purging() ? 1 : 0, hash, JsonMapper.builder().build().readTree("{}"),
                "HOST_ORIGIN", now, now.plusSeconds(3600), fixture.actor(), null, null);
        repository.create(token, List.of(new DashboardShareVariableScope("devices", fixture.model(), List.of(fixture.device()))),
                new DashboardShareCreationResult(fixture.tenant(), fixture.project(), fixture.dashboard(), fixture.actor(),
                        key.repeat(64), "a".repeat(64), token.id(), now));
        return token;
    }

    /** 以owner构造独立项目/版本/模型图，普通权限测试另用APP连接。 */
    private Fixture fixture(boolean purging) {
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID dashboard = UUID.randomUUID(), version = UUID.randomUUID(), model = UUID.randomUUID();
        UUID type = UUID.randomUUID(), device = UUID.randomUUID(), lease = UUID.randomUUID();
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'test','分享测试')", actor, actor + "@share.test");
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'分享测试租户')", tenant);
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at,
                    cleanup_stage,cleanup_started_at,cleanup_next_attempt_at,cleanup_lease_token,cleanup_lease_until)
                VALUES (?,?,'分享测试项目',?,?,?,?,?,?,?,?,?)
                """, project, tenant, project.toString().replace("-", ""), purging ? "PURGING" : "ACTIVE", purging ? 1L : 0L,
                purging ? Timestamp.from(Instant.now().minusSeconds(31L * 86400)) : null, purging ? "DASHBOARD" : null,
                purging ? Timestamp.from(Instant.now().minusSeconds(60)) : null,
                purging ? Timestamp.from(Instant.now().minusSeconds(1)) : null, purging ? lease : null,
                purging ? Timestamp.from(Instant.now().plusSeconds(600)) : null);
        owner.update("INSERT INTO dash_dashboard(id,tenant_id,project_id,management_name,created_by,updated_by) VALUES (?,?,?,'分享看板',?,?)",
                dashboard, tenant, project, actor, actor);
        owner.update("""
                INSERT INTO dash_dashboard_version(id,tenant_id,project_id,dashboard_id,version_number,source_draft_revision,
                    schema,schema_version,schema_digest_algorithm,schema_digest,required_components,required_resources,
                    published_by_account_id,published_at)
                VALUES (?,?,?,?,1,0,'{"schemaVersion":"tc.dashboard/v1"}'::jsonb,'tc.dashboard/v1',
                    'PG_JSONB_TEXT_V1_SHA256',repeat('a',64),'[]','[]',?,clock_timestamp())
                """, version, tenant, project, dashboard, actor);
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) "
                + "VALUES (?,?,?,?,'分享设备类型','STANDARD','DIRECT')", type, tenant, project, "t_" + type.toString().replace("-", ""));
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}',repeat('a',64),'PG_JSONB_TEXT_V1_SHA256')
                """, model, tenant, project, type);
        owner.update("""
                INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,thing_model_version_id)
                VALUES (?,?,?,?,?,'分享设备',?)
                """, device, tenant, project, type, "d_" + device.toString().replace("-", ""), model);
        owner.update("""
                INSERT INTO dash_dashboard_version_model_ref(tenant_id,project_id,dashboard_id,dashboard_version_id,
                    position,model_key,thing_model_version_id) VALUES (?,?,?,?,0,'model',?)
                """, tenant, project, dashboard, version, model);
        return new Fixture(tenant, project, actor, dashboard, version, model, device, lease, purging);
    }

    /** 普通APP事务中建立显式项目范围，返回后连接销毁而不泄漏GUC。 */
    private <T> T scoped(Fixture fixture, Supplier<T> action) {
        return transaction.execute(status -> {
            app.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, fixture.tenant().toString());
            app.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, fixture.project().toString());
            return action.get();
        });
    }

    /** 十二领域按全局版本汇合，旧库target只用于增量证据。 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** @param tenant 租户 @param project 项目 @param actor 真实操作者 @param dashboard 看板 @param version 精确版本
     * @param model 精确模型 @param device 设备 @param lease 清理租约 @param purging 是否清理中 */
    private record Fixture(UUID tenant, UUID project, UUID actor, UUID dashboard, UUID version,
                           UUID model, UUID device, UUID lease, boolean purging) { }
}
