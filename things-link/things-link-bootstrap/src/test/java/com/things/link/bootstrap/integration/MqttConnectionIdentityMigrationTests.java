package com.things.link.bootstrap.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实0320前序数据升级到0330，不以最新空库通过替代历史兼容。 */
@Testcontainers
class MqttConnectionIdentityMigrationTests {
    /** 本类独占数据库，角色及全部历史夹具随容器回收。 */
    @Container final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse(
            "timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mqtt_connection_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 固定升级目标，禁止无意执行未来迁移改变本合同。 */
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword())
                .locations("classpath:db/migration").placeholders(Map.of("app_role_password","thingslink")).target(target).load();
    }
    /** 旧MQTT/网关子设备收敛，原生协议、已关闭历史及已知原身份保持原事实。 */
    @Test void legacyConnectionsAndTopologyConvergeWithoutInventingWebhookHistory() {
        flyway("20260921.0320").migrate();
        var source = new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
        var jdbc = new JdbcTemplate(source);
        UUID tenant=UUID.randomUUID(), project=UUID.randomUUID(), mqtt=UUID.randomUUID(), explicit=UUID.randomUUID();
        UUID http=UUID.randomUUID(), tcp=UUID.randomUUID(), gateway=UUID.randomUUID(), child=UUID.randomUUID(), known=UUID.randomUUID();
        UUID gatewayType=UUID.randomUUID(), childType=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'connection-upgrade')",tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'connection-upgrade','mqtt_connection_upgrade')",project,tenant);
        for(var entry:Map.of(gatewayType,"GATEWAY",childType,"SUB_DEVICE").entrySet())
            jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES(?,?,?,?,'upgrade',?,?)",
                    entry.getKey(),tenant,project,"t"+entry.getKey().toString().replace("-",""),entry.getValue().equals("GATEWAY")?"STANDARD_GATEWAY":"STANDARD",entry.getValue());
        for(UUID id:new UUID[]{mqtt,explicit,http,tcp,gateway,child,known})
            jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES(?,?,?,?,?,'upgrade','ONLINE')",
                    id,tenant,project,id.equals(gateway)?gatewayType:id.equals(child)?childType:null,"d"+id.toString().replace("-",""));
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s->{
            jdbc.update("INSERT INTO dev_topo(id,tenant_id,project_id,gateway_device_id,sub_device_id,bind_source,online_status,last_online_at,status_changed_at) VALUES(gen_random_uuid(),?,?,?,?,'CONTROL_PLANE','ONLINE',clock_timestamp(),clock_timestamp())",tenant,project,gateway,child);
            jdbc.update("UPDATE dev_device SET gateway_id=? WHERE id=?",gateway,child);return null;
        });
        jdbc.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'MQTT'),(?,?,?,'HTTP'),(?,?,?,'TCP')",
                explicit,tenant,project,http,tenant,project,tcp,tenant,project);
        for(UUID id:new UUID[]{mqtt,explicit,http,gateway})
            jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol) VALUES(gen_random_uuid(),?,?,?,'MQTT')",tenant,project,id);
        UUID closed=UUID.randomUUID(), nativeId=UUID.randomUUID(), ticket=UUID.randomUUID();
        jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol,disconnected_at,disconnect_reason) VALUES(?,?,?,?,'MQTT',clock_timestamp(),'normal')",closed,tenant,project,mqtt);
        jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol,last_seen_at,heartbeat_interval_millis) VALUES(?,?,?,?,'TCP',clock_timestamp(),10000)",nativeId,tenant,project,tcp);
        String session="tc-device-"+"b".repeat(64);
        jdbc.update("INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id,state) VALUES(?,?,?,?,1,0,?,'ACTIVE')",ticket,tenant,project,known,session);
        jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version,mqtt_connection_id) VALUES(gen_random_uuid(),?,?,?,?,'MQTT',0,?)",tenant,project,known,session,ticket);
        String oldHistory=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,closed);
        String nativeHistory=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,nativeId);
        String knownHistory=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE mqtt_connection_id=?",String.class,ticket);
        assertThat(flyway("20260921.0330").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_connection WHERE disconnect_reason='mqtt_connection_identity_upgrade'",Integer.class)).isEqualTo(4);
        for(UUID id:new UUID[]{mqtt,explicit,gateway,child}) assertThat(jdbc.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,id)).isEqualTo("OFFLINE");
        for(UUID id:new UUID[]{http,tcp,known}) assertThat(jdbc.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,id)).isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("SELECT online_status FROM dev_topo WHERE sub_device_id=?",String.class,child)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT version FROM dev_topo WHERE sub_device_id=?",Integer.class,child)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,closed)).isEqualTo(oldHistory);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,nativeId)).isEqualTo(nativeHistory);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE mqtt_connection_id=?",String.class,ticket)).isEqualTo(knownHistory);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(webhook_presence_revision) FROM dev_device WHERE project_id=?",Long.class,project)).isZero();
        assertThat(flyway("20260921.0330").migrate().migrationsExecuted).isZero();
    }
}
