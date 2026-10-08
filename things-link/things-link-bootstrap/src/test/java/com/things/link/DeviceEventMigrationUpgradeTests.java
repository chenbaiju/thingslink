package com.things.link;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** BE-001-B：真实旧候选Inbox升级，旧未知元数据不补造身份或更改摘要。 */
@Testcontainers
class DeviceEventMigrationUpgradeTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("event_migration_upgrade").withUsername("thingslink").withPassword("thingslink");
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser",
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota",
            "classpath:db/migration/integration", "classpath:db/migration/assistant"};
    @Test
    void formerCandidateUpgradesWithoutRewritingOldInboxAndSecondMigrationIsNoop() throws Exception {
        flyway("20261006.0900").migrate();
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), message = UUID.randomUUID();
        String original;
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            sql.update("INSERT INTO sys_tenant(id,name) VALUES (?,'旧候选升级租户')", tenant);
            sql.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'旧候选升级','sh-1',?)",
                    project, tenant, "upgrade_" + project.toString().replace("-", ""));
            sql.update("INSERT INTO sys_inbox_message(message_id,project_id,received_at) VALUES (?,?,clock_timestamp())", message, project);
            original = sql.queryForObject("SELECT row_to_json(i)::text FROM sys_inbox_message i WHERE message_id=?", String.class, message);
        }
        Flyway latest = flyway(null);
        FlywayCompatibilityConfiguration.migrateWithCompatibility(latest);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            assertThat(sql.queryForObject("SELECT message_kind FROM sys_inbox_message WHERE message_id=?", String.class, message)).isEqualTo("PROPERTY_REPORT");
            assertThat(sql.queryForObject("SELECT (to_jsonb(i)-'message_kind')::text FROM sys_inbox_message i WHERE message_id=?", String.class, message))
                    .isEqualTo(sql.queryForObject("SELECT ?::jsonb::text", String.class, original));
            assertThat(sql.queryForObject("SELECT thing_model_version_id IS NULL AND payload_digest IS NULL FROM sys_inbox_message WHERE message_id=?", Boolean.class, message)).isTrue();
            assertThat(sql.queryForObject("SELECT count(*) FROM ts_device_event", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND script IN "
                    + "('V20261006_0910__device_event_scope_keys.sql','V20261006_0920__device_event_occurrence.sql')", Long.class)).isEqualTo(2);
        }
    }
    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", "thingslink"));
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
