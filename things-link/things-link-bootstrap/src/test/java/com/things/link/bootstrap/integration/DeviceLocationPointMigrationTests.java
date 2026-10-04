package com.things.link.bootstrap.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 有旧文本位置的真实前序库升级，不用最新空库冒充兼容证据。 */
@Testcontainers
class DeviceLocationPointMigrationTests {
    @Container final PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse(
            "timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("location_upgrade").withUsername("thingslink").withPassword("thingslink");
    Flyway flyway(String target) { return Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").placeholders(Map.of("app_role_password","thingslink")).target(target).load(); }
    @Test void previousTextSurvivesAndPointTypeIndexAndConstraintsAreReal() {
        flyway("20260922.0470").migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'geo-upgrade')",tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'geo-upgrade','geo_upgrade')",project,tenant);
        jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,location) VALUES(?,?,?,'geo','geo','旧机房地址')",device,tenant,project);
        assertThat(flyway("20260922.0480").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT location='旧机房地址' AND location_point IS NULL AND location_point_version=0 FROM dev_device WHERE id=?",Boolean.class,device)).isTrue();
        assertThat(jdbc.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='dev_device'::regclass AND attname='location_point'",String.class)).isEqualTo("geography(Point,4326)");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='dev_device_location_point_idx'",String.class)).contains("USING gist (location_point)");
        assertThatThrownBy(()->jdbc.update("UPDATE dev_device SET location_point_version=-1 WHERE id=?",device)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE dev_device SET location_point=ST_GeogFromText('LINESTRING(0 0,1 1)') WHERE id=?",device)).isInstanceOf(DataAccessException.class);
        assertThat(flyway("20260922.0480").migrate().migrationsExecuted).isZero();
    }
}
