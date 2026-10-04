package com.things.link.bootstrap.telemetry.overview;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 已有上报事实的真实旧库升级，不把新增默认字段冒充历史接受顺序。 */
@Testcontainers
class DeviceReportedRevisionMigrationTests {
    /** 本类专库由JUnit先后管理，未创建Spring上下文或共享数据库。 */
    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("reported_revision_upgrade")
            .withUsername("thingslink").withPassword("thingslink");
    /** 当前迁移的实际全局前驱，先停旧库再插入原结构业务行。 */
    private static final String PREDECESSOR = "20260906.0300";
    /** 本专项验证的确定迁移终点，后续新增迁移不混入单步数量断言。 */
    private static final String TARGET = "20260912.0100";
    /** 全部生产迁移域，保留跨域依赖和原有数据库守卫。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser",
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota"
    };

    /** 真实迁移保留旧值、发生时间、来源和desired版本，新字段未知且重复迁移无写入。 */
    @Test
    void upgradesExistingReportedFactsWithoutInventingOrder() throws Exception {
        flyway(PREDECESSOR).migrate();
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID type = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        String before;
        try (Connection owner = owner()) {
            assertThat(text(owner, """
                    SELECT count(*)::text FROM information_schema.columns
                     WHERE table_schema='public' AND table_name='dev_shadow'
                       AND column_name='reported_revisions'
                    """)).isEqualTo("0");
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?,'接受序号升级')", tenant);
            execute(owner, """
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key)
                    VALUES (?,?,'接受序号升级','sh-1','reported_upgrade')
                    """, project, tenant);
            execute(owner, """
                    INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind)
                    VALUES (?,?,?,'reported_upgrade','升级','STANDARD','DIRECT')
                    """, type, tenant, project);
            execute(owner, """
                    INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status)
                    VALUES (?,?,?,?,'reported_upgrade','升级','ONLINE')
                    """, device, tenant, project, type);
            // 来源字段是既有逐属性UUID记录；本迁移不读取或改写设备当前绑定。
            execute(owner, """
                    INSERT INTO dev_shadow(device_id,tenant_id,project_id,desired,reported,reported_at,
                                           reported_model_version,version)
                    VALUES (?,?,?,'{"target":2}'::jsonb,
                            '{"value":0.123456789012345678901,"legacy":9223372036854775808123}'::jsonb,
                            '{"value":"2026-09-12T00:00:00.123456Z"}'::jsonb,
                            jsonb_build_object('value',?::text),19)
                    """, device, tenant, project, source);
            before = facts(owner, device);
        }
        assertThat(flyway(TARGET).migrate().migrationsExecuted).isEqualTo(1);
        try (Connection owner = owner()) {
            assertThat(facts(owner, device)).isEqualTo(before);
            assertThat(text(owner, "SELECT reported_sequence::text FROM dev_shadow WHERE device_id=?", device))
                    .isEqualTo("0");
            assertThat(text(owner, "SELECT reported_revisions::text FROM dev_shadow WHERE device_id=?", device))
                    .isEqualTo("{}");
            assertThat(text(owner, "SELECT reported_model_version->>'value' FROM dev_shadow WHERE device_id=?", device))
                    .isEqualTo(source.toString());
            assertThat(text(owner, "SELECT (NOT jsonb_exists(reported_model_version, 'legacy'))::text FROM dev_shadow WHERE device_id=?", device))
                    .isEqualTo("true");
            assertThat(text(owner, """
                    SELECT count(*)::text FROM pg_proc p,
                      LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    WHERE p.oid='public.app_reported_revisions_valid(jsonb)'::regprocedure
                      AND a.grantee=0 AND a.privilege_type='EXECUTE'
                    """)).isEqualTo("0");
        }
        try (Connection app = DriverManager.getConnection(DATABASE.getJdbcUrl(), "thingslink_app", "thingslink")) {
            assertThat(text(app, "SELECT (NOT rolsuper AND NOT rolbypassrls)::text FROM pg_roles WHERE rolname=current_user"))
                    .isEqualTo("true");
            assertThat(text(app, "SELECT public.app_reported_revisions_valid('{\"value\":\"9223372036854775807\"}'::jsonb)::text"))
                    .isEqualTo("true");
        }
        // 单步历史保留已验证；再升级到当前全部迁移，不能把未来迁移数量固定为零或一。
        flyway(null).migrate();
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
        try (Connection owner = owner()) {
            assertThat(facts(owner, device)).isEqualTo(before);
            assertThat(text(owner, "SELECT reported_revisions::text FROM dev_shadow WHERE device_id=?", device))
                    .isEqualTo("{}");
        }
    }

    /** 排除本次新增字段后比较完整旧行，JSONB确定性序列化保持数字原文。 */
    private String facts(Connection connection, UUID device) throws SQLException {
        return text(connection, """
                SELECT (to_jsonb(s)-'reported_sequence'-'reported_revisions')::text
                  FROM dev_shadow s WHERE device_id=?
                """, device);
    }

    /** 前驱与最新均使用同一完整域列表；空目标表示真实最新迁移。 */
    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", "thingslink"));
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    /** owner只用于旧库种子和独立证据，不冒充应用角色权限。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }

    /** 固定参数化写入，五秒语句边界，全部保留原数据库约束。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    /** 单行标量事实有界读取，不借被测仓储投影共同制造期望。 */
    private String text(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }
}
