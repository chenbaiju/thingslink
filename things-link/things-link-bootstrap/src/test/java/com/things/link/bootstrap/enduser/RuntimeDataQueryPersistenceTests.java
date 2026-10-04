package com.things.link.bootstrap.enduser;

import com.things.link.alarm.application.AlarmDeviceQueryPage;
import com.things.link.alarm.application.AlarmDeviceQueryService;
import com.things.link.alarm.infrastructure.persistence.JdbcAlarmInstanceRepository;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.application.AppTelemetryDataPlaneService;
import com.things.link.telemetry.application.AppVersionedPropertyHistory;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.PropertyHistoryService;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.infrastructure.persistence.JdbcPropertyPointRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 数据运行合同§3.4/3.5的真实SQL反例；同一个独占库覆盖过滤前分页与多版本日聚合超预算。
 * owner只准备事实和刷新连续聚合；被测服务/仓储以普通APP身份读取真实生产迁移对象。
 * App/Console上层授权由相应API验收覆盖，这里只替代history无关的设备归属公开门。
 */
@Testcontainers
class RuntimeDataQueryPersistenceTests {
    /** 一个独占Timescale库，避免全Spring/Kafka启动及跨测试共享夹具。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("runtime_data_queries").withUsername("thingslink").withPassword("thingslink");
    /** 固定当前前置迁移，不能用测试手写宽松表或替代查询函数造绿。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export", "classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** owner只造完整外键夹具，不执行实际读取。 */
    private static JdbcTemplate owner;
    /** 普通APP持久访问。 */
    private JdbcTemplate jdbc;
    /** 真实普通事务，GUC和SQL共享同一个物理连接。 */
    private TransactionTemplate transaction;
    /** 集中可信范围组件。 */
    private TransactionLocalRlsScope scope;
    /** 真实alarm应用服务与仓储。 */
    private AlarmDeviceQueryService alarms;
    /** 真实history查询循环与仓储。 */
    private PropertyHistoryService history;
    /** 真实跨模块版本化投影。 */
    private AppTelemetryDataPlaneService telemetry;
    /** 每例独立实际项目/类型/设备/规则/账号。 */
    private Fixture fixture;
    /** 当前UTC前一天的整日，保留策略和默认聚合刷新不会删除这些新测试点。 */
    private Instant day;

    /** 迁移生产schema及APP角色；不启动Spring应用和消息中间件。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink").locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink")).target("20260906.0260").load().migrate();
    }

    /** 新项目隔离每例事实，无跨测试DELETE、清理调度或共享缓存。 */
    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        scope = new TransactionLocalRlsScope(jdbc);
        alarms = new AlarmDeviceQueryService(new JdbcAlarmInstanceRepository(jdbc));
        var devices = mock(DeviceIngestionService.class);
        var quota = mock(com.things.link.project.application.PlanCapacityService.class);
        org.mockito.Mockito.when(devices.requireDeviceOwner(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DeviceIngestionService.DeviceOwnerContext(UUID.randomUUID()));
        // 本测试固定旧schema验证RLS/版本数据；当前套餐真库合同由HistoryQuotaIntegrationTests覆盖。
        org.mockito.Mockito.when(quota.historyWindow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.things.link.project.application.PlanHistoryWindow(Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z")));
        history = new PropertyHistoryService(new JdbcPropertyPointRepository(jdbc), mock(ProjectService.class), devices, quota);
        telemetry = new AppTelemetryDataPlaneService(history, mock(DeviceCommandService.class), new ObjectMapper());
        fixture = fixture();
        day = owner.queryForObject("SELECT date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' - interval '1 day'",
                Timestamp.class).toInstant();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
    }

    /** 新近不匹配行交错在前；先WHERE后LIMIT仍拿到完整两条命中，第二页同time按id严格降序不重复。 */
    @Test
    void filtersEveryConditionBeforeLimitAndPagesEqualTimestampById() {
        UUID high = new UUID(0, 3);
        UUID middle = new UUID(0, 2);
        UUID low = new UUID(0, 1);
        for (UUID id : List.of(low, high, middle)) insertAlarm(fixture, fixture.device(), id, "ACTIVE", "UNACKNOWLEDGED", "MAJOR", day);
        UUID otherDevice = device(fixture);
        for (int index = 0; index < 12; index++) {
            Instant newer = day.plusSeconds(index + 1);
            insertAlarm(fixture, fixture.device(), UUID.randomUUID(), "PENDING", "UNACKNOWLEDGED", "MAJOR", newer);
            insertAlarm(fixture, fixture.device(), UUID.randomUUID(), "ACTIVE", "ACKNOWLEDGED", "MAJOR", newer);
            insertAlarm(fixture, fixture.device(), UUID.randomUUID(), "ACTIVE", "UNACKNOWLEDGED", "MINOR", newer);
            insertAlarm(fixture, otherDevice, UUID.randomUUID(), "ACTIVE", "UNACKNOWLEDGED", "MAJOR", newer);
        }
        var first = alarmPage(fixture, null, null, 2);
        assertThat(first.items().stream().map(item -> item.id()).toList()).containsExactly(high, middle);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextUpdatedAt()).isEqualTo(day);
        assertThat(first.nextId()).isEqualTo(middle);
        var second = alarmPage(fixture, first.nextUpdatedAt(), first.nextId(), 2);
        assertThat(second.items().stream().map(item -> item.id()).toList()).containsExactly(low);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextId()).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM alarm_instance WHERE project_id=?", Integer.class, fixture.project())).isEqualTo(51);
    }

