package com.things.link.bootstrap.project.lifecycle;

import com.things.link.enduser.infrastructure.persistence.JdbcAppDeviceBindTokenRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppRefreshTokenRepository;
import com.things.link.iam.infrastructure.persistence.JdbcRefreshTokenRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0073控制台与App持久凭据代次的真实旧库升级、权限及JDBC映射验收。 */
@Testcontainers
class CredentialGenerationMigrationTests {

    /** 独占真实PG避免旧schema、全局角色及RLS范围与其他测试互相污染。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("credential_generation")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** 全量目录保留旧enduser迁移对设备、告警等跨域表的真实前置，不裁剪schema。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser"
    };

    /** 项目代次迁移完成、三张凭据表尚无代次列的精确旧库版本。 */
    private static final String LEGACY_VERSION = "20260904.0230";

    /** 运行连接角色必须通过原表授权及bind表RLS读取新增列。 */
    private static final String APP_ROLE = "thingslink_app";

    /** 测试容器内APP角色密码，由support迁移占位符创建。 */
    private static final String APP_PASSWORD = "thingslink";

    /** 一个顺序流程覆盖0240/0250旧行回填、约束、列注释、APP权限、mapper及幂等重跑。 */
    @Test
    void upgradesLegacyCredentialRowsAndMapsZeroGenerationAsApplicationRole() throws Exception {
        flyway(LEGACY_VERSION).migrate();
        Fixture fixture = seedLegacyCredentials();
        List<String> before = legacyFacts();

        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(2);

        assertThat(legacyFacts()).isEqualTo(before);
        assertZeroBackfillAndCatalog();
        assertNegativeValuesRejected(fixture);
        assertApplicationJdbcMappings(fixture);

        List<String> completed = generationFacts();
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(generationFacts()).isEqualTo(completed);
    }

    /** 三张旧凭据表都回填0，并冻结bigint、非空默认、注释、APP读取及原RLS边界。 */
    private void assertZeroBackfillAndCatalog() throws SQLException {
        try (Connection owner = owner()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_refresh_token WHERE project_generation=0"))
                    .isEqualTo(1L);
            assertThat(number(owner, "SELECT count(*) FROM app_refresh_token WHERE project_generation=0"))
                    .isEqualTo(1L);
            assertThat(number(owner, "SELECT count(*) FROM app_device_bind_token WHERE project_generation=0"))
                    .isEqualTo(1L);
            for (String table : List.of("sys_refresh_token", "app_refresh_token", "app_device_bind_token")) {
                assertThat(text(owner, """
                        SELECT data_type||':'||is_nullable||':'||column_default
                          FROM information_schema.columns
                         WHERE table_schema='public' AND table_name=? AND column_name='project_generation'
                        """, table)).isEqualTo("bigint:NO:0");
                assertThat(text(owner, """
                        SELECT col_description(?::regclass,
                               (SELECT attnum FROM pg_attribute
                                 WHERE attrelid=?::regclass AND attname='project_generation'))
                        """, table, table)).isNotBlank();
                assertThat(text(owner, """
                        SELECT has_column_privilege(?, ?, 'project_generation', 'SELECT')::text
                        """, APP_ROLE, "public." + table)).isEqualTo("true");
            }
            assertThat(number(owner, """
                    SELECT count(*) FROM pg_constraint
                     WHERE conrelid IN ('sys_refresh_token'::regclass,
                                        'app_refresh_token'::regclass,
                                        'app_device_bind_token'::regclass)
                       AND contype='c'
                       AND position('project_generation' in pg_get_constraintdef(oid)) > 0
                       AND position('>= 0' in pg_get_constraintdef(oid)) > 0
                    """)).isEqualTo(3L);
            assertThat(text(owner, "SELECT relrowsecurity::text FROM pg_class "
                    + "WHERE oid='app_device_bind_token'::regclass")).isEqualTo("true");
            assertThat(rows(owner, """
                    SELECT relname||':'||relrowsecurity FROM pg_class
                     WHERE oid IN ('sys_refresh_token'::regclass,'app_refresh_token'::regclass)
                     ORDER BY relname
                    """)).containsExactly("app_refresh_token:false", "sys_refresh_token:false");
        }
    }

