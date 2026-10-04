package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.domain.DashboardShareCreationResult;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardShareRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0101分享四表、普通RLS、复合FK、零变化撤销和501行有界清理的真实数据库反例。 */
@Testcontainers
class DashboardSharePersistenceTests {
    /** 独占真实PostgreSQL，不借共享业务容器绕过RLS或污染其他夹具。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_share_persistence").withUsername("thingslink").withPassword("thingslink");
    /** Bootstrap正式十二域全清单，按全局版本增量升级。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export", "classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** 仅迁移owner用于夹具和跨租户结果观察。 */ private static JdbcTemplate owner;
    /** 真正受RLS约束的运行连接。 */ private static JdbcTemplate app;
    /** 普通运行连接的原事务。 */ private static TransactionTemplate transaction;
    /** 被测生产JDBC适配器。 */ private static JdbcDashboardShareRepository repository;

    /** 旧库0270升级到本次唯一迁移0280，再启动不重放迁移。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260906.0270").migrate();
        assertThat(flyway("20260906.0280").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260906.0280").migrate().migrationsExecuted).isZero();
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new JdbcDashboardShareRepository(app);
    }

    /** 四表均保持普通APP项目RLS和只追加ACL，最终清理函数显式登记全部新增事实。 */
    @Test
    void migrationRegistersRlsPrivilegesCommentsAndResidualChecks() {
        for (String table : List.of("dash_share_token", "dash_share_scope", "dash_share_scope_device", "dash_share_creation_result")) {
            assertThat(owner.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=?::regclass", Boolean.class, table)).isTrue();
            assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',?,'SELECT')", Boolean.class, table)).isTrue();
            assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',?,'INSERT')", Boolean.class, table)).isTrue();
            assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',?,'DELETE')", Boolean.class, table)).isFalse();
            assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',?,'UPDATE')", Boolean.class, table)).isFalse();
            assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',?,'TRUNCATE')", Boolean.class, table)).isFalse();
            assertThat(owner.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 "
                    + "AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL", Long.class, table)).isZero();
        }
        assertThat(owner.queryForObject("SELECT has_column_privilege('thingslink_app','dash_share_token','revoked_at','UPDATE')", Boolean.class)).isTrue();
        String cleanup = owner.queryForObject("SELECT pg_get_functiondef('dashboard_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure)", String.class);
        for (String table : List.of("dash_share_token", "dash_share_scope", "dash_share_scope_device", "dash_share_creation_result")) {
            assertThat(cleanup).contains("FROM public." + table + " WHERE project_id = p_project_id");
        }
        assertThat(owner.queryForObject("SELECT has_function_privilege('thingslink_app',"
                + "'dashboard_project_cleanup_batch(uuid,uuid,bigint,uuid)','EXECUTE')", Boolean.class)).isTrue();
    }

