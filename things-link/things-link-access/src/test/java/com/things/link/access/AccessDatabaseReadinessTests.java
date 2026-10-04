package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class AccessDatabaseReadinessTests {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("access_readiness")
            .withUsername("thingslink")
            .withPassword("thingslink");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void requiresApplicationRoleMigratedColumnsAndProjectRlsBeforeAcceptingTraffic() throws Exception {
        var owner = source(POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = owner.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE thingslink_app LOGIN PASSWORD 'access-role-test-secret'");
        }
        var application = source("thingslink_app", "access-role-test-secret");
        assertThatThrownBy(() -> AccessDatabaseReadiness.verify(owner))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.datasource.username");
        assertThatThrownBy(() -> AccessDatabaseReadiness.verify(application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("public.dev_");

        try (Connection connection = owner.getConnection(); Statement statement = connection.createStatement()) {
            for (String declaration : new String[]{
                    "dev_device (device_key text)",
                    "dev_credential (credential_hash text)",
                    "dev_connection (generation bigint, config_version bigint, mqtt_connection_id uuid)",
                    "dev_access_binding (config_version bigint, last_activity_at timestamptz)",
                    "dev_access_request (message_id uuid, payload_digest text)",
                    "dev_mqtt_connection_ticket (auth_order bigint, state text)",
                    "dev_mqtt_session_cursor (max_observed_order bigint)"}) {
                String table = declaration.substring(0, declaration.indexOf(' '));
                statement.execute("CREATE TABLE public." + table + " (project_id uuid, "
                        + declaration.substring(declaration.indexOf('(') + 1));
                statement.execute("ALTER TABLE public." + table + " ENABLE ROW LEVEL SECURITY");
                statement.execute("CREATE POLICY project_isolation ON public." + table
                        + " USING (project_id = nullif(current_setting('app.project_id', true), '')::uuid)"
                        + " WITH CHECK (project_id = nullif(current_setting('app.project_id', true), '')::uuid)");
                statement.execute("GRANT SELECT, INSERT, UPDATE ON public." + table + " TO thingslink_app");
            }
        }
        assertThatCode(() -> AccessDatabaseReadiness.verify(application)).doesNotThrowAnyException();
        try (Connection connection = owner.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.dev_access_request DISABLE ROW LEVEL SECURITY");
        }
        assertThatThrownBy(() -> AccessDatabaseReadiness.verify(application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("public.dev_access_request");
        try (Connection connection = owner.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.dev_access_request ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE public.dev_access_binding DROP COLUMN last_activity_at");
        }
        assertThatThrownBy(() -> AccessDatabaseReadiness.verify(application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev_access_binding.last_activity_at");
        try (Connection connection = owner.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.dev_access_binding ADD COLUMN last_activity_at timestamptz");
            statement.execute("REVOKE INSERT ON public.dev_access_request FROM thingslink_app");
        }
        assertThatThrownBy(() -> AccessDatabaseReadiness.verify(application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("public.dev_access_request");
    }

    private static DriverManagerDataSource source(String user, String password) {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
    }
}
