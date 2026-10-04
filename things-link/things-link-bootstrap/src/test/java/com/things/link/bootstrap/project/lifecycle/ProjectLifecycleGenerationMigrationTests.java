package com.things.link.bootstrap.project.lifecycle;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0073项目生命周期代次的真实旧库升级、约束、权限及幂等验收。 */
@Testcontainers
class ProjectLifecycleGenerationMigrationTests {

    /** 独占实例避免Flyway角色、旧库事实和其他全局迁移测试互相污染。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_lifecycle_generation")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** 装载项目迁移、角色基础及project_member所依赖的IAM账号表。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam"
    };

    /** 5f1之前project迁移集合的最后版本；局部Flyway目标必须存在于本测试加载的位置中。 */
    private static final String LEGACY_VERSION = "20260903.0700";

    /** Flyway创建的非owner运行角色，新列必须继承原表真实读取权限。 */
    private static final String APP_ROLE = "thingslink_app";

    /** 测试专库运行角色密码，仅用于Flyway占位符和本地容器连接。 */
    private static final String APP_PASSWORD = "thingslink";

    /** 固定到微秒的事实时间，避免PostgreSQL精度差异干扰旧行不改写断言。 */
    private static final Instant FIXED_AT = Instant.parse("2026-09-04T12:00:00Z");

    /** 一个顺序流程覆盖V20260904_0230旧库回填、默认兼容、约束、权限及Flyway幂等。 */
    @Test
    void upgradesLegacyProjectsWithoutRewritingExistingFacts() throws Exception {
        flyway(LEGACY_VERSION).migrate();
        Fixture fixture = seedLegacyProjects();
        List<String> legacyFacts = projectFacts(false);

        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(1);

        assertThat(projectFacts(false)).isEqualTo(legacyFacts);
        assertBackfill(fixture);
        assertCatalogAndPrivileges();
        assertNonNegativeConstraint(fixture.activeProject());
        assertNewActiveProjectDefaultsToGenerationZero(fixture.tenantId());

        List<String> completed = projectFacts(true);
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(projectFacts(true)).isEqualTo(completed);
    }

