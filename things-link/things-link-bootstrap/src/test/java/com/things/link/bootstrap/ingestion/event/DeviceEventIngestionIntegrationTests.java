package com.things.link.bootstrap.ingestion.event;

import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition.TransitionType;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.QuotaMetric;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.EventIngestionService;
import com.things.link.telemetry.application.MessageLogService;
import com.things.link.telemetry.application.ProjectIngestionRejectedException;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.telemetry.application.PropertyReportMessage;
import com.things.link.telemetry.infrastructure.persistence.TelemetryDailyUsageContributor;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** BE-001-B：独占真实PG的原事务、全scope幂等、额度、模型与字节事实，未造事件发生行。 */
@Import(DeviceEventIngestionIntegrationTests.IsolatedDatabase.class)
@OwnedTestContainers({"PROBE_POSTGRES"})
class DeviceEventIngestionIntegrationTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> PROBE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_event_" + UUID.randomUUID().toString().replace("-", ""))
            .withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    private static final String URL = startDatabase();
    private static final String SNAPSHOT = """
            {"properties":{"temperature":{"dataType":"NUMBER","accessType":"REPORT"}},
             "events":{"alarm":{"level":"WARNING","parameters":{
               "temperature":{"dataType":"NUMBER","required":true},
               "sensor_token":{"dataType":"TEXT","required":false}}},
               "empty":{"level":"INFO","parameters":{}}},"commands":{}}
            """;
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedQuotaRunner;
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotifications;
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfill;
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTasks;
    @MockitoBean(enforceOverride = true)
    private com.things.link.ingestion.application.RealtimeKafkaPublisher unusedRealtime;
    @Autowired private EventIngestionService events;
    @Autowired private PropertyIngestionService properties;
    @Autowired private ThingModelVersionBindingService bindings;
    @Autowired private TelemetryDailyUsageContributor usage;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private MessageLogService logs;
    private UUID tenant, project, account, type, device, version;

    @BeforeEach
    void seed() throws Exception {
        tenant = Uuid7.generate(); project = Uuid7.generate(); account = Uuid7.generate();
        type = Uuid7.generate(); device = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            sql.update("INSERT INTO sys_tenant(id,name) VALUES (?, '事件探针租户')", tenant);
            sql.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '事件OWNER')",
                    account, account + "@example.com");
            sql.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", Uuid7.generate(), tenant, account);
            sql.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'事件探针','sh-1',?)",
                    project, tenant, "event_" + project.toString().replace("-", ""));
            sql.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                    Uuid7.generate(), project, account);
            sql.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) "
                            + "VALUES (?,?,?,'event_type','事件类型','STANDARD','DIRECT','PUBLISHED')", type, tenant, project);
            sql.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) "
                            + "VALUES (?,?,?,?,'event_device','事件设备','ONLINE')", device, tenant, project, type);
            sql.update("INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,desired) VALUES (?,?,?,'{}'::jsonb,'{}'::jsonb)", device, tenant, project);
            sql.update("INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) "
                            + "VALUES (?,?,?,?,'temperature','温度','REPORT','NUMBER')", Uuid7.generate(), tenant, project, type);
            owner.commit();
        }
        version = seedThingModelVersion(tenant, project, type, device, SNAPSHOT);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(PROBE_POSTGRES.getDatabaseName());
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
    }

    @AfterEach void clearScope() { TenantContext.clear(); }
    @Override protected Connection fixtureOwnerConnection() throws java.sql.SQLException {
        return DriverManager.getConnection(URL, PROBE_POSTGRES.getUsername(), PROBE_POSTGRES.getPassword());
    }

    @Test
    void emptyEventAndCredentialProjectionCommitOnlyOwnHistoryOnce() throws Exception {
        EventUplinkMessage empty = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        assertThat(events.ingest(empty)).isTrue();
        assertThat(events.ingest(empty)).isFalse();
        EventUplinkMessage sensitive = message(Uuid7.generate(), "alarm",
                Map.of("temperature", 21, "sensor_token", "synthetic-private-value"), Instant.now());
        assertThat(events.ingest(sensitive)).isTrue();
        EventUplinkMessage secretConflict = new EventUplinkMessage(sensitive.messageId(), tenant, project, device,
                TransportProtocol.MQTT, "alarm", "1.0.0", sensitive.occurredAt(), sensitive.receivedAt(),
                "changed-private-value", sensitive.rawBytes(), Map.of("temperature", 21, "sensor_token", "different-private-value"));
        assertBusiness(() -> events.ingest(secretConflict), 30059);
        try (Connection owner = fixtureOwnerConnection()) {
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            assertThat(sql.queryForObject("SELECT params::text FROM ts_device_event WHERE message_id=?", String.class,
                    empty.messageId())).isEqualTo("{}");
            assertThat(sql.queryForObject("SELECT params::text FROM ts_device_event WHERE message_id=?", String.class,
                    sensitive.messageId())).doesNotContain("sensor_token", "synthetic-private-value");
            assertThat(sql.queryForObject("SELECT params_redacted FROM ts_device_event WHERE message_id=?", Boolean.class,
                    sensitive.messageId())).isTrue();
            assertThat(sql.queryForObject("SELECT message_kind FROM sys_inbox_message WHERE message_id=?", String.class,
                    sensitive.messageId())).isEqualTo("EVENT");
            assertThat(sql.queryForObject("SELECT payload_summary FROM ts_device_message_log WHERE message_id=?", String.class,
                    sensitive.messageId())).doesNotContain("sensor_token", "synthetic-private-value");
            assertThat(sql.queryForObject("SELECT count(*) FROM ts_property_point_internal WHERE project_id=?", Long.class, project)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?", Long.class, project)).isZero();
        }
        assertThat(count("ts_device_event")).isEqualTo(2);
        assertThat(count("sys_inbox_message")).isEqualTo(2);
        assertThat(count("ts_device_message_log")).isEqualTo(2);
    }

    @Test
    void replayChangesReceptionMetadataButNeverOriginalFacts() throws Exception {
        EventUplinkMessage first = message(Uuid7.generate(), "alarm", Map.of("temperature", 22), Instant.now());
        assertThat(events.ingest(first)).isTrue();
        String before = fact(first.messageId());
        EventUplinkMessage retry = new EventUplinkMessage(first.messageId(), tenant, project, device,
                TransportProtocol.MQTT, first.eventKey(), "1.0.0", first.occurredAt(), first.receivedAt().plusSeconds(20),
                "different-retry-trace", first.rawBytes() + 4, first.params());
        assertThat(events.ingest(retry)).isFalse();
        assertThat(fact(first.messageId())).isEqualTo(before);
        assertThat(count("sys_inbox_message")).isEqualTo(1);
    }

    @Test
    void nanosecondOccurrenceReplaysAgainstPostgresMicrosecondStorage() throws Exception {
        Instant time = Instant.parse("2026-10-01T12:34:56.123456789Z");
        EventUplinkMessage first = new EventUplinkMessage(Uuid7.generate(), tenant, project, device,
                TransportProtocol.MQTT, "empty", "1.0.0", time, Instant.now(), "nanos", 128, Map.of());
        assertThat(events.ingest(first)).isTrue();
        String original = fact(first.messageId());
        assertThat(events.ingest(first)).isFalse();
        assertThat(fact(first.messageId())).isEqualTo(original);
        assertThat(count("ts_device_event")).isEqualTo(1);
    }

    @Test
    void replayConflictAndBothPropertyEntrypointsCannotConsumeEventIdentity() {
        EventUplinkMessage first = message(Uuid7.generate(), "alarm", Map.of("temperature", 22), Instant.now());
        assertThat(events.ingest(first)).isTrue();
        assertBusiness(() -> events.ingest(message(first.messageId(), "alarm", Map.of("temperature", 23), first.receivedAt())), 30059);
        StandardUplinkMessage property = new StandardUplinkMessage(first.messageId(), tenant, project, device, null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                "1.0.0", first.occurredAt(), first.receivedAt(), "property-collision", 32, Map.of("temperature", 22));
        assertBusiness(() -> properties.ingest(property), 30059);
        TenantContext.set(new TenantScope(tenant, project, account));
        try {
            assertBusiness(() -> properties.ingest(new PropertyReportMessage(first.messageId(), project, device,
                    "temperature", 22, first.occurredAt())), 30059);
        } finally { TenantContext.clear(); }
    }

    @Test
    void crossTenantGlobalCollisionCannotRewriteOrRevealOriginalOccurrence() throws Exception {
        EventUplinkMessage original = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        assertThat(events.ingest(original)).isTrue();
        String before = fact(original.messageId());
        seed(); // 全新的本测试租户、项目和设备身份；不造事件事实。
        assertBusiness(() -> events.ingest(message(original.messageId(), "empty", Map.of(), Instant.now())), 30059);
        assertThat(count("ts_device_event")).isZero();
        assertThat(count("sys_inbox_message")).isZero();
        assertThat(fact(original.messageId())).isEqualTo(before);
    }

    @Test
    void eventCannotReuseCommittedPropertyMessageIdentity() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = Uuid7.generate();
        StandardUplinkMessage property = new StandardUplinkMessage(id, tenant, project, device, null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                "1.0.0", now, now, "property-first", 128, Map.of("temperature", 22));
        assertThat(properties.ingest(property)).isTrue();
        assertBusiness(() -> events.ingest(message(id, "empty", Map.of(), now)), 30059);
        assertThat(count("ts_device_event")).isZero();
        assertThat(count("sys_inbox_message")).isEqualTo(1);
        assertThat(count("ts_device_message_log")).isEqualTo(1);
    }

    @Test
    void invalidSnapshotParametersAndIdentityLeaveNoArbitrationOrHistory() throws Exception {
        assertBusiness(() -> events.ingest(message(Uuid7.generate(), "alarm", Map.of(), Instant.now())), 30070);
        assertBusiness(() -> events.ingest(message(Uuid7.generate(), "alarm", Map.of("temperature", "wrong-type"), Instant.now())), 30070);
        assertBusiness(() -> events.ingest(message(Uuid7.generate(), "empty", Map.of("unknown", 1), Instant.now())), 30070);
        EventUplinkMessage wrongDevice = new EventUplinkMessage(Uuid7.generate(), tenant, project, Uuid7.generate(),
                TransportProtocol.MQTT, "empty", "1.0.0", Instant.now(), Instant.now(), "wrong-device", 12, Map.of());
        assertThatThrownBy(() -> events.ingest(wrongDevice)).isInstanceOf(BusinessException.class);
        assertThat(count("ts_device_event")).isZero();
        assertThat(count("sys_inbox_message")).isZero();
        assertThat(count("ts_device_message_log")).isZero();
    }

    @Test
    void concurrentDuplicateCommitsExactlyOneAtomicOccurrence() throws Exception {
        EventUplinkMessage event = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return events.ingest(event); });
            var second = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return events.ingest(event); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("ts_device_event")).isEqualTo(1);
        assertThat(count("sys_inbox_message")).isEqualTo(1);
        assertThat(count("ts_device_message_log")).isEqualTo(1);
    }

    @Test
    void downstreamLogFailureRollsBackThenSameOriginalEventRecovers() throws Exception {
        EventUplinkMessage first = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("controlled-after-log"); })
                .when(logs).log(any());
        assertThatThrownBy(() -> events.ingest(first)).isInstanceOf(IllegalStateException.class);
        assertThat(count("ts_device_event")).isZero();
        assertThat(count("sys_inbox_message")).isZero();
        assertThat(count("ts_device_message_log")).isZero();
        doAnswer(invocation -> invocation.callRealMethod()).when(logs).log(any());
        assertThat(events.ingest(first)).isTrue();
        assertThat(count("ts_device_event")).isEqualTo(1);
    }

    @Test
    void archivedProjectRejectsNewButPreservesAlreadyCommittedExactReplay() throws Exception {
        EventUplinkMessage first = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        assertThat(events.ingest(first)).isTrue();
        try (Connection owner = fixtureOwnerConnection()) {
            new JdbcTemplate(new SingleConnectionDataSource(owner, true)).update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        }
        assertThat(events.ingest(first)).isFalse();
        assertThatThrownBy(() -> events.ingest(message(Uuid7.generate(), "empty", Map.of(), Instant.now())))
                .isInstanceOf(ProjectIngestionRejectedException.class);
        assertThat(count("ts_device_event")).isEqualTo(1);
        assertThat(count("sys_inbox_message")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"UPLINK_MESSAGE,0,0", "UPLINK_MESSAGE,10,10", "UPLINK_MESSAGE,10,12", "UPLINK_BYTES,0,0", "UPLINK_BYTES,10,10", "UPLINK_BYTES,10,12"})
    void disabledHardAndDegradedQuotaRejectWholeEvent(String metric, long limit, long used) throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            UUID policy = Uuid7.generate();
            sql.update("INSERT INTO sys_quota_policy(id,code,uplink_message_daily_limit,uplink_bytes_daily_limit) VALUES (?,?,?,?)",
                    policy, "event_" + policy.toString().replace("-", "").substring(0, 20),
                    metric.equals("UPLINK_MESSAGE") ? limit : null, metric.equals("UPLINK_BYTES") ? limit : null);
            sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", policy, tenant);
            if (used > 0) sql.update("INSERT INTO sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value) "
                            + "VALUES (?,?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,?,?)", Uuid7.generate(), tenant, project, metric, used);
        }
        assertBusiness(() -> events.ingest(message(Uuid7.generate(), "empty", Map.of(), Instant.now())), 30071);
        assertThat(count("ts_device_event")).isZero(); assertThat(count("sys_inbox_message")).isZero();
        assertThat(count("ts_device_message_log")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1"})
    void selfHostedDatabaseQuotaConstraintRejectsWithoutPartialFacts(long limit, long used) throws Exception {
        // 只验证数据库已核验投影的拒绝，不冒称签名授权导入或发行端资格。
        UUID deployment = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            assertThat(sql.queryForObject("SELECT count(*) FROM sys_shc_local_grant_state", Long.class)).isZero();
            sql.update("""
                    INSERT INTO sys_shc_local_grant_state
                      (deployment_id,billing_tenant_id,deployment_public_key_sha256,grant_id,
                       highest_sequence,signed_envelope,envelope_sha256,
                       devices_max,uplink_message_daily,downlink_message_daily)
                    VALUES (?,?,decode(repeat('00',32),'hex'),?,1,
                      decode(repeat('00',76),'hex'),decode(repeat('00',32),'hex'),100,?,100)
                    """, deployment, tenant, Uuid7.generate(), limit);
            if (used > 0) sql.update("""
                    INSERT INTO sys_shc_local_message_usage(billing_tenant_id,usage_date,direction,used_count)
                    VALUES (?,(clock_timestamp() AT TIME ZONE 'UTC')::date,'UP',?)
                    """, tenant, used);
        }
        try {
            assertBusiness(() -> events.ingest(message(Uuid7.generate(), "empty", Map.of(), Instant.now())), 30071);
            assertThat(count("ts_device_event")).isZero();
            assertThat(count("sys_inbox_message")).isZero();
            assertThat(count("ts_device_message_log")).isZero();
            try (Connection owner = fixtureOwnerConnection()) {
                assertThat(new JdbcTemplate(new SingleConnectionDataSource(owner, true)).queryForObject(
                        "SELECT coalesce(sum(used_count),0) FROM sys_shc_local_message_usage WHERE billing_tenant_id=?",
                        Long.class, tenant)).isEqualTo(used);
            }
        } finally {
            try (Connection owner = fixtureOwnerConnection()) {
                JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
                sql.update("DELETE FROM sys_shc_local_message_usage WHERE billing_tenant_id=?", tenant);
                sql.update("DELETE FROM sys_shc_local_grant_state WHERE deployment_id=? AND billing_tenant_id=?", deployment, tenant);
            }
        }
    }

    @Test
    void ancientOccurrenceCountsRawBytesFromEventWithoutDoubleCountingLog() throws Exception {
        Instant received = Instant.now();
        EventUplinkMessage old = new EventUplinkMessage(Uuid7.generate(), tenant, project, device, TransportProtocol.MQTT,
                "empty", "1.0.0", received.minus(110, ChronoUnit.DAYS), received, "late-event", 1234, Map.of());
        assertThat(events.ingest(old)).isTrue();
        TenantContext.set(new TenantScope(tenant, project, account));
        try {
            var values = transactions.execute(status -> usage.calculate(new DailyUsageScope(tenant, project), LocalDate.now(ZoneOffset.UTC)));
            assertThat(values).isNotNull();
            assertThat(values.stream().filter(v -> v.metric() == QuotaMetric.UPLINK_BYTES).findFirst().orElseThrow().usedValue()).isEqualTo(1234);
            assertThat(values.stream().filter(v -> v.metric() == QuotaMetric.UPLINK_MESSAGE).findFirst().orElseThrow().usedValue()).isEqualTo(1);
            assertThat(values.stream().filter(v -> v.metric() == QuotaMetric.TIME_SERIES_POINT).findFirst().orElseThrow().usedValue()).isZero();
        } finally { TenantContext.clear(); }
        assertThat(count("ts_device_event")).isEqualTo(1);
    }

    @Test
    void successfulReplayDoesNotReinterpretBindingAfterRealUpgrade() throws Exception {
        EventUplinkMessage first = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        assertThat(events.ingest(first)).isTrue();
        UUID nextVersion = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            new JdbcTemplate(new SingleConnectionDataSource(owner, true)).update("""
                    INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                      version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                    VALUES (?,?,?,?,'2.0.0',2,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                      encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                    """, nextVersion, tenant, project, type, SNAPSHOT, SNAPSHOT);
        }
        TenantContext.set(new TenantScope(tenant, project, account));
        try { bindings.bind(project, device, nextVersion, Uuid7.generate(), TransitionType.UPGRADE, Instant.now()); }
        finally { TenantContext.clear(); }
        Instant beforeOldReport = activity();
        assertThat(events.ingest(first)).isFalse();
        EventUplinkMessage lateOld = message(Uuid7.generate(), "empty", Map.of(), Instant.now().plusSeconds(1));
        assertThat(events.ingest(lateOld)).isTrue();
        assertThat(activity()).isEqualTo(beforeOldReport);
        try (Connection owner = fixtureOwnerConnection()) {
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            assertThat(sql.queryForObject("SELECT thing_model_version_id FROM ts_device_event WHERE message_id=?",
                    UUID.class, first.messageId())).isEqualTo(version);
            assertThat(sql.queryForObject("SELECT eligibility FROM ts_device_event WHERE message_id=?",
                    String.class, lateOld.messageId())).isEqualTo("HISTORY_ONLY");
        }
    }


    @Test
    void immutableFactsRlsAndControlledRetentionProtectNewLateEvent() throws Exception {
        EventUplinkMessage fresh = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        EventUplinkMessage expired = message(Uuid7.generate(), "empty", Map.of(), Instant.now().minus(95, ChronoUnit.DAYS));
        assertThat(events.ingest(fresh)).isTrue();
        assertThat(events.ingest(expired)).isTrue();
        TenantContext.set(new TenantScope(tenant, project, account));
        try {
            assertThatThrownBy(() -> transactions.execute(status -> jdbc.update(
                    "UPDATE ts_device_event SET level='ERROR' WHERE message_id=?", fresh.messageId())))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.execute("SELECT purge_device_events(0,'{}'::jsonb)")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { TenantContext.clear(); }
        TenantContext.set(new TenantScope(Uuid7.generate(), Uuid7.generate(), account));
        try {
            Long visible = transactions.execute(status -> jdbc.queryForObject(
                    "SELECT count(*) FROM ts_device_event", Long.class));
            assertThat(visible).isZero();
        } finally { TenantContext.clear(); }
        try (Connection owner = fixtureOwnerConnection()) {
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            assertThatThrownBy(() -> sql.update("UPDATE ts_device_event SET level=level WHERE message_id=?", fresh.messageId()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> sql.update("DELETE FROM dev_device WHERE id=?", device))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            sql.execute("SELECT purge_device_events(0,'{}'::jsonb)");
            assertThat(sql.queryForObject("SELECT count(*) FROM ts_device_event WHERE message_id=?", Long.class, expired.messageId())).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM ts_device_event WHERE message_id=?", Long.class, fresh.messageId())).isEqualTo(1);
        }
    }

    @Test
    void currentReportsUseReceptionTimeAndReplayDoesNotRefreshActivity() throws Exception {
        Instant received = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        EventUplinkMessage first = message(Uuid7.generate(), "empty", Map.of(), received);
        assertThat(events.ingest(first)).isTrue();
        assertThat(activity()).isEqualTo(received);
        EventUplinkMessage replay = new EventUplinkMessage(first.messageId(), tenant, project, device,
                first.protocol(), first.eventKey(), first.modelVersion(), first.occurredAt(), Instant.now(),
                "activity-replay", first.rawBytes(), first.params());
        assertThat(events.ingest(replay)).isFalse();
        assertThat(activity()).isEqualTo(received);
        assertThat(events.ingest(message(Uuid7.generate(), "empty", Map.of(), received.minusSeconds(60)))).isTrue();
        assertThat(activity()).isEqualTo(received);
        Instant propertyReceived = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var property = new StandardUplinkMessage(Uuid7.generate(), tenant, project, device, null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                "1.0.0", propertyReceived.minusSeconds(30), propertyReceived, "activity-property", 32,
                Map.of("temperature", 22));
        assertThat(properties.ingest(property)).isTrue();
        assertThat(activity()).isEqualTo(propertyReceived);
        assertThat(properties.ingest(property)).isFalse();
        assertThat(activity()).isEqualTo(propertyReceived);
    }

    @Test
    void futureReceptionCannotMakeActivityPermanent() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        Instant future = Instant.now().plusSeconds(3600);
        var event = new EventUplinkMessage(Uuid7.generate(), tenant, project, device,
                TransportProtocol.MQTT, "empty", "1.0.0", Instant.now(), future,
                "activity-clock-skew", 128, Map.of());
        assertThat(events.ingest(event)).isTrue();
        assertThat(activity()).isAfter(before).isBeforeOrEqualTo(Instant.now()).isBefore(future);
    }

    @Test
    void rolledBackOrRejectedReportsDoNotCreateActivity() throws Exception {
        var event = message(Uuid7.generate(), "empty", Map.of(), Instant.now());
        transactions.executeWithoutResult(status -> {
            assertThat(events.ingest(event)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(activity()).isNull();
        assertThat(count("ts_device_event")).isZero();
        assertBusiness(() -> events.ingest(message(Uuid7.generate(), "alarm", Map.of(), Instant.now())), 30070);
        assertThat(activity()).isNull();
    }

    private Instant activity() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            return new JdbcTemplate(new SingleConnectionDataSource(owner, true)).queryForObject(
                    "SELECT last_data_report_at FROM dev_device WHERE id=?",
                    (row, number) -> row.getTimestamp(1) == null ? null : row.getTimestamp(1).toInstant(), device);
        }
    }

    private EventUplinkMessage message(UUID id, String key, Map<String,Object> params, Instant received) {
        return new EventUplinkMessage(id, tenant, project, device, TransportProtocol.MQTT, key, "1.0.0",
                received.truncatedTo(ChronoUnit.MICROS), received, "event-pg-probe", 128, params);
    }
    private static void assertBusiness(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
    private long count(String table) throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            return new JdbcTemplate(new SingleConnectionDataSource(owner, true)).queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE project_id=?", Long.class, project);
        }
    }
    private String fact(UUID message) throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            return new JdbcTemplate(new SingleConnectionDataSource(owner, true)).queryForObject(
                    "SELECT row_to_json(e)::text FROM ts_device_event e WHERE message_id=?", String.class, message);
        }
    }
    private static String startDatabase() { PROBE_POSTGRES.start(); return PROBE_POSTGRES.getJdbcUrl(); }
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabase {
        @Bean DynamicPropertyRegistrar databaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> URL); registry.add("spring.flyway.url", () -> URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }
}
