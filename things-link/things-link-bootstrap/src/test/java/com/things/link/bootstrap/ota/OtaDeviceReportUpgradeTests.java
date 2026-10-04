package com.things.link.bootstrap.ota;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** 独占0610旧库依次升级设备报告与清理接线，不改变已有迁移或共享测试数据库。 */
@Testcontainers
class OtaDeviceReportUpgradeTests {
    /** 独立数据库角色和历史版本环境。 */
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ota_report_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 与应用相同的完整迁移位置。 */
    private static final String[] LOCATIONS={"classpath:db/migration/support","classpath:db/migration/project",
            "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
            "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/iam",
            "classpath:db/migration/enduser","classpath:db/migration/export","classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** 原项目保持，新增对象注释及RLS生效，两次增量均只执行一次。 */
    @Test void upgradesDeviceReportAndCleanupFromPriorRelease() {
        flyway("20260912.0610").migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),type=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'升级前租户')",tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'升级前项目',?)",project,tenant,"baseline_"+project.toString().replace("-",""));
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol) VALUES (?,?,?,'baseline-type','升级前类型','DIRECT','STANDARD')",type,tenant,project);
        assertThat(flyway("20260912.0620").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260912.0630").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM sys_project WHERE id=?",String.class,project)).isEqualTo("升级前项目");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname='ota_device_report' AND relforcerowsecurity",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_attribute a WHERE a.attrelid='ota_device_report'::regclass AND a.attnum>0 AND NOT a.attisdropped AND col_description(a.attrelid,a.attnum) IS NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('thingslink_app','ota_device_report','DELETE')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT pg_get_functiondef('ota_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure)",String.class))
                .contains("ota_device_report");
        assertThat(jdbc.queryForObject("SELECT name FROM dev_type WHERE id=?",String.class,type)).isEqualTo("升级前类型");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='ota_device_report'::regclass AND conname='ota_device_report_device_fk' AND contype='f'",Integer.class)).isEqualTo(1);
        assertThat(flyway("20260912.0630").migrate().migrationsExecuted).isZero();
    }
    /** 显式目标版本及容器内应用角色口令。 */
    private Flyway flyway(String target) {return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
            .locations(LOCATIONS).placeholders(Map.of("app_role_password","thingslink")).target(target).load();}
}