    /** 三个非负CHECK均真实返回23514，失败后原旧行仍为零代。 */
    private void assertNegativeValuesRejected(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            for (Mutation mutation : List.of(
                    new Mutation("sys_refresh_token", fixture.consoleToken()),
                    new Mutation("app_refresh_token", fixture.appToken()),
                    new Mutation("app_device_bind_token", fixture.bindToken()))) {
                assertThatThrownBy(() -> execute(owner,
                        "UPDATE " + mutation.table() + " SET project_generation=-1 WHERE id=?",
                        mutation.id()))
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState())
                                .isEqualTo("23514"));
                assertThat(number(owner, "SELECT project_generation FROM " + mutation.table()
                        + " WHERE id=?", mutation.id())).isZero();
            }
        }
    }

    /** 真实APP连接经三套生产Jdbc mapper读取旧行，bind令牌还必须通过项目RLS范围。 */
    private void assertApplicationJdbcMappings(Fixture fixture) throws SQLException {
        try (Connection application = application()) {
            application.setAutoCommit(false);
            try {
                execute(application, "SELECT set_config('app.tenant_id',?,true)", fixture.tenantId().toString());
                execute(application, "SELECT set_config('app.project_id',?,true)", fixture.projectId().toString());
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(application, true));

                assertThat(new JdbcRefreshTokenRepository(jdbc).findByHash(fixture.consoleHash()))
                        .hasValueSatisfying(token -> assertThat(token.projectLifecycleGeneration()).isZero());
                assertThat(new JdbcAppRefreshTokenRepository(jdbc).findByHash(fixture.appHash()))
                        .hasValueSatisfying(token -> assertThat(token.projectGeneration()).isZero());
                assertThat(new JdbcAppDeviceBindTokenRepository(jdbc)
                        .findByProjectAndHash(fixture.projectId(), fixture.bindHash()))
                        .hasValueSatisfying(token -> assertThat(token.projectGeneration()).isZero());
            } finally {
                application.rollback();
            }
        }
    }

    /** 0230旧schema写入控制台refresh、App refresh及CLAIM能力各一条真实事实。 */
    private Fixture seedLegacyCredentials() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID appUserId = UUID.randomUUID();
        UUID consoleToken = UUID.randomUUID();
        UUID appToken = UUID.randomUUID();
        UUID bindToken = UUID.randomUUID();
        byte[] consoleHash = tokenHash(1);
        byte[] appHash = tokenHash(2);
        byte[] bindHash = tokenHash(3);
        try (Connection owner = owner()) {
            execute(owner, "INSERT INTO sys_tenant(id,name,status) VALUES (?,'凭据代次租户','ACTIVE')", tenantId);
            execute(owner, """
                    INSERT INTO sys_account(id,email,password_hash,display_name)
                    VALUES (?,?,'{noop}unused','凭据代次账号')
                    """, accountId, accountId + "@example.com");
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) "
                    + "VALUES (?,?,'凭据代次项目','sh-1',?)", projectId, tenantId,
                    "credential_generation_" + projectId.toString().replace("-", ""));
            execute(owner, """
                    INSERT INTO sys_refresh_token
                        (id,account_id,tenant_id,project_id,token_hash,family_id,expires_at)
                    VALUES (?,?,?,?,?,?,now()+interval '1 day')
                    """, consoleToken, accountId, tenantId, projectId, consoleHash, UUID.randomUUID());
            execute(owner, """
                    INSERT INTO app_user(id,tenant_id,username,password_hash,status)
                    VALUES (?,?,'credential-user','{noop}unused','ACTIVE')
                    """, appUserId, tenantId);
            execute(owner, """
                    INSERT INTO app_refresh_token
                        (id,tenant_id,project_id,app_user_id,token_hash,family_id,expires_at)
                    VALUES (?,?,?,?,?,?,now()+interval '1 day')
                    """, appToken, tenantId, projectId, appUserId, appHash, UUID.randomUUID());
            execute(owner, """
                    INSERT INTO app_device_bind_token
                        (id,tenant_id,project_id,device_id,token_hash,purpose,target_role,
                         expires_at,max_attempts)
                    VALUES (?,?,?,?,?,'CLAIM','PRIMARY',now()+interval '1 day',5)
                    """, bindToken, tenantId, projectId, UUID.randomUUID(), bindHash);
        }
        return new Fixture(tenantId, projectId, consoleToken, appToken, bindToken,
                consoleHash, appHash, bindHash);
    }

    /** 迁移前后只比较旧字段，新增默认列不能顺便改写轮换、消费或时间事实。 */
    private List<String> legacyFacts() throws SQLException {
        try (Connection owner = owner()) {
            return rows(owner, """
                    SELECT 'C:'||row(id,account_id,tenant_id,project_id,token_hash,family_id,
                                    issued_at,expires_at,revoked_at,replaced_by)::text
                      FROM sys_refresh_token
                    UNION ALL
                    SELECT 'A:'||row(id,tenant_id,project_id,app_user_id,token_hash,family_id,
                                    issued_at,expires_at,revoked_at,replaced_by)::text
                      FROM app_refresh_token
                    UNION ALL
                    SELECT 'B:'||row(id,tenant_id,project_id,device_id,token_hash,purpose,target_role,
                                    expires_at,attempt_count,max_attempts,consumed_at,created_at,updated_at)::text
                      FROM app_device_bind_token
                    ORDER BY 1
                    """);
        }
    }

    /** 迁移后含代次快照用于Flyway重跑不漂移断言。 */
    private List<String> generationFacts() throws SQLException {
        try (Connection owner = owner()) {
            return rows(owner, """
                    SELECT 'C:'||id||':'||project_generation FROM sys_refresh_token
                    UNION ALL SELECT 'A:'||id||':'||project_generation FROM app_refresh_token
                    UNION ALL SELECT 'B:'||id||':'||project_generation FROM app_device_bind_token
                    ORDER BY 1
                    """);
        }
    }

    /** 以首字节构造不同的合法32字节摘要，不在测试中保存明文凭据。 */
    private static byte[] tokenHash(int marker) {
        byte[] hash = new byte[32];
        hash[0] = (byte) marker;
        return hash;
    }

    /** 连接独占容器owner执行迁移种子与目录观察。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 连接真实APP角色验证表授权、RLS与生产Jdbc mapper。 */
    private Connection application() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    /** 构造精确旧版本Flyway。 */
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target(target).load();
    }

    /** 精确止于本例验收的0250，未来无关迁移不能改变“两条凭据迁移”的断言。 */
    private Flyway flyway() {
        return flyway("20260904.0250");
    }

    /** 查询首行首列，设置五秒预算避免基础设施故障挂起。 */
    private String text(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            bind(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** bigint业务和目录计数统一以long读取。 */
    private long number(Connection connection, String sql, Object... values) throws SQLException {
        return Long.parseLong(text(connection, sql, values));
    }

    /** 顺序读取查询首列用于字节事实与目录集合比较。 */
    private List<String> rows(Connection connection, String sql, Object... values) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            bind(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return List.copyOf(result);
    }

    /** 执行DDL/DML或set_config，保留原SQLSTATE供约束断言。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            bind(statement, values);
            statement.execute();
        }
    }

    /** 参数只通过预编译绑定，令牌摘要保持bytea类型。 */
    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    /** 负代次约束的表与目标旧行。 */
    private record Mutation(String table, UUID id) {
    }

    /** 三张凭据旧行及其真实哈希。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID consoleToken, UUID appToken,
                           UUID bindToken, byte[] consoleHash, byte[] appHash, byte[] bindHash) {
    }
}
