package com.things.link.bootstrap.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR0193：真实前序升级只关闭旧MQTT未知认证快照，不修改原生权威或伪造公开历史。 */
@Testcontainers
class MqttConfigurationIdentityMigrationTests {
    /** 独占容器在JUnit用例完成后释放，不复用全局数据库角色。 */
    @Container
    final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse(
            "timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mqtt_config_upgrade").withUsername("thingslink").withPassword("thingslink");

    /** 指定真实前序迁移或当前升级目标。 */
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration").placeholders(Map.of("app_role_password", "thingslink"))
                .target(target).load();
    }

    /** 数据和旧连接确实存在后升级，历史行完整比较并验证重跑无二次清理。 */
    @Test
    void closesUnknownMqttOnlyAndPreservesNativeAndClosedHistory() {
        flyway("20260921.0300").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        UUID tenant=UUID.randomUUID(), project=UUID.randomUUID();
        UUID mqtt=UUID.randomUUID(), explicit=UUID.randomUUID(), http=UUID.randomUUID(), tcp=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'mqtt-upgrade')",tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'mqtt-upgrade','mqtt_upgrade')",project,tenant);
        for(UUID id:new UUID[]{mqtt,explicit,http,tcp}) {
            jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,status) VALUES(?,?,?,?,?,'ONLINE')",id,tenant,project,id.toString(),"upgrade");
        }
        jdbc.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'MQTT'),(?,?,?,'HTTP'),(?,?,?,'TCP')",
                explicit,tenant,project,http,tenant,project,tcp,tenant,project);
        for(UUID id:new UUID[]{mqtt,explicit,http}) {
            jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol) VALUES(gen_random_uuid(),?,?,?,'MQTT')",tenant,project,id);
        }
        UUID closed=UUID.randomUUID(), nativeRow=UUID.randomUUID();
        jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol,disconnected_at,disconnect_reason) VALUES(?,?,?,?,'MQTT',clock_timestamp(),'normal')",closed,tenant,project,mqtt);
        jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol,last_seen_at,heartbeat_interval_millis) VALUES(?,?,?,?,'TCP',clock_timestamp(),10000)",nativeRow,tenant,project,tcp);
        String closedBefore=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,closed);
        String nativeBefore=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,nativeRow);
        assertThat(flyway("20260921.0310").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_connection WHERE disconnect_reason='access_config_contract_upgrade'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,mqtt)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,explicit)).isEqualTo("OFFLINE");
        for(UUID id:new UUID[]{http,tcp}) assertThat(jdbc.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,id)).isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,closed)).isEqualTo(closedBefore);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,nativeRow)).isEqualTo(nativeBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(webhook_presence_revision) FROM dev_device WHERE project_id=?",Long.class,project)).isZero();
        jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol) VALUES(gen_random_uuid(),?,?,?,'MQTT')",tenant,project,mqtt);
        assertThat(flyway("20260921.0310").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_connection WHERE protocol='MQTT' AND disconnected_at IS NULL",Integer.class)).isEqualTo(1);
    }
}