    /** ACTIVE/ARCHIVED旧项目为0；任一删除信号存在时为1，恢复不能再匹配旧凭据。 */
    private void assertBackfill(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            assertThat(number(owner, "SELECT lifecycle_generation FROM sys_project WHERE id=?",
                    fixture.activeProject())).isZero();
            assertThat(number(owner, "SELECT lifecycle_generation FROM sys_project WHERE id=?",
                    fixture.archivedProject())).isZero();
            assertThat(number(owner, "SELECT lifecycle_generation FROM sys_project WHERE id=?",
                    fixture.deletingProject())).isEqualTo(1L);
            assertThat(number(owner, "SELECT lifecycle_generation FROM sys_project WHERE id=?",
                    fixture.softDeletedProject())).isEqualTo(1L);
        }
    }

    /** 新列必须是有注释的非负bigint、非空默认0，并继承项目表真实APP读取权限。 */
    private void assertCatalogAndPrivileges() throws SQLException {
        try (Connection owner = owner()) {
            assertThat(text(owner, """
                    SELECT data_type || ':' || is_nullable || ':' || column_default
                      FROM information_schema.columns
                     WHERE table_schema='public' AND table_name='sys_project'
                       AND column_name='lifecycle_generation'
                    """)).isEqualTo("bigint:NO:0");
            assertThat(text(owner, """
                    SELECT col_description('sys_project'::regclass,
                           (SELECT attnum FROM pg_attribute
                             WHERE attrelid='sys_project'::regclass
                               AND attname='lifecycle_generation'))
                    """)).isNotBlank();
            assertThat(text(owner, """
                    SELECT has_column_privilege(?, 'public.sys_project',
                                                'lifecycle_generation', 'SELECT')::text
                    """, APP_ROLE)).isEqualTo("true");
            assertThat(number(owner, """
                    SELECT count(*) FROM pg_constraint
                     WHERE conrelid='sys_project'::regclass AND contype='c'
                       AND position('lifecycle_generation' in pg_get_constraintdef(oid)) > 0
                       AND position('>= 0' in pg_get_constraintdef(oid)) > 0
                    """)).isEqualTo(1L);
        }
    }

    /** 真实23514不能改写原值；连接自动提交使失败语句不污染后续恢复观察。 */
    private void assertNonNegativeConstraint(UUID activeProject) throws SQLException {
        try (Connection owner = owner()) {
            assertThatThrownBy(() -> execute(owner,
                    "UPDATE sys_project SET lifecycle_generation=-1 WHERE id=?", activeProject))
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
            assertThat(number(owner, "SELECT lifecycle_generation FROM sys_project WHERE id=?", activeProject))
                    .isZero();
        }
    }

    /** 迁移后的普通ACTIVE新项目默认代次0，未经历删除的项目不能被误撤销。 */
    private void assertNewActiveProjectDefaultsToGenerationZero(UUID tenantId) throws SQLException {
        UUID projectId = UUID.randomUUID();
        try (Connection owner = owner()) {
            execute(owner, """
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key,status)
                    VALUES (?,?,'代次默认项目','sh-1',?,'ACTIVE')
                    """, projectId, tenantId, projectKey(projectId));
            assertThat(number(owner, "SELECT lifecycle_generation FROM sys_project WHERE id=?", projectId))
                    .isZero();
        }
    }

    /** 写入四种旧项目事实，分别证明状态与deleted_at两个删除信号的回填。 */
    private Fixture seedLegacyProjects() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID deleting = UUID.randomUUID();
        UUID softDeleted = UUID.randomUUID();
        try (Connection owner = owner()) {
            execute(owner, "INSERT INTO sys_tenant(id,name,status) VALUES (?, '代次升级租户','ACTIVE')",
                    tenantId);
            insertProject(owner, tenantId, active, "ACTIVE", null);
            insertProject(owner, tenantId, archived, "ARCHIVED", null);
            insertProject(owner, tenantId, deleting, "DELETING", FIXED_AT);
            insertProject(owner, tenantId, softDeleted, "ACTIVE", FIXED_AT);
        }
        return new Fixture(tenantId, active, archived, deleting, softDeleted);
    }

    /** 写入旧项目字段；删除信号组合故意包含status与deleted_at各自单独命中。 */
    private void insertProject(Connection owner, UUID tenantId, UUID projectId,
                               String status, Instant deletedAt) throws SQLException {
        execute(owner, """
                INSERT INTO sys_project(id,tenant_id,name,region,project_key,status,deleted_at)
                VALUES (?,?,'代次旧项目','sh-1',?,?,?)
                """, projectId, tenantId, projectKey(projectId), status,
                deletedAt == null ? null : Timestamp.from(deletedAt));
    }

    /** 迁移前后完整项目字段快照；新增列不能改写状态、时间或项目标识。 */
    private List<String> projectFacts(boolean includeGeneration) throws SQLException {
        String generation = includeGeneration ? ", lifecycle_generation" : "";
        try (Connection owner = owner()) {
            return rows(owner, "SELECT row(id,tenant_id,name,region,project_key,status,deleted_at"
                    + generation + ")::text FROM sys_project ORDER BY id");
        }
    }

    /** project_key只允许字母数字下划线，UUID去连字符后稳定满足唯一约束。 */
    private static String projectKey(UUID projectId) {
        return "generation_" + projectId.toString().replace("-", "");
    }

    /** 连接独占迁移容器owner，用于旧事实种子和目录观察。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Flyway旧版本目标，先构造真实缺列数据库。 */
    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target(target)
                .load();
    }

    /** 只推进到被测0230，避免同时加载的IAM依赖位置把0240计入项目迁移断言。 */
    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target("20260904.0230")
                .load();
    }

    /** 参数化单值查询，所有SQL均设5秒边界，基础设施阻塞不会挂住全量验证。 */
    private String text(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            bind(query, values);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    /** bigint目录和业务值统一按long断言，避免generation越过integer上限后测试截断。 */
    private long number(Connection connection, String sql, Object... values) throws SQLException {
        return Long.parseLong(text(connection, sql, values));
    }

    /** 顺序读取第一列，用于稳定比较旧字段与升级重跑。 */
    private List<String> rows(Connection connection, String sql, Object... values) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            bind(query, values);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return List.copyOf(result);
    }

    /** 真实写入辅助不吞SQLSTATE，约束失败由调用测试精确断言。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    /** Instant显式绑定timestamptz兼容的Timestamp，其余参数沿JDBC原生类型。 */
    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
    }

    /** 升级后需要精确定位的四类项目事实。 */
    private record Fixture(UUID tenantId, UUID activeProject, UUID archivedProject,
                           UUID deletingProject, UUID softDeletedProject) {
    }
}
