package com.things.link.bootstrap.project.lifecycle;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实旧库升级保留项目全部原有事实，为描述补空值及长度约束。 */
@Testcontainers
class ProjectDescriptionMigrationTests {
    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_description_upgrade")
            .withUsername("thingslink").withPassword("thingslink");

    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser",
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota",
            "classpath:db/migration/assistant"
    };

    @Test
    void upgradesExistingProjectsWithoutChangingFactsAndEnforcesDescriptionBound() throws Exception {
        flyway("20261006.0920").migrate();
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        String before;
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "thingslink", "thingslink")) {
            try (var insert = connection.prepareStatement("INSERT INTO sys_tenant(id,name) VALUES (?,'描述升级租户')")) {
                insert.setObject(1, tenant);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'旧项目','sh-1','description_upgrade')")) {
                insert.setObject(1, project);
                insert.setObject(2, tenant);
                insert.executeUpdate();
            }
            before = facts(connection);
        }
        assertThat(flyway("20261007.0100").migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "thingslink", "thingslink")) {
            assertThat(facts(connection)).isEqualTo(before);
            try (var rows = connection.createStatement().executeQuery("SELECT description FROM sys_project WHERE project_key='description_upgrade'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEmpty();
            }
            try (var update = connection.prepareStatement("UPDATE sys_project SET description=? WHERE id=?")) {
                update.setString(1, "描".repeat(1000));
                update.setObject(2, project);
                assertThat(update.executeUpdate()).isOne();
                update.setString(1, "描".repeat(1001));
                assertThatThrownBy(update::executeUpdate).isInstanceOf(java.sql.SQLException.class);
                update.setString(1, null);
                assertThatThrownBy(update::executeUpdate).isInstanceOf(java.sql.SQLException.class);
            }
        }
        assertThat(flyway("20261007.0100").migrate().migrationsExecuted).isZero();
    }

    /** 排除新描述列后比较既有行，保证迁移未改名或生命周期等事实。 */
    private String facts(java.sql.Connection connection) throws Exception {
        try (var rows = connection.createStatement().executeQuery("SELECT (to_jsonb(p)-'description')::text FROM sys_project p WHERE project_key='description_upgrade'")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    /** 使用生产迁移全集与实际前驱，保留跨域依赖。 */
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink")
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", "thingslink"))
                .target(target).load();
    }
}
