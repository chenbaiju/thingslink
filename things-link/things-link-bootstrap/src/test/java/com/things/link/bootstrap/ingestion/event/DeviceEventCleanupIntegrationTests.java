package com.things.link.bootstrap.ingestion.event;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupBatchService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.EventIngestionService;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 独占PG的真实事件摄取到生产清理；SQL只准备静态身份、清理许可及显式时间推进夹具。 */
@Import(DeviceEventCleanupIntegrationTests.IsolatedDatabase.class)
@OwnedTestContainers({"PROBE_POSTGRES"})
class DeviceEventCleanupIntegrationTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> PROBE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("event_cleanup_" + UUID.randomUUID().toString().replace("-", ""))
            .withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    private static final String URL = startDatabase();
    private static final String SNAPSHOT = """
            {"properties":{},"events":{"empty":{"level":"INFO","parameters":{}}},"commands":{}}
            """;
    @MockitoBean(enforceOverride=true,name="relaxRestQuota") private ApplicationRunner unusedQuotaRunner;
    @MockitoBean(enforceOverride=true) private NotificationWorkCoordinator unusedNotifications;
    @MockitoBean(enforceOverride=true) private PropertyAggregateBackfillScanner unusedBackfill;
    @MockitoBean(enforceOverride=true) private TaskSchedulingScanner unusedTasks;
    @MockitoBean(enforceOverride=true) private com.things.link.ingestion.application.RealtimeKafkaPublisher unusedRealtime;
    @Autowired private EventIngestionService events;
    @Autowired private ProjectCleanupBatchService batches;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach void isolateMaintenance() throws Exception {
        try (Connection connection=fixtureOwnerConnection()) {
            owner(connection).queryForList("SELECT alter_job(job_id,scheduled=>false) FROM timescaledb_information.jobs");
        }
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(PROBE_POSTGRES.getDatabaseName());
        assertThat(jdbc.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
    }
    @AfterEach void clearIdentity() { TenantContext.clear(); }
    @Override protected Connection fixtureOwnerConnection() throws java.sql.SQLException {
        return DriverManager.getConnection(URL,PROBE_POSTGRES.getUsername(),PROBE_POSTGRES.getPassword());
    }

    @Test
    void real501EventsDrainIn500BatchesBeforeInboxAndKeepTenantNeighbor() throws Exception {
        Fixture target=fixture(),neighbor=fixture();
        ingest(target,501,Instant.now().minus(10,ChronoUnit.DAYS));
        ingest(neighbor,1,Instant.now().minus(10,ChronoUnit.DAYS));
        ageAcceptedFixture(target);
        Map<String,String> before=snapshot(neighbor);
        assertThat(count(target,"ts_device_event")).isEqualTo(501);
        ProjectCleanupBatchResult first=run(target,lease(target));
        assertThat(first.deletedRows()).isEqualTo(500);
        assertThat(first.complete()).isFalse();
        assertThat(count(target,"ts_device_event")).isEqualTo(1);
        assertThat(count(target,"sys_inbox_message")).isEqualTo(501);
        assertThat(snapshot(neighbor)).isEqualTo(before);
        ProjectCleanupBatchResult second=run(target,lease(target));
        assertThat(second.deletedRows()).isEqualTo(1);
        assertThat(count(target,"ts_device_event")).isZero();
        assertThat(count(target,"sys_inbox_message")).isEqualTo(501);
        int total=501;
        boolean complete=false;
        for (int i=0;i<20;i++) {
            ProjectCleanupBatchResult result=run(target,lease(target));
            assertThat(result.deletedRows()).isBetween(0,500);
            assertThat(result.blockedReason()).isNull();
            total+=result.deletedRows();
            assertThat(snapshot(neighbor)).isEqualTo(before);
            if (result.complete()) { complete=true; break; }
        }
        assertThat(complete).isTrue();
        assertThat(total).isEqualTo(501*4);
        assertThat(snapshot(target).values()).allSatisfy(value -> assertThat(value).isEqualTo("[]"));
        try (Connection connection=fixtureOwnerConnection()) {
            JdbcTemplate sql=owner(connection);
            assertThat(sql.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",String.class,target.project())).isEqualTo("DEVICE");
            assertThat(sql.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,target.project())).isEqualTo(501*4L);
            assertThat(sql.queryForObject("SELECT count(*) FROM dev_device WHERE id=?",Long.class,target.device())).isEqualTo(1);
        }
    }

    @Test
    void recentlyAcceptedOldReceivedEventBlocksAllCleanupWithoutReinterpretingDeviceTime() throws Exception {
        Fixture target=fixture();
        ingest(target,1,Instant.now().minus(10,ChronoUnit.DAYS));
        ageOnlyMessageLogFixture(target);
        Map<String,String> before=snapshot(target);
        ProjectCleanupBatchResult result=run(target,lease(target));
        assertThat(result.deletedRows()).isZero();
        assertThat(result.blockedReason()).isEqualTo("TELEMETRY_REPLAY_WINDOW");
        assertThat(snapshot(target)).isEqualTo(before);
    }

    @Test
    void recentTrustedReceptionBlocksEvenWhenAcceptedFixtureIsOld() throws Exception {
        Fixture target=fixture();
        ingest(target,1,Instant.now().minus(7,ChronoUnit.DAYS));
        ageAcceptedFixture(target);
        Map<String,String> before=snapshot(target);
        ProjectCleanupBatchResult result=run(target,lease(target));
        assertThat(result.deletedRows()).isZero();
        assertThat(result.blockedReason()).isEqualTo("TELEMETRY_REPLAY_WINDOW");
        assertThat(snapshot(target)).isEqualTo(before);
    }

    @Test
    void wrongGenerationTokenOrExpiredLeaseCannotDeleteAnyActualEvent() throws Exception {
        Fixture target=fixture();
        ingest(target,1,Instant.now().minus(10,ChronoUnit.DAYS));
        ageAcceptedFixture(target);
        ProjectCleanupClaim valid=lease(target);
        Map<String,String> before=snapshot(target);
        TenantContext.set(new TenantScope(target.tenant(),target.project(),target.account()));
        try {
            assertThat(batches.execute(new ProjectCleanupClaim(target.tenant(),target.project(),valid.generation()+1,
                    valid.stage(),valid.leaseToken(),valid.leaseUntil(),false))).isEmpty();
            assertThat(batches.execute(new ProjectCleanupClaim(target.tenant(),target.project(),valid.generation(),
                    valid.stage(),Uuid7.generate(),valid.leaseUntil(),false))).isEmpty();
            try (Connection connection=fixtureOwnerConnection()) {
                owner(connection).update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",target.project());
            }
            assertThat(batches.execute(valid)).isEmpty();
        } finally { TenantContext.clear(); }
        assertThat(snapshot(target)).isEqualTo(before);
        assertThat(run(target,lease(target)).deletedRows()).isEqualTo(1);
    }

    private void ingest(Fixture fixture,int count,Instant received) {
        for (int index=0;index<count;index++) {
            Instant at=received.truncatedTo(ChronoUnit.MICROS);
            EventUplinkMessage message=new EventUplinkMessage(Uuid7.generate(),fixture.tenant(),fixture.project(),fixture.device(),
                    TransportProtocol.MQTT,"empty","1.0.0",at.minus(110,ChronoUnit.DAYS),at,"cleanup-event",128,Map.of());
            assertThat(events.ingest(message)).isTrue();
        }
    }
    private Fixture fixture() throws Exception {
        Fixture fixture=new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());
        try (Connection connection=fixtureOwnerConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate sql=owner(connection);
            sql.update("INSERT INTO sys_tenant(id,name) VALUES (?,'事件清理独占租户')",fixture.tenant());
            sql.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','事件清理OWNER')",fixture.account(),fixture.account()+"@example.test");
            sql.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",Uuid7.generate(),fixture.tenant(),fixture.account());
            sql.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'事件清理','sh-1',?)",fixture.project(),fixture.tenant(),"cleanup_"+fixture.project().toString().replace("-",""));
            sql.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),fixture.project(),fixture.account());
            sql.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'event_cleanup','事件清理类型','STANDARD','DIRECT','PUBLISHED')",fixture.type(),fixture.tenant(),fixture.project());
            sql.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,'cleanup_device','事件清理设备','ONLINE')",fixture.device(),fixture.tenant(),fixture.project(),fixture.type());
            sql.update("INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,desired) VALUES (?,?,?,'{}'::jsonb,'{}'::jsonb)",fixture.device(),fixture.tenant(),fixture.project());
            connection.commit();
        }
        seedThingModelVersion(fixture.tenant(),fixture.project(),fixture.type(),fixture.device(),SNAPSHOT);
        return fixture;
    }
    /** 仅推进已真实摄取事实的受控处理时刻；不新造事件、不改正文/摘要/版本/可信receivedAt。 */
    private void ageAcceptedFixture(Fixture fixture) throws Exception {
        try (Connection connection=fixtureOwnerConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate sql=owner(connection);
            sql.execute("ALTER TABLE ts_device_event DISABLE TRIGGER ts_device_event_immutable_trg");
            try {
                sql.update("UPDATE ts_device_event SET accepted_at=clock_timestamp()-interval '10 days' WHERE project_id=?",fixture.project());
            } finally {
                sql.execute("ALTER TABLE ts_device_event ENABLE TRIGGER ts_device_event_immutable_trg");
            }
            sql.update("UPDATE ts_device_message_log SET created_at=clock_timestamp()-interval '10 days',processed_at=clock_timestamp()-interval '10 days' WHERE project_id=?",fixture.project());
            connection.commit();
        }
    }
    private void ageOnlyMessageLogFixture(Fixture fixture) throws Exception {
        try (Connection connection=fixtureOwnerConnection()) {
            owner(connection).update("UPDATE ts_device_message_log SET created_at=clock_timestamp()-interval '10 days',processed_at=clock_timestamp()-interval '10 days' WHERE project_id=?",fixture.project());
        }
    }
    private ProjectCleanupClaim lease(Fixture fixture) throws Exception {
        UUID token=Uuid7.generate();
        try (Connection connection=fixtureOwnerConnection()) {
            JdbcTemplate sql=owner(connection);
            if ("ACTIVE".equals(sql.queryForObject("SELECT status FROM sys_project WHERE id=?",String.class,fixture.project()))) {
                sql.update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=clock_timestamp()-interval '31 days',cleanup_stage='TELEMETRY',cleanup_started_at=clock_timestamp(),cleanup_next_attempt_at=clock_timestamp() WHERE id=?",fixture.project());
            }
            sql.update("UPDATE sys_project SET cleanup_lease_token=?,cleanup_lease_until=clock_timestamp()+interval '2 minutes',cleanup_next_attempt_at=clock_timestamp() WHERE id=?",token,fixture.project());
            Instant until=sql.queryForObject("SELECT cleanup_lease_until FROM sys_project WHERE id=?",Timestamp.class,fixture.project()).toInstant();
            return new ProjectCleanupClaim(fixture.tenant(),fixture.project(),1,"TELEMETRY",token,until,false);
        }
    }
    private ProjectCleanupBatchResult run(Fixture fixture,ProjectCleanupClaim claim) {
        TenantContext.set(new TenantScope(fixture.tenant(),fixture.project(),fixture.account()));
        try { return batches.execute(claim).orElseThrow(); }
        finally { TenantContext.clear(); }
    }
    private long count(Fixture fixture,String table) throws Exception {
        try (Connection connection=fixtureOwnerConnection()) {
            return owner(connection).queryForObject("SELECT count(*) FROM public."+table+" WHERE project_id=?",Long.class,fixture.project());
        }
    }
    private Map<String,String> snapshot(Fixture fixture) throws Exception {
        Map<String,String> result=new LinkedHashMap<>();
        try (Connection connection=fixtureOwnerConnection()) {
            JdbcTemplate sql=owner(connection);
            for (String table:java.util.List.of("ts_device_event","sys_inbox_message","sys_message_log_inbox","ts_device_message_log"))
                result.put(table,sql.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY to_jsonb(d)::text),'[]'::jsonb)::text FROM public."+table+" d WHERE project_id=?",String.class,fixture.project()));
        }
        return result;
    }
    private static JdbcTemplate owner(Connection connection) { return new JdbcTemplate(new SingleConnectionDataSource(connection,true)); }
    private record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID device) {}
    private static String startDatabase() { PROBE_POSTGRES.start(); return PROBE_POSTGRES.getJdbcUrl(); }
    @TestConfiguration(proxyBeanMethods=false)
    static class IsolatedDatabase {
        @Bean DynamicPropertyRegistrar databaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url",() -> URL); registry.add("spring.flyway.url",() -> URL);
                registry.add("things-link.outbox.publisher.enabled",() -> "false");
                registry.add("things-link.notification.retry.enabled",() -> "false");
                registry.add("things-link.project.cleanup.enabled",() -> "false");
                registry.add("spring.kafka.listener.auto-startup",() -> "false");
            };
        }
    }
}
