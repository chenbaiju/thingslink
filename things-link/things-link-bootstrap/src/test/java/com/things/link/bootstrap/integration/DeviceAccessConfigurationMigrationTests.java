package com.things.link.bootstrap.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0193的真实前序升级、历史错误阻断及数据库版本守卫验收。 */
@Testcontainers
class DeviceAccessConfigurationMigrationTests {
    /** 每用例独占数据库容器及全局角色，无Spring上下文，JUnit在用例结束后释放。 */
    @Container
    final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse(
            "timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("access_config_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 数据库所有者连接，仅用于构造历史数据及验证约束。 */
    private JdbcTemplate jdbc;
    /** 本用例隔离的租户、项目、设备。 */
    private UUID tenant, project, device;

    /** 构造指定迁移目标，禁止借最新空库冒充真实前序升级。 */
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").placeholders(Map.of("app_role_password", "thingslink"))
                .target(target).load();
    }

    /** 每个用例回到0290，旧记录在新迁移运行前已真实存在。 */
    @BeforeEach
    void previousSchema() {
        flyway("20260921.0290").migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        tenant = UUID.randomUUID(); project = UUID.randomUUID(); device = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'config-upgrade')", tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'config-upgrade','config_upgrade')", project, tenant);
        jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,status) VALUES(?,?,?,'upgrade','upgrade','ONLINE')", device, tenant, project);
    }

    /** 切回MQTT仍沿用旧行代次；活动更新不增代次，非法配置改写均由数据库拒绝。 */
    @Test
    void mqttReturnPreservesEpochAndRejectsUnversionedChanges() {
        jdbc.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'TCP',7)", device, tenant, project);
        jdbc.update("UPDATE dev_access_binding SET enabled=false WHERE device_id=?", device);
        assertThat(jdbc.queryForObject("SELECT config_version FROM dev_access_binding WHERE device_id=?", Long.class, device))
                .as("旧模型允许无代次禁用，升级后必须阻止").isEqualTo(7);
        jdbc.update("UPDATE dev_access_binding SET enabled=true WHERE device_id=?", device);
        assertThatThrownBy(() -> jdbc.update("UPDATE dev_access_binding SET protocol='MQTT',config_version=8 WHERE device_id=?", device))
                .as("前序真实模型不接受显式MQTT").isInstanceOf(DataAccessException.class);
        flyway("20260921.0300").migrate();
        jdbc.update("UPDATE dev_access_binding SET protocol='MQTT',config_version=8 WHERE device_id=?", device);
        assertThat(jdbc.queryForObject("SELECT config_version FROM dev_access_binding WHERE device_id=?", Long.class, device)).isEqualTo(8);
        jdbc.update("UPDATE dev_access_binding SET last_activity_at=clock_timestamp() WHERE device_id=?", device);
        for (String change : new String[]{"protocol='HTTP'", "enabled=false", "heartbeat_seconds=30",
                "config_version=7", "config_version=10", "tenant_id=gen_random_uuid()", "device_id=gen_random_uuid()"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE dev_access_binding SET " + change + " WHERE device_id=?", device))
                    .as(change).isInstanceOf(DataAccessException.class);
        }
        jdbc.update("UPDATE dev_access_binding SET enabled=false,config_version=9 WHERE device_id=?", device);
        assertThat(flyway("20260921.0300").migrate().migrationsExecuted).isZero();
    }

    /** 历史设备错属必须阻止整体迁移，错误修正后才能升级，不能静默重写。 */
    @Test
    void wrongHistoricalScopeFailsAtomicallyAndCanBeCorrected() {
        UUID wrong = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'wrong')", wrong);
        jdbc.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')", device, wrong, project);
        assertThatThrownBy(() -> flyway("20260921.0300").migrate()).hasStackTraceContaining("ACCESS_BINDING_SCOPE_INVALID");
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM dev_access_binding WHERE device_id=?", UUID.class, device)).isEqualTo(wrong);
        assertThatThrownBy(() -> jdbc.update("UPDATE dev_access_binding SET protocol='MQTT' WHERE device_id=?", device))
                .as("失败迁移不遗留部分协议约束").isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE dev_access_binding SET tenant_id=? WHERE device_id=?", tenant, device);
        assertThat(flyway("20260921.0300").migrate().migrationsExecuted).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'MQTT')",
                UUID.randomUUID(), tenant, project)).hasStackTraceContaining("ACCESS_BINDING_SCOPE_INVALID");
    }

    /** 基础模型升级不提前关闭旧MQTT；认证身份升级清理属于后续6c-2b。 */
    @Test
    void modelMigrationPreservesLegacyMqttAndCreatesNoPublicHistory() {
        UUID connection = UUID.randomUUID();
        jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol) VALUES(?,?,?,?,'MQTT')", connection, tenant, project, device);
        String before = jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?", String.class, connection);
        assertThat(flyway("20260921.0300").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?", String.class, connection)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_access_binding", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?", Long.class, device)).isZero();
    }
}
