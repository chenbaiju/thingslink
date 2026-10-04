package com.things.link.bootstrap.device.runtime;

import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.DeviceRuntimeCatalogService;
import com.things.link.device.application.RuntimeDeviceCatalogItem;
import com.things.link.device.application.RuntimeDeviceCurrentResult;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.device.infrastructure.persistence.JdbcDeviceRuntimeRepository;
import com.things.link.device.infrastructure.persistence.JdbcDeviceRuntimeCatalogRepository;
import com.things.link.device.infrastructure.schema.JacksonThingModelSchemaValidator;
import com.things.link.support.tenant.TransactionLocalRlsScope;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据运行合同§3.1/3.2的设备域真实SQL验收，使用普通APP角色证明模型快照、PG同row值和目录视图RLS。
 * 独占数据库避免通过owner或共享测试残留掩盖0270授权与security_invoker边界。
 */
@Testcontainers
class DeviceRuntimeDataPersistenceTests {
    /** 独占PostgreSQL验证真实pgcrypto摘要、RLS与视图权限。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres")).withDatabaseName("device_runtime_data")
            .withUsername("thingslink").withPassword("thingslink");
    /** 固定生产迁移集合，不能用测试替代表或放宽RLS。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export", "classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** owner只建立事实和读取catalog元数据。 */
    private static JdbcTemplate owner;
    /** 普通APP JDBC，所有被测业务读取均由它执行。 */
    private JdbcTemplate application;
    /** 普通APP只读事务，保证RLS GUC与业务SQL同连接。 */
    private TransactionTemplate transaction;
    /** 集中双轴范围组件。 */
    private TransactionLocalRlsScope scope;
    /** 真实设备运行服务与JDBC仓储。 */
    private DeviceRuntimeDataService service;
    /** 每例唯一项目与模型设备链。 */
    private Fixture fixture;

