package com.things.link.bootstrap.telemetry.history;

import com.things.link.telemetry.application.PropertyAggregateBackfillMetrics;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository.BackfillWindow;
import com.things.link.telemetry.infrastructure.persistence.JdbcPropertyAggregateBackfillRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12-P0-5h7a：以真实保留删除和原维护入口证明旧窗口回补不能擦除邻居历史。 */
@Testcontainers
class PropertyAggregateRetentionSafetyTests {

    /** 独占数据库；真实作业与chunk操作只影响本类夹具。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("aggregate_retention_safety").withUsername("thingslink").withPassword("thingslink");
    /** 固定三层查询；不访问Timescale内部物化表。 */
    private static final List<String> AGGREGATES = List.of("ts_property_point_1m_internal",
            "ts_property_point_1h_internal", "ts_property_point_1d_internal");
    /** owner仅初始化历史、执行真实保留及观察事实。 */
    private static JdbcTemplate owner;
    /** APP负责领取和结果，真实owner连接只经既有维护适配器。 */
    private JdbcPropertyAggregateBackfillRepository repository;

    /** 真实旧库升级后禁用自动时钟作业，由测试明确触发，避免后台随机吞掉反例。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260905.0500").migrate();
        assertThat(flyway("20260905.0600").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0600").migrate().migrationsExecuted).isZero();
        owner.queryForList("SELECT alter_job(job_id, scheduled => false) FROM timescaledb_information.jobs");
    }

    /** 每例新建真实维护适配器，不模拟refresh或数据库时间。 */
    @BeforeEach
    void prepare() {
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink"));
        repository = new JdbcPropertyAggregateBackfillRepository(app, new MockEnvironment()
                .withProperty("spring.flyway.url", POSTGRES.getJdbcUrl())
                .withProperty("spring.flyway.user", "thingslink")
                .withProperty("spring.flyway.password", "thingslink"));
    }

    /** raw已按真实90天策略删除，迟到A点不能使B仅剩的三层聚合消失。 */
    @Test
    void refusesExpiredBackfillBeforeItErasesNeighborsRetainedAggregates() {
        Fixture first = fixture(110);
        Fixture neighbor = fixture(first.start());
        point(first, 10);
        point(neighbor, 42);
        refreshAsOwner(first);
        List<String> before = snapshot(neighbor);
        assertThat(before).allSatisfy(value -> assertThat(value).contains("42"));
        owner.queryForList("SELECT drop_chunks('public.ts_property_point_internal', older_than => interval '90 days')");
        assertThat(rawCount(neighbor)).isZero();
        point(first, 20);
        boolean rejected = false;
        try {
            repository.refresh(window(first));
        } catch (RuntimeException expected) {
            rejected = true;
        }
        // 先检查真实邻居数据，以使旧实现RED证明损坏而非仅缺少预期异常。
        assertThat(snapshot(neighbor)).isEqualTo(before);
        assertThat(rejected).isTrue();
    }

    /** 可重建的10天迟到窗口仍刷新全部层级，按原raw对账成功完成。 */
    @Test
    void refreshesReconstructibleWindowAndCompletesOriginalRequest() {
        Fixture first = fixture(10);
        point(first, 10);
        refreshAsOwner(first);
        point(first, 30);
        assertThat(repository.request(first.tenant(), first.project(), first.start().plusSeconds(60), Instant.now())).isTrue();
        repository.refresh(window(first));
        assertThat(repository.mismatchCount(window(first))).isZero();
        assertThat(repository.complete(window(first))).isTrue();
        assertThat(snapshot(first)).allSatisfy(value -> assertThat(value).contains("40"));
    }

