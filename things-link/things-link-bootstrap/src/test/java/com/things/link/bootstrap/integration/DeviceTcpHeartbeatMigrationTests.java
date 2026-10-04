package com.things.link.bootstrap.integration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** A real old active TCP row has no provable interval; stop/reconnect upgrade must not fabricate one. */
@Testcontainers
class DeviceTcpHeartbeatMigrationTests {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("tcp_upgrade").withUsername("thingslink").withPassword("thingslink");
    Flyway flyway(String target){return Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).locations("classpath:db/migration").placeholders(Map.of("app_role_password","thingslink")).target(target).load();}
    @Test void explicitlyClosesOnlyUnknownActiveTcpAndRequiresActualNewContract(){
        flyway("20260921.0280").migrate();var jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),type=UUID.randomUUID(),device=UUID.randomUUID(),tcp=UUID.randomUUID(),closed=UUID.randomUUID(),mqtt=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'tcp-upgrade')",tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'tcp-upgrade','tcp_upgrade')",project,tenant);
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES(?,?,?,'upgrade','upgrade','STANDARD','DIRECT')",type,tenant,project);
        jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES(?,?,?,?,'upgrade','upgrade','ONLINE')",device,tenant,project,type);
        for(UUID id:List.of(tcp,closed,mqtt))jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,owner_instance,connected_at,last_seen_at,disconnected_at,disconnect_reason) VALUES(?,?,?,?,?,?,?,clock_timestamp()-interval '2 minutes',clock_timestamp()-interval '1 minute',CASE WHEN ? THEN clock_timestamp() ELSE NULL END,?)",id,tenant,project,device,id.toString(),id.equals(mqtt)?"MQTT":"TCP",id.equals(mqtt)?null:"old-node",id.equals(closed),id.equals(closed)?"normal":null);
        String oldClosed=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,closed),oldMqtt=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM dev_connection c WHERE id=?",String.class,mqtt);
        assertThat(flyway("20260921.0290").migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway("20260921.0290").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT disconnect_reason FROM dev_connection WHERE id=?",String.class,tcp)).isEqualTo("heartbeat_contract_upgrade");
        assertThat(jdbc.queryForObject("SELECT disconnected_at IS NOT NULL AND heartbeat_interval_millis IS NULL FROM dev_connection WHERE id=?",Boolean.class,tcp)).isTrue();
        assertThat(jdbc.queryForObject("SELECT (to_jsonb(c)-'heartbeat_interval_millis')::text FROM dev_connection c WHERE id=?",String.class,closed)).isEqualTo(oldClosed);
        assertThat(jdbc.queryForObject("SELECT (to_jsonb(c)-'heartbeat_interval_millis')::text FROM dev_connection c WHERE id=?",String.class,mqtt)).isEqualTo(oldMqtt);
        assertThatThrownBy(()->jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol) VALUES(gen_random_uuid(),?,?,?,'TCP')",tenant,project,device)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID fresh=UUID.randomUUID();jdbc.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,protocol,last_seen_at,heartbeat_interval_millis) VALUES(?,?,?,?,'TCP',clock_timestamp(),10000)",fresh,tenant,project,device);
        assertThatThrownBy(()->jdbc.update("UPDATE dev_connection SET heartbeat_interval_millis=20000 WHERE id=?",fresh)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
        assertThat(jdbc.queryForObject("SELECT webhook_presence_revision FROM dev_device WHERE id=?",Long.class,device)).isZero();
    }
}
