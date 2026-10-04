package com.things.link.bootstrap.project.cleanup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0076：模型父行不得隐式无界清理跨域原始点，真实Timescale压缩历史与父删除锁序资格。 */
@Testcontainers
class ModelVersionRawCleanupGuardTests {

    /** 固定生产资格版本；不能用普通PG冒充压缩chunk行为。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("model_raw_restrict").withUsername("thingslink").withPassword("thingslink");
    /** 迁移及观察完整事实；没有关闭外键或触发器。 */
    private static JdbcTemplate owner;

    /** 先证旧CASCADE的一版本/1001行反例，再验证压缩旧库增量升级与行保留。 */
    @BeforeAll
    static void migrate() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink"));
        flyway("20260905.1000").migrate();
        Fixture legacy=fixture(1001);
        Fixture neighbor=fixture(1);
        String neighborBefore=snapshot(neighbor);
        assertThat(count(legacy)).isEqualTo(1001);
        assertThat(owner.update("DELETE FROM public.dev_thing_model_version WHERE id=?",legacy.version())).isEqualTo(1);
        assertThat(count(legacy)).isZero();
        assertThat(snapshot(neighbor)).isEqualTo(neighborBefore);
        compress();
        assertThat(compressedChunks()).isPositive();
        assertThat(flyway("20260905.1110").migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway("20260905.1110").migrate().migrationsExecuted).isZero();
        assertThat(snapshot(neighbor)).isEqualTo(neighborBefore);
        assertThat(compressedChunks()).isPositive();
        assertThat(owner.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='public.ts_property_point_internal'::regclass AND conname='ts_property_point_internal_project_model_version_fk'",String.class)).isEqualTo("c");
    }

    /** 先清本域原始点再清项目父实体；新RESTRICT必须改变旧夹具隐式级联习惯。 */
    @BeforeEach
    void clear() {
        owner.update("DELETE FROM public.ts_property_point_internal");
        owner.update("DELETE FROM public.sys_project");
        owner.queryForList("SELECT decompress_chunk(c,if_compressed=>true) FROM show_chunks('public.ts_property_point_internal') c");
    }

    /** 未压缩与压缩1001点均拒绝版本删除，子点有界清空后父行才可删除，邻居始终不变。 */
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void rejectsRawCascadeUntilPointsAreExplicitlyDrained(boolean compressed) {
        Fixture first=fixture(1001);
        Fixture neighbor=fixture(1);
        if (compressed) compress();
        assertThat(compressedChunks()>0).isEqualTo(compressed);
        String before=snapshot(first);
        String neighborBefore=snapshot(neighbor);
        assertSqlState(() -> owner.update("DELETE FROM public.dev_thing_model_version WHERE id=?",first.version()),"23503");
        assertThat(snapshot(first)).isEqualTo(before);
        int total=0;
        for (int expected:new int[]{500,500,1}) {
            int deleted=owner.update("""
                    WITH candidates AS (SELECT project_id,device_id,property_key,ts,message_id FROM public.ts_property_point_internal
                        WHERE project_id=? ORDER BY ts,message_id LIMIT 500)
                    DELETE FROM public.ts_property_point_internal p USING candidates c
                    WHERE (p.project_id,p.device_id,p.property_key,p.ts,p.message_id)=(c.project_id,c.device_id,c.property_key,c.ts,c.message_id)
                    """,first.project());
            assertThat(deleted).isEqualTo(expected); total+=deleted;
            assertThat(snapshot(neighbor)).isEqualTo(neighborBefore);
        }
        assertThat(total).isEqualTo(1001);
        assertThat(owner.update("DELETE FROM public.dev_thing_model_version WHERE id=?",first.version())).isEqualTo(1);
        assertThat(snapshot(neighbor)).isEqualTo(neighborBefore);
    }

    /** 原始点先持FK锁，版本删除等到提交后观察新引用并23503，不靠应用旧快照。 */
    @Test
    void committedIncomingPointWinsBeforeVersionDeletion() throws Exception {
        Fixture first=fixture(0);
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            insert(writer,first);
            var deletion=workers.submit(() -> {
                try { owner.update("DELETE FROM public.dev_thing_model_version WHERE id=?",first.version()); return null; }
                catch (RuntimeException failure) { return failure; }
            });
            try { awaitLock("DELETE FROM public.dev_thing_model_version%"); } finally { writer.commit(); }
            RuntimeException failure=deletion.get(3,TimeUnit.SECONDS);
            assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
            assertThat(((SQLException)failure.getCause()).getSQLState()).isEqualTo("23503");
        }
        assertThat(count(first)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM public.dev_thing_model_version WHERE id=?",Long.class,first.version())).isEqualTo(1);
    }