    /** 显式项目/租户谓词与普通RLS均有效；存在的邻项目数据不能因扩大设备集合而进入本页。 */
    @Test
    void tenantAndProjectIsolationRemainIndependentOfDeviceFilter() {
        Fixture neighbor = fixture();
        UUID visible = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        insertAlarm(fixture, fixture.device(), visible, "ACTIVE", "UNACKNOWLEDGED", "MAJOR", day);
        insertAlarm(neighbor, neighbor.device(), hidden, "ACTIVE", "UNACKNOWLEDGED", "MAJOR", day);
        var page = scoped(fixture, () -> alarms.query(fixture.tenant(), fixture.project(), List.of(fixture.device(), neighbor.device()),
                Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"), null, null, 20));
        assertThat(page.items().stream().map(item -> item.id()).toList()).containsExactly(visible);
        var wrongProjectScope = scoped(neighbor, () -> alarms.query(fixture.tenant(), fixture.project(), List.of(fixture.device()),
                Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"), null, null, 20));
        assertThat(wrongProjectScope.items()).isEmpty();
        assertThat(alarmPage(neighbor, null, null, 20).items().stream().map(item -> item.id()).toList()).containsExactly(hidden);
    }

    /** 一个UTC日的2001个真实模型版本生成2001个日聚合点，旧截断可实证而新入口必须整序列10001。 */
    @Test
    void realDailyMultiVersionAggregateRejectsFinalOverflowInsteadOfTruncating() {
        versions(fixture, 2001);
        owner.update("""
                INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                    data_type,thing_model_version_id,model_version,value_double)
                SELECT project_id,?,'temperature',?,gen_random_uuid(),'NUMBER',id,version_number,version_patch
                  FROM dev_thing_model_version WHERE project_id=?
                """, fixture.device(), Timestamp.from(day.plusSeconds(60)), fixture.project());
        refreshAggregates();
        assertThat(owner.queryForObject("SELECT count(*) FROM ts_property_point_1d_internal WHERE project_id=?",
                Integer.class, fixture.project())).isEqualTo(2001);
        assertThat(scoped(fixture, () -> history.queryTrusted(fixture.project(), fixture.device(), "temperature", day,
                day.plusSeconds(86400), HistoryGranularity.ONE_DAY, HistoryAggregation.AVG).points().size())).isEqualTo(2000);
        assertCode(() -> scoped(fixture, () -> telemetry.historyVersioned(fixture.project(), fixture.device(), "temperature",
                day, day.plusSeconds(86400), "ONE_DAY", "AVG")), 10001);
        // RAW窗口估算不能识别同桶多版本数；必须沿真实2001候选一路升级后仍拒绝。
        assertCode(() -> scoped(fixture, () -> telemetry.historyVersioned(fixture.project(), fixture.device(), "temperature",
                day, day.plusSeconds(86400), "RAW", "AVG")), 10001);
    }

    /** 同时刻两精确模型和LEGACY三个点完整保留，排他to与另一项目scope都不泄漏数据。 */
    @Test
    void preservesRealVersionFieldsLegacyAndExclusiveEnd() {
        versions(fixture, 2);
        Instant pointAt = day.plusSeconds(60);
        owner.update("""
                INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                    data_type,thing_model_version_id,model_version,value_double)
                SELECT project_id,?,'temperature',?,gen_random_uuid(),'NUMBER',id,version_number,version_patch
                  FROM dev_thing_model_version WHERE project_id=?
                """, fixture.device(), Timestamp.from(pointAt), fixture.project());
        owner.update("INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double)"
                + " VALUES (?,?,'temperature',?,gen_random_uuid(),9)", fixture.project(), fixture.device(), Timestamp.from(pointAt));
        AppVersionedPropertyHistory values = scoped(fixture, () -> telemetry.historyVersioned(fixture.project(), fixture.device(),
                "temperature", day, pointAt.plusSeconds(1), "RAW", "AVG"));
        assertThat(values.points()).hasSize(3);
        assertThat(values.points().stream().map(point -> point.modelVersion()).toList())
                .containsExactlyInAnyOrder("1.0.1", "1.0.2", "LEGACY_UNVERSIONED");
        assertThat(values.points()).allSatisfy(point -> {
            assertThat(point.ts()).isEqualTo(pointAt);
            assertThat(point.sampleCount()).isEqualTo(1);
            assertThat(point.thingModelVersionId() == null).isEqualTo("LEGACY_UNVERSIONED".equals(point.modelVersion()));
        });
        assertThat(scoped(fixture, () -> telemetry.historyVersioned(fixture.project(), fixture.device(), "temperature",
                day, pointAt, "RAW", "AVG").points())).isEmpty();
        Fixture neighbor = fixture();
        assertThat(scoped(neighbor, () -> telemetry.historyVersioned(fixture.project(), fixture.device(), "temperature",
                day, pointAt.plusSeconds(1), "RAW", "AVG").points())).isEmpty();
    }

    /** 全窗口只要含一个TEXT事实就30058，即使其他NUMBER或LEGACY点合法也不能偷偷删除文本段。 */
    @Test
    void mixedRealNonNumberWindowFailsWholeSeries() {
        versions(fixture, 1);
        owner.update("""
                INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                    data_type,thing_model_version_id,model_version,value_text)
                SELECT project_id,?,'temperature',?,gen_random_uuid(),'TEXT',id,version_number,'not numeric'
                  FROM dev_thing_model_version WHERE project_id=?
                """, fixture.device(), Timestamp.from(day.plusSeconds(60)), fixture.project());
        owner.update("INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double)"
                + " VALUES (?,?,'temperature',?,gen_random_uuid(),9)", fixture.project(), fixture.device(), Timestamp.from(day.plusSeconds(120)));
        assertCode(() -> scoped(fixture, () -> telemetry.historyVersioned(fixture.project(), fixture.device(), "temperature",
                day, day.plusSeconds(86400), "RAW", "AVG")), 30058);
        assertCode(() -> scoped(fixture, () -> telemetry.historyVersioned(fixture.project(), fixture.device(), "temperature",
                day, day.plusSeconds(86400), "ONE_DAY", "AVG")), 30058);
    }

    /** 独立完整父schema事实，不复用共享环境或放宽外键。 */
    private Fixture fixture() {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'运行查询租户')", f.tenant());
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key,status) VALUES (?,?,'运行查询项目',?,'ACTIVE')",
                f.project(), f.tenant(), f.project().toString().replace("-", ""));
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'test-only','查询夹具')",
                f.actor(), f.actor() + "@example.com");
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status)"
                + " VALUES (?,?,?,'type','运行类型','STANDARD','DIRECT','DRAFT')", f.type(), f.tenant(), f.project());
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,?,'运行设备')",
                f.device(), f.tenant(), f.project(), f.type(), f.device().toString());
        owner.update("""
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,
                    trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,'运行规则','TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')
                """, f.rule(), f.tenant(), f.project(), f.device());
        return f;
    }

    /** 同项目未被请求的设备，不能因先LIMIT再过滤而占掉合法名额。 */
    private UUID device(Fixture f) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,?,'邻设备')",
                id, f.tenant(), f.project(), f.type(), id.toString());
        return id;
    }

    /** 真实约束下按状态填齐清除/确认字段，每个alarmType唯一避免活动事故身份仲裁遮蔽查询反例。 */
    private void insertAlarm(Fixture f, UUID originator, UUID id, String condition, String ack, String severity, Instant updated) {
        owner.update("""
                INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,
                    condition_state,ack_state,first_condition_at,activated_at,acknowledged_at,acknowledged_by,
                    last_received_at,last_value,created_at,updated_at)
                VALUES (?,?,?,?,'DEVICE',?,?,?,?,?,?,?, ?,?, ?,31,?,?)
                """, id, f.tenant(), f.project(), f.rule(), originator, "alarm_" + id, severity, condition, ack,
                Timestamp.from(day), "PENDING".equals(condition) ? null : Timestamp.from(day),
                "ACKNOWLEDGED".equals(ack) ? Timestamp.from(day) : null, "ACKNOWLEDGED".equals(ack) ? f.actor() : null,
                Timestamp.from(day), Timestamp.from(day), Timestamp.from(updated));
    }

    /** 批量创建真实版本父行；同一天不同模型版本形成不同连续聚合组，不伪造聚合表。 */
    private void versions(Fixture f, int count) {
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                SELECT gen_random_uuid(),?,?,?,'1.0.'||n,1,0,n,'PATCH','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":[],"commands":[],"events":[]}'::jsonb,
                    encode(digest('{"properties":[],"commands":[],"events":[]}'::jsonb::text,'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256'
                  FROM generate_series(1,?) n
                """, f.tenant(), f.project(), f.type(), count);
    }

    /** 原生产连续聚合逐层刷新，测试禁止创建等价普通表或直接写物化内部chunk。 */
    private void refreshAggregates() {
        for (String name : List.of("ts_property_point_1m_internal", "ts_property_point_1h_internal", "ts_property_point_1d_internal")) {
            owner.update("CALL public.refresh_continuous_aggregate(?::regclass,?::timestamptz,?::timestamptz)",
                    name, Timestamp.from(day), Timestamp.from(day.plusSeconds(86400)));
        }
    }

    /** 只通过真实应用服务查询，内部锚点完全来自上一页最后一条已返回事实。 */
    private AlarmDeviceQueryPage alarmPage(Fixture f, Instant before, UUID beforeId, int limit) {
        return scoped(f, () -> alarms.query(f.tenant(), f.project(), List.of(f.device()), Set.of("ACTIVE"),
                Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"), before, beforeId, limit));
    }

    /** 指定普通APP事务可信双轴，无owner读取或跨线程会话GUC。 */
    private <T> T scoped(Fixture f, Supplier<T> work) {
        return transaction.execute(status -> {
            scope.establish(f.tenant(), f.project());
            return work.get();
        });
    }

    /** 稳定业务码验证，不把聚合预算失败当作数据库故障。 */
    private static void assertCode(Runnable work, int code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }

    /** 完整最小项目父链，owner仅负责创建这一链。 */
    private record Fixture(UUID tenant, UUID project, UUID type, UUID device, UUID rule, UUID actor) {
    }
}
