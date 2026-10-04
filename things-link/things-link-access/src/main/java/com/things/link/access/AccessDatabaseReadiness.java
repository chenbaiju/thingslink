package com.things.link.access;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 生产接入进程只用应用角色确认核心设备模式和项目 RLS 已由平台迁移就绪。 */
@Component
@Profile("prod")
final class AccessDatabaseReadiness implements InitializingBean {

    private static final Map<String, String[]> REQUIRED = Map.of(
            "dev_device", new String[]{"device_key"},
            "dev_credential", new String[]{"credential_hash"},
            "dev_connection", new String[]{"generation", "config_version", "mqtt_connection_id"},
            "dev_access_binding", new String[]{"config_version", "last_activity_at"},
            "dev_access_request", new String[]{"message_id", "payload_digest"},
            "dev_mqtt_connection_ticket", new String[]{"auth_order", "state"},
            "dev_mqtt_session_cursor", new String[]{"max_observed_order"});

    private final DataSource dataSource;

    AccessDatabaseReadiness(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        verify(dataSource);
    }

    static void verify(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT current_user = 'thingslink_app' AND NOT rolsuper AND NOT rolbypassrls "
                            + "FROM pg_catalog.pg_roles WHERE rolname = current_user");
                    ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getBoolean(1), "spring.datasource.username");
            }
            for (var table : REQUIRED.entrySet()) {
                verifyTable(connection, table.getKey(), table.getValue());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Device-access database readiness failed", exception);
        }
    }

    private static void verifyTable(Connection connection, String table, String[] columns) throws SQLException {
        String sql = "SELECT c.relrowsecurity, NOT pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER'), "
                + "pg_catalog.has_table_privilege(c.oid, 'SELECT'), "
                + "pg_catalog.has_table_privilege(c.oid, 'INSERT'), "
                + "pg_catalog.has_table_privilege(c.oid, 'UPDATE'), "
                + "EXISTS (SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid = c.oid "
                + "AND p.polname = 'project_isolation') "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'public' AND c.relname = ? AND c.relkind IN ('r', 'p')";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getBoolean(1) && result.getBoolean(2)
                        && result.getBoolean(3) && result.getBoolean(4) && result.getBoolean(5)
                        && result.getBoolean(6), "public." + table);
            }
        }
        for (String column : columns) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a "
                            + "WHERE a.attrelid = pg_catalog.to_regclass(?) AND a.attname = ? "
                            + "AND a.attnum > 0 AND NOT a.attisdropped)")) {
                statement.setString(1, "public." + table);
                statement.setString(2, column);
                try (ResultSet result = statement.executeQuery()) {
                    require(result.next() && result.getBoolean(1), "public." + table + "." + column);
                }
            }
        }
    }

    private static void require(boolean ready, String key) {
        if (!ready) {
            throw new IllegalStateException("Device-access database is not ready: " + key);
        }
    }
}
