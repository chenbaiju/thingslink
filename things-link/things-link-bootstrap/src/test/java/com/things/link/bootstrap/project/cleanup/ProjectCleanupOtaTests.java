package com.things.link.bootstrap.project.cleanup;

import com.things.link.ota.infrastructure.persistence.JdbcOtaProjectCleanupRepository;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupStage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0114：真实普通应用角色证明OTA先子后父、有界清理和租约失败关闭。 */
@Testcontainers
class ProjectCleanupOtaTests {
    /** 独占真实PostgreSQL，不使用H2替代权限、行锁或数据库函数。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ota_cleanup").withUsername("thingslink").withPassword("thingslink");
    /** 迁移owner只建立和独立观察夹具。 */
    private static JdbcTemplate owner;
    /** 生产普通数据库身份。 */
    private JdbcTemplate app;
    /** 保持调用与租约锁在同一真实事务。 */
    private TransactionTemplate transaction;
    /** 被测本域固定函数适配器。 */
    private JdbcOtaProjectCleanupRepository repository;

    /** 从上一全局版本增量升级并验证重复执行不产生迁移。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260912.0100").migrate();
        assertThat(flyway("20260912.0220").migrate().migrationsExecuted).isEqualTo(3);
        assertThat(flyway("20260912.0220").migrate().migrationsExecuted).isZero();
    }

    /** 每例独立项目，不依赖清除其他测试夹具。 */
    @BeforeEach
    void prepare() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new JdbcOtaProjectCleanupRepository(app);
    }

    /** 501恢复行和501固件分四个非空批次，额外空批证明清空，邻居保持原样。 */
    @Test
    void drainsCreationMappingsBeforeFirmwareInBoundedBatches() {
        Fixture target = fixture();
        Fixture neighbor = fixture();
        seed(target, 501);
        seed(neighbor, 2);
        String neighborBefore = snapshot(neighbor);
        for (int expected : List.of(500, 1, 500, 1)) {
            long before = count(target);
            var result = transaction.execute(status -> repository.clean(claim(target)));
            assertThat(result.deletedRows()).isEqualTo(expected);
            assertThat(result.complete()).isFalse();
            assertThat(result.blockedReason()).isNull();
            assertThat(before - count(target)).isEqualTo(expected);
            assertThat(snapshot(neighbor)).isEqualTo(neighborBefore);
        }
        var finished = transaction.execute(status -> repository.clean(claim(target)));
        assertThat(finished.complete()).isTrue();
        assertThat(finished.deletedRows()).isZero();
        assertThat(count(target)).isZero();
        assertThat(ProjectCleanupStage.DASHBOARD.next()).isEqualTo(ProjectCleanupStage.OTA);
        assertThat(ProjectCleanupStage.OTA.next()).isEqualTo(ProjectCleanupStage.INTEGRATION);
    }

    /** 旧token、错误项目和实际过期租约均不能删除；失败后数据完全保留。 */
    @Test
    void rejectsWrongIdentityAndExpiredLease() {
        Fixture target = fixture();
        Fixture other = fixture();
        seed(target, 1);
        String before = snapshot(target);
        var old = new ProjectCleanupClaim(target.tenant(), target.project(), 1, "OTA",
                UUID.randomUUID(), Instant.now().plusSeconds(60), false);
        assertThatThrownBy(() -> transaction.execute(status -> repository.clean(old)))
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .extracting(failure -> ((java.sql.SQLException) failure).getSQLState()).isEqualTo("42501");
        var wrong = new ProjectCleanupClaim(target.tenant(), other.project(), 1, "OTA",
                target.token(), Instant.now().plusSeconds(60), false);
        assertThatThrownBy(() -> transaction.execute(status -> repository.clean(wrong)))
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .extracting(failure -> ((java.sql.SQLException) failure).getSQLState()).isEqualTo("42501");
        owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
                target.project());
        assertThatThrownBy(() -> transaction.execute(status -> repository.clean(claim(target))))
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .extracting(failure -> ((java.sql.SQLException) failure).getSQLState()).isEqualTo("42501");
        assertThat(snapshot(target)).isEqualTo(before);
    }

    /** 行锁等待消耗租约时，数据库函数的删后复核必须回滚已执行删除。 */
    @Test
    void rollsBackWhenRowLockWaitConsumesLease() throws Exception {
        Fixture target = fixture();
        seed(target, 1);
        String before = snapshot(target);
        try (var blocker = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink");
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT firmware_id FROM ota_firmware_creation_request WHERE project_id=? FOR UPDATE")) {
                statement.setObject(1, target.project());
                statement.executeQuery().close();
            }
            owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()+interval '2 seconds' WHERE id=?",
                    target.project());
            var pending = executor.submit(() -> {
                try {
                    transaction.execute(status -> repository.clean(claim(target)));
                    return (RuntimeException) null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            try {
                awaitDatabaseCondition("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()"
                        + " AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM public.ota_project_cleanup_batch%')");
                awaitDatabaseCondition("SELECT cleanup_lease_until<=clock_timestamp() FROM sys_project WHERE id='"
                        + target.project() + "'");
            } finally {
                blocker.commit();
            }
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotNull()
                    .rootCause().isInstanceOf(java.sql.SQLException.class)
                    .extracting(failure -> ((java.sql.SQLException) failure).getSQLState()).isEqualTo("42501");
        }
        assertThat(snapshot(target)).isEqualTo(before);
    }

    /** 只轮询独占测试库的可观测状态，固定截止避免并发反例无限等待。 */
    private static void awaitDatabaseCondition(String sql) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!Boolean.TRUE.equals(owner.queryForObject(sql, Boolean.class))) {
            if (System.nanoTime() >= deadline) throw new AssertionError("OTA清理数据库条件未在预算内出现");
            Thread.sleep(10);
        }
    }

    /** 普通身份不能直接删表，受限函数不授予PUBLIC或动态对象权限。 */
    @Test
    void keepsDirectDeletionForbiddenAndFunctionRestricted() {
        for (String table : List.of("ota_firmware_creation_request", "ota_firmware")) {
            assertThatThrownBy(() -> app.update("DELETE FROM public." + table))
                    .hasStackTraceContaining("permission denied");
        }
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a
                 WHERE p.oid='public.ota_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure
                   AND a.grantee=0
                """, Long.class)).isZero();
        assertThat(owner.queryForObject("""
                SELECT prosecdef FROM pg_proc
                 WHERE oid='public.ota_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure
                """, Boolean.class)).isTrue();
        assertThat(owner.queryForObject("""
                SELECT 'row_security=off'=ANY(proconfig) FROM pg_proc
                 WHERE oid='public.ota_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure
                """, Boolean.class)).isTrue();
    }

    /** 完整父事实与清理租约，不伪造约束关闭。 */
    private static Fixture fixture() {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','OTA清理')",
                f.account(), f.account() + "@example.test");
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA清理租户')", f.tenant());
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,
                    deleted_at,cleanup_stage,cleanup_lease_token,cleanup_lease_until,
                    cleanup_started_at,cleanup_next_attempt_at)
                VALUES (?,?,'OTA清理项目',?,'PURGING',1,now()-interval '31 days','OTA',?,
                    clock_timestamp()+interval '5 minutes',now(),now())
                """, f.project(), f.tenant(), "ota_" + f.project().toString().replace("-", ""), f.token());
        owner.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol)
                VALUES (?,?,?,'ota-type','OTA类型','DIRECT','STANDARD')
                """, f.type(), f.tenant(), f.project());
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,
                    schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'PATCH','TC_PROPERTY_COMPOSITE_V1','{}'::jsonb,
                    encode(digest(convert_to('{}','UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                """, f.model(), f.tenant(), f.project(), f.type());
        return f;
    }

    /** 只生成合法草稿与恢复映射，模型快照来自真实不可变版本。 */
    private static void seed(Fixture f, int size) {
        owner.update("""
                INSERT INTO ota_firmware(id,tenant_id,project_id,created_by,device_type_id,
                    thing_model_version_id,product_key,firmware_version,schema_digest_algorithm,
                    schema_digest,schema_profile,status,revision,created_at)
                SELECT gen_random_uuid(),v.tenant_id,v.project_id,?,v.device_type_id,v.id,'ota_product',
                    'firmware-'||n,v.digest_algorithm,v.schema_digest,v.schema_profile,'DRAFT',0,now()
                  FROM dev_thing_model_version v CROSS JOIN generate_series(1,?) n WHERE v.id=?
                """, f.account(), size, f.model());
        owner.update("""
                INSERT INTO ota_firmware_creation_request(tenant_id,project_id,account_id,
                    idempotency_key_digest,request_digest,firmware_id,created_at)
                SELECT tenant_id,project_id,created_by,encode(digest(id::text,'sha256'),'hex'),
                    encode(digest(id::text,'sha256'),'hex'),id,created_at
                  FROM ota_firmware WHERE project_id=?
                """, f.project());
    }

    /** @return 与真实数据库行完全对应的租约声明。 */
    private static ProjectCleanupClaim claim(Fixture f) {
        return new ProjectCleanupClaim(f.tenant(), f.project(), 1, "OTA", f.token(),
                Instant.now().plusSeconds(60), false);
    }

    /** @return 独立owner统计，不受普通RLS隐藏影响。 */
    private static long count(Fixture f) {
        return owner.queryForObject("SELECT (SELECT count(*) FROM ota_firmware WHERE project_id=?)"
                + "+(SELECT count(*) FROM ota_firmware_creation_request WHERE project_id=?)",
                Long.class, f.project(), f.project());
    }

    /** @return 两表全字段快照，用于证明邻居和失败回滚完全不变。 */
    private static String snapshot(Fixture f) {
        return List.of("ota_firmware", "ota_firmware_creation_request").stream().map(table ->
                owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text"
                        + " FROM " + table + " t WHERE project_id=?", String.class, f.project())).toList().toString();
    }

    /** @return 全部生产迁移目录，旧库目标明确。 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project",
                        "classpath:db/migration/device", "classpath:db/migration/telemetry",
                        "classpath:db/migration/alarm", "classpath:db/migration/task", "classpath:db/migration/rule",
                        "classpath:db/migration/iam", "classpath:db/migration/enduser", "classpath:db/migration/export",
                        "classpath:db/migration/dashboard", "classpath:db/migration/ota")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** 完整独立租户、项目、账号、类型、模型与清理token。 */
    private record Fixture(UUID tenant, UUID project, UUID account, UUID type, UUID model, UUID token) { }
}