    /** 迁移到0270并创建默认权限收紧后的真实APP角色。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink").locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink")).target("20260906.0270").load().migrate();
    }

    /** 每例新建父事实，不依赖跨例DELETE或超级用户业务读取。 */
    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        application = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        scope = new TransactionLocalRlsScope(application);
        ObjectMapper json = new ObjectMapper();
        service = new DeviceRuntimeDataService(new JdbcDeviceRuntimeRepository(application), json,
                new JacksonThingModelSchemaValidator(json));
        fixture = fixture();
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
    }

    /** 单设备发现使用普通APP/RLS与真实PG摘要，不通过owner读取生产投影。 */
    @Test
    void bindingMetadataDiscoversCurrentModelUnderOrdinaryRls() {
        RuntimeDeviceSnapshotResult result = scoped(fixture,
                () -> service.bindingMetadata(fixture.project(), fixture.device()));
        assertThat(result.devices()).hasSize(1);
        assertThat(result.models().getFirst().versionId()).isEqualTo(fixture.model());
        assertThat(result.models().getFirst().digest()).isEqualTo(fixture.digest());
        assertThat(result.models().getFirst().properties()).isNotEmpty();
        assertThat(result.models().getFirst().properties())
                .extracting(com.things.link.device.application.RuntimePropertyDescription::propertyKey).contains("value");
    }

    /** 外项目设备必须被RLS隐藏，发现接口不能借设备UUID绕过项目范围。 */
    @Test
    void bindingMetadataRejectsForeignProjectDevice() {
        Fixture other = fixture();
        assertThatThrownBy(() -> scoped(fixture,
                () -> service.bindingMetadata(fixture.project(), other.device())))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
    }

    /** 五种稀疏状态来自同一dev_shadow行；VALUE的值、时刻、来源版本保持精确一致。 */
    @Test
    void readsAllSparseStatesFromAuthoritativeShadowRow() {
        UUID oldModel = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,reported_model_version)
                VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb)
                """, fixture.device(), fixture.tenant(), fixture.project(),
                "{\"value\":21.5,\"unknown\":1,\"old\":1,\"outside\":151}",
                "{\"value\":\"2026-09-07T01:02:03.123456Z\",\"unknown\":\"2026-09-07T01:02:03Z\","
                        + "\"old\":\"2026-09-07T01:02:03Z\",\"outside\":\"2026-09-07T01:02:03Z\"}",
                "{\"value\":\"" + fixture.model() + "\",\"old\":\"" + oldModel
                        + "\",\"outside\":\"" + fixture.model() + "\"}");
        List<String> keys = List.of("value", "empty", "unknown", "old", "outside");

        RuntimeDeviceCurrentResult result = scoped(fixture, () -> service.queryCurrentValues(fixture.project(),
                List.of(new RuntimeDeviceQuery(fixture.device(), fixture.model(), keys))));

        assertThat(result.devices().getFirst().values()).extracting(RuntimeDeviceCurrentResult.PropertyValue::state)
                .containsExactly(RuntimeDeviceCurrentResult.State.VALUE, RuntimeDeviceCurrentResult.State.NO_VALUE,
                        RuntimeDeviceCurrentResult.State.SOURCE_VERSION_UNKNOWN,
                        RuntimeDeviceCurrentResult.State.SOURCE_MODEL_MISMATCH,
                        RuntimeDeviceCurrentResult.State.CONTRACT_MISMATCH);
        RuntimeDeviceCurrentResult.PropertyValue value = result.devices().getFirst().values().getFirst();
        assertThat(value.value().decimalValue()).isEqualByComparingTo("21.5");
        assertThat(value.occurredAt()).isEqualTo(Instant.parse("2026-09-07T01:02:03.123456Z"));
        assertThat(value.reportedModelVersionId()).isEqualTo(fixture.model());
    }

    /** 模型描述只来自精确不可变版本并复核PG规范摘要；伪造持久摘要必须作为内部故障拒绝。 */
    @Test
    void readsExactImmutableModelDescriptionAndRejectsStoredDigestDrift() {
        RuntimeModelReference reference = new RuntimeModelReference(fixture.model(),
                "PG_JSONB_TEXT_V1_SHA256", fixture.digest(), "TC_PROPERTY_COMPOSITE_V1");
        RuntimeDeviceSnapshotResult result = scoped(fixture, () -> service.querySnapshots(fixture.project(),
                List.of(new RuntimeDeviceQuery(fixture.device(), fixture.model(), List.of("value"))), List.of(reference)));
        assertThat(result.models()).singleElement().satisfies(model -> {
            assertThat(model.versionId()).isEqualTo(fixture.model());
            assertThat(model.digest()).isEqualTo(fixture.digest());
            assertThat(model.properties()).singleElement().satisfies(property -> {
                assertThat(property.propertyKey()).isEqualTo("value");
                assertThat(property.minimumValue()).isEqualByComparingTo("-50");
                assertThat(property.maximumValue()).isEqualByComparingTo("150");
            });
        });

        Fixture corrupted = fixtureWithStoredDigest("0".repeat(64));
        assertThatThrownBy(() -> scoped(corrupted, () -> service.querySnapshots(corrupted.project(),
                List.of(new RuntimeDeviceQuery(corrupted.device(), corrupted.model(), List.of("value"))),
                List.of(new RuntimeModelReference(corrupted.model(), "PG_JSONB_TEXT_V1_SHA256",
                        corrupted.digest(), "TC_PROPERTY_COMPOSITE_V1")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("摘要不完整");
    }

    /** security_invoker视图继续服从底表项目RLS，且0270撤销默认继承的全部写权限。 */
    @Test
    void runtimeCatalogViewIsReadOnlyAndUsesInvokerProjectScope() {
        Fixture neighbor = fixture();
        assertThat(application.queryForObject("SELECT count(*) FROM dev_device_runtime_catalog_v1", Integer.class))
                .isZero();
        assertThat(scoped(fixture, () -> application.queryForObject(
                "SELECT count(*) FROM dev_device_runtime_catalog_v1", Integer.class))).isEqualTo(1);
        assertThat(scoped(neighbor, () -> application.queryForObject(
                "SELECT count(*) FROM dev_device_runtime_catalog_v1 WHERE id=?", Integer.class, fixture.device())))
                .isZero();
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", fixture.device());
        assertThat(scoped(fixture, () -> application.queryForObject(
                "SELECT count(*) FROM dev_device_runtime_catalog_v1", Integer.class))).isZero();

        assertThat(owner.queryForObject("SELECT reloptions @> ARRAY['security_invoker=true']::text[] "
                + "FROM pg_class WHERE oid='dev_device_runtime_catalog_v1'::regclass", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',"
                + "'dev_device_runtime_catalog_v1','SELECT')", Boolean.class)).isTrue();
        for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
            assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',"
                    + "'dev_device_runtime_catalog_v1',?)", Boolean.class, privilege)).isFalse();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' "
                + "AND indexname='dev_device_project_model_created_id_idx'", Integer.class)).isEqualTo(1);
    }

    /** 候选/当前模型/软删过滤先于分页；同刻设备按UUID倒序续页，不把非候选挤入结果。 */
    @Test
    void candidateCatalogFiltersBeforeKeysetPaginationUnderOrdinaryRls() {
        var catalog = new DeviceRuntimeCatalogService(new JdbcDeviceRuntimeCatalogRepository(application));
        Instant time = Instant.parse("2026-09-07T01:02:03.123456Z");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID deleted = UUID.randomUUID();
        UUID noModel = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        catalogDevice(low, time);
        catalogDevice(high, time);
        catalogDevice(deleted, time.plusSeconds(1));
        catalogDevice(noModel, time.plusSeconds(2), null);
        catalogDevice(outside, time.plusSeconds(3));
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", deleted);
        Fixture neighbor = fixture();
        List<UUID> candidates = List.of(low, high, deleted, noModel, neighbor.device());

        List<RuntimeDeviceCatalogItem> first = scoped(fixture, () -> catalog.find(fixture.project(),
                fixture.model(), candidates, null, null, 1));
        assertThat(first).singleElement().satisfies(item -> {
            assertThat(item.deviceId()).isEqualTo(high);
            assertThat(item.name()).isEqualTo("目录候选");
            assertThat(item.deviceStatus()).isEqualTo("ONLINE");
            assertThat(item.currentModelVersionId()).isEqualTo(fixture.model());
            assertThat(item.createdAt()).isEqualTo(time);
        });
        assertThat(scoped(fixture, () -> catalog.find(fixture.project(), fixture.model(), candidates,
                time, high, 51))).extracting(RuntimeDeviceCatalogItem::deviceId).containsExactly(low);
        assertThat(scoped(fixture, () -> catalog.find(fixture.project(), fixture.model(), candidates,
                time, low, 51))).isEmpty();
        assertThat(scoped(fixture, () -> catalog.find(fixture.project(), fixture.model(), candidates,
                null, null, 51))).extracting(RuntimeDeviceCatalogItem::deviceId).containsExactly(high, low);
        assertThat(catalog.find(fixture.project(), fixture.model(), candidates, null, null, 51)).isEmpty();
        assertThat(scoped(neighbor, () -> catalog.find(fixture.project(), fixture.model(), candidates,
                null, null, 51))).isEmpty();
        assertThat(scoped(fixture, () -> catalog.find(neighbor.project(), neighbor.model(),
                List.of(neighbor.device()), null, null, 51))).isEmpty();
        assertThat(scoped(fixture, () -> catalog.find(fixture.project(), neighbor.model(), candidates,
                null, null, 51))).isEmpty();
    }

    /** Console目录独立支持超过分享20候选，SQL过滤在LIMIT前且普通双轴RLS不可绕开。 */
    @Test
    void consoleCatalogFiltersBeforePagingAndRetainsOrdinaryRls() {
        var repository = new JdbcDeviceRuntimeCatalogRepository(application);
        Instant time = Instant.now().plusSeconds(60);
        for (int index = 0; index < 26; index++) catalogDevice(UUID.randomUUID(), time.plusSeconds(index));
        UUID deleted = UUID.randomUUID(), unbound = UUID.randomUUID();
        catalogDevice(deleted, time.plusSeconds(100));
        catalogDevice(unbound, time.plusSeconds(101), null);
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", deleted);
        var first = scoped(fixture, () -> repository.findConsole(fixture.project(), fixture.model(), null, null, 21));
        assertThat(first).hasSize(21);
        assertThat(first).extracting(com.things.link.device.domain.DeviceRuntimeCatalogRepository.Item::deviceId)
                .doesNotContain(deleted, unbound);
        var anchor = first.get(19);
        var second = scoped(fixture, () -> repository.findConsole(fixture.project(), fixture.model(),
                anchor.createdAt(), anchor.deviceId(), 21));
        assertThat(second).hasSize(7);
        assertThat(second).extracting(com.things.link.device.domain.DeviceRuntimeCatalogRepository.Item::deviceId)
                .doesNotContainAnyElementsOf(first.subList(0, 20).stream().map(
                        com.things.link.device.domain.DeviceRuntimeCatalogRepository.Item::deviceId).toList());
        assertThat(repository.findConsole(fixture.project(), fixture.model(), null, null, 21)).isEmpty();
        Fixture neighbor = fixture();
        assertThat(scoped(neighbor, () -> repository.findConsole(fixture.project(), fixture.model(), null, null, 21))).isEmpty();
        assertThat(scoped(fixture, () -> repository.findConsole(fixture.project(), neighbor.model(), null, null, 21))).isEmpty();
    }

    /** 复制同项目完整类型链，只改变目录字段；不建立测试替代视图或提升业务连接权限。 */
    private void catalogDevice(UUID id, Instant createdAt) {
        catalogDevice(id, createdAt, fixture.model());
    }

    /** 未绑定模型设备在初始插入时保持NULL，不绕过已绑定设备禁止清空的生产触发器。 */
    private void catalogDevice(UUID id, Instant createdAt, UUID modelVersionId) {
        owner.update("""
                INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,
                    thing_model_version_id,status,created_at)
                SELECT ?,tenant_id,project_id,device_type_id,?,'目录候选',?,'ONLINE',?
                  FROM dev_device WHERE id=?
                """, id, "catalog_" + id.toString().replace("-", ""), modelVersionId, Timestamp.from(createdAt), fixture.device());
    }

    /** 建立一个完整项目、类型、不可变模型及显式绑定设备。 */
    private Fixture fixture() {
        return fixtureWithStoredDigest(null);
    }

    /** 允许单例伪造摘要只用于证明读取失败关闭，模型正文仍是合法生产形状。 */
    private Fixture fixtureWithStoredDigest(String forcedDigest) {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID type = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        String snapshot = """
                {"properties":{"value":{"dataType":"NUMBER","unit":null,"minimum":-50,"maximum":150},
                "empty":{"dataType":"NUMBER"},"unknown":{"dataType":"NUMBER"},
                "old":{"dataType":"NUMBER"},"outside":{"dataType":"NUMBER","maximum":150}},
                "events":{},"commands":{}}
                """;
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'设备运行租户')", tenant);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key,status) VALUES (?,?,'设备运行项目',?,'ACTIVE')",
                project, tenant, project.toString().replace("-", ""));
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) "
                + "VALUES (?,?,?,?,'设备运行类型','STANDARD','DIRECT')",
                type, tenant, project, "type_" + type.toString().replace("-", ""));
        String calculated = owner.queryForObject(
                "SELECT encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex')", String.class, snapshot);
        String stored = forcedDigest == null ? calculated : forcedDigest;
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,
                    schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,?,
                    'PG_JSONB_TEXT_V1_SHA256')
                """, model, tenant, project, type, snapshot, stored);
        owner.update("""
                INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,
                    thing_model_version_id,status,last_online_at,created_at)
                VALUES (?,?,?,?,?,'设备运行实例',?,'ONLINE',?,?)
                """, device, tenant, project, type, "device_" + device.toString().replace("-", ""), model,
                Timestamp.from(Instant.parse("2026-09-07T00:00:00Z")), Timestamp.from(Instant.now()));
        return new Fixture(tenant, project, model, stored, device);
    }

    /** 在普通APP事务中建立双轴范围后执行真实业务读取。 */
    private <T> T scoped(Fixture value, Supplier<T> work) {
        return transaction.execute(status -> {
            scope.establish(value.tenant(), value.project());
            return work.get();
        });
    }

    /** @param tenant 租户 @param project 项目 @param model 模型版本 @param digest 对外摘要 @param device 设备 */
    private record Fixture(UUID tenant, UUID project, UUID model, String digest, UUID device) { }
}
