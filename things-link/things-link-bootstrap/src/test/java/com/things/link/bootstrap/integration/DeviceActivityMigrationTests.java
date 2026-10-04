package com.things.link.bootstrap.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real predecessor database, including live and expired windows; no historical events are fabricated. */
@Testcontainers
class DeviceActivityMigrationTests {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("activity_upgrade").withUsername("thingslink").withPassword("thingslink");
    Flyway flyway(String target){return Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).locations("classpath:db/migration").placeholders(Map.of("app_role_password","thingslink")).target(target).load();}
    @Test void baselinesOnlyCurrentWindowsWithoutHistoryAndPreservesExistingColumns(){
        flyway("20260921.0270").migrate();
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),type=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'activity-upgrade')",tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES(?,?,'activity-upgrade','sh-1','activity_upgrade')",project,tenant);
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES(?,?,?,'upgrade','upgrade','STANDARD','DIRECT')",type,tenant,project);
        var devices=new ArrayList<UUID>();var before=new ArrayList<String>();
        for(int i=0;i<6;i++){
            UUID d=UUID.randomUUID();devices.add(d);
            jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES(?,?,?, ?,?,'upgrade','INACTIVE')",d,tenant,project,type,"d"+i);
            jdbc.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,enabled,last_activity_at) VALUES(?,?,?,?,?,CASE WHEN ?=5 THEN NULL ELSE clock_timestamp()-make_interval(secs=>?) END)",d,tenant,project,i==4?"TCP":i==1?"COAP":"HTTP",i!=3,i,i==2?600:30);
            before.add(jdbc.queryForObject("SELECT to_jsonb(b)::text FROM dev_access_binding b WHERE device_id=?",String.class,d));
        }
        assertThat(flyway("20260921.0280").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260921.0280").migrate().migrationsExecuted).isZero();
        for(int i=0;i<devices.size();i++){
            assertThat(jdbc.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,devices.get(i))).isEqualTo(i<2);
            assertThat(jdbc.queryForObject("SELECT (to_jsonb(b)-'activity_online')::text FROM dev_access_binding b WHERE device_id=?",String.class,devices.get(i))).isEqualTo(before.get(i));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(webhook_presence_revision) FROM dev_device WHERE project_id=?",Long.class,project)).isZero();
    }
}