    /** DB时间、完整关系和恢复映射一致；列表不需要也不能返回secret，重复撤销不写新tuple。 */
    @Test
    void createsScopedFactAndRevokesOnlyOnce() {
        Fixture fixture = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "a"));
        scoped(fixture, () -> {
            assertThat(repository.countActive(fixture.project(), fixture.dashboard(), repository.databaseNow())).isEqualTo(1);
            assertThat(repository.findCreationResult(fixture.tenant(), fixture.project(), fixture.dashboard(), fixture.actor(),
                    "a".repeat(64))).get().extracting(DashboardShareCreationResult::shareId).isEqualTo(token.id());
            var page = repository.page(fixture.project(), fixture.dashboard(), null, null, 2);
            assertThat(page).hasSize(1);
            assertThat(page.getFirst().status()).isEqualTo("ACTIVE");
            assertThat(page.getFirst().dashboardVersionNumber()).isEqualTo(1);
            return null;
        });
        DashboardShareToken revoked = scoped(fixture,
                () -> repository.revoke(fixture.project(), fixture.dashboard(), token.id(), fixture.actor()).orElseThrow());
        String firstTuple = owner.queryForObject("SELECT xmin::text FROM dash_share_token WHERE id=?", String.class, token.id());
        assertThat(scoped(fixture, () -> repository.revoke(fixture.project(), fixture.dashboard(), token.id(), fixture.actor()))).isEmpty();
        assertThat(owner.queryForObject("SELECT xmin::text FROM dash_share_token WHERE id=?", String.class, token.id())).isEqualTo(firstTuple);
        assertThat(scoped(fixture, () -> repository.find(fixture.project(), fixture.dashboard(), token.id()))).get()
                .extracting(DashboardShareToken::revokedAt).isEqualTo(revoked.revokedAt());
        assertThat(scoped(fixture, () -> repository.page(fixture.project(), fixture.dashboard(), null, null, 2)))
                .first().extracting(item -> item.status()).isEqualTo("REVOKED");
    }

    /** 普通身份对邻居分享不可见，精确FK拒绝把其他看板版本或设备拼到本scope。 */
    @Test
    void rlsAndCompositeKeysRejectNeighborFacts() {
        Fixture first = fixture(false);
        Fixture neighbor = fixture(false);
        DashboardShareToken token = scoped(first, () -> create(first, "b"));
        assertThat(scoped(neighbor, () -> repository.find(first.project(), first.dashboard(), token.id()))).isEmpty();
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO dash_share_token(id,tenant_id,project_id,dashboard_id,dashboard_version_id,
                    project_generation,secret_hash,host_compatibility,referer_policy,created_at,expires_at,creator_account_id)
                SELECT gen_random_uuid(),tenant_id,project_id,dashboard_id,?,project_generation,?,host_compatibility,
                    referer_policy,created_at,expires_at,creator_account_id FROM dash_share_token WHERE id=?
                """, neighbor.version(), "c".repeat(64), token.id())).isInstanceOf(DataAccessException.class);
        // 本例由owner撤除封存映射以单独观察复合FK；普通APP无DELETE权限，封存路径由独立反例验证。
        owner.update("DELETE FROM dash_share_creation_result WHERE share_id=?", token.id());
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO dash_share_scope_device(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,device_id,thing_model_version_id,position)
                VALUES (?,?,?,?,?,'devices',?,?,1)
                """, first.tenant(), first.project(), first.dashboard(), first.version(), token.id(),
                neighbor.device(), first.model())).isInstanceOf(DataAccessException.class).hasStackTraceContaining("foreign key constraint");
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO dash_share_scope(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,thing_model_version_id,position) VALUES (?,?,?,?,?,'other',?,1)
                """, first.tenant(), first.project(), first.dashboard(), first.version(), token.id(), neighbor.model()))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("foreign key constraint");
    }

    /** 同键冲突回滚后不遗留第二个token或scope，凭据/范围普通UPDATE与DELETE均被拒绝。 */
    @Test
    void duplicateCreationRollsBackAndRuntimeCannotRewriteSecret() {
        Fixture fixture = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "d"));
        assertThatThrownBy(() -> scoped(fixture, () -> create(fixture, "d"))).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_token WHERE project_id=?", Long.class, fixture.project())).isEqualTo(1);
        assertThatThrownBy(() -> scoped(fixture, () -> app.update("UPDATE dash_share_token SET secret_hash=? WHERE id=?",
                "f".repeat(64), token.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scoped(fixture, () -> app.update("DELETE FROM dash_share_scope WHERE share_id=?", token.id())))
                .isInstanceOf(DataAccessException.class);
    }

    /** 创建结果最后封存；普通APP不能在已签发token上追加原本FK合法的变量或设备。 */
    @Test
    void sealedScopeRejectsFurtherVariableAndDeviceInsertions() {
        Fixture fixture = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "1"));
        UUID extraDevice = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,thing_model_version_id)
                SELECT ?,tenant_id,project_id,device_type_id,device_key || '_extra',name,thing_model_version_id
                  FROM dev_device WHERE id=?
                """, extraDevice, fixture.device());
        assertThatThrownBy(() -> scoped(fixture, () -> app.update("""
                INSERT INTO dash_share_scope(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,position,thing_model_version_id) VALUES (?,?,?,?,?,'extra',1,?)
                """, fixture.tenant(), fixture.project(), fixture.dashboard(), fixture.version(), token.id(), fixture.model())))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("share scope is sealed");
        assertThatThrownBy(() -> scoped(fixture, () -> app.update("""
                INSERT INTO dash_share_scope_device(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,device_id,thing_model_version_id,position) VALUES (?,?,?,?,?,'devices',?,?,1)
                """, fixture.tenant(), fixture.project(), fixture.dashboard(), fixture.version(), token.id(), extraDevice, fixture.model())))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("share scope is sealed");
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_scope WHERE share_id=?", Long.class, token.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_scope_device WHERE share_id=?", Long.class, token.id())).isEqualTo(1);
    }

    /** tx2在旧封存观察开始后等待token锁，tx1提交封存后必须用新快照拒绝追加。 */
    @Test
    void concurrentSealingLocksThenReadsCommittedSeal() throws Exception {
        Fixture fixture = fixture(false);
        DashboardShareToken token = scoped(fixture, () -> create(fixture, "2"));
        // owner仅构造已提交但尚未封存的异常中间事实，普通APP无权撤除已有封存。
        owner.update("DELETE FROM dash_share_creation_result WHERE share_id=?", token.id());
        CountDownLatch sealedButUncommitted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CompletableFuture<Integer> waitingBackend = new CompletableFuture<>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var seal = workers.submit(() -> scoped(fixture, () -> {
                app.update("""
                        INSERT INTO dash_share_creation_result(tenant_id,project_id,dashboard_id,account_id,
                            idempotency_key_digest,request_digest,share_id,created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, fixture.tenant(), fixture.project(), fixture.dashboard(), fixture.actor(),
                        "2".repeat(64), "a".repeat(64), token.id(), Timestamp.from(token.createdAt()));
                sealedButUncommitted.countDown();
                try {
                    if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("等待并发封存测试放行超时");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("封存测试中断", interrupted);
                }
                return null;
            }));
            try {
                assertThat(sealedButUncommitted.await(5, TimeUnit.SECONDS)).isTrue();
                var append = workers.submit(() -> scoped(fixture, () -> {
                    app.execute("SET LOCAL lock_timeout='8s'");
                    app.execute("SET LOCAL statement_timeout='10s'");
                    waitingBackend.complete(app.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return app.update("""
                            INSERT INTO dash_share_scope(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                                variable_key,position,thing_model_version_id) VALUES (?,?,?,?,?,'concurrent',1,?)
                            """, fixture.tenant(), fixture.project(), fixture.dashboard(), fixture.version(), token.id(), fixture.model());
                }));
                int backend = waitingBackend.get(5, TimeUnit.SECONDS);
                // 观察数据库真实阻塞，而非用线程启动或固定sleep推断锁已经生效。
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(owner.queryForObject("SELECT cardinality(pg_blocking_pids(?))", Integer.class, backend))
                                .isGreaterThan(0));
                allowCommit.countDown();
                seal.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> append.get(5, TimeUnit.SECONDS))
                        .hasStackTraceContaining("share scope is sealed");
                assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_scope WHERE share_id=? AND variable_key='concurrent'",
                        Long.class, token.id())).isZero();
            } finally {
                allowCommit.countDown();
            }
        }
    }

    /** 501条每类分享事实都必须分批，阶段/租约错误不能删，邻居保持且空域重入稳定。 */
    @Test
    void cleanupIsBoundedAndPreservesNeighbor() {
        Fixture fixture = fixture(true);
        Fixture neighbor = fixture(false);
        DashboardShareToken original = scoped(fixture, () -> create(fixture, "e"));
        DashboardShareToken untouched = scoped(neighbor, () -> create(neighbor, "f"));
        // 一条完整原始图加500个历史分享；历史过期不意味着可以跳过清理。
        owner.update("""
                INSERT INTO dash_share_token(id,tenant_id,project_id,dashboard_id,dashboard_version_id,
                    project_generation,secret_hash,host_compatibility,referer_policy,created_at,expires_at,creator_account_id)
                SELECT gen_random_uuid(),tenant_id,project_id,dashboard_id,dashboard_version_id,project_generation,
                    encode(digest(id::text || sequence::text,'sha256'),'hex'),host_compatibility,referer_policy,
                    clock_timestamp()-interval '2 hours',clock_timestamp()-interval '1 hour',creator_account_id
                  FROM dash_share_token CROSS JOIN generate_series(1,500) sequence WHERE id=?
                """, original.id());
        owner.update("""
                INSERT INTO dash_share_scope(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,position,thing_model_version_id)
                SELECT tenant_id,project_id,dashboard_id,dashboard_version_id,id,'devices',0,?
                  FROM dash_share_token WHERE project_id=? AND id<>?
                """, fixture.model(), fixture.project(), original.id());
        owner.update("""
                INSERT INTO dash_share_scope_device(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,device_id,thing_model_version_id,position)
                SELECT tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,'devices',?,thing_model_version_id,0
                  FROM dash_share_scope WHERE project_id=? AND share_id<>?
                """, fixture.device(), fixture.project(), original.id());
        owner.update("""
                INSERT INTO dash_share_creation_result(tenant_id,project_id,dashboard_id,account_id,
                    idempotency_key_digest,request_digest,share_id,created_at)
                SELECT tenant_id,project_id,dashboard_id,creator_account_id,
                    encode(digest(id::text,'sha256'),'hex'),repeat('a',64),id,created_at
                  FROM dash_share_token WHERE project_id=? AND id<>?
                """, fixture.project(), original.id());
        assertThatThrownBy(() -> scoped(fixture, () -> clean(fixture, UUID.randomUUID()))).isInstanceOf(DataAccessException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='DEVICE' WHERE id=?", fixture.project());
        assertThatThrownBy(() -> scoped(fixture, () -> clean(fixture, fixture.lease()))).isInstanceOf(DataAccessException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='DASHBOARD' WHERE id=?", fixture.project());
        boolean complete = false;
        int shareRows = 0;
        for (int index = 0; index < 30; index++) {
            Batch batch = scoped(fixture, () -> clean(fixture, fixture.lease()));
            assertThat(batch.deleted()).isBetween(0, 500);
            if (index < 8) shareRows += batch.deleted();
            if (batch.complete()) { complete = true; break; }
        }
        assertThat(complete).isTrue();
        assertThat(shareRows).isEqualTo(501 * 4);
        for (String table : List.of("dash_share_creation_result", "dash_share_scope_device", "dash_share_scope", "dash_share_token")) {
            assertThat(owner.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id=?", Long.class, fixture.project())).isZero();
        }
        assertThat(scoped(fixture, () -> clean(fixture, fixture.lease())).complete()).isTrue();
        assertThat(scoped(neighbor, () -> repository.find(neighbor.project(), neighbor.dashboard(), untouched.id()))).isPresent();
    }

    /** 创建一个有效且关系完整的分享；独立随机secret摘要避免同键反例被secret唯一提前遮住。 */
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

    /** 独立身份骨架和精确模型图；owner只负责构造数据库测试前提。 */
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

    /** 固定可信双轴进入普通运行事务，不以owner身份替代RLS实证。 */
    private <T> T scoped(Fixture fixture, Supplier<T> action) {
        return transaction.execute(status -> {
            app.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, fixture.tenant().toString());
            app.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, fixture.project().toString());
            return action.get();
        });
    }

    /** 使用真实清理租约和阶段，不临时关闭安全函数。 */
    private Batch clean(Fixture fixture, UUID lease) {
        return app.queryForObject("SELECT * FROM dashboard_project_cleanup_batch(?,?,?,?)",
                (result, row) -> new Batch(result.getInt("deleted_rows"), result.getBoolean("complete")),
                fixture.tenant(), fixture.project(), 1L, lease);
    }

    /** 与生产相同十二领域，仅用target取得旧库增量证据。 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** @param tenant 租户 @param project 项目 @param actor 操作者 @param dashboard 看板 @param version 看板版本
     * @param model 模型版本 @param device 设备 @param lease 清理租约 @param purging 是否正在清理 */
    private record Fixture(UUID tenant, UUID project, UUID actor, UUID dashboard, UUID version,
                           UUID model, UUID device, UUID lease, boolean purging) { }
    /** @param deleted 实际行数 @param complete 是否完成 */
    private record Batch(int deleted, boolean complete) { }
}