    /** 原扫描器对过期请求记录失败并保留请求，不冒称对账完成或静默吞掉迟到点。 */
    @Test
    void scannerRetainsExpiredRequestAndRecordsFailure() {
        Fixture first = fixture(120);
        point(first, 15);
        repository.request(first.tenant(), first.project(), first.start().plusSeconds(60), Instant.now());
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            new PropertyAggregateBackfillScanner(repository, new PropertyAggregateBackfillMetrics(metrics)).scan();
            assertThat(metrics.get(PropertyAggregateBackfillMetrics.BACKFILLS)
                    .tag("result", "failure").counter().count()).isEqualTo(1);
        } finally {
            metrics.close();
        }
        assertThat(owner.queryForObject("SELECT attempts FROM public.ts_property_aggregate_backfill WHERE project_id=?",
                Integer.class, first.project())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT available_at > requested_at AND lease_until IS NULL AND last_error IS NOT NULL "
                + "FROM public.ts_property_aggregate_backfill WHERE project_id=?", Boolean.class, first.project())).isTrue();
        assertThat(rawCount(first)).isEqualTo(1);
    }

    /** 错误窗口宽度在原入口拒绝，零SQL刷新。 */
    @Test
    void rejectsReversedAndOversizedWindows() {
        Fixture first = fixture(10);
        assertThatThrownBy(() -> repository.refresh(new BackfillWindow(first.tenant(), first.project(),
                first.start(), first.start(), 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.refresh(new BackfillWindow(first.tenant(), first.project(),
                first.start(), first.start().plusSeconds(86401), 1))).isInstanceOf(IllegalArgumentException.class);
    }

    /** prepared语句跨过安全截止后必须按执行时间拒绝，不能复用准备时的资格。 */
    @Test
    void checksActualExecutionTimeAfterPreparedWindowBecomesExpired() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink");
             PreparedStatement call = connection.prepareStatement("""
                     CALL public.refresh_continuous_aggregate('public.ts_property_point_1m_internal',
                         public.property_aggregate_safe_refresh_start(?::timestamptz, ?::timestamptz), ?::timestamptz)
                     """)) {
            Instant start = owner.queryForObject("SELECT clock_timestamp()-interval '90 days'+interval '15 minutes 1 second'",
                    Timestamp.class).toInstant();
            call.setTimestamp(1, Timestamp.from(start));
            call.setTimestamp(2, Timestamp.from(start.plusSeconds(86400)));
            call.setTimestamp(3, Timestamp.from(start.plusSeconds(86400)));
            owner.execute("SELECT pg_sleep(1.1)");
            assertThatThrownBy(call::execute).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("22023"))
                    .hasMessageContaining("PROPERTY_AGGREGATE_SOURCE_EXPIRED");
        }
    }

    /** SQL边界直接调用仍验证宽度、无穷和保留余量；APP不能借新函数取得维护能力。 */
    @Test
    void rejectsInvalidSqlWindowsAndKeepsMaintenanceFunctionOwnerOnly() {
        assertThat(owner.queryForObject("SELECT public.property_aggregate_safe_refresh_start("
                + "clock_timestamp()-interval '90 days'+interval '16 minutes',clock_timestamp()-interval '89 days') IS NOT NULL",
                Boolean.class)).isTrue();
        for (String arguments : List.of("NULL,now()", "now(),now()", "now(),now()+interval '2 days'",
                "'-infinity','infinity'", "now()-interval '90 days'+interval '14 minutes',now()-interval '89 days'")) {
            assertThatThrownBy(() -> owner.queryForObject("SELECT public.property_aggregate_safe_refresh_start(" + arguments + ")",
                    Timestamp.class)).hasRootCauseInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("22023"));
        }
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink"));
        assertThatThrownBy(() -> app.queryForObject("SELECT public.property_aggregate_safe_refresh_start(now(),now()+interval '1 hour')",
                Timestamp.class)).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("42501"));
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a "
                + "WHERE p.oid='public.property_aggregate_safe_refresh_start(timestamptz,timestamptz)'::regprocedure AND a.grantee=0",
                Long.class)).isZero();
    }

    /** @param days 数据年龄 @return 使用数据库UTC日界的独立项目 */
    private Fixture fixture(int days) {
        Instant start = owner.queryForObject("SELECT date_trunc('day', clock_timestamp() AT TIME ZONE 'UTC') "
                + "AT TIME ZONE 'UTC' - ? * interval '1 day'", Timestamp.class, days).toInstant();
        return fixture(start);
    }

    /** @param start 同chunk邻居共用时间 @return 真实项目及独立设备标识 */
    private Fixture fixture(Instant start) {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), start);
        owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'回补保留测试')", fixture.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key) VALUES (?,?,'回补项目',?)",
                fixture.project(), fixture.tenant(), "backfill_" + fixture.project().toString().replace("-", ""));
        return fixture;
    }

    /** @param fixture 明确项目 @param value 用于精确邻居快照和求和的数值 */
    private void point(Fixture fixture, double value) {
        owner.update("INSERT INTO public.ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double) "
                + "VALUES (?,?,'temperature',?,gen_random_uuid(),?)", fixture.project(), fixture.device(),
                Timestamp.from(fixture.start().plusSeconds(60)), value);
    }

    /** @param fixture 受控原始完整窗口，owner只在夹具准备时刷新 */
    private void refreshAsOwner(Fixture fixture) {
        for (String aggregate : AGGREGATES) {
            owner.update("CALL public.refresh_continuous_aggregate(?::regclass,?::timestamptz,?::timestamptz)",
                    "public." + aggregate, Timestamp.from(fixture.start()), Timestamp.from(fixture.start().plusSeconds(86400)));
        }
    }

    /** @param fixture 项目 @return 三层全部字段快照 */
    private List<String> snapshot(Fixture fixture) {
        return AGGREGATES.stream().map(table -> owner.queryForObject(
                "SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY bucket,device_id,property_key),'[]'::jsonb)::text FROM public."
                        + table + " p WHERE project_id=?", String.class, fixture.project())).toList();
    }

    /** @param fixture 项目 @return 剩余真实raw行数 */
    private long rawCount(Fixture fixture) {
        return owner.queryForObject("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id=?",
                Long.class, fixture.project());
    }

    /** @param fixture 项目 @return 与初次请求一致的revision */
    private BackfillWindow window(Fixture fixture) {
        return new BackfillWindow(fixture.tenant(), fixture.project(), fixture.start(), fixture.start().plusSeconds(86400), 1);
    }

    /** @param target 旧库或当前迁移 @return 完整工作区迁移器 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam",
                        "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
                        "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/enduser", "classpath:db/migration/export")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** 时间来自数据库且三种身份显式隔离，防止随机UUID掩盖共享chunk。 */
    private record Fixture(UUID tenant, UUID project, UUID device, Instant start) { }
}
