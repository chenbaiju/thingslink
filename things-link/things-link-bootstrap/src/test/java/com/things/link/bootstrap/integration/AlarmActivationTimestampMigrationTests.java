package com.things.link.bootstrap.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实0330历史升级0340；错误证据必须连同可修复行整体回滚。 */
@Testcontainers
class AlarmActivationTimestampMigrationTests {
    @Container final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse(
            "timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("alarm_history_upgrade").withUsername("thingslink").withPassword("thingslink");
    private JdbcTemplate jdbc;
    private UUID tenant, project, device;
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration").placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }
    @BeforeEach void oldDatabase() {
        flyway("20260921.0330").migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        tenant = UUID.randomUUID(); project = UUID.randomUUID(); device = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES(?,'alarm-history')", tenant);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'alarm-history','alarm_history_upgrade')", project, tenant);
        jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,status) VALUES(?,?,?,'history-device','history','OFFLINE')", device, tenant, project);
    }
    @Test void repairUsesOnlyOriginalEventAndPreservesAllOtherFacts() {
        UUID broken = instance("CLEARED", "AUTO_RECOVERY", 20, 3); activation(broken, 5, "ACTIVE");
        UUID correct = instance("CLEARED", "AUTO_RECOVERY", 5, 7); activation(correct, 5, "ACTIVE");
        UUID equal = instance("CLEARED", "AUTO_RECOVERY", 20, 9); activation(equal, 20, "ACTIVE");
        UUID manual = instance("CLEARED", "MANUAL", 20, 1);
        UUID candidate = instance("CLEARED", "AUTO_RECOVERY", null, 2);
        UUID active = instance("ACTIVE", null, 5, 8);
        var snapshots = new LinkedHashMap<UUID, String>();
        for (UUID id : List.of(correct, equal, manual, candidate, active)) snapshots.put(id, row(id));
        String untouched = jdbc.queryForObject("SELECT (to_jsonb(i)-'activated_at'-'version')::text FROM alarm_instance i WHERE id=?", String.class, broken);
        String events = events();
        assertThat(flyway("20260921.0340").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT activated_at FROM alarm_instance WHERE id=?", Timestamp.class, broken).toInstant()).isEqualTo(START.plusSeconds(5));
        assertThat(jdbc.queryForObject("SELECT version FROM alarm_instance WHERE id=?", Integer.class, broken)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT (to_jsonb(i)-'activated_at'-'version')::text FROM alarm_instance i WHERE id=?", String.class, broken)).isEqualTo(untouched);
        snapshots.forEach((id, text) -> assertThat(row(id)).isEqualTo(text)); assertThat(events()).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event", Integer.class)).isZero();
        assertThat(flyway("20260921.0340").migrate().migrationsExecuted).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "ARCHIVED", "DELETING"})
    void recoverableProjectsAreIncluded(String status) {
        UUID id = instance("CLEARED", "AUTO_RECOVERY", 20, 0); activation(id, 4, "ACTIVE");
        jdbc.update("UPDATE sys_project SET status=?,deleted_at=CASE WHEN ?='DELETING' THEN clock_timestamp() ELSE NULL END WHERE id=?", status, status, project);
        flyway("20260921.0340").migrate();
        assertThat(jdbc.queryForObject("SELECT activated_at FROM alarm_instance WHERE id=?", Timestamp.class, id).toInstant()).isEqualTo(START.plusSeconds(4));
    }
    @Test void partiallyPurgedProjectWithNoEventsIsNotMisclassifiedAsCorruption() {
        UUID id = instance("CLEARED", "AUTO_RECOVERY", 20, 0); String before = row(id);
        jdbc.update("UPDATE sys_project SET status='PURGING',deleted_at=clock_timestamp(),cleanup_stage='ALARM',cleanup_started_at=clock_timestamp(),cleanup_next_attempt_at=clock_timestamp() WHERE id=?", project);
        flyway("20260921.0340").migrate(); assertThat(row(id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING", "DUPLICATE", "EARLY", "LATE", "STATE", "VERSION"})
    void invalidEvidenceRollsBackWholeMigrationAndCanRecoverWithoutSkipping(String failure) {
        UUID valid = instance("CLEARED", "AUTO_RECOVERY", 20, 2); activation(valid, 5, "ACTIVE");
        UUID bad = instance("CLEARED", "AUTO_RECOVERY", 20, failure.equals("VERSION") ? Integer.MAX_VALUE : 0);
        if (!failure.equals("MISSING")) activation(bad, failure.equals("EARLY") ? -1 : failure.equals("LATE") ? 21 : 5, failure.equals("STATE") ? "CLEARED" : "ACTIVE");
        if (failure.equals("DUPLICATE")) activation(bad, 6, "ACTIVE");
        String validBefore = row(valid), badBefore = row(bad), eventsBefore = events();
        assertThatThrownBy(() -> flyway("20260921.0340").migrate()).hasStackTraceContaining("ALARM_ACTIVATION_RECONCILIATION_REQUIRED");
        assertThat(row(valid)).isEqualTo(validBefore); assertThat(row(bad)).isEqualTo(badBefore); assertThat(events()).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='20260921.0340'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event", Integer.class)).isZero();
        if (failure.equals("MISSING")) {
            // 本夹具握有被故意延迟插入的原证据；真实运维不得凭空仿造此步骤。
            activation(bad, 7, "ACTIVE");
            assertThat(flyway("20260921.0340").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT activated_at FROM alarm_instance WHERE id=?", Timestamp.class, bad).toInstant()).isEqualTo(START.plusSeconds(7));
        }
    }
    private UUID instance(String state, String reason, Integer activatedSeconds, int version) {
        UUID rule = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.update("INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES (?,?,?,?, 'TYPE',?,'value','GT',30,'LT',25,'WARNING')", rule, tenant, project, rule.toString(), device);
        jdbc.update("INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,clear_reason,first_condition_at,activated_at,cleared_at,last_received_at,last_occurred_at,last_value,version,created_at,updated_at) VALUES(?,?,?,?,'DEVICE',?,'TYPE','WARNING',?,?,?,?,?,?,?,20,?,?,?)",
                id, tenant, project, rule, device, state, reason, Timestamp.from(START), activatedSeconds == null ? null : Timestamp.from(START.plusSeconds(activatedSeconds)),
                state.equals("CLEARED") ? Timestamp.from(START.plusSeconds(20)) : null, Timestamp.from(START.plusSeconds(20)), Timestamp.from(START.plusSeconds(19)), version, Timestamp.from(START), Timestamp.from(START.plusSeconds(21)));
        return id;
    }
    private void activation(UUID id, int seconds, String state) {
        jdbc.update("INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,trace_id,value,occurred_at,received_at,condition_state,ack_state) VALUES(gen_random_uuid(),?,?,?,'ACTIVATED',gen_random_uuid(),'historical-source',40,?,?,?,'UNACKNOWLEDGED')",
                tenant, project, id, Timestamp.from(START.plusSeconds(seconds)), Timestamp.from(START.plusSeconds(seconds)), state);
    }
    private String row(UUID id) { return jdbc.queryForObject("SELECT to_jsonb(i)::text FROM alarm_instance i WHERE id=?", String.class, id); }
    private String events() { return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(e) ORDER BY id)::text FROM alarm_event e", String.class); }
}