    /** 版本删除先持锁，后来的原始点等到删除提交后23503，不能创建没有版本的历史。 */
    @Test
    void versionDeletionWinsBeforeIncomingPoint() throws Exception {
        Fixture first=fixture(0);
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement deletion=writer.prepareStatement("DELETE FROM public.dev_thing_model_version WHERE id=?")) {
                deletion.setObject(1,first.version()); assertThat(deletion.executeUpdate()).isEqualTo(1);
            }
            var insertion=workers.submit(() -> {
                try (Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink")) {
                    insert(connection,first); return null;
                } catch (SQLException failure) { return failure; }
            });
            try { awaitLock("INSERT INTO public.ts_property_point_internal%"); } finally { writer.commit(); }
            SQLException failure=insertion.get(3,TimeUnit.SECONDS);
            assertThat(failure!=null).isTrue();
            assertThat(failure.getSQLState()).isEqualTo("23503");
        }
        assertThat(count(first)).isZero();
    }

    /** 复合项目范围仍由FK仲裁，不允许邻居版本被写入本项目原始点。 */
    @Test
    void preservesProjectVersionIdentity() {
        Fixture first=fixture(0);
        Fixture neighbor=fixture(1);
        Fixture wrong=new Fixture(first.tenant(),first.project(),first.type(),neighbor.version(),first.device());
        assertThatThrownBy(() -> {
            try (Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink")) { insert(connection,wrong); }
        }).isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException)failure).getSQLState()).isEqualTo("23503"));
        assertThat(count(first)).isZero();
        assertThat(count(neighbor)).isEqualTo(1);
    }

    /** RR/SSI旧快照不具备父删除资格，未引用版本也须按明确的RC合同删除。 */
    @ParameterizedTest
    @ValueSource(strings={"REPEATABLE READ","SERIALIZABLE"})
    void rejectsStrongIsolationRatherThanUsingStaleSnapshot(String isolation) throws Exception {
        Fixture first=fixture(0);
        try (Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink")) {
            connection.setAutoCommit(false);
            try (var settings=connection.createStatement()) { settings.execute("SET TRANSACTION ISOLATION LEVEL "+isolation); }
            try (PreparedStatement deletion=connection.prepareStatement("DELETE FROM public.dev_thing_model_version WHERE id=?")) {
                deletion.setObject(1,first.version());
                assertThatThrownBy(deletion::executeUpdate).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException)failure).getSQLState()).isEqualTo("25001"));
            }
            connection.rollback();
        }
    }

    /** APP不能把私有端口当跨项目探测器；实际清理必须通过父域受限入口。 */
    @Test
    void keepsRawReferenceProbePrivate() {
        Fixture first=fixture(1);
        JdbcTemplate app=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink_app","thingslink"));
        assertSqlState(() -> app.queryForObject("SELECT public.telemetry_model_version_raw_referenced(?,?)",Boolean.class,first.project(),first.version()),"42501");
        assertThat(count(first)).isEqualTo(1);
    }

    /** 私有守卫升级遇已有行锁立即失败且无半装配，释放后仅补一迁移。 */
    @Test
    void upgradeLockConflictRollsBackTriggerInstallation() throws Exception {
        try (PostgreSQLContainer<?> database=new PostgreSQLContainer<>(
                DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("model_guard_upgrade_busy").withUsername("thingslink").withPassword("thingslink")) {
            database.start();
            Flyway old=Flyway.configure().configuration(flyway("20260905.1100").getConfiguration())
                    .dataSource(database.getJdbcUrl(),"thingslink","thingslink").load();
            old.migrate();
            Flyway upgrade=Flyway.configure().configuration(old.getConfiguration()).target("20260905.1110").load();
            JdbcTemplate check=new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),"thingslink","thingslink"));
            try (Connection holder=DriverManager.getConnection(database.getJdbcUrl(),"thingslink","thingslink")) {
                holder.setAutoCommit(false);
                try (var lock=holder.createStatement()) { lock.executeQuery("SELECT id FROM public.dev_thing_model_version FOR UPDATE").close(); }
                assertThatThrownBy(upgrade::migrate).hasRootCauseInstanceOf(SQLException.class)
                        .satisfies(failure -> {
                            Throwable root=failure;
                            while (root.getCause()!=null) root=root.getCause();
                            assertThat(((SQLException)root).getSQLState()).isEqualTo("55P03");
                        });
                assertThat(check.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='dev_thing_model_version_raw_delete_guard_trg'",Long.class)).isZero();
                assertThat(check.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='20260905.1110'",Long.class)).isZero();
                holder.rollback();
            }
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        }
    }

    /** @param rows 原始点数 @return 无设备当前绑定的真实版本，避免把别的RESTRICT误当原始点保护 */
    private static Fixture fixture(int rows) {
        Fixture f=new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'版本外键租户')",f.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key) VALUES (?,?,'版本外键项目',?)",f.project(),f.tenant(),"raw_"+f.project().toString().replace("-",""));
        owner.update("INSERT INTO public.dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol) VALUES (?,?,?,'type','版本类型','DIRECT','STANDARD')",f.type(),f.tenant(),f.project());
        owner.update("""
                INSERT INTO public.dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,version_minor,
                    version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'PATCH','TC_PROPERTY_COMPOSITE_V1','{}'::jsonb,
                    encode(digest(convert_to('{}'::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                """,f.version(),f.tenant(),f.project(),f.type());
        owner.update("""
                INSERT INTO public.ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double,
                    data_type,thing_model_version_id,model_version)
                SELECT ?,?,'temperature',date_trunc('day',now())-interval '30 days'+n*interval '1 second',gen_random_uuid(),42,'NUMBER',?,'1.0.0'
                  FROM generate_series(1,?) n
                """,f.project(),f.device(),f.version(),rows);
        return f;
    }

    /** @param connection 受控事务 @param f 真实完整版本归属 */
    private static void insert(Connection connection,Fixture f) throws SQLException {
        try (PreparedStatement insert=connection.prepareStatement("""
                INSERT INTO public.ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double,data_type,thing_model_version_id,model_version)
                VALUES (?,?,'temperature',now()-interval '30 days',gen_random_uuid(),42,'NUMBER',?,'1.0.0')
                """)) {
            insert.setObject(1,f.project()); insert.setObject(2,f.device()); insert.setObject(3,f.version()); insert.executeUpdate();
        }
    }

    /** 只压缩测试隔离容器旧chunk，不删除或解压历史。 */
    private static void compress() {
        owner.queryForList("SELECT compress_chunk(c,if_not_compressed=>true) FROM show_chunks('public.ts_property_point_internal',older_than=>now()-interval '7 days') c");
    }

    /** @return 真正压缩的本域chunk数 */
    private static long compressedChunks() {
        return owner.queryForObject("SELECT count(*) FROM timescaledb_information.chunks WHERE hypertable_name='ts_property_point_internal' AND is_compressed",Long.class);
    }

    /** @param f 项目 @return 全字段原始点快照 */
    private static String snapshot(Fixture f) {
        return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY ts,message_id),'[]'::jsonb)::text FROM public.ts_property_point_internal p WHERE project_id=?",String.class,f.project());
    }

    /** @param f 项目 @return 原始点实际数量 */
    private static long count(Fixture f) { return owner.queryForObject("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id=?",Long.class,f.project()); }

    /** @param action 数据库动作 @param state 准确SQLSTATE防错误前置伪装通过 */
    private static void assertSqlState(Runnable action,String state) {
        assertThatThrownBy(action::run).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException)failure.getCause()).getSQLState()).isEqualTo(state));
    }

    /** @param prefix 实际SQL锁等待，三秒测试屏障不代替生产五秒预算 */
    private static void awaitLock(String prefix) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,prefix))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
        throw new AssertionError("未观察到模型/原始点外键锁等待");
    }

    /** @param target 新旧库截止版本 @return 本隔离容器完整迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink")
                .locations("classpath:db/migration/support","classpath:db/migration/project","classpath:db/migration/iam",
                        "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
                        "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/enduser","classpath:db/migration/export")
                .placeholders(Map.of("app_role_password","thingslink")).target(target).load();
    }

    /** 模型版本无当前绑定，原始点是测试中唯一入边。 */
    private record Fixture(UUID tenant,UUID project,UUID type,UUID version,UUID device) { }
}
